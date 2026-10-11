/* AEmulator Super Plus, 2026-10-11: host microphone for the guest. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.LocalSocket
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Host side of the guest's microphone (audio HAL input stream, native/audiohal: unix socket /dev/aemu_mic).
 * A guest recording connects with "MIC1", rate, channels and gets 16-bit PCM from the phone's microphone in exactly
 * that format. Only with the VM setting on, the RECORD_AUDIO permission granted and the VM on screen; otherwise the
 * connection is closed at once and the guest HAL records silence, so guest apps keep working either way.
 */
class HostMicBridge(private val ctx: Context, paths: VmPaths, private val enabled: () -> Boolean,
                    private val log: (String) -> Unit) {
    @Volatile private var foreground = false
    private val clients = ConcurrentHashMap.newKeySet<LocalSocket>()
    private val server = UnixServer(File(paths.root, "dev/aemu_mic"), "mic") { client ->
        clients.add(client)
        try { serveClient(client) } catch (e: Exception) { if (permitted()) log("microphone: ${e.message}") }
        finally { clients.remove(client) }
    }

    private fun granted() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun permitted() = foreground && enabled() && granted()

    fun serve(): Boolean = server.start(log).also { log("microphone: bridge ${if (it) "ready" else "unavailable"}; permission ${granted()}; enabled ${enabled()}") }
    /** The VM left the screen: no recording in the background (the guest gets silence until it is back). */
    fun visible(value: Boolean) { foreground = value; if (!value) clients.toList().forEach { runCatching { it.close() } } }
    fun stop() { foreground = false; clients.toList().forEach { runCatching { it.close() } }; server.stop() }

    @SuppressLint("MissingPermission") // checked in permitted()
    private fun serveClient(c: LocalSocket) {
        val head = ByteArray(16)
        DataInputStream(c.inputStream).readFully(head)
        if (String(head, 0, 4, Charsets.US_ASCII) != "MIC1") return
        val rate = le(head, 4)
        val channels = le(head, 8)
        if (rate !in 8000..48000 || channels !in 1..2) return
        if (!permitted()) return                       // closing = silence in the guest
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { log("microphone: $rate Hz x$channels not available on this phone"); return }
        val chunk = rate / 50 * channels * 2           // 20 ms
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, mask, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 4))
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) { log("microphone: could not open the phone's microphone"); return }
            rec.startRecording()
            log("microphone: guest recording at $rate Hz, ${if (channels == 2) "stereo" else "mono"}")
            val buf = ByteArray(chunk)
            val out = c.outputStream
            while (permitted()) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) out.write(buf, 0, n)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun le(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
        ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}
