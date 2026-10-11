/*
 * audio.primary.default.so для прошивок Android 4.2–4.4 (AOSP и близкие к нему).
 *
 * Звук стенда устроен просто: гость пишет PCM 16 бит стерео 48 кГц в /dev/eac (FIFO), приложение читает
 * его и играет через AudioTrack. HAL автора делает то же, но его структуры разложены под AudioFlinger HTC
 * (лишние слоты в audio_hw_device и audio_stream_out), и AudioFlinger AOSP вызывает не те функции.
 * Здесь раскладка — ровно как в hardware/libhardware/include/hardware/audio.h KitKat; для 4.2/4.3 она
 * совместима (хвостовые поля 4.4 просто не читаются).
 *
 * Без заголовков AOSP: структуры описаны вручную, зависимостей — только libc.
 *
 * Modified for AEmulator Super Plus, 2026-10-11: microphone input. An input stream reads 16-bit PCM from the host
 * (unix socket /dev/aemu_mic, HostMicBridge in the app) and gives silence at the real pace when the host has no
 * microphone for it (setting off, permission denied, app in the background), so recording never fails.
 */
#include <stdint.h>
#include <stddef.h>

extern int open(const char *path, int flags, ...);
extern long write(int fd, const void *buf, unsigned long n);
extern int close(int fd);
extern void *calloc(unsigned long n, unsigned long size);
extern void free(void *p);
extern int *__errno(void);
extern int usleep(unsigned long us);
extern long read(int fd, void *buf, unsigned long n);
extern int socket(int domain, int type, int protocol);
extern int connect(int fd, const void *addr, unsigned len);
struct pollfd_s { int fd; short events, revents; };
extern int poll(struct pollfd_s *fds, unsigned long n, int timeout);

#define O_RDWR 2
#define EAGAIN 11
#define O_NONBLOCK 04000
#define EINTR 4
#define ENOSYS 38
#define EINVAL 22

#ifndef AEMU_PCM_DEV
#define AEMU_PCM_DEV "/dev/eac"
#endif
#define RATE 48000
#define CHANNELS_STEREO 3        /* AUDIO_CHANNEL_OUT_STEREO */
#define FORMAT_PCM16 1           /* AUDIO_FORMAT_PCM_16_BIT */
#define DEVICE_OUT_SPEAKER 0x2
#define DEVICE_OUT_DEFAULT 0x40000000
#define BUFFER_BYTES 4096
#define LATENCY_MS 100

typedef void *fnp;

/* ---- hw_module_t / hw_device_t ---- */
struct hw_module;
struct hw_device;
struct hw_module_methods { int (*open)(const struct hw_module *m, const char *id, struct hw_device **dev); };
struct hw_module {
    uint32_t tag;
    uint16_t module_api_version, hal_api_version;
    const char *id, *name, *author;
    struct hw_module_methods *methods;
    void *dso;
    uint32_t reserved[25];
};
struct hw_device {
    uint32_t tag, version;
    struct hw_module *module;
    uint32_t reserved[12];
    int (*close)(struct hw_device *dev);
};

/* ---- audio_config (4.4: + audio_offload_info_t, который мы не трогаем) ---- */
struct audio_config { uint32_t sample_rate, channel_mask; int format; };

