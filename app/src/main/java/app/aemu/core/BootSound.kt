/* AEmulator Super Plus, 2026-10-14: boot sound. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * A sound the app plays when a system starts or finishes booting. The built-in ones are synthesized here (no recorded
 * audio ships with the app); the user can also pick their own file. The choice lives in a file, not in preferences:
 * the VM screen runs in another process and reads it when a VM screen opens.
 */
object BootSound {
    enum class Sound(val id: String) { OFF("off"), CHIME("chime"), RETRO("retro"), RISE("rise"), CUSTOM("custom") }
    enum class Moment(val id: String) { START("start"), FINISHED("finished") }
    data class Choice(val sound: Sound = Sound.OFF, val moment: Moment = Moment.FINISHED, val customName: String = "")

    const val RATE = 44100
    private const val FILE = "boot-sound.json"
    private const val CUSTOM_MAX = 10L shl 20

    fun customFile(ctx: Context) = File(ctx.filesDir, "boot-sound.custom")

    fun load(ctx: Context): Choice = runCatching {
        val o = JSONObject(String(AtomicFile(File(ctx.filesDir, FILE)).readFully(), Charsets.UTF_8))
        Choice(
            Sound.entries.firstOrNull { it.id == o.optString("sound") } ?: Sound.OFF,
            Moment.entries.firstOrNull { it.id == o.optString("when") } ?: Moment.FINISHED,
            o.optString("customName"),
        ).let { if (it.sound == Sound.CUSTOM && !customFile(ctx).isFile) it.copy(sound = Sound.OFF) else it }
    }.getOrDefault(Choice())

    fun save(ctx: Context, c: Choice) {
        val f = AtomicFile(File(ctx.filesDir, FILE))
        val out = f.startWrite()
        try {
            out.write(JSONObject().put("sound", c.sound.id).put("when", c.moment.id).put("customName", c.customName).toString().toByteArray())
            f.finishWrite(out)
        } catch (e: Exception) { f.failWrite(out); throw e }
    }

    /** Copies a picked audio file into the app; its display name is kept for the settings row. */
    fun importCustom(ctx: Context, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= CUSTOM_MAX) { "the file must be between 1 byte and 10 MB" }
        customFile(ctx).writeBytes(bytes)
    }

    /** Plays [sound] once, in the background; a custom file goes through MediaPlayer, the built-ins through AudioTrack. */
    fun play(ctx: Context, sound: Sound) {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        if (sound == Sound.CUSTOM) {
            val f = customFile(ctx).takeIf { it.isFile } ?: return
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(f.path)
                    setOnCompletionListener { it.release() }
                    setOnErrorListener { mp, _, _ -> mp.release(); true }
                    prepare()
                    start()
                }
            }
            return
        }
        val pcm = pcm(sound)
        if (pcm.isEmpty()) return
        Thread({
            runCatching {
                val track = AudioTrack.Builder().setAudioAttributes(attrs)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                try {
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    Thread.sleep(pcm.size * 1000L / RATE + 150)
                } finally { track.release() }
            }
        }, "boot-sound").apply { isDaemon = true; start() }
    }

    /** The built-in sounds as 16-bit mono samples at [RATE]; empty for OFF and CUSTOM. */
    fun pcm(sound: Sound): ShortArray = when (sound) {
        // a bell-like arpeggio, C5 E5 G5 C6, each note ringing into the next
        Sound.CHIME -> mix(listOf(523.25, 659.25, 783.99, 1046.5).mapIndexed { i, f -> Note(f, i * 0.16, 1.1, bell = true) }, 1.9)
        // two quick square-ish blips, like an old handheld
        Sound.RETRO -> mix(listOf(Note(880.0, 0.0, 0.12, square = true), Note(1318.5, 0.14, 0.22, square = true)), 0.42)
        // a soft rising sweep
        Sound.RISE -> sweep(320.0, 960.0, 0.9)
        Sound.OFF, Sound.CUSTOM -> ShortArray(0)
    }

    private class Note(val freq: Double, val start: Double, val length: Double, val bell: Boolean = false, val square: Boolean = false)

    private fun mix(notes: List<Note>, seconds: Double): ShortArray {
        val n = (seconds * RATE).toInt()
        val buf = DoubleArray(n)
        for (note in notes) {
            val s0 = (note.start * RATE).toInt()
            val len = min((note.length * RATE).toInt(), n - s0)
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                val attack = min(1.0, t / 0.005)
                val env = attack * if (note.bell) exp(-3.2 * t) else exp(-8.0 * t)
                val w = 2 * PI * note.freq * t
                val v = when {
                    note.square -> (if (sin(w) >= 0) 1.0 else -1.0) * 0.35
                    note.bell -> sin(w) + 0.35 * sin(2.01 * w) + 0.12 * sin(3.98 * w)
                    else -> sin(w)
                }
                buf[s0 + i] += v * env
            }
        }
        return normalize(buf)
    }

    private fun sweep(from: Double, to: Double, seconds: Double): ShortArray {
        val n = (seconds * RATE).toInt()
        val buf = DoubleArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val p = i.toDouble() / n
            val f = from + (to - from) * p * p
            phase += 2 * PI * f / RATE
            val env = min(1.0, p / 0.1) * min(1.0, (1 - p) / 0.35)
            buf[i] = (sin(phase) + 0.25 * sin(2 * phase)) * env
        }
        return normalize(buf)
    }

    /** to 16-bit at about 60% of full scale: clear, never clipping */
    private fun normalize(buf: DoubleArray): ShortArray {
        val peak = buf.maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0 } ?: return ShortArray(buf.size)
        val k = 0.6 * Short.MAX_VALUE / peak
        return ShortArray(buf.size) { (buf[it] * k).toInt().toShort() }
    }
}
