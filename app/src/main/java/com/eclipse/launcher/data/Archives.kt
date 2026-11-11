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

    // ---------------------------------------------------------------- WUA (Cemu's Wii U archive, also a ZArchive)

    /**
     * A decrypted Wii U game as a folder (code / content / meta): the folder, and the name Cemu wants for it inside a
     * .wua, "<title id, 16 hex digits>_v<version>". Found from the game's .rpx (in code/). Only base games (title ids
     * starting 00050000): updates and DLC are left alone.
     */
    data class WiiUTitle(val dir: File, val inner: String)

    fun wiiuTitle(rpx: File): WiiUTitle? {
        if (!rpx.isFile || !rpx.extension.equals("rpx", true)) return null
        val code = rpx.parentFile ?: return null
        if (!code.name.equals("code", true)) return null
        val dir = code.parentFile ?: return null
        val meta = File(dir, "meta/meta.xml")
        if (!meta.isFile || !File(dir, "content").isDirectory) return null
        val xml = runCatching { meta.readText() }.getOrNull() ?: return null
        val id = Regex("<title_id[^>]*>\\s*([0-9A-Fa-f]{16})\\s*<").find(xml)?.groupValues?.get(1)?.lowercase() ?: return null
        val ver = Regex("<title_version[^>]*>\\s*(\\d+)\\s*<").find(xml)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (!id.startsWith("00050000") || ver > 0xFFFF) return null
        return WiiUTitle(dir, "${id}_v$ver")
    }

    /** Every file in [dir] as (path inside the archive, file); folders as (path + "/", null). */
    private fun folderItems(dir: File, prefix: String): List<Pair<String, File?>> {
        val out = ArrayList<Pair<String, File?>>()
        fun go(d: File, p: String) {
            out += p to null
            for (c in d.listFiles().orEmpty().sortedBy { it.name }) {
                if (c.isDirectory) go(c, "$p/${c.name}") else if (c.isFile) out += "$p/${c.name}" to c
            }
        }
        go(dir, prefix)
        return out
    }

    /** Packs the game folder [t] into [out] as a .wua. Throws on failure. */
    fun makeWua(t: WiiUTitle, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        if (!available) throw IOException("the compressor isn't available on this device")
        val items = folderItems(t.dir, t.inner)
        val total = items.sumOf { it.second?.length() ?: 0L }.coerceAtLeast(1L)
        out.delete()
        val h = zarOpen(out.absolutePath)
        if (h == 0L) throw IOException("can't write the new file")
        var ok = false
        try {
            val buf = ByteArray(1 shl 20)
            var done = 0L
            for ((path, f) in items) {
                if (cancelled()) throw InterruptedException()
                if (f == null) { if (!zarDir(h, path)) throw IOException("couldn't add folder $path"); continue }
                if (!zarFile(h, path)) throw IOException("couldn't add $path")
                f.inputStream().use { s ->
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        if (n > 0) zarData(h, buf, n)
                        done += n
                    }
                }
                progress(done.toFloat() / total)
            }
            ok = true
        } finally {
            val written = zarClose(h, ok)
            if (!ok || !written) out.delete()
            if (ok && !written) throw IOException("couldn't write the new file")
        }
    }

    /** Reads every file of [wua] and checks it against the game folder [t], byte for byte. */
    fun sameWua(t: WiiUTitle, wua: File, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Boolean {
        if (!available) return false
        val items = folderItems(t.dir, t.inner)
        val total = items.sumOf { it.second?.length() ?: 0L }.coerceAtLeast(1L)
        val r = zarReader(wua.absolutePath)
        if (r == 0L) return false
        try {
            val a = ByteArray(1 shl 20)
            val b = ByteArray(1 shl 20)
            var done = 0L
            for ((path, f) in items) {
                if (f == null) continue
                if (cancelled()) return false
                val size = f.length()
                if (zarSize(r, path) != size) return false
                f.inputStream().use { s ->
                    var off = 0L
                    while (off < size) {
                        val n = minOf(size - off, a.size.toLong()).toInt()
                        var got = 0
                        while (got < n) { val k = s.read(a, got, n - got); if (k < 0) return false; got += k }
                        if (zarRead(r, path, off, b, n) != n) return false
                        for (i in 0 until n) if (a[i] != b[i]) return false
                        off += n
                        done += n
                    }
                }
                progress(done.toFloat() / total)
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            zarReaderClose(r)
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