/* ---- audio_stream / audio_stream_out (KitKat) ---- */
struct audio_stream {
    uint32_t (*get_sample_rate)(const struct audio_stream *s);
    int (*set_sample_rate)(struct audio_stream *s, uint32_t rate);
    size_t (*get_buffer_size)(const struct audio_stream *s);
    uint32_t (*get_channels)(const struct audio_stream *s);
    int (*get_format)(const struct audio_stream *s);
    int (*set_format)(struct audio_stream *s, int format);
    int (*standby)(struct audio_stream *s);
    int (*dump)(const struct audio_stream *s, int fd);
    uint32_t (*get_device)(const struct audio_stream *s);
    int (*set_device)(struct audio_stream *s, uint32_t device);
    int (*set_parameters)(struct audio_stream *s, const char *kv);
    char *(*get_parameters)(const struct audio_stream *s, const char *keys);
    int (*add_audio_effect)(const struct audio_stream *s, void *effect);
    int (*remove_audio_effect)(const struct audio_stream *s, void *effect);
};
struct audio_stream_out {
    struct audio_stream common;
    uint32_t (*get_latency)(const struct audio_stream_out *s);
    int (*set_volume)(struct audio_stream_out *s, float left, float right);
    long (*write)(struct audio_stream_out *s, const void *buf, size_t bytes);
    int (*get_render_position)(const struct audio_stream_out *s, uint32_t *frames);
#ifndef AEMU_ICS
#ifdef AEMU_DIRECTTRACK
    /* Sony/CAF QCOM_DIRECTTRACK inserts two slots after render position.
     * Stock ZR libnbaio: write 0x40, next timestamp 0x50, presentation 0x68.
     * Do not use AEMU_QCOM: its device-level extensions are a separate ABI. */
    int (*start)(struct audio_stream_out *s);
    int (*stop)(struct audio_stream_out *s);
#endif
    int (*get_next_write_timestamp)(const struct audio_stream_out *s, int64_t *ts);
    /* 4.4: офлоад и точное положение — не поддерживаем (NULL, AudioFlinger это проверяет) */
    fnp set_callback, pause, resume, drain, flush, get_presentation_position;
#ifdef AEMU_DIRECTTRACK
    fnp get_time_stamp, set_observer, get_buffer_info, is_buffer_available;
#endif
#endif
};

/* ---- audio_hw_device (KitKat) ---- */
struct audio_hw_device {
    struct hw_device common;
    uint32_t (*get_supported_devices)(const struct audio_hw_device *d);
    int (*init_check)(const struct audio_hw_device *d);
    int (*set_voice_volume)(struct audio_hw_device *d, float v);
    int (*set_master_volume)(struct audio_hw_device *d, float v);
#ifdef AEMU_QCOM
    int (*set_fm_volume)(struct audio_hw_device *d, float v);   /* Qualcomm CAF (libaudioflinger has setFmVolume) */
#endif
#ifndef AEMU_ICS
    int (*get_master_volume)(struct audio_hw_device *d, float *v);
#endif
    int (*set_mode)(struct audio_hw_device *d, int mode);
    int (*set_mic_mute)(struct audio_hw_device *d, int state);
    int (*get_mic_mute)(const struct audio_hw_device *d, unsigned char *state);
    int (*set_parameters)(struct audio_hw_device *d, const char *kv);
    char *(*get_parameters)(const struct audio_hw_device *d, const char *keys);
#ifdef AEMU_ICS
    /* Android 4.0: format/channels/rate passed as separate in-out pointers, no stream handle */
    size_t (*get_input_buffer_size)(const struct audio_hw_device *d, uint32_t rate, int format, int channels);
    int (*open_output_stream)(struct audio_hw_device *d, uint32_t devices, int *format, uint32_t *channels,
                              uint32_t *rate, struct audio_stream_out **out);
#else
    size_t (*get_input_buffer_size)(const struct audio_hw_device *d, const struct audio_config *c);
    int (*open_output_stream)(struct audio_hw_device *d, int handle, uint32_t devices, int flags,
                              struct audio_config *c, struct audio_stream_out **out);
#endif
#ifdef AEMU_QCOM
    int (*open_output_session)(struct audio_hw_device *d, uint32_t devices, int *format, int session,
                               struct audio_stream_out **out);  /* CAF LPA/tunnel output */
#endif
    void (*close_output_stream)(struct audio_hw_device *d, struct audio_stream_out *s);
#ifdef AEMU_ICS
    int (*open_input_stream)(struct audio_hw_device *d, uint32_t devices, int *format, uint32_t *channels,
                             uint32_t *rate, int acoustics, void **in);
#else
    int (*open_input_stream)(struct audio_hw_device *d, int handle, uint32_t devices,
                             struct audio_config *c, void **in);
#endif
    void (*close_input_stream)(struct audio_hw_device *d, void *in);
    int (*dump)(const struct audio_hw_device *d, int fd);
#ifndef AEMU_ICS
    int (*set_master_mute)(struct audio_hw_device *d, int mute);
    int (*get_master_mute)(struct audio_hw_device *d, int *mute);
#endif
};

struct out {
    struct audio_stream_out s;   /* первым полем: указатель на out == указатель на поток */
    int fd;
    uint32_t device;
    uint64_t frames;
};

