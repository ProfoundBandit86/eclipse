package com.eclipse.launcher.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Original Xbox discs → XISO, the form xemu plays. A full ("Redump") disc image also holds a video partition and
 * padding the game never reads; xemu can't open those at all. Rebuilding just the game's file system (XDVDFS) gives an
 * XISO that's often gigabytes smaller. An XISO that still carries padding (cut from a Redump image) is rebuilt too.
 *
 * XDVDFS: 2048-byte sectors; the volume descriptor ("MICROSOFT*XBOX*MEDIA") is at sector 32 of the game partition and
 * names the root directory table. A directory table is a binary tree of entries: left / right child offsets (u16, in
 * 4-byte units), start sector, size, attributes (0x10 = directory), name length, name; entries are padded to 4 bytes
 * and never cross a sector; unused bytes are 0xFF.
 */
object XIso {
    private const val SECTOR = 2048L
    private val MAGIC = "MICROSOFT*XBOX*MEDIA".toByteArray(Charsets.US_ASCII)
    /** Where the game partition starts: an XISO, then Redump images of XGD1, XGD2 and XGD3 discs. */
    private val OFFSETS = longArrayOf(0L, 0x18300000L, 0x0FD90000L, 0x02080000L)
    /** An XISO's size is rounded up to this, as extract-xiso does. */
    private const val MODULUS = 0x10000L

    class Entry(val name: String, val attr: Int, val sector: Long, val size: Long, val children: List<Entry>?) {
        val isDir get() = children != null
    }

    class Disc(val offset: Long, val root: List<Entry>, val time: ByteArray) {
        /**
         * Which console the disc is for, from the program it starts: "xbox" (default.xbe), "x360" (default.xex), or
         * null. Both use the same file system, so this is how they're told apart.
         */
        val console: String?
            get() = when {
                root.any { !it.isDir && it.name.equals("default.xbe", true) } -> "xbox"
                root.any { !it.isDir && it.name.equals("default.xex", true) } -> "x360"
                else -> null
            }
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    private fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }

    /** Reads the disc's file system, or null when [f] isn't an Xbox disc image. */
    fun open(f: File): Disc? = try {
        RandomAccessFile(f, "r").use { r -> read(r) }
    } catch (e: Exception) { null }

    private fun read(r: RandomAccessFile): Disc? {
        val buf = ByteArray(SECTOR.toInt())
        for (off in OFFSETS) {
            if (off + 0x10000 + SECTOR > r.length()) continue
            r.seek(off + 0x10000)
            r.readFully(buf)
            if (!buf.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) continue
            val rootSector = le32(buf, 20)
            val rootSize = le32(buf, 24)
            return Disc(off, readDir(r, off, rootSector, rootSize, 0), buf.copyOfRange(28, 36))
        }
        return null
    }

    private fun readDir(r: RandomAccessFile, off: Long, sector: Long, size: Long, depth: Int): List<Entry> {
        if (size <= 0L || depth > 48) return emptyList()
        if (size > 16L * 1024 * 1024) throw IOException("directory table too big")
        val table = ByteArray(size.toInt())
        r.seek(off + sector * SECTOR)
        r.readFully(table)
        val out = ArrayList<Entry>()
        val seen = HashSet<Int>()
        // in-order walk of the tree, without recursion (big directories make deep trees)
        val stack = ArrayDeque<Pair<Int, Boolean>>() // (offset, children already pushed)
        stack.addLast(0 to false)
        while (stack.isNotEmpty()) {
            val (pos, expanded) = stack.removeLast()
            if (pos < 0 || pos + 14 > table.size) continue
            val left = le16(table, pos)
            val right = le16(table, pos + 2)
            if (left == 0xFFFF && right == 0xFFFF) continue // empty directory / padding
            if (!expanded) {
                if (!seen.add(pos)) continue
                if (right != 0) stack.addLast(right * 4 to false)
                stack.addLast(pos to true)
                if (left != 0) stack.addLast(left * 4 to false)
                continue
            }
            val sec = le32(table, pos + 4)
            val sz = le32(table, pos + 8)
            val attr = table[pos + 12].toInt() and 0xff
            val len = table[pos + 13].toInt() and 0xff
            if (pos + 14 + len > table.size) continue
            val name = String(table, pos + 14, len, Charsets.ISO_8859_1)
            out += if (attr and 0x10 != 0) Entry(name, attr, sec, sz, readDir(r, off, sec, sz, depth + 1))
            else Entry(name, attr, sec, sz, null)
        }
        return out
    }

