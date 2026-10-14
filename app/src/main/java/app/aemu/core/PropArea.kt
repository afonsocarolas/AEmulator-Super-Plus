package app.aemu.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Область системных свойств в формате bionic v1 ("PROP"/0x45434f76).
 * Этот формат понимают bionic 2.x напрямую и 4.x–5.x в режиме совместимости,
 * когда область передана через ANDROID_PROPERTY_WORKSPACE.
 *
 *  0: count, 4: serial, 8: magic, 12: version, 16..31: резерв
 * 32: оглавление — по 4 байта: (длина имени << 24) | смещение prop_info
 * prop_info (128 байт): name[32], serial(4) = (длина значения << 24) | счётчик, value[92]
 *
 * Super Plus, 2026-10-14: the trie format too (version 0xfc6ed0ab, bionic 4.4+), the only one Android 8+ reads: its
 * libc dropped the v1 compatibility mode, so every property looked empty and zygote aborted without
 * ro.product.cpu.abilist32. Layout (bionic android-8.0.0_r1, libc/bionic/system_properties.cpp):
 *   0: bytes_used, 4: serial, 8: magic, 12: version, 16..127: reserved; data from 128, offsets relative to it
 *   prop_bt (one per name segment): namelen, prop, left, right, children, name + NUL (aligned to 4)
 *   prop_info: serial = (value length << 24) | counter, value[92], name + NUL (aligned to 4)
 * The root prop_bt is at data offset 0 with namelen 0; siblings form a binary tree ordered by length, then bytes.
 */
class PropArea private constructor(private val b: ByteBuffer, private val chan: FileChannel?) : AutoCloseable {

    /** the trie format (Android 8+ needs it) rather than v1 */
    val isTrie: Boolean get() = b.getInt(12) == VERSION_TRIE

    val count: Int get() = if (isTrie) all().size else b.getInt(0)

    fun get(name: String): String? {
        if (isTrie) return trieGet(name)
        val off = find(name) ?: return null
        val serial = b.getInt(off + NAME_MAX)
        val len = (serial ushr 24).coerceIn(0, VALUE_MAX - 1)
        val v = ByteArray(len)
        for (i in 0 until len) v[i] = b.get(off + NAME_MAX + 4 + i)
        return String(v, Charsets.UTF_8)
    }

