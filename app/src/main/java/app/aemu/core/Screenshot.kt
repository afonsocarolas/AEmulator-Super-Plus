/* Modified for AEmulator Super Plus, 2026-10-11: library thumbnails. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

/**
 * Screenshot of the running guest from the raw framebuffer instead of `screencap`:
 *   adb pull /dev/graphics/fb0 /sdcard/Pictures/AEmulator/temp-<timestamp>-fb0.raw
 *   convert the raw pixels (RGB565 by default) to PNG, 320 dpi
 *   delete the temporary .raw, keep /sdcard/Pictures/AEmulator/<timestamp>-<VM id>.png
 */
object Screenshot {
    private const val PHONE_REL = "Pictures/AEmulator"
    private const val DPI = 320

    /** /sdcard/Pictures/AEmulator on the phone */
    fun phoneDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AEmulator")

    /**
     * Blocking. [fb] is the host file behind the guest's /dev/graphics/fb0, [w]×[h] the guest screen,
     * [format] 0 = RGB565, 1 = RGBA_8888/RGBX, 2 = BGRA_8888; with [pages] == 2 the file holds two pages
     * and [page] is the one the guest drew last. Returns the phone path of the saved PNG, or null on failure.
     */
    fun take(ctx: Context, vmId: String, fb: File?, w: Int, h: Int, format: Int, pages: Int, page: Int): String? {
        if (fb == null || !fb.isFile || w <= 0 || h <= 0) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "$stamp-$vmId.png"

        // adb pull /dev/graphics/fb0 → temp-<timestamp>-fb0.raw (phone folder; app cache if that is not writable)
        val raw = pullRaw(ctx, fb, "temp-$stamp-fb0.raw") ?: return null
        try {
            val bpp = if (format == 0) 2 else 4
            val png = rawToPng(raw, w, h, format, bpp, if (pages == 2) page else 0) ?: return null
            return save(ctx, png, name)
        } finally {
            raw.delete()
        }
    }

