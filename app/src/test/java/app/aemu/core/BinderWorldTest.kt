/* AEmulator Super Plus, 2026-10-11: /dev/hwbinder daemon. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderWorldTest {
    private val world = BinderWorld("hwbinder") { println(it) }

    /** a guest thread: what the daemon sends it, decoded */
    private class G(world: BinderWorld, pid: Int, tid: Int) {
        val got = ArrayList<ByteArray>()
        val t = world.attach(pid, tid, 1000) { got.add(it) }
        fun take(): ByteArray = got.removeAt(0)
        fun takeSimple(): Int { val f = take(); assertEquals(Wire.D_SIMPLE, Wire.u32(f, 4)); return Wire.u32(f, 8) }
        fun takeTxn(): Txn { val f = take(); assertEquals("expected a transaction", Wire.D_TXN, Wire.u32(f, 4)); return Txn(f.copyOfRange(8, f.size)) }
    }

    /** decoded D_TXN body */
    private class Txn(val b: ByteArray) {
        val ptr get() = Wire.u64(b, 0)
        val cookie get() = Wire.u64(b, 8)
        val reply get() = Wire.u32(b, 16) != 0
        val code get() = Wire.u32(b, 24)
        val senderPid get() = Wire.u32(b, 28)
        val data: ByteArray get() = b.copyOfRange(48, 48 + Wire.u32(b, 36))
        val offCount get() = Wire.u32(b, 40)
        val sgCount get() = Wire.u32(b, 44)
        val sgBytes: ByteArray get() = b.copyOfRange(48 + Wire.align8(Wire.u32(b, 36)) + Wire.align8(4 * offCount), b.size)
    }

    /** M_TXN body exactly as hwbinder.c builds it */
    private fun txn(reply: Boolean, handle: Int, code: Int, flags: Int, data: ByteArray, offsets: IntArray = IntArray(0),
                    sg: List<Pair<Int, ByteArray>> = emptyList()): ByteArray {
        val oOff = 28 + Wire.align8(data.size)
        val sgOff = oOff + Wire.align8(4 * offsets.size)
        val b = ByteArray(sgOff + sg.sumOf { 8 + Wire.align8(it.second.size) })
        Wire.put32(b, 0, if (reply) 1 else 0); Wire.put32(b, 4, handle); Wire.put32(b, 8, code); Wire.put32(b, 12, flags)
        Wire.put32(b, 16, data.size); Wire.put32(b, 20, offsets.size); Wire.put32(b, 24, sg.size)
        data.copyInto(b, 28)
        offsets.forEachIndexed { i, o -> Wire.put32(b, oOff + 4 * i, o) }
        var p = sgOff
        for ((idx, bytes) in sg) { Wire.put32(b, p, idx); Wire.put32(b, p + 4, bytes.size); bytes.copyInto(b, p + 8); p += 8 + Wire.align8(bytes.size) }
        return b
    }

    private fun obj(type: Int, a: Long, cookie: Long = 0) = ByteArray(24).also { Wire.put32(it, 0, type); Wire.put64(it, 8, a); Wire.put64(it, 16, cookie) }
    private fun ptrObj(len: Int) = ByteArray(40).also { Wire.put32(it, 0, Wire.T_PTR); Wire.put64(it, 8, 0x5000); Wire.put64(it, 16, len.toLong()) }
    private fun u32s(vararg v: Int) = ByteArray(4 * v.size).also { b -> v.forEachIndexed { i, x -> Wire.put32(b, 4 * i, x) } }

    private fun serviceManager(): G = G(world, 100, 100).apply {
        world.onMessage(t, Wire.LOOPER, u32s(2))
        world.onMessage(t, Wire.CTXMGR, ByteArray(0))
        world.onMessage(t, Wire.IDLE, ByteArray(0))
    }

    @Test fun halRegistersWithTheContextManagerAndGetsTheReply() {
        val sm = serviceManager()
        val hal = G(world, 200, 200)
        val name = "android.hardware.configstore@1.0::ISurfaceFlingerConfigs".toByteArray()
        // add(name, service): a hidl_string (PTR object + its buffer) and the HAL's own binder object
        val data = ptrObj(name.size) + obj(Wire.T_BINDER, 0x1000, 0x2000)
        world.onMessage(hal.t, Wire.TXN, txn(false, 0, 2, 0, data, intArrayOf(0, 40), listOf(0 to name)))

        assertEquals(Wire.BR_TRANSACTION_COMPLETE, hal.takeSimple())
        val inbound = sm.takeTxn()
        assertEquals(0L, inbound.ptr)                      // the context object
        assertEquals(2, inbound.code)
        assertEquals(200, inbound.senderPid)
        assertEquals("the HAL's binder arrives as handle 1", Wire.T_HANDLE, Wire.u32(inbound.data, 40))
        assertEquals(1L, Wire.u64(inbound.data, 48))
        assertEquals(1, inbound.sgCount)
        assertArrayEquals(name, inbound.sgBytes.copyOfRange(8, 8 + name.size))

        world.onMessage(sm.t, Wire.TXN, txn(true, 0, 0, 0, u32s(0)))
        assertEquals(Wire.BR_TRANSACTION_COMPLETE, sm.takeSimple())
        val reply = hal.takeTxn()
        assertTrue(reply.reply)
        assertArrayEquals(u32s(0), reply.data)
    }

    @Test fun nestedCallReachesTheThreadThatIsWaiting() {
        val sm = serviceManager()
        val hal = G(world, 200, 200)
        world.onMessage(hal.t, Wire.TXN, txn(false, 0, 2, 0, obj(Wire.T_BINDER, 0x1000, 0x2000), intArrayOf(0)))
        hal.takeSimple()
        sm.takeTxn()
        // while handling add(), hwservicemanager calls interfaceChain() on the HAL (handle 1)
        world.onMessage(sm.t, Wire.TXN, txn(false, 1, 0xf43484e, 0, ByteArray(0)))
        assertEquals(Wire.BR_TRANSACTION_COMPLETE, sm.takeSimple())
        val nested = hal.takeTxn()
        assertEquals("the HAL's own node, by ptr", 0x1000L, nested.ptr)
        assertEquals(0x2000L, nested.cookie)
        world.onMessage(hal.t, Wire.TXN, txn(true, 0, 0, 0, u32s(7)))
        assertEquals(Wire.BR_TRANSACTION_COMPLETE, hal.takeSimple())
        assertArrayEquals(u32s(7), sm.takeTxn().data)
    }

    @Test fun ownBinderComesBackAsPtrAndProcessDeathIsReported() {
        val sm = serviceManager()
        val hal = G(world, 200, 200)
        world.onMessage(hal.t, Wire.TXN, txn(false, 0, 2, 0, obj(Wire.T_BINDER, 0x1000, 0x2000), intArrayOf(0)))
        hal.takeSimple(); sm.takeTxn()
        world.onMessage(sm.t, Wire.DEATH, u32s(1, 1) + Wire.cookie(0xdead))
        world.onMessage(sm.t, Wire.TXN, txn(true, 0, 0, 0, ByteArray(0)))
        sm.takeSimple(); hal.takeTxn()
        world.onMessage(sm.t, Wire.IDLE, ByteArray(0))
        world.detach(hal.t)
        val f = sm.take()
        assertEquals(Wire.BR_DEAD_BINDER, Wire.u32(f, 8))
        assertEquals(0xdeadL, Wire.u64(f, 12))
    }

    @Test fun transactionToUnknownHandleFailsAndFdsAreRefused() {
        serviceManager()
        val c = G(world, 300, 300)
        world.onMessage(c.t, Wire.TXN, txn(false, 9, 1, 0, ByteArray(0)))
        assertEquals(Wire.BR_FAILED_REPLY, c.takeSimple())
        world.onMessage(c.t, Wire.TXN, txn(false, 0, 1, 0, obj(Wire.T_FD, 3), intArrayOf(0)))
        assertEquals(Wire.BR_FAILED_REPLY, c.takeSimple())
    }

    @Test fun processWorkWaitsForAnIdleLooper() {
        val sm = G(world, 100, 100)
        world.onMessage(sm.t, Wire.LOOPER, u32s(2))
        world.onMessage(sm.t, Wire.CTXMGR, ByteArray(0))     // looper, but not idle yet
        val c = G(world, 300, 300)
        world.onMessage(c.t, Wire.TXN, txn(false, 0, 5, Wire.TF_ONE_WAY, ByteArray(0)))
        assertEquals(Wire.BR_TRANSACTION_COMPLETE, c.takeSimple())
        assertTrue(sm.got.isEmpty())
        world.onMessage(sm.t, Wire.IDLE, ByteArray(0))
        assertEquals(5, sm.takeTxn().code)
    }
}