    // ---------------------------------------------------------------- the new image's layout

    private fun round(v: Long, to: Long) = (v + to - 1) / to * to
    private fun entrySize(e: Entry) = (14 + e.name.toByteArray(Charsets.ISO_8859_1).size + 3) and 3.inv()
    /** Names in the order the Xbox looks them up: case-insensitive. */
    private val ORDER = compareBy<Entry>({ it.name.uppercase(java.util.Locale.ROOT) }, { it.name })

    private class TNode(val e: Entry, val left: TNode?, val right: TNode?) { var offset = 0 }

    private fun tree(sorted: List<Entry>, lo: Int, hi: Int): TNode? {
        if (lo > hi) return null
        val mid = (lo + hi) ushr 1
        return TNode(sorted[mid], tree(sorted, lo, mid - 1), tree(sorted, mid + 1, hi))
    }

    /** Places a directory's entries (root first, then each subtree), none crossing a sector. Returns the table size. */
    private fun layout(root: TNode?): Int {
        if (root == null) return SECTOR.toInt()
        var cur = 0
        val stack = ArrayDeque<TNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val size = entrySize(n.e)
            if (cur % SECTOR.toInt() + size > SECTOR) cur = round(cur.toLong(), SECTOR).toInt()
            n.offset = cur
            cur += size
            n.right?.let { stack.addLast(it) }
            n.left?.let { stack.addLast(it) }
        }
        return round(cur.toLong(), SECTOR).toInt().coerceAtLeast(SECTOR.toInt())
    }

    private class DirPlan(val entries: List<Entry>, val tree: TNode?, val tableSize: Int) { var sector = 0L }


    /**
     * [root]: the root table; [dirOf]: each folder's table, by its own entry (not by its contents: two empty folders
     * would look alike); [order]: every table in the order it's written.
     */
    private class Plan(
        val root: DirPlan, val dirOf: java.util.IdentityHashMap<Entry, DirPlan>, val order: List<DirPlan>,
        val fileSector: java.util.IdentityHashMap<Entry, Long>, val totalSectors: Long,
    )

    /** Directory tables first (root at sector 33, after the descriptor), then every file, each on its own sectors. */
    private fun plan(disc: Disc): Plan {
        val dirOf = java.util.IdentityHashMap<Entry, DirPlan>()
        val order = ArrayList<DirPlan>()
        val queue = ArrayDeque<Pair<Entry?, List<Entry>>>() // (the folder's entry, null for the root; its contents)
        queue.addLast(null to disc.root)
        var sector = 33L
        var root: DirPlan? = null
        val files = ArrayList<Entry>()
        while (queue.isNotEmpty()) {
            val (owner, list) = queue.removeFirst()
            val sorted = list.sortedWith(ORDER)
            val t = tree(sorted, 0, sorted.size - 1)
            val p = DirPlan(sorted, t, layout(t))
            p.sector = sector
            sector += p.tableSize / SECTOR
            if (owner == null) root = p else dirOf[owner] = p
            order += p
            for (e in sorted) if (e.isDir) queue.addLast(e to e.children!!) else files += e
        }
        val fileSector = java.util.IdentityHashMap<Entry, Long>()
        for (f in files) {
            fileSector[f] = if (f.size == 0L) 0L else sector
            sector += round(f.size, SECTOR) / SECTOR
        }
        return Plan(root!!, dirOf, order, fileSector, sector)
    }

    private fun table(p: DirPlan, plan: Plan): ByteArray {
        val b = ByteArray(p.tableSize) { 0xFF.toByte() }
        val stack = ArrayDeque<TNode>()
        p.tree?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val e = n.e
            val o = n.offset
            put16(b, o, (n.left?.offset ?: 0) / 4)
            put16(b, o + 2, (n.right?.offset ?: 0) / 4)
            if (e.isDir) {
                val child = plan.dirOf[e] ?: throw IOException("folder missing from the layout")
                put32(b, o + 4, child.sector)
                put32(b, o + 8, child.tableSize.toLong())
            } else {
                put32(b, o + 4, plan.fileSector[e] ?: 0L)
                put32(b, o + 8, e.size)
            }
            b[o + 12] = e.attr.toByte()
            val name = e.name.toByteArray(Charsets.ISO_8859_1)
            b[o + 13] = name.size.toByte()
            System.arraycopy(name, 0, b, o + 14, name.size)
            n.right?.let { stack.addLast(it) }
            n.left?.let { stack.addLast(it) }
        }
        return b
    }

    /** How big [f] would be as a rebuilt XISO (null when it isn't an Xbox disc image). */
    fun rebuiltSize(f: File): Long? {
        val d = open(f)?.takeIf { it.console == "xbox" } ?: return null
        return round(plan(d).totalSectors * SECTOR, MODULUS)
    }

    /** Writes [src]'s game as a fresh XISO into [out]. Throws on failure (InterruptedException when cancelled). */
    fun rebuild(src: File, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        try {
            RandomAccessFile(src, "r").use { r ->
                val disc = read(r) ?: throw IOException("not an Xbox disc image")
                val plan = plan(disc)
                val total = plan.fileSector.keys.sumOf { it.size }.coerceAtLeast(1L)
                RandomAccessFile(out, "rw").use { w ->
                    w.setLength(0)
                    // sectors 0-31: empty; 32: the volume descriptor
                    w.write(ByteArray(0x10000))
                    val vd = ByteArray(SECTOR.toInt())
                    System.arraycopy(MAGIC, 0, vd, 0, MAGIC.size)
                    val root = plan.root
                    put32(vd, 20, root.sector)
                    put32(vd, 24, root.tableSize.toLong())
                    System.arraycopy(disc.time, 0, vd, 28, 8)
                    System.arraycopy(MAGIC, 0, vd, SECTOR.toInt() - MAGIC.size - 0, MAGIC.size)
                    w.write(vd)
                    // directory tables, in the order they were placed
                    for (p in plan.order) {
                        if (w.filePointer != p.sector * SECTOR) throw IOException("layout mismatch")
                        w.write(table(p, plan))
                    }
                    // the files
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    for ((e, sec) in plan.fileSector.entries.sortedBy { it.value }) {
                        if (e.size == 0L) continue
                        if (cancelled()) throw InterruptedException()
                        if (w.filePointer != sec * SECTOR) throw IOException("layout mismatch")
                        r.seek(disc.offset + e.sector * SECTOR)
                        var left = e.size
                        while (left > 0) {
                            val n = minOf(left, buf.size.toLong()).toInt()
                            r.readFully(buf, 0, n)
                            w.write(buf, 0, n)
                            left -= n
                            done += n
                        }
                        val pad = (round(e.size, SECTOR) - e.size).toInt()
                        if (pad > 0) w.write(ByteArray(pad))
                        progress(done.toFloat() / total)
                    }
                    w.setLength(round(w.filePointer, MODULUS))
                }
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
    }

    /** Reads [xiso] back and checks every file and folder of [original] is in it, byte for byte. */
    fun same(original: File, xiso: File, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Boolean {
      return try {
        RandomAccessFile(original, "r").use { ra ->
            RandomAccessFile(xiso, "r").use { rb ->
                val a = read(ra) ?: return false
                val b = read(rb) ?: return false
                if (b.offset != 0L) return false
                val total = count(a.root).coerceAtLeast(1L)
                var done = 0L
                val bufA = ByteArray(1 shl 20)
                val bufB = ByteArray(1 shl 20)
                fun compare(x: List<Entry>, y: List<Entry>): Boolean {
                    if (x.size != y.size) return false
                    val byName = y.associateBy { it.name }
                    for (e in x) {
                        if (cancelled()) return false
                        val f = byName[e.name] ?: return false
                        if (e.isDir != f.isDir) return false
                        if (e.isDir) { if (!compare(e.children!!, f.children!!)) return false; continue }
                        if (e.size != f.size) return false
                        ra.seek(a.offset + e.sector * SECTOR)
                        rb.seek(f.sector * SECTOR)
                        var left = e.size
                        while (left > 0) {
                            val n = minOf(left, bufA.size.toLong()).toInt()
                            ra.readFully(bufA, 0, n)
                            rb.readFully(bufB, 0, n)
                            for (i in 0 until n) if (bufA[i] != bufB[i]) return false
                            left -= n
                            done += n
                        }
                        progress(done.toFloat() / total)
                    }
                    return true
                }
                compare(a.root, b.root)
            }
        }
      } catch (e: Exception) { false }
    }

    private fun count(list: List<Entry>): Long = list.sumOf { if (it.isDir) count(it.children!!) else it.size }
}
