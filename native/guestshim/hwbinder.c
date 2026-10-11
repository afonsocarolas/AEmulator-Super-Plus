/*
 * libaemuhwb.so — AEmulator Super Plus, 2026-10-11. GPL-3.0; see LICENSE and NOTICE.md.
 *
 * Android 8+ (Treble) needs /dev/hwbinder and /dev/vndbinder next to /dev/binder. The engine (patched QEMU +
 * binderd, prebuilt without source) only provides /dev/binder, so this preload library implements the two new
 * devices in the guest itself: open() returns a unix socket to the app's HwBinderDaemon
 * (/dev/socket/aemu_hwbinder), ioctl(BINDER_WRITE_READ) turns the BC_* command stream into messages for the daemon
 * and the daemon's messages back into BR_* records, mmap() of the device yields plain memory (received transactions
 * are copied into malloc'ed blocks that BC_FREE_BUFFER releases).
 *
 * The kernel ABI is the 64-bit one (binder protocol 8), which every Treble build uses, also on 32-bit ARM.
 * Each guest thread gets its own connection (the kernel keeps per-thread state); the descriptor open() returned
 * belongs to the opening thread, so a looper that polls it (hwservicemanager) wakes up when the daemon sends it
 * work. The wire format is described in app/src/main/java/app/aemu/core/HwBinderDaemon.kt.
 *
 * Loaded first in LD_PRELOAD, only for Android 8+ firmware; every call that is not for these devices goes on to
 * the next library (libashmemshim, libaemushim, libc) through dlsym(RTLD_NEXT).
 */
#include <stdarg.h>

#define EXPORT __attribute__((visibility("default")))

typedef unsigned char u8;
typedef unsigned int u32;
typedef unsigned long long u64;
typedef unsigned long ulong;

extern int *__errno(void);
extern void *malloc(ulong n);
extern void free(void *p);
extern void *dlsym(void *handle, const char *name);
#define RTLD_NEXT ((void *)0xfffffffe) /* 32-bit bionic */

__attribute__((naked, noinline)) static long sys6(long n, long a, long b, long c, long d, long e, long f) {
    __asm__ volatile(
        "push {r4, r5, r6, r7}\n"
        "mov r7, r0\n mov r0, r1\n mov r1, r2\n mov r2, r3\n"
        "ldr r3, [sp, #16]\n ldr r4, [sp, #20]\n ldr r5, [sp, #24]\n"
        "svc #0\n"
        "pop {r4, r5, r6, r7}\n bx lr");
}
#define sys3(n, a, b, c) sys6((n), (long)(a), (long)(b), (long)(c), 0, 0, 0)

#define SYS_write 4
#define SYS_close 6
#define SYS_getpid 20
#define SYS_poll 168
#define SYS_sched_yield 158
#define SYS_geteuid32 201
#define SYS_gettid 224
#define SYS_socket 281
#define SYS_connect 283
#define SYS_recv 291
#define MSG_PEEK 2
#define MSG_DONTWAIT 0x40
#define MSG_WAITALL 0x100
#define EINVAL 22
#define ENOENT 2
#define ENOMEM 12
#define EIO 5

static int fail(int e) { *__errno() = e; return -1; }

/* ---------------------------------------------------------------- kernel ABI (protocol 8) */

#define BINDER_WRITE_READ      0xc0306201u
#define BINDER_WRITE_READ_32   0xc0186201u
#define BINDER_SET_IDLE_TIMEOUT 0x40086203u
#define BINDER_SET_MAX_THREADS 0x40046205u
#define BINDER_SET_IDLE_PRIORITY 0x40046206u
#define BINDER_SET_CONTEXT_MGR 0x40046207u
#define BINDER_THREAD_EXIT     0x40046208u
#define BINDER_VERSION         0xc0046209u

#define BR_TRANSACTION 0x80407202u
#define BR_REPLY       0x80407203u
#define BR_DEAD_REPLY  0x7205u
#define BR_FAILED_REPLY 0x7211u

#define TF_ONE_WAY 0x01
#define T_PTR 0x70742a85u          /* BINDER_TYPE_PTR */
#define BUF_HAS_PARENT 0x01

struct bwr { u64 write_size, write_consumed, write_buffer, read_size, read_consumed, read_buffer; };

/* ---------------------------------------------------------------- daemon wire format */

