/* AEmulator Super Plus, 2026-10-11: /dev/hwbinder and /dev/vndbinder for Android 8+. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.net.LocalSocket
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * Host side of the Treble binder devices. The engine (patched QEMU + binderd, prebuilt without source) only knows
 * /dev/binder; the guest library libaemuhwb.so (native/guestshim/hwbinder.c) intercepts /dev/hwbinder and
 * /dev/vndbinder and talks to this daemon over the unix socket /dev/socket/aemu_hwbinder, one connection per
 * guest thread. Each device is its own [BinderWorld] with its own context manager (hwservicemanager,
 * vndservicemanager).
 *
 * Wire format: frames of u32 length (of what follows), u32 type, payload; little endian.
 *   guest -> daemon
 *     1 HELLO        u32 dev (1 hwbinder, 2 vndbinder), pid, tid, euid [, protocol 7/8]  (first frame of every connection)
 *     2 LOOPER       u32 1 register, 2 enter, 3 exit
 *     3 CTXMGR
 *     4 TXN          u32 is_reply, handle, code, flags, data_len, off_count, sg_count; data (pad 8);
 *                    u32 offsets (pad 8); per scatter-gather buffer: u32 object index, u32 length, bytes (pad 8)
 *     5 REF          u32 BC number (4 incref, 5 acquire, 6 release, 7 decref), u32 handle
 *     6 DEATH        u32 1 request / 2 clear, u32 handle, u64 cookie
 *     7 IDLE         the thread has nothing to do and no reply outstanding: it may be handed process work
 *     8 THREAD_EXIT
 *    11 MAXTHREADS   u32
 *   daemon -> guest
 *   101 SIMPLE       u32 BR code, payload in kernel layout
 *   102 TXN          u64 target ptr, u64 cookie, u32 is_reply, flags, code, sender_pid, sender_euid, data_len,
 *                    off_count, sg_count; data, offsets and scatter-gather buffers as above
 * Binder objects in the data are translated here (node <-> handle) for the receiving process, in the guest's own
 * layout: 24 bytes in protocol 8, 16 in protocol 7 (the 32-bit binder ABI of upgraded 32-bit phones); scatter-gather
 * buffers travel as they are and are placed and linked by the receiving guest library.
 */
class HwBinderDaemon(private val sockFile: File, private val log: (String) -> Unit) {
    private val worlds = mapOf(1 to BinderWorld("hwbinder", log), 2 to BinderWorld("vndbinder", log))
    private val server = UnixServer(sockFile, "hwbinder") { serve(it) }

    fun start(): Boolean = server.start(log).also { if (it) log("hwbinder: daemon listening (/dev/hwbinder, /dev/vndbinder)") }
    fun stop() = server.stop()

    private fun serve(s: LocalSocket) {
        val inp = DataInputStream(BufferedInputStream(s.inputStream, 1 shl 16))
        val out = s.outputStream
        val hello = readFrame(inp) ?: return
        if (hello.type != Wire.HELLO || hello.body.size < 16) { log("hwbinder: connection without HELLO"); return }
        val world = worlds[hello.u32(0)] ?: run { log("hwbinder: unknown device ${hello.u32(0)}"); return }
        val queue = LinkedBlockingQueue<ByteArray>()
        val closed = ByteArray(0)
        // a separate writer: the world never blocks on a guest that is busy and not reading
        val writer = Thread({
            try {
                while (true) {
                    val b = queue.take()
                    if (b === closed) break
                    out.write(b)
                    out.flush()
                }
            } catch (_: Throwable) {}
        }, "hwbinder-out").apply { isDaemon = true; start() }
        val protocol = if (hello.body.size >= 20) hello.u32(16) else 8
        val th = world.attach(hello.u32(4), hello.u32(8), hello.u32(12), protocol == 7) { queue.add(it) }
        try {
            while (true) {
                val f = readFrame(inp) ?: break
                world.onMessage(th, f.type, f.body)
            }
        } catch (e: Throwable) {
            if (e !is EOFException) log("hwbinder: connection error: $e")
        } finally {
            world.detach(th)
            queue.add(closed)
            writer.join(1000)
        }
    }