/* ---- audio_stream_in (4.0-7.x: the same three calls; later and vendor slots stay NULL, which callers check) ---- */
struct audio_stream_in {
    struct audio_stream common;
    int (*set_gain)(struct audio_stream_in *s, float gain);
    long (*read)(struct audio_stream_in *s, void *buf, size_t bytes);
    uint32_t (*get_input_frames_lost)(struct audio_stream_in *s);
};
#define IN_TAIL 16
#define CHANNEL_IN_MONO 0x10
#define CHANNEL_IN_STEREO 0xC
struct in {
    struct audio_stream_in s;
    fnp tail[IN_TAIL];           /* get_capture_position and later additions: NULL = not supported */
    int fd;
    uint32_t rate, mask, device;
    unsigned channels;
    long retry_us;               /* until the next attempt to reach the host microphone */
};

/* ---------------------------------------------------------------- поток вывода */

static uint32_t o_rate(const struct audio_stream *s) { (void)s; return RATE; }
static int o_set_rate(struct audio_stream *s, uint32_t r) { (void)s; return r == RATE ? 0 : -EINVAL; }
static size_t o_bufsize(const struct audio_stream *s) { (void)s; return BUFFER_BYTES; }
static uint32_t o_channels(const struct audio_stream *s) { (void)s; return CHANNELS_STEREO; }
static int o_format(const struct audio_stream *s) { (void)s; return FORMAT_PCM16; }
static int o_set_format(struct audio_stream *s, int f) { (void)s; return f == FORMAT_PCM16 ? 0 : -EINVAL; }
static int o_dump(const struct audio_stream *s, int fd) { (void)s; (void)fd; return 0; }
static uint32_t o_device(const struct audio_stream *s) { return ((const struct out *)s)->device; }
static int o_set_device(struct audio_stream *s, uint32_t d) { ((struct out *)s)->device = d; return 0; }
static int o_set_params(struct audio_stream *s, const char *kv) { (void)s; (void)kv; return 0; }
static char *o_get_params(const struct audio_stream *s, const char *k) {
    (void)s; (void)k;
    char *r = (char *)calloc(1, 1); /* AudioFlinger освобождает строку сам */
    return r;
}
static int o_effect(const struct audio_stream *s, void *e) { (void)s; (void)e; return 0; }
static uint32_t o_latency(const struct audio_stream_out *s) { (void)s; return LATENCY_MS; }
static int o_volume(struct audio_stream_out *s, float l, float r) { (void)s; (void)l; (void)r; return -ENOSYS; }

static int o_standby(struct audio_stream *st) {
    struct out *o = (struct out *)st;
    if (o->fd >= 0) { close(o->fd); o->fd = -1; }
    return 0;
}

static long o_write(struct audio_stream_out *st, const void *buf, size_t bytes) {
    struct out *o = (struct out *)st;
    /* O_RDWR: открытие FIFO не ждёт читателя (O_WRONLY ждал бы — и микшер AudioFlinger вставал бы,
       а за ним system_server в AudioSystem.setParameters); O_NONBLOCK: полный канал не вешает микшер */
    if (o->fd < 0) o->fd = open(AEMU_PCM_DEV, O_RDWR | O_NONBLOCK);
    unsigned long frame_us = (unsigned long)(bytes / 4) * 1000000UL / RATE;
    int played = 0;
    if (o->fd >= 0) {
        const char *p = (const char *)buf;
        size_t left = bytes;
        unsigned long waited = 0;
        while (left > 0) {
            long n = write(o->fd, p, left);
            if (n > 0) { p += n; left -= (size_t)n; continue; }
            int e = n < 0 ? *__errno() : 0;
            if (e == EINTR) continue;
            if (e == EAGAIN) {
                /* приложение ещё не выбрало звук — подождём не дольше длительности куска, потом выбросим */
                if (waited >= frame_us + 20000) break;
                usleep(5000); waited += 5000;
                continue;
            }
            close(o->fd); o->fd = -1;
            break;
        }
        played = left < bytes;
    }
    /* без читателя канал не тормозит запись — держим темп сами, иначе микшер крутится на 100% процессора */
    if (!played) usleep(frame_us);
    o->frames += bytes / 4;
    return (long)bytes;
}