enum { M_HELLO = 1, M_LOOPER = 2, M_CTXMGR = 3, M_TXN = 4, M_REF = 5, M_DEATH = 6, M_IDLE = 7,
       M_THREAD_EXIT = 8, M_MAXTHREADS = 11, D_SIMPLE = 101, D_TXN = 102 };

/* ---------------------------------------------------------------- next library in the chain */

typedef int (*open_fn)(const char *, int, ...);
typedef int (*openat_fn)(int, const char *, int, ...);
typedef int (*ioctl_fn)(int, int, ...);
typedef void *(*mmap_fn)(void *, ulong, int, int, int, long);
typedef void *(*mmap64_fn)(void *, ulong, int, int, int, long long);
typedef int (*close_fn)(int);

static void *next(const char *name, void **slot) {
    void *f = *slot;
    if (!f) { f = dlsym(RTLD_NEXT, name); *slot = f; }
    return f;
}
static void *n_open, *n_open2, *n_openat, *n_openat2, *n_ioctl, *n_mmap, *n_mmap64, *n_close;

/* ---------------------------------------------------------------- devices and connections */

static int dev_of_path(const char *p) {
    static const char hw[] = "/dev/hwbinder", vnd[] = "/dev/vndbinder";
    if (!p) return 0;
    int i = 0;
    while (hw[i] && p[i] == hw[i]) i++;
    if (!hw[i] && !p[i]) return 1;
    i = 0;
    while (vnd[i] && p[i] == vnd[i]) i++;
    if (!vnd[i] && !p[i]) return 2;
    return 0;
}

#define MAXCONN 256
struct conn { int fd, dev, tid, pid, main, waiting_replies; };
static struct conn g_conn[MAXCONN];
static volatile int g_lock;
static void lock(void) { while (!__sync_bool_compare_and_swap(&g_lock, 0, 1)) sys3(SYS_sched_yield, 0, 0, 0); }
static void unlock(void) { __sync_lock_release(&g_lock); }

static int write_all(int fd, const void *buf, ulong n) {
    const u8 *p = buf;
    while (n) {
        long r = sys3(SYS_write, fd, p, n);
        if (r == -4) continue; /* EINTR */
        if (r <= 0) return -1;
        p += r; n -= (ulong)r;
    }
    return 0;
}
static long recv_n(int fd, void *buf, ulong n, int flags) {
    for (;;) {
        long r = sys6(SYS_recv, fd, (long)buf, (long)n, flags, 0, 0);
        if (r != -4) return r;
    }
}
static int read_all(int fd, void *buf, ulong n) {
    u8 *p = buf;
    while (n) {
        long r = recv_n(fd, p, n, MSG_WAITALL);
        if (r <= 0) return -1;
        p += r; n -= (ulong)r;
    }
    return 0;
}

static int send_msg(int fd, u32 type, const u32 *words, int nwords) {
    u32 m[8];
    m[0] = 4 + 4 * nwords; m[1] = type;
    for (int i = 0; i < nwords; i++) m[2 + i] = words[i];
    return write_all(fd, m, 8 + 4 * nwords);
}

static int connect_daemon(int dev, int tid) {
    long s = sys3(SYS_socket, 1 /* AF_UNIX */, 1 | 0x80000 /* SOCK_STREAM | SOCK_CLOEXEC */, 0);
    if (s < 0) return -1;
    struct { unsigned short family; char path[108]; } a;
    static const char path[] = "/dev/socket/aemu_hwbinder";
    a.family = 1;
    int n = 0;
    while (path[n]) { a.path[n] = path[n]; n++; }
    a.path[n] = 0;
    if (sys3(SYS_connect, s, &a, 2 + n + 1) < 0) { sys3(SYS_close, s, 0, 0); return -1; }
    u32 hello[4] = { (u32)dev, (u32)sys3(SYS_getpid, 0, 0, 0), (u32)tid, (u32)sys3(SYS_geteuid32, 0, 0, 0) };
    if (send_msg((int)s, M_HELLO, hello, 4) < 0) { sys3(SYS_close, s, 0, 0); return -1; }
    return (int)s;
}

static struct conn *main_conn(int fd) {
    for (int i = 0; i < MAXCONN; i++) if (g_conn[i].main && g_conn[i].fd == fd) return &g_conn[i];
    return 0;
}