    fun all(): List<Pair<String, String>> {
        if (isTrie) return trieAll()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until count) {
            val e = b.getInt(TOC + i * 4)
            val off = e and 0xffffff
            val nl = e ushr 24
            val nb = ByteArray(nl) { b.get(off + it) }
            val n = String(nb, Charsets.UTF_8)
            out.add(n to (get(n) ?: ""))
        }
        return out
    }

    @Synchronized
    fun put(name: String, value: String): Boolean {
        if (isTrie) return triePut(name, value)
        val nb = name.toByteArray(Charsets.UTF_8)
        var vb = value.toByteArray(Charsets.UTF_8)
        if (nb.isEmpty() || nb.size >= NAME_MAX) return false
        if (vb.size >= VALUE_MAX) vb = vb.copyOf(VALUE_MAX - 1)
        val off = find(name)
        if (off != null) {
            val s0 = b.getInt(off + NAME_MAX)
            b.putInt(off + NAME_MAX, s0 or 1) // нечётный — «пишу»
            for (i in vb.indices) b.put(off + NAME_MAX + 4 + i, vb[i])
            b.put(off + NAME_MAX + 4 + vb.size, 0)
            b.putInt(off + NAME_MAX, (vb.size shl 24) or (((s0 or 1) + 1) and 0xffffff))
            b.putInt(4, b.getInt(4) + 1)
            return true
        }
        val n = count
        val at = nextInfo(n)
        if ((n + 1) * 4 + TOC > firstInfo(n)) return false
        if (at + INFO > b.capacity()) return false
        for (i in 0 until INFO) b.put(at + i, 0)
        for (i in nb.indices) b.put(at + i, nb[i])
        for (i in vb.indices) b.put(at + NAME_MAX + 4 + i, vb[i])
        b.putInt(at + NAME_MAX, vb.size shl 24)
        b.putInt(TOC + n * 4, (nb.size shl 24) or at)
        b.putInt(0, n + 1)
        b.putInt(4, b.getInt(4) + 1)
        return true
    }

    fun room(): Int {
        if (isTrie) return maxOf(0, (b.capacity() - HDR - b.getInt(0)) / 160)
        val n = count
        val byToc = (firstInfo(n) - TOC) / 4 - n
        val byTail = (b.capacity() - nextInfo(n)) / INFO
        return maxOf(0, minOf(byToc, byTail))
    }

    // ---------------------------------------------------------------- trie format

    private fun used() = b.getInt(0)
    private fun alloc(size: Int): Int? {
        val aligned = (size + 3) and 3.inv()
        val off = used()
        if (off + aligned > b.capacity() - HDR) return null
        for (i in 0 until aligned) b.put(HDR + off + i, 0)
        b.putInt(0, off + aligned)
        return off
    }
    private fun i32(off: Int) = b.getInt(HDR + off)
    private fun set32(off: Int, v: Int) = b.putInt(HDR + off, v)

    /** bionic's cmp_prop_name: shorter first, then strncmp (unsigned bytes) */
    private fun cmp(name: ByteArray, s: Int, len: Int, node: Int): Int {
        val nl = i32(node)
        if (len != nl) return if (len < nl) -1 else 1
        for (i in 0 until len) {
            val x = name[s + i].toInt() and 0xff; val y = b.get(HDR + node + BT_NAME + i).toInt() and 0xff
            if (x != y) return x - y
        }
        return 0
    }

    private fun newBt(name: ByteArray, s: Int, len: Int): Int? {
        val off = alloc(BT_NAME + len + 1) ?: return null
        set32(off, len)
        for (i in 0 until len) b.put(HDR + off + BT_NAME + i, name[s + i])
        return off
    }

    private fun findBt(root: Int, name: ByteArray, s: Int, len: Int, create: Boolean): Int? {
        var cur = root
        while (true) {
            val r = cmp(name, s, len, cur)
            if (r == 0) return cur
            val link = if (r < 0) 8 else 12
            val next = i32(cur + link)
            if (next != 0) { cur = next; continue }
            if (!create) return null
            val n = newBt(name, s, len) ?: return null
            set32(cur + link, n)
            return n
        }
    }

    /** prop_info offset of [name]; with [value], created when missing (bionic's find_property) */
    private fun findInfo(name: ByteArray, value: ByteArray?): Int? {
        var cur = 0
        var s = 0
        while (true) {
            var sep = -1
            for (i in s until name.size) if (name[i] == '.'.code.toByte()) { sep = i; break }
            val len = if (sep >= 0) sep - s else name.size - s
            if (len == 0) return null
            var root = i32(cur + 16)
            if (root == 0) {
                if (value == null) return null
                root = newBt(name, s, len) ?: return null
                set32(cur + 16, root)
            }
            cur = findBt(root, name, s, len, value != null) ?: return null
            if (sep < 0) break
            s = sep + 1
        }
        val p = i32(cur + 4)
        if (p != 0 || value == null) return p.takeIf { it != 0 }
        val info = alloc(PI_NAME + name.size + 1) ?: return null
        set32(info, value.size shl 24)
        for (i in value.indices) b.put(HDR + info + 4 + i, value[i])
        for (i in name.indices) b.put(HDR + info + PI_NAME + i, name[i])
        set32(cur + 4, info)
        return info
    }

    private fun trieGet(name: String): String? {
        val info = findInfo(name.toByteArray(Charsets.UTF_8), null) ?: return null
        val len = (i32(info) ushr 24).coerceIn(0, VALUE_MAX - 1)
        return String(ByteArray(len) { b.get(HDR + info + 4 + it) }, Charsets.UTF_8)
    }

    private fun triePut(name: String, value: String): Boolean {
        val nb = name.toByteArray(Charsets.UTF_8)
        var vb = value.toByteArray(Charsets.UTF_8)
        if (nb.isEmpty()) return false
        if (vb.size >= VALUE_MAX) vb = vb.copyOf(VALUE_MAX - 1)
        val existing = findInfo(nb, null)
        if (existing != null) {
            // __system_property_update: dirty, write, then length and a new counter
            val s0 = i32(existing)
            set32(existing, s0 or 1)
            for (i in vb.indices) b.put(HDR + existing + 4 + i, vb[i])
            b.put(HDR + existing + 4 + vb.size, 0)
            set32(existing, (vb.size shl 24) or (((s0 or 1) + 1) and 0xffffff))
        } else if (findInfo(nb, vb) == null) return false
        b.putInt(4, b.getInt(4) + 1)
        return true
    }

    private fun trieAll(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun visit(node: Int, depth: Int) {
            if (depth > 512) return
            val p = i32(node + 4)
            if (p != 0) {
                var n = 0
                while (b.get(HDR + p + PI_NAME + n) != 0.toByte() && HDR + p + PI_NAME + n < b.capacity()) n++
                val name = String(ByteArray(n) { b.get(HDR + p + PI_NAME + it) }, Charsets.UTF_8)
                out.add(name to (trieGet(name) ?: ""))
            }
            for (link in intArrayOf(8, 12, 16)) i32(node + link).takeIf { it != 0 }?.let { visit(it, depth + 1) }
        }
        visit(0, 0)
        return out
    }

    private fun find(name: String): Int? {
        val nb = name.toByteArray(Charsets.UTF_8)
        for (i in 0 until count) {
            val e = b.getInt(TOC + i * 4)
            if ((e ushr 24) != nb.size) continue
            val off = e and 0xffffff
            var same = true
            for (j in nb.indices) if (b.get(off + j) != nb[j]) { same = false; break }
            if (same && b.get(off + nb.size) == 0.toByte()) return off
        }
        return null
    }

    private fun firstInfo(n: Int): Int {
        if (n == 0) return INFO_START
        var m = Int.MAX_VALUE
        for (i in 0 until n) m = minOf(m, b.getInt(TOC + i * 4) and 0xffffff)
        return m
    }

    private fun nextInfo(n: Int): Int {
        if (n == 0) return firstInfo(0)
        var m = 0
        for (i in 0 until n) m = maxOf(m, b.getInt(TOC + i * 4) and 0xffffff)
        return m + INFO
    }

    override fun close() {
        (b as? MappedByteBuffer)?.force()
        runCatching { chan?.close() }
    }

    companion object {
        const val SIZE = 131072
        const val MAGIC = 0x504f5250
        const val VERSION = 0x45434f76
        private const val TOC = 32
        private const val INFO = 128
        private const val NAME_MAX = 32
        private const val VALUE_MAX = 92
        private const val INFO_START = 4096
        const val VERSION_TRIE = 0xfc6ed0ab.toInt()
        private const val HDR = 128
        private const val BT_NAME = 20
        private const val PI_NAME = 96
        /** room for a few hundred properties in the trie, where each name segment is a node */
        const val TRIE_SIZE = 262144

        /**
         * Rewrites [f] in the trie format with the same properties (Android 8+), in place so the file keeps its inode
         * (the owners table maps it to root). A file that already is a trie is left as it is.
         */
        fun convertToTrie(f: File): Boolean {
            // read and build in memory, then write over the file: no resizing of a file that may still be mapped
            val src = ByteBuffer.wrap(runCatching { f.readBytes() }.getOrNull() ?: return false).order(ByteOrder.LITTLE_ENDIAN)
            if (src.capacity() < 16 || src.getInt(8) != MAGIC) return false
            val old = PropArea(src, null)
            if (old.isTrie) return true
            val m = ByteBuffer.allocate(maxOf(TRIE_SIZE, src.capacity())).order(ByteOrder.LITTLE_ENDIAN)
            m.putInt(0, BT_NAME)                // bytes_used: the root prop_bt
            m.putInt(4, 0); m.putInt(8, MAGIC); m.putInt(12, VERSION_TRIE)
            val t = PropArea(m, null)
            for ((k, v) in old.all()) if (!t.put(k, v)) return false
            RandomAccessFile(f, "rw").use { it.seek(0); it.write(m.array()) }
            return true
        }

        /** Создаёт пустую область в файле. */
        fun create(f: File): PropArea {
            f.parentFile?.mkdirs()
            RandomAccessFile(f, "rw").use { it.setLength(0); it.setLength(SIZE.toLong()) }
            val a = open(f) ?: error("property area failed to open")
            a.b.putInt(0, 0); a.b.putInt(4, 0); a.b.putInt(8, MAGIC); a.b.putInt(12, VERSION)
            return a
        }

        /** Отображает существующую область; null, если это не v1. */
        fun open(f: File): PropArea? {
            val ch = RandomAccessFile(f, "rw").channel
            val m = ch.map(FileChannel.MapMode.READ_WRITE, 0, f.length())
            m.order(ByteOrder.LITTLE_ENDIAN)
            val a = PropArea(m, ch)
            if (m.getInt(8) != MAGIC && !(m.getInt(8) == 0 && m.getInt(0) == 0)) { a.close(); return null }
            return a
        }

        /** Разбор build.prop/default.prop: ключ=значение, комментарии, import не поддерживаем. */
        fun parseProps(text: String): LinkedHashMap<String, String> {
            val out = LinkedHashMap<String, String>()
            for (raw in text.lineSequence()) {
                val ln = raw.trim()
                if (ln.isEmpty() || ln.startsWith("#") || ln.startsWith("import ")) continue
                val i = ln.indexOf('=')
                if (i <= 0) continue
                out[ln.substring(0, i).trim()] = ln.substring(i + 1).trim()
            }
            return out
        }
    }
}
