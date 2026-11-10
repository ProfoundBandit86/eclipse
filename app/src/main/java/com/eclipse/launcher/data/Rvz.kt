package com.eclipse.launcher.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * GameCube and Wii discs → RVZ, Dolphin's own compressed format (Dolphin can't play CHD). The work is done by
 * Dolphin's converter, built into Eclipse as libeclipse_rvz.so (app/src/main/cpp). Lossless: nothing is scrubbed,
 * and [same] reads the RVZ back in full and compares it with the original before the original is touched.
 */
object Rvz {
    /** false when the native part couldn't load (a device Eclipse wasn't built for). */
    val available: Boolean by lazy { runCatching { System.loadLibrary("eclipse_rvz"); true }.getOrDefault(false) }

    @JvmStatic private external fun nativeConvert(src: String, out: String, level: Int): String?
    @JvmStatic private external fun nativeSame(a: String, b: String): Int
    @JvmStatic private external fun nativeProgress(): Float
    @JvmStatic private external fun nativeCancel()

    /** Disc image file types that may hold a GameCube / Wii disc. */
    val EXTS = setOf("iso", "gcm")

    /**
     * Is [f] a GameCube or Wii disc? Read from the disc header itself (the Wii magic word at 0x18, the GameCube one at
     * 0x1C), so a PS2 or PSP .iso is never mistaken for one.
     */
    fun isNintendoDisc(f: File): Boolean {
        if (f.extension.lowercase() !in EXTS || !f.isFile || f.length() < 0x440) return false
        return runCatching {
            RandomAccessFile(f, "r").use { r ->
                r.seek(0x18)
                val wii = r.readInt()
                val gc = r.readInt()
                wii == 0x5D1C9EA3 || gc == 0xC2339F3D.toInt()
            }
        }.getOrDefault(false)
    }

    /** "Game.iso" → "Game.rvz" beside it. */
    fun rvzOf(f: File): File = File(f.parentFile, f.nameWithoutExtension + ".rvz")

    /**
     * Converts [src] into [out]. Blocks; [progress] gets 0..1 a few times a second, and [cancelled] is checked as often.
     * Throws on failure (InterruptedException when cancelled).
     */
    fun convert(src: File, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        if (!available) throw IOException("the RVZ converter isn't available on this device")
        out.delete()
        val err = watching(progress, cancelled) { nativeConvert(src.absolutePath, out.absolutePath, 5) }
        if (err != null) {
            out.delete()
            if (err == "cancelled") throw InterruptedException()
            throw IOException(err)
        }
    }

    /** Reads [rvz] back through Dolphin and checks it holds exactly the bytes of [original]. */
    fun same(original: File, rvz: File, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Boolean {
        if (!available) return false
        return watching(progress, cancelled) { nativeSame(original.absolutePath, rvz.absolutePath) } == 1
    }

    /** Runs [work] (one at a time) while a small thread passes on its progress and any cancel. */
    @Synchronized
    private fun <T> watching(progress: (Float) -> Unit, cancelled: () -> Boolean, work: () -> T): T {
        val watcher = Thread {
            try {
                while (true) {
                    Thread.sleep(250)
                    if (cancelled()) nativeCancel()
                    progress(nativeProgress().coerceIn(0f, 1f))
                }
            } catch (_: InterruptedException) { }
        }
        watcher.isDaemon = true
        watcher.start()
        try {
            return work()
        } finally {
            watcher.interrupt()
        }
    }
}