/* the connection of the calling thread for the device behind main descriptor fd (created on first use) */
static struct conn *thread_conn(struct conn *m) {
    int tid = (int)sys3(SYS_gettid, 0, 0, 0), pid = (int)sys3(SYS_getpid, 0, 0, 0);
    if (m->tid == tid && m->pid == pid) return m;
    lock();
    for (int i = 0; i < MAXCONN; i++)
        if (!g_conn[i].main && g_conn[i].fd > 0 && g_conn[i].dev == m->dev && g_conn[i].tid == tid && g_conn[i].pid == pid) {
            unlock(); return &g_conn[i];
        }
    unlock();
    int fd = connect_daemon(m->dev, tid);
    if (fd < 0) return 0;
    lock();
    for (int i = 0; i < MAXCONN; i++)
        if (g_conn[i].fd <= 0) {
            g_conn[i] = (struct conn){ fd, m->dev, tid, pid, 0, 0 };
            unlock(); return &g_conn[i];
        }
    unlock();
    sys3(SYS_close, fd, 0, 0);
    return 0;
}

static int open_device(int dev) {
    int tid = (int)sys3(SYS_gettid, 0, 0, 0);
    int fd = connect_daemon(dev, tid);
    if (fd < 0) return fail(ENOENT); /* as if the device did not exist */
    lock();
    for (int i = 0; i < MAXCONN; i++)
        if (g_conn[i].fd <= 0) {
            g_conn[i] = (struct conn){ fd, dev, tid, (int)sys3(SYS_getpid, 0, 0, 0), 1, 0 };
            unlock(); return fd;
        }
    unlock();
    sys3(SYS_close, fd, 0, 0);
    return fail(ENOMEM);
}

/* ---------------------------------------------------------------- BC_* -> daemon */

static void put32(u8 *p, u32 v) { p[0] = (u8)v; p[1] = (u8)(v >> 8); p[2] = (u8)(v >> 16); p[3] = (u8)(v >> 24); }
static u32 get32(const u8 *p) { return p[0] | p[1] << 8 | p[2] << 16 | (u32)p[3] << 24; }
static u64 get64(const u8 *p) { return get32(p) | (u64)get32(p + 4) << 32; }
static void put64(u8 *p, u64 v) { put32(p, (u32)v); put32(p + 4, (u32)(v >> 32)); }
static void copy(u8 *d, const u8 *s, ulong n) { while (n--) *d++ = *s++; }
static ulong align8(ulong n) { return (n + 7) & ~7ul; }

/* M_TXN: u32 is_reply, handle, code, flags, data_len, off_count, sg_count; data (pad 8); u32 offsets (pad 8);
 * per sg buffer: u32 object index, u32 length, bytes (pad 8) */
static int send_txn(struct conn *c, const u8 *tr, int reply) {
    u32 handle = get32(tr), code = get32(tr + 16), flags = get32(tr + 20);
    u64 dsz = get64(tr + 32), osz = get64(tr + 40);
    const u8 *data = (const u8 *)(ulong)get64(tr + 48);
    const u8 *offs = (const u8 *)(ulong)get64(tr + 56);
    if (dsz > (16u << 20) || osz > (1u << 20) || (osz & 7)) return -EINVAL;
    u32 n = (u32)(osz / 8), sg = 0;
    ulong total = 4 + 4 + 28 + align8((ulong)dsz) + align8(4ul * n);
    for (u32 i = 0; i < n; i++) {
        u32 off = (u32)get64(offs + 8 * i);
        if ((u64)off + 4 > dsz) return -EINVAL;
        if (get32(data + off) == T_PTR && (u64)off + 40 <= dsz) {
            u64 len = get64(data + off + 16);
            if (len > (16u << 20)) return -EINVAL;
            total += 8 + align8((ulong)len); sg++;
        }
    }
    u8 *m = malloc(total);
    if (!m) return -ENOMEM;
    u8 *p = m;
    put32(p, (u32)(total - 4)); put32(p + 4, M_TXN); p += 8;
    put32(p, (u32)reply); put32(p + 4, handle); put32(p + 8, code); put32(p + 12, flags);
    put32(p + 16, (u32)dsz); put32(p + 20, n); put32(p + 24, sg); p += 28;
    copy(p, data, (ulong)dsz); for (ulong k = (ulong)dsz; k < align8((ulong)dsz); k++) p[k] = 0; p += align8((ulong)dsz);
    for (u32 i = 0; i < n; i++) put32(p + 4 * i, (u32)get64(offs + 8 * i));
    for (ulong k = 4ul * n; k < align8(4ul * n); k++) p[k] = 0;
    p += align8(4ul * n);
    for (u32 i = 0; i < n; i++) {
        u32 off = (u32)get64(offs + 8 * i);
        if (get32(data + off) != T_PTR || (u64)off + 40 > dsz) continue;
        ulong len = (ulong)get64(data + off + 16);
        put32(p, i); put32(p + 4, (u32)len); p += 8;
        copy(p, (const u8 *)(ulong)get64(data + off + 8), len);
        for (ulong k = len; k < align8(len); k++) p[k] = 0;
        p += align8(len);
    }
    int r = write_all(c->fd, m, total);
    free(m);
    if (r < 0) return -EIO;
    if (!reply && !(flags & TF_ONE_WAY)) c->waiting_replies++;
    return 0;
}

