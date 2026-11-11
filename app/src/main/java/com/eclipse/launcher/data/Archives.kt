package com.eclipse.launcher.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Two compressed formats emulators read directly (the work is native, in libeclipse_rvz.so: rvz_jni.cpp):
 *  - Z3DS (.zcci .zcxi .z3dsx .zcia): Azahar's compressed 3DS files, seekable Zstandard. Only Azahar (and emulators
 *    built on it) can play them.
 *  - ZArchive (.zar): Exzap's format, which Xenia Canary and X360 Mobile read for Xbox 360 discs: the disc's files,
 *    Zstandard-compressed. The disc's file system is read with [XIso] (Xbox 360 discs use the same XDVDFS).
 */
object Archives {
    val available: Boolean get() = Rvz.available

    @JvmStatic private external fun nativeZ3ds(src: String, out: String, magic: String, frame: Int, metadata: ByteArray): String?
    @JvmStatic private external fun nativeZ3dsSame(src: String, z3ds: String): Int
    @JvmStatic private external fun zarOpen(out: String): Long
    @JvmStatic private external fun zarDir(h: Long, path: String): Boolean
    @JvmStatic private external fun zarFile(h: Long, path: String): Boolean
    @JvmStatic private external fun zarData(h: Long, data: ByteArray, len: Int)
    @JvmStatic private external fun zarClose(h: Long, finish: Boolean): Boolean
    @JvmStatic private external fun zarReader(path: String): Long
    @JvmStatic private external fun zarSize(r: Long, path: String): Long
    @JvmStatic private external fun zarRead(r: Long, path: String, offset: Long, buf: ByteArray, len: Int): Int
    @JvmStatic private external fun zarReaderClose(r: Long)
    @JvmStatic private external fun nativeProgress(): Float
    @JvmStatic private external fun nativeCancel()

    // ---------------------------------------------------------------- Z3DS

    /** What a 3DS file becomes: the original's magic (stored in the header), the new extension, Azahar's frame size. */
    data class Z3ds(val magic: String, val ext: String, val frame: Int)

    private fun magicAt(f: File, at: Long, len: Int): String? = runCatching {
        RandomAccessFile(f, "r").use { r ->
            if (r.length() < at + len) return null
            r.seek(at)
            ByteArray(len).also { r.readFully(it) }.toString(Charsets.ISO_8859_1)
        }
    }.getOrNull()

    /**
     * The Z3DS kind of [f], checked from its contents (as Azahar does): a .cci (NCSD), .cxi (NCCH), .3dsx or .cia.
     * (.3ds files aren't offered: Azahar deliberately doesn't load that format.)
     */
    fun z3dsKind(f: File): Z3ds? {
        if (!f.isFile) return null
        return when (f.extension.lowercase()) {
            "cci" -> if (magicAt(f, 0x100, 4) == "NCSD") Z3ds("NCSD", "zcci", 256 * 1024) else null
            "cxi" -> if (magicAt(f, 0x100, 4) == "NCCH") Z3ds("NCCH", "zcxi", 256 * 1024) else null
            "3dsx" -> if (magicAt(f, 0, 4) == "3DSX") Z3ds("3DSX", "z3dsx", 256 * 1024) else null
            // a CIA starts with its header size, 0x2020
            "cia" -> if (magicAt(f, 0, 4) == "  \u0000\u0000") Z3ds("CIA\u0000", "zcia", 32 * 1024 * 1024) else null
            else -> null
        }
    }

    /** Azahar's metadata block: version 1, then (type 1, name length, data length, name, data) items, then an end item. */
    private fun metadata(frame: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(1)
        fun item(name: String, value: String) {
            val n = name.toByteArray(Charsets.UTF_8)
            val v = value.toByteArray(Charsets.UTF_8)
            out.write(1); out.write(n.size); out.write(v.size and 0xff); out.write((v.size ushr 8) and 0xff)
            out.write(n); out.write(v)
        }
        item("compressor", "Eclipse")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        item("date", fmt.format(java.util.Date()))
        item("maxframesize", frame.toString())
        out.write(ByteArray(4)) // end
        return out.toByteArray()
    }

