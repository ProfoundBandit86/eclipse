package com.eclipse.launcher.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Turns disc images into CHD files (MAME's compressed disc format, which RetroArch, DuckStation, AetherSX2/NetherSX2,
 * Flycast and most other emulators read), with no outside tools.
 *
 *  - .cue + .bin  -> a CD CHD, like `chdman createcd` (PS1, Saturn, Sega CD, PC Engine CD, Neo Geo CD, 3DO ...)
 *  - .iso         -> a DVD CHD when it's bigger than a CD (PS2, PSP, GameCube ...), like `chdman createdvd`;
 *                    otherwise a single-track CD CHD
 *
 * The files follow the CHD v5 layout exactly (header, metadata, hunks, compressed map). Hunks are compressed with
 * deflate ("cdzl" for CDs, "zlib" for DVDs), which every CHD reader supports. chdman's defaults also try LZMA and FLAC,
 * so its files come out a little smaller, but the data inside is the same.
 */
object Chd {

    // ---------------------------------------------------------------- CD layout (as chdman stores it)

    private const val SECTOR = 2352
    private const val SUBCODE = 96
    private const val FRAME = SECTOR + SUBCODE      // 2448 bytes per frame in the CHD
    private const val FRAMES_PER_HUNK = 8
    private const val TRACK_PADDING = 4             // each track is padded to a multiple of 4 frames

    /** The biggest a CD image can be; an ISO bigger than this is a DVD. */
    const val CD_MAX_BYTES = 900L * 1024 * 1024

    class Track(
        val type: String,          // MODE1, MODE1_RAW, MODE2, MODE2_FORM1, MODE2_FORM2, MODE2_FORM_MIX, MODE2_RAW, AUDIO
        val dataSize: Int,         // bytes per sector in the source file
        var frames: Int = 0,
        var pregap: Int = 0,
        var pregapInFile: Boolean = false,
        var postgap: Int = 0,
        var file: File,
        var offset: Long = 0,
        val swap: Boolean = false, // audio: the .bin is little-endian, CHDs store big-endian
        var idx0: Int = -1,
        var idx1: Int = -1
    ) {
        val extraFrames get() = ((frames + TRACK_PADDING - 1) / TRACK_PADDING) * TRACK_PADDING - frames
    }

    class ChdException(msg: String) : Exception(msg)

    private val TYPES = mapOf(
        "MODE1" to ("MODE1" to 2048), "MODE1/2048" to ("MODE1" to 2048),
        "MODE1_RAW" to ("MODE1_RAW" to 2352), "MODE1/2352" to ("MODE1_RAW" to 2352),
        "MODE2" to ("MODE2" to 2336), "MODE2/2336" to ("MODE2" to 2336),
        "MODE2_FORM1" to ("MODE2_FORM1" to 2048), "MODE2/2048" to ("MODE2_FORM1" to 2048),
        "MODE2_FORM2" to ("MODE2_FORM2" to 2324), "MODE2/2324" to ("MODE2_FORM2" to 2324),
        "MODE2_FORM_MIX" to ("MODE2_FORM_MIX" to 2336), "MODE2/2336" to ("MODE2" to 2336),
        "MODE2_RAW" to ("MODE2_RAW" to 2352), "MODE2/2352" to ("MODE2_RAW" to 2352),
        "AUDIO" to ("AUDIO" to 2352)
    )

    private fun msf(s: String): Int {
        val p = s.split(":").map { it.trim().toIntOrNull() ?: throw ChdException("Bad time \"$s\" in the .cue") }
        if (p.size != 3) throw ChdException("Bad time \"$s\" in the .cue")
        return (p[0] * 60 + p[1]) * 75 + p[2]
    }