static int o_render_pos(const struct audio_stream_out *st, uint32_t *frames) {
    if (!frames) return -EINVAL;
    *frames = (uint32_t)((const struct out *)st)->frames;
    return 0;
}
static int o_next_ts(const struct audio_stream_out *s, int64_t *ts) { (void)s; (void)ts; return -EINVAL; }
#ifdef AEMU_DIRECTTRACK
static int o_direct_unsupported(struct audio_stream_out *s) { (void)s; return -ENOSYS; }
#endif

/* ---------------------------------------------------------------- input (microphone) */

/* the library links no compiler runtime (-nostdlib): no division by a variable, so these do it by hand */
static uint32_t udiv(uint32_t n, uint32_t d) {
    uint32_t q = 0, r = 0;
    for (int b = 31; b >= 0; b--) {
        r = (r << 1) | ((n >> b) & 1);
        if (r >= d) { r -= d; q |= 1u << b; }
    }
    return q;
}
/* duration of [frames] at [rate], in microseconds (1e6 = 15625 * 64; exact enough up to ~270000 frames) */
static unsigned long frames_us(uint32_t frames, uint32_t rate) { return (unsigned long)udiv(frames * 15625u, rate) * 64u; }

static int rate_ok(uint32_t r) {
    return r == 8000 || r == 11025 || r == 16000 || r == 22050 || r == 32000 || r == 44100 || r == 48000;
}
/* 20 ms, a whole number of 16-frame blocks */
static size_t in_buffer_bytes(uint32_t rate, unsigned channels) {
    size_t frames = ((size_t)rate / 50 + 15) & ~(size_t)15;
    return frames * channels * 2;
}

static uint32_t i_rate(const struct audio_stream *s) { return ((const struct in *)s)->rate; }
static int i_set_rate(struct audio_stream *s, uint32_t r) { return r == ((struct in *)s)->rate ? 0 : -EINVAL; }
static size_t i_bufsize(const struct audio_stream *s) { const struct in *i = (const struct in *)s; return in_buffer_bytes(i->rate, i->channels); }
static uint32_t i_channels(const struct audio_stream *s) { return ((const struct in *)s)->mask; }
static uint32_t i_device(const struct audio_stream *s) { return ((const struct in *)s)->device; }
static int i_set_device(struct audio_stream *s, uint32_t d) { ((struct in *)s)->device = d; return 0; }
static int i_set_gain(struct audio_stream_in *s, float g) { (void)s; (void)g; return 0; }
static uint32_t i_lost(struct audio_stream_in *s) { (void)s; return 0; }

static int i_standby(struct audio_stream *st) {
    struct in *i = (struct in *)st;
    if (i->fd >= 0) { close(i->fd); i->fd = -1; }   /* the host stops recording when the socket closes */
    return 0;
}

/* "MIC1", rate, channels, 0 (little endian); the host then streams PCM 16-bit in that format */
static void i_connect(struct in *i) {
    int fd = socket(1 /* AF_UNIX */, 1 | 0x80000 /* SOCK_STREAM | SOCK_CLOEXEC */, 0);
    if (fd < 0) return;
    struct { unsigned short family; char path[108]; } a;
    const char *p = "/dev/aemu_mic";
    int n = 0;
    a.family = 1;
    while (p[n]) { a.path[n] = p[n]; n++; }
    a.path[n] = 0;
    unsigned char hello[16] = { 'M', 'I', 'C', '1' };
    for (int k = 0; k < 4; k++) { hello[4 + k] = (unsigned char)(i->rate >> (8 * k)); hello[8 + k] = (unsigned char)(i->channels >> (8 * k)); }
    if (connect(fd, &a, 2 + n + 1) < 0 || write(fd, hello, sizeof(hello)) != (long)sizeof(hello)) { close(fd); return; }
    i->fd = fd;
}