    /**
     * For the in-app GPU bridge (frames go straight to the SurfaceView and fb0 stays empty):
     * [bmp] is a PixelCopy of the surface. Scaled to the guest size [w]×[h] when they differ.
     */
    fun takeBitmap(ctx: Context, vmId: String, bmp: Bitmap, w: Int, h: Int): String? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val src = if (w > 0 && h > 0 && (bmp.width != w || bmp.height != h)) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
        val out = ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (src !== bmp) src.recycle()
        return save(ctx, withDpi(out.toByteArray(), DPI), "$stamp-$vmId.png")
    }

    private fun pullRaw(ctx: Context, fb: File, name: String): File? {
        for (dir in listOf(phoneDir(), ctx.cacheDir)) {
            val dst = File(dir, name)
            val ok = runCatching {
                if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) error("cannot create ${dir.absolutePath}")
                fb.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                if (dst.length() == 0L) error("empty framebuffer")
            }.isSuccess
            if (ok) return dst
            dst.delete()
        }
        return null
    }

    /** Same conversion as the reference extract.py (RGB565, little endian), generalised to the guest size and 32-bit formats. */
    private fun rawToPng(raw: File, w: Int, h: Int, format: Int, bpp: Int, page: Int): ByteArray? {
        val bmp = rawToBitmap(raw, w, h, format, bpp, page) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return withDpi(out.toByteArray(), DPI)
    }

    /** File name of the library thumbnail in the image's folder (VmPaths.dir). */
    const val THUMB = "thumb.jpg"
    private const val THUMB_WIDTH = 270

    /** Small JPEG of the guest screen for the 3D library (Super Plus); written atomically, never shown elsewhere. */
    fun saveThumbnail(dir: File, bmp: Bitmap): Boolean = runCatching {
        val h = (THUMB_WIDTH.toLong() * bmp.height / bmp.width.coerceAtLeast(1)).toInt().coerceIn(1, THUMB_WIDTH * 3)
        val small = if (bmp.width == THUMB_WIDTH && bmp.height == h) bmp else Bitmap.createScaledBitmap(bmp, THUMB_WIDTH, h, true)
        val tmp = File(dir, "$THUMB.tmp")
        tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (small !== bmp) small.recycle()
        tmp.renameTo(File(dir, THUMB))
    }.getOrDefault(false)

    /** An all-black frame (screen off, nothing drawn yet) is not worth keeping. */
    fun isBlank(bmp: Bitmap): Boolean =
        (0 until 16).all { i -> bmp.getPixel(bmp.width * (i % 4 + 1) / 5, bmp.height * (i / 4 + 1) / 5) and 0xffffff == 0 }

    /** Thumbnail straight from the guest framebuffer (same formats as [take]). Blocking. */
    fun thumbnailFromFb(dir: File, fb: File?, w: Int, h: Int, format: Int, pages: Int, page: Int): Boolean {
        if (fb == null || !fb.isFile || w <= 0 || h <= 0) return false
        val bmp = runCatching { rawToBitmap(fb, w, h, format, if (format == 0) 2 else 4, if (pages == 2) page else 0) }.getOrNull() ?: return false
        val ok = !isBlank(bmp) && saveThumbnail(dir, bmp)
        bmp.recycle()
        return ok
    }

    private fun rawToBitmap(raw: File, w: Int, h: Int, format: Int, bpp: Int, page: Int): Bitmap? {
        val size = w.toLong() * h * bpp
        val data = ByteArray(size.toInt())
        RandomAccessFile(raw, "r").use { f ->
            val off = page * size
            if (f.length() < off + size) {
                // a single-page file was asked for page 1: fall back to the first page
                if (page != 0 && f.length() >= size) f.seek(0) else return null
            } else f.seek(off)
            f.readFully(data)
        }
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val px = IntArray(w * h)
        when (format) {
            0 -> for (i in px.indices) {
                val v = bb.getShort(i * 2).toInt() and 0xFFFF
                val r = ((v shr 11) and 0x1F) * 255 / 31
                val g = ((v shr 5) and 0x3F) * 255 / 63
                val b = (v and 0x1F) * 255 / 31
                px[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
            // BGRA in memory = 0xAARRGGBB as a little-endian int; the alpha byte is padding, force it opaque
            2 -> for (i in px.indices) px[i] = bb.getInt(i * 4) or -0x1000000
            // RGBA in memory: swap R and B
            else -> for (i in px.indices) {
                val v = bb.getInt(i * 4)
                px[i] = -0x1000000 or ((v and 0xff) shl 16) or (v and 0xff00) or ((v shr 16) and 0xff)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Inserts a pHYs chunk right after IHDR, like PIL's save(dpi=...). */
    private fun withDpi(png: ByteArray, dpi: Int): ByteArray {
        if (png.size < 33) return png
        val ppm = Math.round(dpi / 0.0254).toInt()
        val body = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN).putInt(ppm).putInt(ppm).put(1).array()
        val type = "pHYs".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(body) }.value
        val chunk = ByteBuffer.allocate(4 + 4 + 9 + 4).order(ByteOrder.BIG_ENDIAN)
            .putInt(9).put(type).put(body).putInt(crc.toInt()).array()
        val ihdrEnd = 8 + 4 + 4 + 13 + 4 // signature + IHDR chunk
        return png.copyOfRange(0, ihdrEnd) + chunk + png.copyOfRange(ihdrEnd, png.size)
    }

    private fun save(ctx: Context, png: ByteArray, name: String): String? {
        val shown = "/sdcard/$PHONE_REL/$name"
        val dir = phoneDir()
        val dst = File(dir, name)
        val direct = runCatching {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) error("cannot create ${dir.absolutePath}")
            dst.writeBytes(png)
            if (dst.length() != png.size.toLong()) error("short write")
        }.isSuccess
        if (direct) {
            MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), arrayOf("image/png"), null)
            return shown
        }
        dst.delete()
        // scoped storage without all-files access: MediaStore creates the folder and indexes the file
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, PHONE_REL)
            }
            val uri = runCatching { ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) }.getOrNull() ?: return null
            val wrote = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(png) } }.isSuccess
            if (!wrote) { runCatching { ctx.contentResolver.delete(uri, null, null) }; return null }
            return shown
        }
        return null
    }
}
