package com.eclipse.launcher.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * WUX: a Wii U disc image (.wud) with repeated 32 KB sectors stored once (Exzap's wudcompress format, which Cemu reads).
 * A .wud is always 25 GB; most of it is the same blank sector over and over, so a .wux is about the size of the game.
 * Nothing is decrypted and the original can be rebuilt exactly.
 *
 * Layout (little-endian, as Cemu's wud.cpp reads it): a 32-byte header ("WUX0", 0x1099D02E, sector size, 4 bytes
 * padding, uncompressed size (u64), flags, 4 bytes padding), then one u32 per sector of the original (which stored
 * sector it is), then the stored sectors, starting at the next multiple of the sector size.
 */
object Wux {
    const val SECTOR = 0x8000
    private const val MAGIC0 = 0x30585557 // "WUX0"
    private const val MAGIC1 = 0x1099D02E
    private const val HEADER = 32

    /** Every Wii U disc image is this size (a dump split into _part1 … _part12 files isn't offered). */
    const val WUD_SIZE = 25_025_314_816L

    /** A whole Wii U disc image: a 25 GB .wud that starts with its "WUP-" product code. */
    fun isWud(f: File): Boolean {
        if (!f.isFile || !f.extension.equals("wud", true) || f.length() != WUD_SIZE) return false
        return runCatching {
            RandomAccessFile(f, "r").use { r -> ByteArray(4).also { r.readFully(it) }.toString(Charsets.ISO_8859_1) == "WUP-" }
        }.getOrDefault(false)
    }

    private fun le32(b: ByteArray, at: Int, v: Int) { for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte() }
    private fun le64(b: ByteArray, at: Int, v: Long) { for (i in 0 until 8) b[at + i] = (v ushr (8 * i)).toByte() }
    private fun rd32(b: ByteArray, at: Int): Int = (0 until 4).fold(0) { a, i -> a or ((b[at + i].toInt() and 0xff) shl (8 * i)) }
    private fun rd64(b: ByteArray, at: Int): Long = (0 until 8).fold(0L) { a, i -> a or ((b[at + i].toLong() and 0xff) shl (8 * i)) }

    private fun sectorsStart(count: Long): Long = ((HEADER + count * 4) + SECTOR - 1) / SECTOR * SECTOR

    /** Open-addressing table: a sector's hash (64 bits) → stored sector number. Grows as it fills. */
    private class Table {
        var keys = LongArray(1 shl 16); var vals = IntArray(1 shl 16); var used = BooleanArray(1 shl 16); var n = 0
        /** Every stored sector with this hash, through [each]; stops when [each] returns true. */
        inline fun find(k: Long, each: (Int) -> Boolean): Boolean {
            var i = (k xor (k ushr 29)).toInt() and (keys.size - 1)
            while (used[i]) { if (keys[i] == k && each(vals[i])) return true; i = (i + 1) and (keys.size - 1) }
            return false
        }
        fun add(k: Long, v: Int) {
            if (n * 2 >= keys.size) grow()
            var i = (k xor (k ushr 29)).toInt() and (keys.size - 1)
            while (used[i]) i = (i + 1) and (keys.size - 1)
            keys[i] = k; vals[i] = v; used[i] = true; n++
        }
        private fun grow() {
            val ok = keys; val ov = vals; val ou = used
            keys = LongArray(ok.size * 2); vals = IntArray(ok.size * 2); used = BooleanArray(ok.size * 2); n = 0
            for (i in ok.indices) if (ou[i]) add(ok[i], ov[i])
        }
    }

    /** Writes [src] (a .wud) as a .wux to [out]. Throws on failure. */
    fun compress(src: File, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val size = src.length()
        val count = (size + SECTOR - 1) / SECTOR
        if (count > Int.MAX_VALUE) throw IOException("the disc image is too big")
        val start = sectorsStart(count)
        val index = IntArray(count.toInt())
        val table = Table()
        val md = MessageDigest.getInstance("MD5")
        val buf = ByteArray(SECTOR)
        val other = ByteArray(SECTOR)
        out.delete()
        var ok = false
        try {
            RandomAccessFile(src, "r").use { r ->
                RandomAccessFile(out, "rw").use { w ->
                    w.setLength(start)
                    var stored = 0
                    for (s in 0 until count.toInt()) {
                        if (s and 255 == 0) { if (cancelled()) throw InterruptedException(); progress(s.toFloat() / count) }
                        val n = minOf(SECTOR.toLong(), size - s.toLong() * SECTOR).toInt()
                        r.readFully(buf, 0, n)
                        if (n < SECTOR) java.util.Arrays.fill(buf, n, SECTOR, 0)
                        val d = md.digest(buf)
                        val k = rd64(d, 0)
                        var hit = -1
                        table.find(k) { v ->
                            // same hash: make sure it really is the same sector
                            w.seek(start + v.toLong() * SECTOR); w.readFully(other)
                            if (other.contentEquals(buf)) { hit = v; true } else false
                        }
                        if (hit < 0) {
                            hit = stored++
                            w.seek(start + hit.toLong() * SECTOR); w.write(buf)
                            table.add(k, hit)
                        }
                        index[s] = hit
                    }
                    val head = ByteArray(HEADER)
                    le32(head, 0, MAGIC0); le32(head, 4, MAGIC1); le32(head, 8, SECTOR); le64(head, 16, size); le32(head, 24, 0)
                    w.seek(0); w.write(head)
                    val t = ByteArray(index.size * 4)
                    for (i in index.indices) le32(t, i * 4, index[i])
                    w.write(t)
                    w.setLength(start + stored.toLong() * SECTOR)
                }
            }
            progress(1f)
            ok = true
        } finally {
            if (!ok) out.delete()
        }
    }

    /** Reads [wux] back the way Cemu does and checks it holds exactly [original]. */
    fun same(original: File, wux: File, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Boolean = runCatching {
        RandomAccessFile(wux, "r").use { w ->
            val head = ByteArray(HEADER).also { w.readFully(it) }
            if (rd32(head, 0) != MAGIC0 || rd32(head, 4) != MAGIC1) return false
            val sector = rd32(head, 8)
            val size = rd64(head, 16)
            if (sector != SECTOR || size != original.length()) return false
            val count = ((size + sector - 1) / sector).toInt()
            val t = ByteArray(count * 4).also { w.readFully(it) }
            val start = sectorsStart(count.toLong())
            val stored = (w.length() - start) / sector
            val a = ByteArray(sector); val b = ByteArray(sector)
            RandomAccessFile(original, "r").use { r ->
                for (s in 0 until count) {
                    if (s and 255 == 0) { if (cancelled()) return false; progress(s.toFloat() / count) }
                    val i = rd32(t, s * 4).toLong() and 0xffffffffL
                    if (i >= stored) return false
                    val n = minOf(sector.toLong(), size - s.toLong() * sector).toInt()
                    r.readFully(a, 0, n)
                    w.seek(start + i * sector); w.readFully(b, 0, n)
                    for (j in 0 until n) if (a[j] != b[j]) return false
                }
            }
            true
        }
    }.getOrDefault(false)
}