static long i_read(struct audio_stream_in *st, void *buf, size_t bytes) {
    struct in *i = (struct in *)st;
    unsigned shift = i->channels == 2 ? 2 : 1;      /* bytes per frame: 4 stereo, 2 mono */
    unsigned frame = 1u << shift;
    bytes &= ~(size_t)(frame - 1);
    unsigned long dur_us = frames_us((uint32_t)(bytes >> shift), i->rate);
    if (i->fd < 0 && i->retry_us <= 0) { i_connect(i); if (i->fd < 0) i->retry_us = 1000000; }
    size_t got = 0;
    if (i->fd >= 0) {
        /* the host records in real time: waiting for the data paces the reader; give up a little after its duration */
        int waited_ms = 0, limit_ms = (int)(dur_us >> 10) + 200;   /* ~ms, generous anyway */
        while (got < bytes) {
            struct pollfd_s p = { i->fd, 1 /* POLLIN */, 0 };
            int r = poll(&p, 1, 20);
            if (r > 0) {
                long n = read(i->fd, (char *)buf + got, bytes - got);
                if (n > 0) { got += (size_t)n; continue; }
                if (n < 0 && *__errno() == EINTR) continue;
                close(i->fd); i->fd = -1; i->retry_us = 1000000;   /* host stopped (setting off, app in background) */
                break;
            }
            if (r < 0 && *__errno() == EINTR) continue;
            if (r < 0 || (waited_ms += 20) >= limit_ms) break;
        }
        got &= ~(size_t)(frame - 1);
    }
    if (got < bytes) {
        char *z = (char *)buf + got;
        for (size_t k = 0; k < bytes - got; k++) z[k] = 0;
        /* no host data: hold the real pace of a microphone, else the recorder spins */
        unsigned long rest_us = frames_us((uint32_t)((bytes - got) >> shift), i->rate);
        if (i->fd < 0) { usleep(rest_us); i->retry_us -= (long)rest_us; }
    }
    return (long)bytes;
}

static struct in *new_in(uint32_t rate, unsigned channels, uint32_t device) {
    struct in *i = (struct in *)calloc(1, sizeof(struct in));
    if (!i) return 0;
    i->fd = -1;
    i->rate = rate;
    i->channels = channels;
    i->mask = channels == 2 ? CHANNEL_IN_STEREO : CHANNEL_IN_MONO;
    i->device = device;
    i->s.common.get_sample_rate = i_rate;
    i->s.common.set_sample_rate = i_set_rate;
    i->s.common.get_buffer_size = i_bufsize;
    i->s.common.get_channels = i_channels;
    i->s.common.get_format = o_format;
    i->s.common.set_format = o_set_format;
    i->s.common.standby = i_standby;
    i->s.common.dump = o_dump;
    i->s.common.get_device = i_device;
    i->s.common.set_device = i_set_device;
    i->s.common.set_parameters = o_set_params;
    i->s.common.get_parameters = o_get_params;
    i->s.common.add_audio_effect = o_effect;
    i->s.common.remove_audio_effect = o_effect;
    i->s.set_gain = i_set_gain;
    i->s.read = i_read;
    i->s.get_input_frames_lost = i_lost;
    return i;
}

/* ---------------------------------------------------------------- устройство */

static uint32_t d_devices(const struct audio_hw_device *d) { (void)d; return DEVICE_OUT_SPEAKER | DEVICE_OUT_DEFAULT; }
static int d_zero(void) { return 0; }
static int d_float_nosys(struct audio_hw_device *d, float v) { (void)d; (void)v; return -ENOSYS; }
static int d_get_master_volume(struct audio_hw_device *d, float *v) { (void)d; (void)v; return -ENOSYS; }
static int d_set_mode(struct audio_hw_device *d, int m) { (void)d; (void)m; return 0; }
static int d_set_mic_mute(struct audio_hw_device *d, int s) { (void)d; (void)s; return 0; }
/* bool *state in audio.h: a 4-byte store clobbers the caller's stack (MediaTek AudioFlinger keeps its hardware-lock
   Autolock right after the flag and then never unlocks it; the next setParameters hangs system_server) */