    private class Frame(val type: Int, val body: ByteArray) {
        fun u32(off: Int) = Wire.u32(body, off)
    }

    private fun readFrame(inp: DataInputStream): Frame? {
        val len = try { Integer.reverseBytes(inp.readInt()) } catch (_: EOFException) { return null }
        if (len < 4 || len > (64 shl 20)) throw EOFException("bad frame length $len")
        val type = Integer.reverseBytes(inp.readInt())
        val body = ByteArray(len - 4)
        inp.readFully(body)
        return Frame(type, body)
    }
}

/** Frame types, BR codes and object types of the protocol above (BR payloads in the protocol 8 layout; the guest library narrows them). */
object Wire {
    const val HELLO = 1; const val LOOPER = 2; const val CTXMGR = 3; const val TXN = 4; const val REF = 5
    const val DEATH = 6; const val IDLE = 7; const val THREAD_EXIT = 8; const val MAXTHREADS = 11
    const val D_SIMPLE = 101; const val D_TXN = 102

    const val BR_TRANSACTION_COMPLETE = 0x7206
    const val BR_DEAD_REPLY = 0x7205
    const val BR_FAILED_REPLY = 0x7211
    const val BR_SPAWN_LOOPER = 0x720d
    const val BR_DEAD_BINDER = 0x8008720f.toInt()
    const val BR_CLEAR_DEATH_NOTIFICATION_DONE = 0x80087210.toInt()

    const val T_BINDER = 0x73622a85; const val T_WEAK_BINDER = 0x77622a85
    const val T_HANDLE = 0x73682a85; const val T_WEAK_HANDLE = 0x77682a85
    const val T_FD = 0x66642a85; const val T_FDA = 0x66646185; const val T_PTR = 0x70742a85
    const val TF_ONE_WAY = 1