static int do_write(struct conn *c, struct bwr *b) {
    while (b->write_consumed + 4 <= b->write_size) {
        const u8 *p = (const u8 *)(ulong)(b->write_buffer + b->write_consumed);
        u32 cmd = get32(p), size = (cmd >> 16) & 0x3fff, nr = cmd & 0xff;
        if (((cmd >> 8) & 0xff) != 'c' || b->write_consumed + 4 + size > b->write_size) return -EINVAL;
        const u8 *a = p + 4;
        int r = 0;
        switch (nr) {
        case 0: case 1: r = size == 64 ? send_txn(c, a, nr == 1) : -EINVAL; break;      /* BC_TRANSACTION / BC_REPLY */
        case 17: case 18: r = size == 72 ? send_txn(c, a, nr == 18) : -EINVAL; break;   /* the _SG variants */
        case 3: free((void *)(ulong)get64(a)); break;                                    /* BC_FREE_BUFFER */
        case 4: case 5: case 6: case 7: { u32 w[2] = { nr, get32(a) }; r = send_msg(c->fd, M_REF, w, 2) ? -EIO : 0; break; }
        case 8: case 9: case 2: case 16: break;                     /* *_DONE, ACQUIRE_RESULT, DEAD_BINDER_DONE */
        case 11: case 12: case 13: { u32 w = nr - 10; r = send_msg(c->fd, M_LOOPER, &w, 1) ? -EIO : 0; break; }
        case 14: case 15: {                                          /* (CLEAR_)DEATH_NOTIFICATION: packed handle + cookie */
            u64 ck = get64(a + 4);
            u32 w[4] = { nr - 13, get32(a), (u32)ck, (u32)(ck >> 32) };
            r = send_msg(c->fd, M_DEATH, w, 4) ? -EIO : 0; break;
        }
        default: r = -EINVAL;
        }
        if (r < 0) return r;
        b->write_consumed += 4 + size;
    }
    return 0;
}

/* ---------------------------------------------------------------- daemon -> BR_* */

/* D_TXN: u64 target ptr, u64 cookie, u32 is_reply, flags, code, sender_pid, sender_euid, data_len, off_count,
 * sg_count; data (pad 8); u32 offsets (pad 8); sg buffers as in M_TXN */
