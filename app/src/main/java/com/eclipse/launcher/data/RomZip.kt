package com.eclipse.launcher.data

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Cartridge ROMs → .zip (one ROM per zip, the way No-Intro sets come). Only for systems whose Android emulators open
 * zipped ROMs (RetroArch cores, Lemuroid, DraStic, Mupen64Plus, Pizza Boy …). Arcade zips are ROM *sets* and disc
 * systems use CHD / RVZ, so neither is here; nor are computers, whose zips often mean something else.
 */
object RomZip {
    val SYSTEMS = setOf(
        "2600", "5200", "7800", "32x", "chf", "cv", "duck", "fc", "fds", "gb", "gb-hacks", "gbc", "gbc-hacks", "gba",
        "gba-hacks", "gen-hacks", "genesis", "gg", "gg-hacks", "intv", "jag", "lynx", "mark3", "md", "md-hacks", "msx",
        "n64", "nds", "nes", "nes-hacks", "ngp", "ngpc", "pce", "pico", "satellaview", "sfc", "sfc-hacks", "sg1k", "sms",
        "snes", "snes-hacks", "tg16", "uze", "vb", "vect", "ws", "wsc", "wsv",
    )

    /** Already compressed, or not a cartridge ROM on its own. */
    private val SKIP = setOf(
        "zip", "7z", "rar", "gz", "chd", "rvz", "cso", "pbp", "m3u", "cue", "gdi", "iso", "gcm", "ccd", "img", "sub",
        "mds", "mdf", "part", "txt", "nfo", "png", "jpg", "jpeg", "webp", "srm", "sav", "state",
    )

    /** Can [f] (a game of [platform]) be zipped? Not if it's compressed already, a disc track, or zipped beside it. */
    fun canZip(f: File, platform: String): Boolean {
        if (platform !in SYSTEMS || !f.isFile) return false
        val ext = f.extension.lowercase()
        if (ext.isEmpty() || ext in SKIP) return false
        if (File(f.parentFile, f.nameWithoutExtension + ".cue").exists()) return false // a .bin track of a disc
        return !zipOf(f).exists()
    }

    fun zipOf(f: File): File = File(f.parentFile, f.nameWithoutExtension + ".zip")

    /** Writes [src] into [out] as its only entry (same name inside). Throws on failure (InterruptedException when cancelled). */
    fun zip(src: File, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val total = src.length().coerceAtLeast(1L)
        try {
            ZipOutputStream(FileOutputStream(out).buffered(1 shl 16)).use { zos ->
                zos.setLevel(Deflater.BEST_COMPRESSION)
                zos.putNextEntry(ZipEntry(src.name).apply { time = src.lastModified() })
                BufferedInputStream(FileInputStream(src), 1 shl 16).use { input ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        if (cancelled()) throw InterruptedException()
                        val n = input.read(buf)
                        if (n < 0) break
                        zos.write(buf, 0, n)
                        done += n
                        if (done - last > (1 shl 20)) { last = done; progress(done.toFloat() / total) }
                    }
                }
                zos.closeEntry()
            }
            progress(1f)
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
    }

    /** Reads [zip] back: exactly one entry, named like [original], with every byte the same. */
    fun same(original: File, zip: File, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Boolean = try {
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            val e = entries.singleOrNull() ?: return false
            if (e.name != original.name || e.size != original.length()) return false
            zf.getInputStream(e).use { a -> BufferedInputStream(FileInputStream(original), 1 shl 16).use { b -> equal(a, b, original.length(), progress, cancelled) } }
        }
    } catch (e: IOException) { false }

    private fun equal(a: InputStream, b: InputStream, total: Long, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        val x = ByteArray(1 shl 16); val y = ByteArray(1 shl 16)
        var done = 0L
        while (true) {
            if (cancelled()) return false
            val n = a.readNBytesCompat(x)
            val m = b.readNBytesCompat(y)
            if (n != m) return false
            if (n <= 0) return done == total
            for (i in 0 until n) if (x[i] != y[i]) return false
            done += n
            progress(done.toFloat() / total.coerceAtLeast(1L))
        }
    }

    /** Fills [buf] as far as the stream allows (InputStream.readNBytes needs Android 13). */
    private fun InputStream.readNBytesCompat(buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val n = read(buf, got, buf.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }
}