    fun u32(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
        ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    fun u64(b: ByteArray, o: Int) = (u32(b, o).toLong() and 0xffffffffL) or (u32(b, o + 4).toLong() shl 32)
    fun put32(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte() }
    fun put64(b: ByteArray, o: Int, v: Long) { put32(b, o, v.toInt()); put32(b, o + 4, (v ushr 32).toInt()) }
    fun align8(n: Int) = (n + 7) and 7.inv()

    fun frame(type: Int, body: ByteArray): ByteArray =
        ByteArray(8 + body.size).also { put32(it, 0, 4 + body.size); put32(it, 4, type); body.copyInto(it, 8) }
    fun simple(br: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        frame(D_SIMPLE, ByteArray(4 + payload.size).also { put32(it, 0, br); payload.copyInto(it, 4) })
    fun cookie(c: Long) = ByteArray(8).also { put64(it, 0, c) }
}

/**
 * One binder context (one device): processes, their threads (= guest connections), nodes (binder objects owned
 * by a process), per-process handle tables and the transaction stacks that route replies and nested calls.
 * Reference counts are not tracked yet: handles live as long as the process that holds them.
 */
class BinderWorld(val name: String, private val log: (String) -> Unit) {
    /** [narrow]: the 32-bit binder ABI (protocol 7), binder objects are 16 bytes with 4-byte pointer and cookie */
    class Proc(val pid: Int, val euid: Int, val narrow: Boolean) {
        val threads = ArrayList<Thread>()
        val nodes = HashMap<Pair<Long, Long>, Node>()
        val handles = HashMap<Int, Node>()
        val handleOf = HashMap<Node, Int>()
        var nextHandle = 1
        val todo = ArrayDeque<Work>()
        var maxThreads = 0
        var registered = 0
        var spawnPending = 0
        var dead = false
        fun handleFor(n: Node): Int = handleOf.getOrPut(n) { nextHandle++.also { handles[it] = n } }
    }
    class Thread(val proc: Proc, val tid: Int, val sink: (ByteArray) -> Unit) {
        var looper = false
        var idle = false
        val incoming = ArrayDeque<Txn>()
        var outgoing = 0
        var alive = true
        val busy get() = outgoing > 0 || incoming.isNotEmpty()
    }
    class Node(val owner: Proc, val ptr: Long, val cookie: Long) {
        val deathSubs = ArrayList<Pair<Proc, Long>>()
    }
    class Txn(val from: Thread, val to: Proc)
    class Work(val frame: ByteArray, val txn: Txn? = null)

    private val procs = HashMap<Int, Proc>()
    private var ctx: Node? = null

    @Synchronized fun attach(pid: Int, tid: Int, euid: Int, narrow: Boolean, sink: (ByteArray) -> Unit): Thread {
        val p = procs.getOrPut(pid) { Proc(pid, euid, narrow).also { log("$name: process $pid came" + if (narrow) " (32-bit binder ABI)" else "") } }
        return Thread(p, tid, sink).also { p.threads.add(it) }
    }

    @Synchronized fun detach(t: Thread) {
        if (!t.alive) return
        t.alive = false
        val p = t.proc
        p.threads.remove(t)
        // calls this thread took and will never answer
        while (t.incoming.isNotEmpty()) failTxn(t.incoming.removeLast(), Wire.BR_DEAD_REPLY)
        if (p.threads.isEmpty()) procDied(p)
    }

    private fun procDied(p: Proc) {
        p.dead = true
        procs.remove(p.pid)
        log("$name: process ${p.pid} gone")
        if (ctx?.owner === p) { ctx = null; log("$name: context manager gone") }
        while (p.todo.isNotEmpty()) p.todo.removeFirst().txn?.let { failTxn(it, Wire.BR_DEAD_REPLY) }
        for (n in p.nodes.values) for ((sub, cookie) in n.deathSubs) if (!sub.dead) pushProc(sub, Work(Wire.simple(Wire.BR_DEAD_BINDER, Wire.cookie(cookie))))
    }

    private fun failTxn(x: Txn, br: Int) {
        x.from.outgoing = maxOf(0, x.from.outgoing - 1)
        if (x.from.alive) push(x.from, Wire.simple(br))
    }

    @Synchronized fun onMessage(t: Thread, type: Int, b: ByteArray) {
        if (!t.alive) return
        when (type) {
            Wire.LOOPER -> when (Wire.u32(b, 0)) {
                1 -> { t.looper = true; t.proc.registered++; t.proc.spawnPending = maxOf(0, t.proc.spawnPending - 1) }
                2 -> t.looper = true
                3 -> { t.looper = false; t.idle = false }
            }
            Wire.CTXMGR -> {
                val cur = ctx
                if (cur != null && !cur.owner.dead) log("$name: context manager already taken by ${cur.owner.pid}, refused for ${t.proc.pid}")
                else {
                    ctx = t.proc.nodes.getOrPut(0L to 0L) { Node(t.proc, 0, 0) }
                    log("$name: context manager is process ${t.proc.pid}")
                }
            }
            Wire.TXN -> txn(t, b)
            Wire.REF -> if (Wire.u32(b, 4) != 0 && t.proc.handles[Wire.u32(b, 4)] == null) log("$name: ref op on unknown handle ${Wire.u32(b, 4)} (pid ${t.proc.pid})")
            Wire.DEATH -> death(t, Wire.u32(b, 0), Wire.u32(b, 4), Wire.u64(b, 8))
            Wire.IDLE -> idle(t)
            Wire.THREAD_EXIT -> detach(t)
            Wire.MAXTHREADS -> t.proc.maxThreads = Wire.u32(b, 0)
            else -> log("$name: unknown message $type")
        }
    }

    private fun idle(t: Thread) {
        if (!t.looper || t.busy) return
        val w = t.proc.todo.removeFirstOrNull()
        if (w == null) { t.idle = true; return }
        deliver(t, w)
    }

    private fun death(t: Thread, op: Int, handle: Int, cookie: Long) {
        val n = t.proc.handles[handle] ?: (if (handle == 0) ctx else null)
        if (op == 1) {
            when {
                n == null -> log("$name: death notification for unknown handle $handle (pid ${t.proc.pid})")
                n.owner.dead -> pushProc(t.proc, Work(Wire.simple(Wire.BR_DEAD_BINDER, Wire.cookie(cookie))))
                else -> n.deathSubs.add(t.proc to cookie)
            }
        } else {
            n?.deathSubs?.removeAll { it.first === t.proc && it.second == cookie }
            push(t, Wire.simple(Wire.BR_CLEAR_DEATH_NOTIFICATION_DONE, Wire.cookie(cookie)))
        }
    }

    private fun push(t: Thread, frame: ByteArray) { runCatching { t.sink(frame) } }

    private fun deliver(t: Thread, w: Work) {
        t.idle = false
        w.txn?.let { t.incoming.addLast(it) }
        push(t, w.frame)
    }

    /** Process work goes to an idle looper thread, or waits in the process queue (and asks for one more looper). */
    private fun pushProc(p: Proc, w: Work) {
        val t = p.threads.firstOrNull { it.looper && it.idle && !it.busy }
        if (t != null) { deliver(t, w); return }
        p.todo.addLast(w)
        if (p.registered + p.spawnPending < p.maxThreads) {
            p.threads.firstOrNull { it.looper }?.let { p.spawnPending++; push(it, Wire.simple(Wire.BR_SPAWN_LOOPER)) }
        }
    }

    private fun txn(t: Thread, b: ByteArray) {
        val isReply = Wire.u32(b, 0) != 0
        val handle = Wire.u32(b, 4); val code = Wire.u32(b, 8); val flags = Wire.u32(b, 12)
        val dlen = Wire.u32(b, 16); val n = Wire.u32(b, 20); val sg = Wire.u32(b, 24)
        val dOff = 28
        val oOff = dOff + Wire.align8(dlen)
        val sgOff = oOff + Wire.align8(4 * n)
        if (dlen < 0 || n < 0 || sg < 0 || sgOff > b.size) { log("$name: malformed transaction from ${t.proc.pid}"); push(t, Wire.simple(Wire.BR_FAILED_REPLY)); return }
        val data = b.copyOfRange(dOff, dOff + dlen)
        val offsets = IntArray(n) { Wire.u32(b, oOff + 4 * it) }
        val sgBytes = b.copyOfRange(sgOff, b.size)

        if (isReply) {
            val x = t.incoming.removeLastOrNull()
            push(t, Wire.simple(Wire.BR_TRANSACTION_COMPLETE))
            if (x == null) { log("$name: reply without a transaction from ${t.proc.pid}/${t.tid}"); return }
            x.from.outgoing = maxOf(0, x.from.outgoing - 1)
            if (!x.from.alive) return
            if (!translate(t.proc, x.from.proc, data, offsets)) { push(x.from, Wire.simple(Wire.BR_FAILED_REPLY)); return }
            push(x.from, txnFrame(0, 0, true, flags, 0, t.proc, data, offsets, sg, sgBytes))
            return
        }

        val node = if (handle == 0) ctx else t.proc.handles[handle]
        when {
            node == null -> { log("$name: transaction to unknown handle $handle from ${t.proc.pid} (code $code)"); push(t, Wire.simple(Wire.BR_FAILED_REPLY)); return }
            node.owner.dead -> { push(t, Wire.simple(Wire.BR_DEAD_REPLY)); return }
        }
        node!!
        if (!translate(t.proc, node.owner, data, offsets)) { push(t, Wire.simple(Wire.BR_FAILED_REPLY)); return }
        push(t, Wire.simple(Wire.BR_TRANSACTION_COMPLETE))
        val frame = txnFrame(node.ptr, node.cookie, false, flags, code, t.proc, data, offsets, sg, sgBytes)
        if (flags and Wire.TF_ONE_WAY != 0) { pushProc(node.owner, Work(frame)); return }
        val x = Txn(t, node.owner)
        t.outgoing++
        // a nested call back into a process that is waiting on us goes to the very thread that waits (as the driver does)
        val waiter = t.incoming.lastOrNull { it.from.proc === node.owner && it.from.alive }?.from
        if (waiter != null) deliver(waiter, Work(frame, x)) else pushProc(node.owner, Work(frame, x))
    }

    /** Rewrites the binder objects in [data] for [to]: own nodes become (ptr, cookie), others handles. */
    private fun translate(from: Proc, to: Proc, data: ByteArray, offsets: IntArray): Boolean {
        if (from.narrow != to.narrow) { log("$name: ${from.pid} and ${to.pid} use different binder ABIs"); return false }
        // flat_binder_object: type, flags, binder/handle, cookie; pointer-sized fields are 8 or 4 bytes
        val narrow = from.narrow
        val size = if (narrow) 16 else 24
        fun word(o: Int): Long = if (narrow) Wire.u32(data, o).toLong() and 0xffffffffL else Wire.u64(data, o)
        fun putWord(o: Int, v: Long) { if (narrow) Wire.put32(data, o, v.toInt()) else Wire.put64(data, o, v) }
        val cookieAt = if (narrow) 12 else 16
        for (off in offsets) {
            if (off < 0 || off + 4 > data.size) { log("$name: object offset $off outside the data (${from.pid})"); return false }
            val type = Wire.u32(data, off)
            when (type) {
                Wire.T_BINDER, Wire.T_WEAK_BINDER, Wire.T_HANDLE, Wire.T_WEAK_HANDLE -> {
                    if (off + size > data.size) return false
                    val strong = type == Wire.T_BINDER || type == Wire.T_HANDLE
                    val node = if (type == Wire.T_BINDER || type == Wire.T_WEAK_BINDER) {
                        val ptr = word(off + 8); val cookie = word(off + cookieAt)
                        from.nodes.getOrPut(ptr to cookie) { Node(from, ptr, cookie) }
                    } else from.handles[Wire.u32(data, off + 8)] ?: run {
                        log("$name: object with unknown handle ${Wire.u32(data, off + 8)} from ${from.pid}"); return false
                    }
                    if (node.owner === to) {
                        Wire.put32(data, off, if (strong) Wire.T_BINDER else Wire.T_WEAK_BINDER)
                        putWord(off + 8, node.ptr); putWord(off + cookieAt, node.cookie)
                    } else {
                        Wire.put32(data, off, if (strong) Wire.T_HANDLE else Wire.T_WEAK_HANDLE)
                        putWord(off + 8, to.handleFor(node).toLong()); putWord(off + cookieAt, 0)
                    }
                }
                Wire.T_PTR -> {}
                Wire.T_FD, Wire.T_FDA -> { log("$name: file descriptors in transactions are not supported yet (${from.pid} -> ${to.pid})"); return false }
                else -> { log("$name: unknown object type 0x${Integer.toHexString(type)} from ${from.pid}"); return false }
            }
        }
        return true
    }

    private fun txnFrame(ptr: Long, cookie: Long, reply: Boolean, flags: Int, code: Int, sender: Proc,
                         data: ByteArray, offsets: IntArray, sg: Int, sgBytes: ByteArray): ByteArray {
        val oOff = 48 + Wire.align8(data.size)
        val body = ByteArray(oOff + Wire.align8(4 * offsets.size) + sgBytes.size)
        Wire.put64(body, 0, ptr); Wire.put64(body, 8, cookie)
        Wire.put32(body, 16, if (reply) 1 else 0); Wire.put32(body, 20, flags); Wire.put32(body, 24, code)
        Wire.put32(body, 28, sender.pid); Wire.put32(body, 32, sender.euid)
        Wire.put32(body, 36, data.size); Wire.put32(body, 40, offsets.size); Wire.put32(body, 44, sg)
        data.copyInto(body, 48)
        offsets.forEachIndexed { i, o -> Wire.put32(body, oOff + 4 * i, o) }
        sgBytes.copyInto(body, oOff + Wire.align8(4 * offsets.size))
        return Wire.frame(Wire.D_TXN, body)
    }
}
