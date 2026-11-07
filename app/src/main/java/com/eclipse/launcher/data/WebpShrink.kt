package com.eclipse.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import java.io.File

/**
 * Settings → Tools → Shrink artwork: Eclipse's own pictures (filesDir/art) re-saved as lossless WebP.
 *
 * Only PNGs are tried: a JPEG re-saved losslessly is nearly always bigger, and it wouldn't be the same file anyway.
 * Each new picture is decoded again and compared pixel by pixel with the original before it replaces it, and it's kept
 * only if it's smaller. The file keeps its name (Coil and Android read the format from the bytes, not the name), so every
 * place that points at it still works.
 */
object WebpShrink {
    /** Android 10+ writes lossless WebP (WEBP at quality 100 on 10, WEBP_LOSSLESS from 11). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Pictures bigger than this are left alone (decoding them could run out of memory). */
    private const val MAX_PIXELS = 40_000_000L

    private fun head(f: File, n: Int): ByteArray? = runCatching {
        f.inputStream().use { i -> ByteArray(n).also { b -> if (i.read(b) < n) return null } }
    }.getOrNull()

    /** An 8-bit PNG (16-bit ones would lose detail when decoded to 8 bits, so they're not candidates). */
    fun isPng8(f: File): Boolean {
        val b = head(f, 26) ?: return false
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (!(0 until 8).all { b[it] == sig[it] }) return false
        return b[24].toInt() <= 8 // IHDR bit depth
    }

    /** "image/png", "image/webp", "image/gif" or "image/jpeg", from the file's first bytes. */
    fun mimeOf(f: File): String {
        val b = head(f, 12) ?: return "image/jpeg"
        return when {
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() -> "image/png"
            String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            String(b, 0, 3, Charsets.US_ASCII) == "GIF" -> "image/gif"
            else -> "image/jpeg"
        }
    }

    /** A key that changes when the file does: remembered for pictures WebP couldn't make smaller. */
    fun keyOf(f: File) = "${f.name}:${f.length()}:${f.lastModified()}"

    /** Every PNG in [dir] worth trying (not in [skip]). */
    fun candidates(dir: File, skip: Set<String>): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") && keyOf(it) !in skip && isPng8(it) }

    sealed class Result {
        data class Saved(val bytes: Long) : Result()
        /** fine, but WebP wasn't smaller (or the picture can't be done exactly): remember it and don't try again */
        object Kept : Result()
        object Failed : Result()
    }

    @Suppress("DEPRECATION")
    fun convert(f: File): Result {
        if (!supported) return Result.Failed
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Result.Failed
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) return Result.Kept
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false; inMutable = false }
        val src = try { BitmapFactory.decodeFile(f.path, opts) } catch (e: OutOfMemoryError) { null } ?: return Result.Failed
        val tmp = File(f.parentFile, f.name + ".webp.tmp")
        try {
            // a picture with its own colour profile might not come back exactly: leave it as it is
            if (src.config != Bitmap.Config.ARGB_8888 || src.colorSpace != ColorSpace.get(ColorSpace.Named.SRGB)) return Result.Kept
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
            val ok = tmp.outputStream().use { o -> src.compress(format, 100, o) }
            if (!ok || tmp.length() == 0L) return Result.Failed
            val before = f.length()
            if (tmp.length() >= before) return Result.Kept
            // check: every pixel the same
            val back = try { BitmapFactory.decodeFile(tmp.path, opts) } catch (e: OutOfMemoryError) { null } ?: return Result.Failed
            val same = try { back.sameAs(src) } finally { back.recycle() }
            if (!same) return Result.Kept
            val saved = before - tmp.length()
            if (!tmp.renameTo(f)) return Result.Failed
            return Result.Saved(saved)
        } catch (e: Exception) {
            return Result.Failed
        } finally {
            src.recycle()
            if (tmp.exists()) tmp.delete()
        }
    }
}