static int build_txn(const u8 *m, ulong mlen, u8 *out) {
    if (mlen < 48) return -EIO;
    u32 reply = get32(m + 16), flags = get32(m + 20), code = get32(m + 24), spid = get32(m + 28), seuid = get32(m + 32);
    u32 dlen = get32(m + 36), n = get32(m + 40), sg = get32(m + 44);
    const u8 *p = m + 48, *end = m + mlen;
    if (p + align8(dlen) + align8(4ul * n) > end) return -EIO;
    const u8 *data = p, *offs = p + align8(dlen);
    const u8 *sgp = offs + align8(4ul * n);
    ulong total = align8(dlen) + 8ul * n, sgbytes = 0;
    const u8 *q = sgp;
    for (u32 i = 0; i < sg; i++) {
        if (q + 8 > end) return -EIO;
        ulong len = get32(q + 4);
        sgbytes += align8(len); q += 8 + align8(len);
        if (q > end) return -EIO;
    }
    u8 *blk = malloc(total + sgbytes + 8);
    if (!blk) return -ENOMEM;
    copy(blk, data, dlen);
    u8 *ob = blk + align8(dlen);
    for (u32 i = 0; i < n; i++) put64(ob + 8 * i, get32(offs + 4 * i));
    /* scatter-gather buffers go behind the offsets; their objects and parents point at the new copies */
    u8 *dst = ob + 8ul * n;
    q = sgp;
    for (u32 i = 0; i < sg; i++) {
        u32 idx = get32(q), len = get32(q + 4);
        copy(dst, q + 8, len);
        if (idx < n) {
            u32 off = get32(offs + 4 * idx);
            if (off + 40 <= dlen) {
                put64(blk + off + 8, (u64)(ulong)dst);
                if (get32(blk + off + 4) & BUF_HAS_PARENT) {
                    u32 parent = (u32)get64(blk + off + 24), poff = (u32)get64(blk + off + 32);
                    if (parent < n) {
                        u32 po = get32(offs + 4 * parent);
                        u8 *pbuf = (u8 *)(ulong)get64(blk + po + 8);
                        u64 plen = get64(blk + po + 16);
                        if (po + 40 <= dlen && (u64)poff + 8 <= plen) put64(pbuf + poff, (u64)(ulong)dst);
                    }
                }
            }
        }
        dst += align8(len); q += 8 + align8(len);
    }
    put32(out, reply ? BR_REPLY : BR_TRANSACTION);
    u8 *t = out + 4;
    copy(t, m, 16);                                   /* target.ptr, cookie */
    put32(t + 16, code); put32(t + 20, flags); put32(t + 24, spid); put32(t + 28, seuid);
    put64(t + 32, dlen); put64(t + 40, 8ull * n);
    put64(t + 48, (u64)(ulong)blk); put64(t + 56, (u64)(ulong)ob);
    return 68;
}

/* one daemon message into out (room bytes free); 0 = no complete message waiting, -1 = does not fit, <-1 = error */
static int take_one(struct conn *c, u8 *out, ulong room, int block) {
    u8 h[12];
    long r;
    for (;;) {
        r = recv_n(c->fd, h, 12, MSG_PEEK | MSG_DONTWAIT);
        if (r == 0) return -EIO;          /* daemon gone */
        if (r >= 12 || !block) break;
        if (r > 0) { sys3(SYS_sched_yield, 0, 0, 0); continue; } /* header still arriving */
        struct { int fd; short events, revents; } pfd = { c->fd, 1 /* POLLIN */, 0 };
        long pr = sys3(SYS_poll, &pfd, 1, -1);
        if (pr < 0 && pr != -4) return -EIO;
    }
    if (r < 12) return 0;
    u32 len = get32(h), type = get32(h + 4);
    ulong need = type == D_TXN ? 68 : 4 + ((get32(h + 8) >> 16) & 0x3fff);
    if (len < 4 || len > (64u << 20) || (type != D_TXN && type != D_SIMPLE)) return -EIO;
    if (need > room) return -1;
    u8 small[64];
    u8 *m = len - 4 <= sizeof small ? small : malloc(len - 4);
    if (!m) return -ENOMEM;
    if (read_all(c->fd, h, 8) < 0 || read_all(c->fd, m, len - 4) < 0) { if (m != small) free(m); return -EIO; }
    int n;
    if (type == D_TXN) {
        n = build_txn(m, len - 4, out);
        if (n > 0 && get32(m + 16) && c->waiting_replies > 0) c->waiting_replies--;
    } else {
        n = (int)(len - 4);
        copy(out, m, (ulong)n);
        u32 br = get32(m);
        if ((br == BR_DEAD_REPLY || br == BR_FAILED_REPLY) && c->waiting_replies > 0) c->waiting_replies--;
    }
    if (m != small) free(m);
    return n;
}

static int do_read(struct conn *c, struct bwr *b) {
    int got = 0;
    for (;;) {
        u8 *out = (u8 *)(ulong)(b->read_buffer + b->read_consumed);
        ulong room = (ulong)(b->read_size - b->read_consumed);
        int block = !got;
        if (block) {
            /* about to wait: a thread with no reply outstanding may be handed process work */
            int r0 = take_one(c, out, room, 0);
            if (r0 == 0) {
                if (!c->waiting_replies) send_msg(c->fd, M_IDLE, 0, 0);
            } else if (r0 > 0) { b->read_consumed += (u64)r0; got = 1; continue; }
            else if (r0 == -1) return -EINVAL;
            else return r0;
        }
        int r = take_one(c, out, room, block);
        if (r == 0 || (r == -1 && got)) break;
        if (r == -1) return -EINVAL;
        if (r < 0) return r;
        b->read_consumed += (u64)r;
        got = 1;
    }
    /* drained: tell the daemon this thread can take process work (polling loopers never block in read) */
    if (!c->waiting_replies) send_msg(c->fd, M_IDLE, 0, 0);
    return 0;
}