static int d_get_mic_mute(const struct audio_hw_device *d, unsigned char *s) { (void)d; if (s) *s = 0; return 0; }
static int d_set_params(struct audio_hw_device *d, const char *kv) { (void)d; (void)kv; return 0; }
static char *d_get_params(const struct audio_hw_device *d, const char *k) { (void)d; (void)k; return (char *)calloc(1, 1); }
#ifdef AEMU_ICS
static size_t d_in_bufsize(const struct audio_hw_device *d, uint32_t r, int f, int ch) {
    (void)d;
    return rate_ok(r) && f == FORMAT_PCM16 && (ch == 1 || ch == 2) ? in_buffer_bytes(r, (unsigned)ch) : 0;
}
#else
static size_t d_in_bufsize(const struct audio_hw_device *d, const struct audio_config *c) {
    (void)d;
    if (!c || !rate_ok(c->sample_rate) || c->format != FORMAT_PCM16) return 0;
    if (c->channel_mask != CHANNEL_IN_MONO && c->channel_mask != CHANNEL_IN_STEREO) return 0;
    return in_buffer_bytes(c->sample_rate, c->channel_mask == CHANNEL_IN_STEREO ? 2 : 1);
}
#endif
#ifdef AEMU_QCOM
static int d_open_session(struct audio_hw_device *d, uint32_t dev, int *f, int ses, struct audio_stream_out **out) {
    (void)d; (void)dev; (void)f; (void)ses;
    if (out) *out = 0;
    return -ENOSYS;   /* no hardware decoder: AudioFlinger falls back to the software mixer */
}
#endif
static int d_dump(const struct audio_hw_device *d, int fd) { (void)d; (void)fd; return 0; }
static int d_set_master_mute(struct audio_hw_device *d, int m) { (void)d; (void)m; return -ENOSYS; }
static int d_get_master_mute(struct audio_hw_device *d, int *m) { (void)d; (void)m; return -ENOSYS; }

