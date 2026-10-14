package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PropAreaTrieTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * Android 8.0's own lookup (bionic android-8.0.0_r1 system_properties.cpp: map_fd_ro checks, find_property,
     * find_prop_bt, cmp_prop_name), written out separately from PropArea so a layout mistake shows here.
     */
    private fun bionicGet(file: File, name: String): String? {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(8) != 0x504f5250 || b.getInt(12) != 0xfc6ed0ab.toInt()) return null
        val data = 128
        val dataSize = b.capacity() - data
        fun obj(off: Int): Int? = if (off > dataSize) null else data + off
        fun cmp(seg: String, node: Int): Int {
            val nl = b.getInt(node)
            if (seg.length != nl) return if (seg.length < nl) -1 else 1
            for (i in seg.indices) {
                val d = (seg[i].code and 0xff) - (b.get(node + 20 + i).toInt() and 0xff)
                if (d != 0) return d
            }
            return 0
        }
        var current = obj(0)!!                       // root_node()
        for (seg in name.split('.')) {
            if (seg.isEmpty()) return null
            val children = b.getInt(current + 16)
            if (children == 0) return null
            var bt = obj(children) ?: return null
            while (true) {
                val r = cmp(seg, bt)
                if (r == 0) break
                val next = b.getInt(bt + if (r < 0) 8 else 12)
                if (next == 0) return null
                bt = obj(next) ?: return null
            }
            current = bt
        }
        val prop = b.getInt(current + 4)
        if (prop == 0) return null
        val pi = obj(prop)!!
        val serial = b.getInt(pi)
        assertEquals("not left dirty", 0, serial and 1)
        val len = serial ushr 24
        val nameAt = pi + 96
        val stored = String(ByteArray(name.length) { b.get(nameAt + it) })
        assertEquals("prop_info carries the full name", name, stored)
        return String(ByteArray(len) { b.get(pi + 4 + it) })
    }

    private fun v1(vararg props: Pair<String, String>): File {
        val f = File(tmp.root, "props-${System.nanoTime()}")
        PropArea.create(f).use { a -> props.forEach { (k, v) -> assertTrue(a.put(k, v)) } }
        return f
    }

    @Test fun convertedAreaIsReadableTheBionic8Way() {
        val props = arrayOf(
            "ro.product.cpu.abilist32" to "armeabi-v7a,armeabi", "ro.product.cpu.abi" to "armeabi-v7a",
            "ro.product.cpu.abilist" to "armeabi-v7a,armeabi", "ro.dalvik.vm.native.bridge" to "0",
            "ro.build.version.sdk" to "26", "persist.sys.timezone" to "UTC", "ro.secure" to "0", "dalvik.vm.heapsize" to "256m",
            "ro.zygote" to "zygote32", "sys.usb.config" to "none", "ro" to "top-level", "a.b.c.d.e" to "deep",
        )
        val f = v1(*props)
        assertTrue(PropArea.convertToTrie(f))
        for ((k, v) in props) assertEquals(k, v, bionicGet(f, k))
        assertNull(bionicGet(f, "ro.product.cpu.abilist64"))
        assertNull(bionicGet(f, "ro.product"))           // an inner node, not a property
        PropArea.open(f)!!.use { a ->
            assertTrue(a.isTrie)
            assertEquals(props.size, a.count)
            assertEquals(props.toMap(), a.all().toMap())
        }
    }

    @Test fun putUpdatesAndAddsInTheTrie() {
        val f = v1("ro.product.cpu.abilist32" to "")
        assertTrue(PropArea.convertToTrie(f))
        PropArea.open(f)!!.use { a ->
            val serial0 = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            assertTrue(a.put("ro.product.cpu.abilist32", "armeabi-v7a,armeabi"))   // an empty value replaced
            assertTrue(a.put("sys.boot_completed", "1"))                           // a new one
            assertEquals("armeabi-v7a,armeabi", a.get("ro.product.cpu.abilist32"))
            val serial1 = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            assertEquals("the area serial moves on each write", serial0 + 2, serial1)
        }
        assertEquals("armeabi-v7a,armeabi", bionicGet(f, "ro.product.cpu.abilist32"))
        assertEquals("1", bionicGet(f, "sys.boot_completed"))
    }

    @Test fun conversionKeepsTheFileAndIsIdempotent() {
        val f = v1("ro.secure" to "0")
        assertTrue(PropArea.convertToTrie(f))
        val once = f.readBytes()
        assertTrue(PropArea.convertToTrie(f))
        assertTrue(once.contentEquals(f.readBytes()))
        assertEquals(PropArea.TRIE_SIZE.toLong(), f.length())
    }
}