static int hwb_ioctl(struct conn *m, unsigned req, void *arg) {
    switch (req) {
    case BINDER_VERSION: *(int *)arg = 8; return 0;
    case BINDER_SET_MAX_THREADS: { u32 w = *(u32 *)arg; return send_msg(m->fd, M_MAXTHREADS, &w, 1) ? fail(EIO) : 0; }
    case BINDER_SET_CONTEXT_MGR: return send_msg(m->fd, M_CTXMGR, 0, 0) ? fail(EIO) : 0;
    case BINDER_SET_IDLE_TIMEOUT: case BINDER_SET_IDLE_PRIORITY: return 0;
    case BINDER_THREAD_EXIT: {
        struct conn *c = thread_conn(m);
        if (c && c != m) {
            send_msg(c->fd, M_THREAD_EXIT, 0, 0);
            sys3(SYS_close, c->fd, 0, 0);
            lock(); c->fd = 0; c->tid = 0; unlock();
        }
        return 0;
    }
    case BINDER_WRITE_READ: {
        struct conn *c = thread_conn(m);
        if (!c) return fail(EIO);
        struct bwr *b = arg;
        int r = do_write(c, b);
        if (r == 0 && b->read_size > b->read_consumed) r = do_read(c, b);
        return r < 0 ? fail(-r) : 0;
    }
    default: return fail(EINVAL); /* includes the 32-bit ABI: Treble builds use protocol 8 */
    }
}

/* ---------------------------------------------------------------- interposed libc entry points */

EXPORT int open(const char *path, int flags, ...) {
    int dev = dev_of_path(path);
    if (dev) return open_device(dev);
    int mode = 0;
    { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); } /* harmless without O_CREAT, like bionic */
    return ((open_fn)next("open", &n_open))(path, flags, mode);
}
EXPORT int __open_2(const char *path, int flags) {
    int dev = dev_of_path(path);
    if (dev) return open_device(dev);
    return ((int (*)(const char *, int))next("__open_2", &n_open2))(path, flags);
}
EXPORT int openat(int dirfd, const char *path, int flags, ...) {
    int dev = dev_of_path(path);
    if (dev) return open_device(dev);
    int mode = 0;
    { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return ((openat_fn)next("openat", &n_openat))(dirfd, path, flags, mode);
}
EXPORT int __openat_2(int dirfd, const char *path, int flags) {
    int dev = dev_of_path(path);
    if (dev) return open_device(dev);
    return ((int (*)(int, const char *, int))next("__openat_2", &n_openat2))(dirfd, path, flags);
}

EXPORT int ioctl(int fd, int req, ...) {
    va_list ap; va_start(ap, req); void *arg = va_arg(ap, void *); va_end(ap);
    struct conn *m = fd > 0 ? main_conn(fd) : 0;
    if (m) return hwb_ioctl(m, (unsigned)req, arg);
    return ((ioctl_fn)next("ioctl", &n_ioctl))(fd, req, arg);
}

/* the transaction memory of the device: plain memory, transactions are delivered into malloc'ed blocks */
EXPORT void *mmap(void *addr, ulong len, int prot, int flags, int fd, long off) {
    if (fd > 0 && main_conn(fd)) return ((mmap_fn)next("mmap", &n_mmap))(addr, len, 1 /* PROT_READ */, 0x4022 /* MAP_PRIVATE|ANONYMOUS|NORESERVE */, -1, 0);
    return ((mmap_fn)next("mmap", &n_mmap))(addr, len, prot, flags, fd, off);
}
EXPORT void *mmap64(void *addr, ulong len, int prot, int flags, int fd, long long off) {
    if (fd > 0 && main_conn(fd)) return ((mmap64_fn)next("mmap64", &n_mmap64))(addr, len, 1, 0x4022, -1, 0);
    return ((mmap64_fn)next("mmap64", &n_mmap64))(addr, len, prot, flags, fd, off);
}

EXPORT int close(int fd) {
    if (fd > 0) {
        lock();
        for (int i = 0; i < MAXCONN; i++) if (g_conn[i].main && g_conn[i].fd == fd) { g_conn[i].fd = 0; g_conn[i].main = 0; }
        unlock();
    }
    return ((close_fn)next("close", &n_close))(fd);
}