    fun compress3ds(src: File, out: File, kind: Z3ds, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        if (!available) throw IOException("the compressor isn't available on this device")
        out.delete()
        val err = watching(progress, cancelled) { nativeZ3ds(src.absolutePath, out.absolutePath, kind.magic, kind.frame, metadata(kind.frame)) }
        if (err != null) {
            out.delete()
            if (err == "cancelled") throw InterruptedException()
            throw IOException(err)
        }
    }

    /** Decompresses [z3ds] and checks it holds exactly [original]. */
    fun same3ds(original: File, z3ds: File, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Boolean =
        available && watching(progress, cancelled) { nativeZ3dsSame(original.absolutePath, z3ds.absolutePath) } == 1

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
        try { return work() } finally { watcher.interrupt() }
    }

    // ---------------------------------------------------------------- ZArchive

    /** An Xbox 360 disc image (.iso) Eclipse can turn into a .zar. */
    fun zarSource(f: File): Boolean = f.extension.equals("iso", true) && XIso.open(f)?.console == "x360"

    /** Every file of the disc as ("folder/name", entry), folders as ("folder/", null). */
    private fun walk(list: List<XIso.Entry>, prefix: String, out: MutableList<Pair<String, XIso.Entry?>>) {
        for (e in list) {
            val path = prefix + e.name
            if (e.isDir) { out += "$path/" to null; walk(e.children!!, "$path/", out) } else out += path to e
        }
    }

    /** Packs the disc in [src] into [out] (its files, Zstandard-compressed). Throws on failure. */
    fun makeZar(src: File, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        if (!available) throw IOException("the compressor isn't available on this device")
        val disc = XIso.open(src) ?: throw IOException("not an Xbox 360 disc image")
        val items = ArrayList<Pair<String, XIso.Entry?>>().also { walk(disc.root, "", it) }
        val total = items.sumOf { it.second?.size ?: 0L }.coerceAtLeast(1L)
        out.delete()
        val h = zarOpen(out.absolutePath)
        if (h == 0L) throw IOException("can't write the new file")
        var ok = false
        try {
            RandomAccessFile(src, "r").use { r ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                for ((path, e) in items) {
                    if (cancelled()) throw InterruptedException()
                    if (e == null) { if (!zarDir(h, path.trimEnd('/'))) throw IOException("couldn't add folder $path"); continue }
                    if (!zarFile(h, path)) throw IOException("couldn't add $path")
                    r.seek(disc.offset + e.sector * 2048L)
                    var left = e.size
                    while (left > 0) {
                        val n = minOf(left, buf.size.toLong()).toInt()
                        r.readFully(buf, 0, n)
                        zarData(h, buf, n)
                        left -= n
                        done += n
                    }
                    progress(done.toFloat() / total)
                }
            }
            ok = true
        } finally {
            val written = zarClose(h, ok)
            if (!ok || !written) out.delete()
            if (ok && !written) throw IOException("couldn't write the new file")
        }
    }

    /** Reads every file of [zar] and checks it against the disc in [original], byte for byte. */
    fun sameZar(original: File, zar: File, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Boolean {
        if (!available) return false
        val disc = XIso.open(original) ?: return false
        val items = ArrayList<Pair<String, XIso.Entry?>>().also { walk(disc.root, "", it) }
        val total = items.sumOf { it.second?.size ?: 0L }.coerceAtLeast(1L)
        val r = zarReader(zar.absolutePath)
        if (r == 0L) return false
        try {
            RandomAccessFile(original, "r").use { src ->
                val a = ByteArray(1 shl 20)
                val b = ByteArray(1 shl 20)
                var done = 0L
                for ((path, e) in items) {
                    if (e == null) continue
                    if (cancelled()) return false
                    if (zarSize(r, path) != e.size) return false
                    src.seek(disc.offset + e.sector * 2048L)
                    var off = 0L
                    while (off < e.size) {
                        val n = minOf(e.size - off, a.size.toLong()).toInt()
                        src.readFully(a, 0, n)
                        if (zarRead(r, path, off, b, n) != n) return false
                        for (i in 0 until n) if (a[i] != b[i]) return false
                        off += n
                        done += n
                    }
                    progress(done.toFloat() / total)
                }
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            zarReaderClose(r)
        }
    }
}
