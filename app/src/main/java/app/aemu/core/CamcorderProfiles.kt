/* AEmulator Super Plus, 2026-10-11: camcorder profiles the host camera can record. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File

/**
 * The host camera bridge records 640x480 (see native/camerahal). A firmware's camera app takes its video sizes from
 * /system/etc/media_profiles.xml (often 720p/1080p of the real sensor), and the recorder refuses a size the camera does
 * not list, so while the camera bridge is on every camcorder profile is pointed at 640x480. The original file is parked
 * and put back when the camera is turned off; nothing else in the firmware is touched.
 */
object CamcorderProfiles {
    const val WIDTH = 640
    const val HEIGHT = 480
    /** software encoders under emulation: keep the frame rate and bit rate modest */
    const val MAX_FPS = 30
    const val MAX_BITRATE = 3_000_000

    private const val FILE = "system/etc/media_profiles.xml"
    private const val BACKUP = "system/.aemu-parked/system#etc#media_profiles.xml"
    private const val MARKER = "system/.aemu-camcorder-vga"

    /** Applies or undoes the change; true when the file changed. */
    fun configure(root: File, enabled: Boolean): Boolean {
        val f = File(root, FILE)
        val backup = File(root, BACKUP)
        val marker = File(root, MARKER)
        if (!enabled) {
            if (!marker.isFile) return false
            if (backup.isFile) { backup.copyTo(f, overwrite = true); backup.delete() }
            marker.delete()
            return true
        }
        if (!f.isFile) return false
        val current = f.readText()
        // already ours and unchanged since: nothing to do; replaced (e.g. by an OTA): its new content is the original
        val original = if (marker.isFile && backup.isFile && patch(backup.readText()) == current) backup.readText() else current
        val patched = patch(original)
        if (patched == current) return false
        backup.parentFile?.mkdirs()
        backup.writeText(original)
        f.writeText(patched)
        marker.writeText("1")
        return true
    }

    /** Every <Video .../> of every <CamcorderProfiles> block: 640x480, frame and bit rate capped. */
    fun patch(xml: String): String = Regex("<CamcorderProfiles\\b.*?</CamcorderProfiles>", RegexOption.DOT_MATCHES_ALL).replace(xml) { block ->
        Regex("<Video\\b[^>]*>").replace(block.value) { v ->
            var tag = v.value
            fun set(name: String, value: (Int?) -> Int) {
                val a = Regex("\\b$name\\s*=\\s*\"([^\"]*)\"")
                tag = if (a.containsMatchIn(tag)) a.replace(tag) { "$name=\"${value(it.groupValues[1].trim().toIntOrNull())}\"" } else tag
            }
            set("width") { WIDTH }
            set("height") { HEIGHT }
            set("frameRate") { (it ?: MAX_FPS).coerceIn(1, MAX_FPS) }
            set("bitRate") { (it ?: MAX_BITRATE).coerceIn(64_000, MAX_BITRATE) }
            tag
        }
    }
}