#ifdef AEMU_ICS
static int d_open_out(struct audio_hw_device *d, uint32_t devices, int *format, uint32_t *channels,
                      uint32_t *rate, struct audio_stream_out **out) {
    (void)d;
    if (format) *format = FORMAT_PCM16;
    if (channels) *channels = CHANNELS_STEREO;
    if (rate) *rate = RATE;
#else
static int d_open_out(struct audio_hw_device *d, int handle, uint32_t devices, int flags,
                      struct audio_config *c, struct audio_stream_out **out) {
    (void)d; (void)handle; (void)flags;
    /* поддерживаем ровно один формат; AudioFlinger сам приводит микшер к нему */
    if (c) { c->sample_rate = RATE; c->channel_mask = CHANNELS_STEREO; c->format = FORMAT_PCM16; }
#endif
    struct out *o = (struct out *)calloc(1, sizeof(struct out));
    if (!o) return -12;
    o->fd = -1;
    o->device = devices;
    o->s.common.get_sample_rate = o_rate;
    o->s.common.set_sample_rate = o_set_rate;
    o->s.common.get_buffer_size = o_bufsize;
    o->s.common.get_channels = o_channels;
    o->s.common.get_format = o_format;
    o->s.common.set_format = o_set_format;
    o->s.common.standby = o_standby;
    o->s.common.dump = o_dump;
    o->s.common.get_device = o_device;
    o->s.common.set_device = o_set_device;
    o->s.common.set_parameters = o_set_params;
    o->s.common.get_parameters = o_get_params;
    o->s.common.add_audio_effect = o_effect;
    o->s.common.remove_audio_effect = o_effect;
    o->s.get_latency = o_latency;
    o->s.set_volume = o_volume;
    o->s.write = o_write;
    o->s.get_render_position = o_render_pos;
#ifndef AEMU_ICS
#ifdef AEMU_DIRECTTRACK
    o->s.start = o_direct_unsupported;
    o->s.stop = o_direct_unsupported;
#endif
    o->s.get_next_write_timestamp = o_next_ts;
#endif
    *out = &o->s;
    return 0;
}

static void d_close_out(struct audio_hw_device *d, struct audio_stream_out *s) {
    (void)d;
    if (!s) return;
    o_standby(&s->common);
    free(s);
}

/* Any of the usual rates, mono or stereo, 16 bit. Anything else: the closest supported values are written back and
 * BAD_VALUE returned, and AudioFlinger opens again with them (resampling itself), as with real hardware. */
#ifdef AEMU_ICS
static int d_open_in(struct audio_hw_device *d, uint32_t dev, int *f, uint32_t *ch, uint32_t *r, int ac, void **in) {
    (void)d; (void)ac;
    if (in) *in = 0;
    if (!f || !ch || !r || !in) return -EINVAL;
    int bad = 0;
    if (*f != FORMAT_PCM16) { *f = FORMAT_PCM16; bad = 1; }
    if (*ch != CHANNEL_IN_MONO && *ch != CHANNEL_IN_STEREO) { *ch = CHANNEL_IN_MONO; bad = 1; }
    if (!rate_ok(*r)) { *r = 44100; bad = 1; }
    if (bad) return -EINVAL;
    struct in *i = new_in(*r, *ch == CHANNEL_IN_STEREO ? 2 : 1, dev);
#else
static int d_open_in(struct audio_hw_device *d, int h, uint32_t dev, struct audio_config *c, void **in) {
    (void)d; (void)h;
    if (in) *in = 0;
    if (!c || !in) return -EINVAL;
    int bad = 0;
    if (c->format != FORMAT_PCM16) { c->format = FORMAT_PCM16; bad = 1; }
    if (c->channel_mask != CHANNEL_IN_MONO && c->channel_mask != CHANNEL_IN_STEREO) { c->channel_mask = CHANNEL_IN_MONO; bad = 1; }
    if (!rate_ok(c->sample_rate)) { c->sample_rate = 44100; bad = 1; }
    if (bad) return -EINVAL;
    struct in *i = new_in(c->sample_rate, c->channel_mask == CHANNEL_IN_STEREO ? 2 : 1, dev);
#endif
    if (!i) return -12;
    *in = &i->s;
    return 0;
}
static void d_close_in(struct audio_hw_device *d, void *in) {
    (void)d;
    if (!in) return;
    i_standby(&((struct in *)in)->s.common);
    free(in);
}

#ifdef AEMU_MTK
/* MediaTek extends audio_hw_device with vendor calls (SetAudioCommand, VoiceUnlock...) after the AOSP slots;
   AudioFlinger calls them under its hardware lock. Give it a zeroed tail of harmless stubs. */
#define MTK_EXTRA 48
static long d_mtk_stub(void) { return 0; }
#endif
static int d_close(struct hw_device *dev) { free(dev); return 0; }

#if !defined(AEMU_ICS) && !defined(AEMU_MTK) && !defined(AEMU_QCOM)
#define AEMU_SLOTFILE 1
/*
 * Samsung KitKat AudioFlinger calls open_output_stream at a larger offset of audio_hw_device than AOSP (108): the
 * vendor inserts slots in front of it. With the AOSP layout that call lands on one of our -ENOSYS stubs, the primary
 * output never opens ("Failed to open primary output") and the audio policy then aborts in mediaserver.
 * The emulator reads the offset out of the firmware's libaudioflinger and leaves it as a decimal number in
 * /aemu.audio.slot. Returns how many bytes the tail of the structure (open_output_stream and everything after it)
 * has to move up; 0 when the file is missing or holds the AOSP offset.
 */
static unsigned slot_shift(void) {
    char b[16];
    int fd = open("/aemu.audio.slot", 0);
    if (fd < 0) return 0;
    long n = read(fd, b, sizeof(b) - 1);
    close(fd);
    if (n <= 0) return 0;
    unsigned v = 0;
    for (long i = 0; i < n; i++) { if (b[i] < '0' || b[i] > '9') break; v = v * 10 + (unsigned)(b[i] - '0'); }
    unsigned base = (unsigned)offsetof(struct audio_hw_device, open_output_stream);
    if (v <= base || v > base + 64 || (v & 3)) return 0;
    return v - base;
}
#endif

static int hal_open(const struct hw_module *m, const char *id, struct hw_device **dev) {
    const char *want = "audio_hw_if";
    for (int i = 0; ; i++) {
        if (id[i] != want[i]) return -EINVAL;
        if (!id[i]) break;
    }
    #ifdef AEMU_MTK
    struct audio_hw_device *d = (struct audio_hw_device *)calloc(1, sizeof(struct audio_hw_device) + MTK_EXTRA * sizeof(void *));
    if (d) { void **x = (void **)(d + 1); for (int i = 0; i < MTK_EXTRA; i++) x[i] = (void *)d_mtk_stub; }
#else
#ifdef AEMU_SLOTFILE
    unsigned shift = slot_shift();
#else
    unsigned shift = 0;
#endif
    struct audio_hw_device *d = (struct audio_hw_device *)calloc(1, sizeof(struct audio_hw_device) + shift);
#endif
    if (!d) return -12;
    d->common.tag = 0x48574454;          /* HARDWARE_DEVICE_TAG */
#ifdef AEMU_ICS
    d->common.version = 0;               /* 4.0: no device API versioning */
#else
    d->common.version = 0x0200;          /* AUDIO_DEVICE_API_VERSION_2_0 */
#endif
    d->common.module = (struct hw_module *)m;
    d->common.close = d_close;
    d->get_supported_devices = d_devices;
    d->init_check = (int (*)(const struct audio_hw_device *))d_zero;
    d->set_voice_volume = d_float_nosys;
    d->set_master_volume = d_float_nosys;
#ifdef AEMU_QCOM
    d->set_fm_volume = d_float_nosys;
    d->open_output_session = d_open_session;
#endif
#ifndef AEMU_ICS
    d->get_master_volume = d_get_master_volume;
#endif
    d->set_mode = d_set_mode;
    d->set_mic_mute = d_set_mic_mute;
    d->get_mic_mute = d_get_mic_mute;
    d->set_parameters = d_set_params;
    d->get_parameters = d_get_params;
    d->get_input_buffer_size = d_in_bufsize;
    d->open_output_stream = d_open_out;
    d->close_output_stream = d_close_out;
    d->open_input_stream = d_open_in;
    d->close_input_stream = d_close_in;
    d->dump = d_dump;
#ifndef AEMU_ICS
    d->set_master_mute = d_set_master_mute;
    d->get_master_mute = d_get_master_mute;
#endif
#ifdef AEMU_SLOTFILE
    if (shift) {
        /* open_output_stream .. get_master_mute move up by [shift]; the slots left in front of them answer 0 */
        void **w = (void **)d;
        unsigned first = (unsigned)offsetof(struct audio_hw_device, open_output_stream) / sizeof(void *);
        unsigned last = (unsigned)sizeof(struct audio_hw_device) / sizeof(void *);
        unsigned k = shift / sizeof(void *);
        for (unsigned i = last; i-- > first; ) w[i + k] = w[i];
        for (unsigned i = first; i < first + k; i++) w[i] = (void *)d_zero;
    }
#endif
    *dev = &d->common;
    return 0;
}

static struct hw_module_methods g_methods = { hal_open };

__attribute__((visibility("default"))) struct hw_module HMI = {
    .tag = 0x48574d54,                    /* HARDWARE_MODULE_TAG */
    .module_api_version = 0x0001,         /* AUDIO_MODULE_API_VERSION_0_1 */
    .hal_api_version = 0,
    .id = "audio",
    .name = "AEmulator audio HAL (/dev/eac)",
    .author = "AEmulator",
    .methods = &g_methods,
};

#ifdef AEMU_MTK
/*
 * MediaTek: libaudioflinger takes the primary HAL straight from /system/lib/libaudio.primary.default.so
 * (its HMI) and also links the DcRemove filter from it. This build replaces that library: HMI is our HAL,
 * DcRemove calls go to the vendor original, renamed to libaudio.mtk.so by the emulator.
 */
extern void *dlopen(const char *name, int flags);
extern void *dlsym(void *h, const char *name);
static const char *const k_dc[] = {
    "_ZN7android8DcRemoveC1Ev", "_ZN7android8DcRemoveD1Ev", "_ZN7android8DcRemove4initEjjj",
    "_ZN7android8DcRemove7processEPKvjPv", "_ZN7android8DcRemove5closeEv",
};
static void *g_dc[5];
static void dc_trap(void) { __builtin_trap(); }
__attribute__((used)) static void *dc_resolve(unsigned i) {
    if (!g_dc[i]) {
        static void *h;
        if (!h) h = dlopen("/system/lib/libaudio.mtk.so", 0);
        g_dc[i] = h ? dlsym(h, k_dc[i]) : 0;
        if (!g_dc[i]) g_dc[i] = (void *)dc_trap;
    }
    return g_dc[i];
}
#define DC_STUB(sym, idx) \
    __asm__(".text\n.global " sym "\n.type " sym ",%function\n.thumb_func\n" sym ":\n" \
            "push {r0-r3, lr}\nmovs r0, #" #idx "\nbl dc_resolve\nmov r12, r0\npop {r0-r3, lr}\nbx r12\n");
DC_STUB("_ZN7android8DcRemoveC1Ev", 0)
DC_STUB("_ZN7android8DcRemoveD1Ev", 1)
DC_STUB("_ZN7android8DcRemove4initEjjj", 2)
DC_STUB("_ZN7android8DcRemove7processEPKvjPv", 3)
DC_STUB("_ZN7android8DcRemove5closeEv", 4)
#endif