    /** Splits a .cue line into words, keeping "quoted names" together. */
    private fun words(line: String): List<String> {
        val out = ArrayList<String>(); var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            if (line[i] == '"') {
                val end = line.indexOf('"', i + 1).let { if (it < 0) line.length else it }
                out.add(line.substring(i + 1, end)); i = end + 1
            } else {
                val start = i
                while (i < line.length && !line[i].isWhitespace()) i++
                out.add(line.substring(start, i))
            }
        }
        return out
    }

    /** Finds a file named in a .cue next to it (ignoring upper / lower case, which Android's storage cares about). */
    private fun sibling(dir: File, name: String): File {
        val direct = File(dir, name.replace('\\', '/'))
        if (direct.exists()) return direct
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val files = dir.listFiles()?.filter { it.isFile } ?: return direct
        files.firstOrNull { it.name.equals(base, ignoreCase = true) }?.let { return it }
        // Renamed files the .cue doesn't know about: "Game (Track 1).bin" in the .cue but "Game.bin" on disk (RomM and
        // some tools drop the track number from the first or only track), or the other way round.
        val ext = base.substringAfterLast('.', "")
        val stem = base.substringBeforeLast('.')
        val noTrack = stem.replace(TRACK_RE, "").trim()
        files.firstOrNull { it.extension.equals(ext, true) && it.nameWithoutExtension.equals(noTrack, ignoreCase = true) }?.let { return it }
        val n = TRACK_RE.find(stem)?.groupValues?.get(1)?.toIntOrNull()
        if (n == null) files.firstOrNull {
            it.extension.equals(ext, true) && it.nameWithoutExtension.replace(TRACK_RE, "").trim().equals(stem.trim(), ignoreCase = true) &&
                TRACK_RE.find(it.nameWithoutExtension)?.groupValues?.get(1)?.toIntOrNull() == 1
        }?.let { return it }
        return direct
    }

    private val TRACK_RE = Regex("\\s*\\(\\s*track\\s*(\\d+)\\s*\\)", RegexOption.IGNORE_CASE)

    /** Reads a .cue sheet the way chdman does (tracks, pregaps, one .bin or one per track). */
    fun parseCue(cue: File): List<Track> {
        val dir = cue.absoluteFile.parentFile ?: File(".")
        val tracks = ArrayList<Track>()
        var curFile: File? = null
        var curSwap = false
        for (raw in cue.readLines(Charsets.ISO_8859_1)) {
            val w = words(raw)
            if (w.isEmpty()) continue
            when (w[0].uppercase()) {
                "FILE" -> {
                    if (w.size < 2) throw ChdException("A FILE line in the .cue has no file name")
                    curFile = sibling(dir, w[1])
                    val kind = w.getOrNull(2)?.uppercase() ?: "BINARY"
                    curSwap = when (kind) {
                        "BINARY" -> false
                        "MOTOROLA" -> true
                        "WAVE" -> throw ChdException("This .cue uses .wav audio files, which aren't supported here")
                        else -> throw ChdException("Unsupported file type \"$kind\" in the .cue")
                    }
                }
                "TRACK" -> {
                    val f = curFile ?: throw ChdException("TRACK before any FILE in the .cue")
                    val t = TYPES[w.getOrNull(2)?.uppercase() ?: ""] ?: throw ChdException("Unsupported track type \"${w.getOrNull(2)}\"")
                    if (w.getOrNull(3) != null) throw ChdException("Discs with subchannel data aren't supported here")
                    tracks.add(Track(t.first, t.second, file = f, swap = curSwap))
                }
                "INDEX" -> {
                    val t = tracks.lastOrNull() ?: continue
                    val n = w.getOrNull(1)?.toIntOrNull() ?: continue
                    val fr = msf(w.getOrNull(2) ?: continue)
                    if (n == 0) t.idx0 = fr
                    if (n == 1) {
                        t.idx1 = fr
                        if (t.pregap == 0 && t.idx0 != -1) { t.pregap = fr - t.idx0; t.pregapInFile = true }
                        else if (t.idx0 == -1) t.idx0 = fr
                    }
                }
                "PREGAP" -> tracks.lastOrNull()?.let { it.pregap = msf(w.getOrNull(1) ?: "00:00:00") }
                "POSTGAP" -> tracks.lastOrNull()?.let { it.postgap = msf(w.getOrNull(1) ?: "00:00:00") }
                "REM" -> if (w.getOrNull(1)?.uppercase() == "SESSION" && (w.getOrNull(2)?.toIntOrNull() ?: 1) > 1)
                    throw ChdException("Multi-session discs aren't supported here (use chdman on a computer)")
            }
        }
        if (tracks.isEmpty()) throw ChdException("No tracks in the .cue")
        // audio is stored byte-swapped
        val fixed = tracks.map { t -> if (t.type == "AUDIO" && !t.swap) Track(t.type, t.dataSize, t.frames, t.pregap, t.pregapInFile, t.postgap, t.file, t.offset, true, t.idx0, t.idx1) else t }
        for ((i, t) in fixed.withIndex()) {
            if (t.idx1 == -1) throw ChdException("Track ${i + 1} has no INDEX 01")
            if (!t.file.exists()) throw ChdException("Missing file: ${t.file.name}")
            val bpf = t.dataSize
            val next = fixed.getOrNull(i + 1)
            when {
                // last track, same file as the one before: runs to the end of the file
                next == null && i > 0 && t.file == fixed[i - 1].file -> {
                    val p = fixed[i - 1]
                    t.offset = p.offset + p.frames.toLong() * p.dataSize
                    t.frames = ((t.file.length() - t.offset) / bpf).toInt()
                }
                // same file as the next track: runs up to the next track's start
                next != null && t.file == next.file -> {
                    t.frames = next.idx0 - t.idx0
                    if (t.frames <= 0) throw ChdException("Can't work out the length of track ${i + 1}")
                    if (i > 0) { val p = fixed[i - 1]; t.offset = p.offset + p.frames.toLong() * p.dataSize }
                }
                // its own file
                else -> {
                    t.frames = (t.file.length() / bpf).toInt()
                    t.offset = 0
                }
            }
            if (t.frames <= 0) throw ChdException("Track ${i + 1} is empty")
        }
        return fixed
    }

    /** A CD-sized .iso as one data track (chdman's createcd on an .iso). */
    fun isoAsCd(iso: File): List<Track> {
        val size = iso.length()
        val t = when {
            size % 2048 == 0L -> Track("MODE1", 2048, file = iso)
            size % 2336 == 0L -> Track("MODE2", 2336, file = iso)
            size % 2352 == 0L -> Track("MODE2_RAW", 2352, file = iso)
            else -> throw ChdException("${iso.name} isn't a whole number of CD sectors")
        }
        t.frames = (size / t.dataSize).toInt(); t.idx0 = 0; t.idx1 = 0
        return listOf(t)
    }

    /** The files a .cue uses (to measure, or to delete once the CHD is verified). */
    fun cueFiles(cue: File): List<File> = listOf(cue) + runCatching { parseCue(cue).map { it.file }.distinct() }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- conversion

    /** Progress callback gets 0..1; return true from [cancelled] to stop (the half-written file is deleted). */
    fun convertCd(tracks: List<Track>, out: File, threads: Int, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val total = tracks.sumOf { it.frames + it.extraFrames }
        val meta = tracks.mapIndexed { i, t ->
            val pgType = if (t.pregapInFile) "V" + t.type else "MODE1"
            val text = "TRACK:${i + 1} TYPE:${t.type} SUBTYPE:NONE FRAMES:${t.frames} PREGAP:${t.pregap} PGTYPE:$pgType PGSUB:NONE POSTGAP:${t.postgap}"
            tag("CHT2") to (text.toByteArray(Charsets.US_ASCII) + 0)
        }
        // where each track's frames start in the CHD
        val starts = IntArray(tracks.size); var acc = 0
        for ((i, t) in tracks.withIndex()) { starts[i] = acc; acc += t.frames + t.extraFrames }
        val files = HashMap<File, RandomAccessFile>()
        try {
            val reader: (Long, ByteArray) -> Unit = { frameStart, buf ->
                java.util.Arrays.fill(buf, 0)
                for (f in 0 until FRAMES_PER_HUNK) {
                    val frame = (frameStart + f).toInt()
                    if (frame >= total) break
                    var ti = tracks.size - 1
                    while (ti > 0 && starts[ti] > frame) ti--
                    val t = tracks[ti]
                    val inTrack = frame - starts[ti]
                    if (inTrack >= t.frames) continue // padding frames stay zero
                    val raf = files.getOrPut(t.file) { RandomAccessFile(t.file, "r") }
                    raf.seek(t.offset + inTrack.toLong() * t.dataSize)
                    raf.readFully(buf, f * FRAME, t.dataSize)
                    if (t.swap) {
                        var k = f * FRAME
                        val end = k + SECTOR
                        while (k < end) { val b = buf[k]; buf[k] = buf[k + 1]; buf[k + 1] = b; k += 2 }
                    }
                }
            }
            write(out, total.toLong() * FRAME, FRAMES_PER_HUNK * FRAME, FRAME, tag("cdzl"), meta, threads, progress, cancelled) { hunk, buf ->
                reader(hunk.toLong() * FRAMES_PER_HUNK, buf)
            }
        } finally {
            files.values.forEach { runCatching { it.close() } }
        }
    }

    fun convertDvd(iso: File, out: File, threads: Int, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val size = iso.length()
        if (size % 2048 != 0L) throw ChdException("${iso.name} isn't a whole number of 2048-byte sectors")
        RandomAccessFile(iso, "r").use { raf ->
            write(out, size, 4096, 2048, tag("zlib"), listOf(tag("DVD ") to byteArrayOf(0)), threads, progress, cancelled) { hunk, buf ->
                java.util.Arrays.fill(buf, 0)
                val pos = hunk.toLong() * buf.size
                raf.seek(pos)
                raf.readFully(buf, 0, minOf(buf.size.toLong(), size - pos).toInt())
            }
        }
    }

    /** Converts one .cue or .iso, choosing CD or DVD. */
    fun convert(src: File, out: File, threads: Int, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        when (src.extension.lowercase()) {
            "cue" -> convertCd(parseCue(src), out, threads, progress, cancelled)
            "iso" -> if (src.length() > CD_MAX_BYTES) convertDvd(src, out, threads, progress, cancelled)
                     else convertCd(isoAsCd(src), out, threads, progress, cancelled)
            else -> throw ChdException("Not a .cue or .iso file")
        }
    }

    // ---------------------------------------------------------------- the CHD v5 writer

    private const val HEADER = 124
    private const val COMP_TYPE0 = 0
    private const val COMP_NONE = 4

    private fun tag(s: String): Int = (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code

    private class Hunk(val type: Int, val data: ByteArray, val len: Int, val crc: Int)

    private fun write(
        out: File, logical: Long, hunkBytes: Int, unitBytes: Int, codec: Int, meta: List<Pair<Int, ByteArray>>,
        threads: Int, progress: (Float) -> Unit, cancelled: () -> Boolean, read: (Int, ByteArray) -> Unit
    ) {
        val hunks = ((logical + hunkBytes - 1) / hunkBytes).toInt()
        val isCd = codec == tag("cdzl")
        val map = ByteArray(hunks * 12)
        val rawSha = MessageDigest.getInstance("SHA-1")
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 8))
        val deflaters = ThreadLocal.withInitial { Deflater(Deflater.BEST_COMPRESSION, true) }
        try {
            RandomAccessFile(out, "rw").use { f ->
                f.setLength(0)
                f.write(ByteArray(HEADER))
                // metadata, chained in order
                var firstMeta = 0L; var prevNextField = -1L
                for ((t, data) in meta) {
                    val at = f.filePointer
                    val h = ByteArray(16)
                    putU32(h, 0, t); h[4] = 1 /* checksummed */; putU24(h, 5, data.size)
                    f.write(h); f.write(data)
                    if (prevNextField < 0) firstMeta = at else { val end = f.filePointer; f.seek(prevNextField); f.write(u64(at)); f.seek(end) }
                    prevNextField = at + 8
                }
                // hunks, compressed in batches in parallel, written in order
                val batch = if (hunkBytes < 8192) 512 else 64
                var offset = f.filePointer
                var h = 0
                while (h < hunks) {
                    if (cancelled()) throw InterruptedException()
                    val n = minOf(batch, hunks - h)
                    val bufs = Array(n) { ByteArray(hunkBytes) }
                    for (i in 0 until n) {
                        read(h + i, bufs[i])
                        val pos = (h + i).toLong() * hunkBytes
                        rawSha.update(bufs[i], 0, minOf(hunkBytes.toLong(), logical - pos).toInt())
                    }
                    val futures = (0 until n).map { i ->
                        pool.submit(Callable { compressHunk(bufs[i], isCd, deflaters.get()) })
                    }
                    for (i in 0 until n) {
                        val c = futures[i].get()
                        f.write(c.data, 0, c.len)
                        val e = (h + i) * 12
                        map[e] = c.type.toByte(); putU24(map, e + 1, c.len); putU48(map, e + 4, offset); putU16(map, e + 10, c.crc)
                        offset += c.len
                    }
                    h += n
                    progress(h.toFloat() / hunks)
                }
                // the map, then the header
                val mapOffset = f.filePointer
                f.write(compressMap(map, hunks))
                val raw = rawSha.digest()
                val header = ByteArray(HEADER)
                "MComprHD".toByteArray().copyInto(header, 0)
                putU32(header, 8, HEADER); putU32(header, 12, 5)
                putU32(header, 16, codec)
                putU64(header, 32, logical); putU64(header, 40, mapOffset); putU64(header, 48, firstMeta)
                putU32(header, 56, hunkBytes); putU32(header, 60, unitBytes)
                raw.copyInto(header, 64)
                overallSha(raw, meta).copyInto(header, 84)
                f.seek(0); f.write(header)
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            pool.shutdownNow()
        }
    }

    private fun compressHunk(src: ByteArray, cd: Boolean, def: Deflater): Hunk {
        val crc = crc16(src, 0, src.size)
        val out = ByteArray(src.size + 64)
        val len: Int = if (cd) {
            // cdzl: [ECC flags, none][2-byte length of the sector part][deflated sectors][deflated subcode]
            val frames = src.size / FRAME
            val sectors = ByteArray(frames * SECTOR); val sub = ByteArray(frames * SUBCODE)
            for (fr in 0 until frames) {
                System.arraycopy(src, fr * FRAME, sectors, fr * SECTOR, SECTOR)
                System.arraycopy(src, fr * FRAME + SECTOR, sub, fr * SUBCODE, SUBCODE)
            }
            val eccBytes = (frames + 7) / 8
            val lenBytes = if (src.size < 65536) 2 else 3
            val hdr = eccBytes + lenBytes
            val a = deflate(def, sectors, out, hdr)
            if (a < 0 || a >= src.size) -1 else {
                if (lenBytes == 2) putU16(out, eccBytes, a) else putU24(out, eccBytes, a)
                val b = deflate(def, sub, out, hdr + a)
                if (b < 0) -1 else hdr + a + b
            }
        } else deflate(def, src, out, 0)
        return if (len < 0 || len >= src.size) Hunk(COMP_NONE, src, src.size, crc) else Hunk(COMP_TYPE0, out, len, crc)
    }

    /** Raw deflate into [out] at [at]; -1 if it doesn't fit. */
    private fun deflate(def: Deflater, src: ByteArray, out: ByteArray, at: Int): Int {
        def.reset(); def.setInput(src); def.finish()
        var n = 0
        while (!def.finished()) {
            if (at + n >= out.size) return -1
            n += def.deflate(out, at + n, out.size - at - n)
        }
        return n
    }

    /**
     * The v5 map: per hunk a compression type (Huffman-coded; here every type gets a plain 4-bit code), then for each
     * hunk its compressed length and CRC-16. Offsets aren't stored: hunks are back to back after the first one.
     */
    private fun compressMap(raw: ByteArray, hunks: Int): ByteArray {
        var maxLen = 0
        for (h in 0 until hunks) if (raw[h * 12].toInt() != COMP_NONE) maxLen = maxOf(maxLen, getU24(raw, h * 12 + 1))
        val lengthBits = 32 - Integer.numberOfLeadingZeros(maxLen)
        val bits = BitWriter()
        repeat(16) { bits.write(4, 4) }                  // the Huffman tree: all 16 codes are 4 bits long
        for (h in 0 until hunks) bits.write(raw[h * 12].toInt(), 4)  // no run-length tricks, one code per hunk
        var firstOffs = 0L
        for (h in 0 until hunks) {
            val e = h * 12
            val crc = getU16(raw, e + 10)
            if (raw[e].toInt() == COMP_NONE) bits.write(crc, 16)
            else { bits.write(getU24(raw, e + 1), lengthBits); bits.write(crc, 16) }
            if (h == 0) firstOffs = getU48(raw, e + 4)
        }
        val body = bits.bytes()
        val out = ByteArray(16 + body.size)
        putU32(out, 0, body.size); putU48(out, 4, firstOffs); putU16(out, 10, crc16(raw, 0, hunks * 12))
        out[12] = lengthBits.toByte(); out[13] = 0; out[14] = 0; out[15] = 0
        body.copyInto(out, 16)
        return out
    }

    /** SHA-1 of the raw data's SHA-1 followed by each checksummed metadata item (tag + SHA-1 of its data), sorted. */
    private fun overallSha(raw: ByteArray, meta: List<Pair<Int, ByteArray>>): ByteArray {
        val items = meta.map { (t, d) -> u32(t) + MessageDigest.getInstance("SHA-1").digest(d) }
            .sortedWith { a, b -> for (i in a.indices) { val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (c != 0) return@sortedWith c }; 0 }
        val md = MessageDigest.getInstance("SHA-1")
        md.update(raw); items.forEach { md.update(it) }
        return md.digest()
    }

    // ---------------------------------------------------------------- verify (reads back what we wrote)

    /**
     * Reads the whole CHD back: every hunk decompressed and checked against its CRC-16, and the data against the
     * SHA-1 in the header. Only understands the codecs written here (cdzl / zlib / uncompressed).
     */
    fun verify(chd: File, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Boolean {
        RandomAccessFile(chd, "r").use { f ->
            val header = ByteArray(HEADER); f.readFully(header)
            if (String(header, 0, 8) != "MComprHD" || getU32(header, 12) != 5) return false
            val codec = getU32(header, 16)
            val logical = getU64(header, 32); val mapOffset = getU64(header, 40)
            val hunkBytes = getU32(header, 56)
            val hunks = ((logical + hunkBytes - 1) / hunkBytes).toInt()
            val rawSha = header.copyOfRange(64, 84)
            // map
            f.seek(mapOffset)
            val mh = ByteArray(16); f.readFully(mh)
            val body = ByteArray(getU32(mh, 0)); f.readFully(body)
            val lengthBits = mh[12].toInt()
            var offs = getU48(mh, 4)
            val br = BitReader(body)
            val lens = IntArray(16); for (i in 0 until 16) lens[i] = br.read(4)
            if (lens.any { it != 4 }) return false // not one of ours
            val types = IntArray(hunks) { br.read(4) }
            val md = MessageDigest.getInstance("SHA-1")
            val inf = Inflater(true)
            val buf = ByteArray(hunkBytes)
            val isCd = codec == tag("cdzl")
            for (h in 0 until hunks) {
                if (cancelled()) return false
                val len: Int; val crc: Int
                when (types[h]) {
                    COMP_TYPE0 -> { len = br.read(lengthBits); crc = br.read(16) }
                    COMP_NONE -> { len = hunkBytes; crc = br.read(16) }
                    else -> return false
                }
                val comp = ByteArray(len); f.seek(offs); f.readFully(comp); offs += len
                if (types[h] == COMP_NONE) comp.copyInto(buf)
                else if (isCd) {
                    val frames = hunkBytes / FRAME
                    val eccBytes = (frames + 7) / 8
                    val lenBytes = if (hunkBytes < 65536) 2 else 3
                    val a = if (lenBytes == 2) getU16(comp, eccBytes) else getU24(comp, eccBytes)
                    val sectors = ByteArray(frames * SECTOR); val sub = ByteArray(frames * SUBCODE)
                    if (!inflate(inf, comp, eccBytes + lenBytes, a, sectors)) return false
                    if (!inflate(inf, comp, eccBytes + lenBytes + a, len - eccBytes - lenBytes - a, sub)) return false
                    for (fr in 0 until frames) {
                        System.arraycopy(sectors, fr * SECTOR, buf, fr * FRAME, SECTOR)
                        System.arraycopy(sub, fr * SUBCODE, buf, fr * FRAME + SECTOR, SUBCODE)
                    }
                } else if (!inflate(inf, comp, 0, len, buf)) return false
                if (crc16(buf, 0, hunkBytes) != crc) return false
                val pos = h.toLong() * hunkBytes
                md.update(buf, 0, minOf(hunkBytes.toLong(), logical - pos).toInt())
                if (h % 64 == 0) progress(h.toFloat() / hunks)
            }
            progress(1f)
            return md.digest().contentEquals(rawSha)
        }
    }

    private fun inflate(inf: Inflater, src: ByteArray, at: Int, len: Int, out: ByteArray): Boolean = try {
        inf.reset(); inf.setInput(src, at, len)
        var n = 0
        while (n < out.size) { val k = inf.inflate(out, n, out.size - n); if (k == 0 && (inf.finished() || inf.needsInput())) break; n += k }
        n == out.size
    } catch (e: Exception) { false }

    // ---------------------------------------------------------------- bits and bytes

    private class BitWriter {
        private val out = java.io.ByteArrayOutputStream()
        private var acc = 0L; private var n = 0
        fun write(value: Int, bits: Int) {
            if (bits == 0) return
            acc = (acc shl bits) or (value.toLong() and ((1L shl bits) - 1)); n += bits
            while (n >= 8) { out.write(((acc shr (n - 8)) and 0xff).toInt()); n -= 8 }
        }
        fun bytes(): ByteArray { if (n > 0) { out.write(((acc shl (8 - n)) and 0xff).toInt()); n = 0 }; return out.toByteArray() }
    }

    private class BitReader(private val b: ByteArray) {
        private var pos = 0L
        fun read(bits: Int): Int {
            var v = 0
            repeat(bits) {
                val byte = (pos ushr 3).toInt()
                val bit = if (byte < b.size) (b[byte].toInt() shr (7 - (pos and 7).toInt())) and 1 else 0
                v = (v shl 1) or bit; pos++
            }
            return v
        }
    }

    private val CRC16 = IntArray(256).also { t ->
        for (i in 0 until 256) { var c = i shl 8; repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }; t[i] = c and 0xffff }
    }
    private fun crc16(b: ByteArray, off: Int, len: Int): Int {
        var crc = 0xffff
        for (i in off until off + len) crc = ((crc shl 8) xor CRC16[((crc ushr 8) xor (b[i].toInt() and 0xff)) and 0xff]) and 0xffff
        return crc
    }

    private fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
    private fun putU24(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 16).toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = v.toByte() }
    private fun putU32(b: ByteArray, o: Int, v: Int) { for (i in 0 until 4) b[o + i] = (v ushr (24 - 8 * i)).toByte() }
    private fun putU48(b: ByteArray, o: Int, v: Long) { for (i in 0 until 6) b[o + i] = (v ushr (40 - 8 * i)).toByte() }
    private fun putU64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (56 - 8 * i)).toByte() }
    private fun u32(v: Int) = ByteArray(4).also { putU32(it, 0, v) }
    private fun u64(v: Long) = ByteArray(8).also { putU64(it, 0, v) }
    private fun getU16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    private fun getU24(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 16) or ((b[o + 1].toInt() and 0xff) shl 8) or (b[o + 2].toInt() and 0xff)
    private fun getU32(b: ByteArray, o: Int) = (0 until 4).fold(0) { a, i -> (a shl 8) or (b[o + i].toInt() and 0xff) }
    private fun getU48(b: ByteArray, o: Int) = (0 until 6).fold(0L) { a, i -> (a shl 8) or (b[o + i].toLong() and 0xff) }
    private fun getU64(b: ByteArray, o: Int) = (0 until 8).fold(0L) { a, i -> (a shl 8) or (b[o + i].toLong() and 0xff) }
}
