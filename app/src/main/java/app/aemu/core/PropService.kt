package app.aemu.core

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Замена property_service из init: принимает setprop от гостя и пишет в общую область,
 * которую все гостевые процессы отображают через fd 30 (ANDROID_PROPERTY_WORKSPACE).
 * persist.* сохраняются в /data/property, как у настоящего init.
 */
class PropService(private val paths: VmPaths, private val log: (String) -> Unit) {
    @Volatile var area: PropArea? = null
        private set
    @Volatile var writes = 0L
        private set
    @Volatile var rejects = 0L
        private set
    /** ctl.start / ctl.stop от гостя */
    var onCtl: ((start: Boolean, service: String) -> Unit)? = null
    /** наблюдатель за изменением свойств (например sys.boot_completed) */
    var onSet: ((String, String) -> Unit)? = null

    private val server = UnixServer(paths.socket("property_service"), "props") { c ->
        val din = DataInputStream(c.inputStream)
        val body = ByteArray(128)
        din.readFully(body)
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.getInt()
        val name = cstr(body, 4, 32)
        val value = cstr(body, 36, 92)
        if (cmd == 1) apply(name, value) else log("props: unknown command $cmd")
    }

    /** Разворачивает область из шаблона образа и подмешивает сохранённые persist.*. */
    @Synchronized
    fun prepare(overrides: Map<String, String>, layered: List<File> = emptyList(), trie: Boolean = false): Boolean {
        close()
        val tpl = paths.propsTemplate
        val f = paths.props
        if (!tpl.isFile) {
            log("props: no template ${tpl.name}")
            return false
        }
        f.parentFile?.mkdirs()
        tpl.copyTo(f, overwrite = true)
        // Android 8+ libc reads only the trie format (no v1 compatibility mode any more)
        if (trie && !PropArea.convertToTrie(f)) { log("props: could not convert the area to the trie format"); return false }
        rootOwned(f)
        val a = PropArea.open(f)
        if (a == null) {
            log("props: area not recognized")
            return false
        }
        area = a
        val pd = File(paths.root, "data/property").apply { mkdirs() }
        var n = 0
        pd.listFiles()?.forEach { p ->
            if (p.isFile && p.name.startsWith("persist.")) {
                if (a.put(p.name, runCatching { p.readText().trim() }.getOrDefault(""))) n++
            }
        }
        // Android 8+: init also loads /vendor/build.prop and /odm/build.prop after /system/build.prop; ro.* keep the first
        // value, so only what the template lacks is added (ro.product.cpu.abilist32 lives there on Treble firmware)
        var layeredAdded = 0
        for (file in layered) if (file.isFile) {
            PropArea.parseProps(runCatching { file.readText() }.getOrDefault("")).forEach { (k, v) ->
                if (a.get(k) == null && a.put(k, v)) layeredAdded++
            }
        }
        // наши значения важнее сохранённых гостем
        for ((k, v) in overrides) a.put(k, v)
        writes = 0; rejects = 0
        log("props: ${a.count} entries, ${a.room()} free" + (if (a.isTrie) ", trie format" else "") + (if (n > 0) ", saved persist.* $n" else "") +
            if (layeredAdded > 0) ", vendor/odm build.prop $layeredAdded" else "")
        return true
    }

    fun serve(): Boolean = server.start(log)

    fun get(name: String): String? = area?.get(name)

    fun put(name: String, value: String): Boolean = area?.put(name, value) ?: false

    @Synchronized
    private fun apply(name: String, value: String) {
        if (name.isEmpty()) { rejects++; return }
        if (name.startsWith("ctl.")) {
            val what = name.removePrefix("ctl.")
            onCtl?.invoke(what == "start" || what == "restart", value) ?: log("props: ctl.$what=$value (no services)")
            return
        }
        if (name.startsWith("ro.") && get(name) != null) { rejects++; return }
        if (!put(name, value)) {
            rejects++
            log("props: failed to write $name=$value (out of space?)")
            return
        }
        writes++
        if (name.startsWith("persist.")) {
            runCatching { File(paths.root, "data/property/$name").writeText(value) }
        }
        onSet?.invoke(name, value)
    }

    @Synchronized
    fun close() {
        area?.close()
        area = null
    }

    fun stop() {
        server.stop()
        close()
    }

    /** Отдельная копия области для mediaserver с ro.kernel.qemu=1 (эмуляторный звук). */
    fun privateCopy(name: String, extra: Map<String, String>): File? {
        val src = paths.props
        val dst = File(paths.bin, "props.$name")
        return runCatching {
            src.copyTo(dst, overwrite = true)
            rootOwned(dst)
            PropArea.open(dst)?.use { a -> extra.forEach { (k, v) -> a.put(k, v) } }
            dst
        }.getOrNull()
    }

    /**
     * The area is a fresh file every boot, and its inode may be one the owners table (dev, ino → uid) still
     * maps to an app: 7.0+ libc refuses a property area not owned by root, and every process then sees no
     * properties. A later row wins, so the file is pinned to root here.
     */
    private fun rootOwned(f: File) {
        runCatching {
            val st = android.system.Os.lstat(f.absolutePath)
            paths.owners.appendText(String.format("%016x %016x %08x %08x\n", st.st_dev, st.st_ino, 0, 0))
        }
    }

    private fun cstr(b: ByteArray, at: Int, max: Int): String {
        var n = 0
        while (n < max && b[at + n] != 0.toByte()) n++
        return String(b, at, n, Charsets.UTF_8)
    }
}
