package com.eclipse.launcher.data

import android.os.Environment
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PlayStation Vita games for Vita3K. Vita3K only starts games installed inside it (its ux0/app/<title id> folders), and
 * Eclipse opens them through a "<Game>.psvita" file holding the title ID. [sync] writes those files for every game
 * installed in Vita3K, so nobody has to make them by hand.
 */
object Vita {
    /** First line of every shortcut Eclipse makes: lets it tell its own files from yours (Library.shortcutId skips it). */
    const val MARK = "# Made by Eclipse from Vita3K"

    data class Installed(val titleId: String, val title: String)

    /** Folder names Vita3K's storage is usually given (setup guides: "Vita3K" or "Vita" at the root of a storage). */
    private val NAMES = setOf("vita3k", "vita", "vita3k+", "vita3kzx", "psvita")

    /**
     * Vita3K's storage folder: [chosen] (picked in the Vita system editor) if it holds ux0/app, else the first usual
     * place that does. Vita3K's default inside Android/data can't be read by other apps, so it's not looked for.
     */
    fun findRoot(chosen: String?): File? {
        chosen?.takeIf { it.isNotBlank() }?.let { c -> rootOf(File(c))?.let { return it } }
        val storages = buildList {
            add(Environment.getExternalStorageDirectory())
            File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }?.let { addAll(it) }
        }
        for (s in storages) {
            val kids = s.listFiles()?.filter { it.isDirectory && it.name.lowercase() in NAMES } ?: continue
            for (k in kids.sortedBy { it.name.lowercase() }) rootOf(k)?.let { return it }
        }
        return null
    }

    /** [f] itself, its ux0, or the folder above it: whichever is Vita3K's storage (has ux0/app). */
    private fun rootOf(f: File): File? = listOfNotNull(f, f.parentFile, f.parentFile?.parentFile)
        .firstOrNull { File(it, "ux0/app").isDirectory }

    /** Every game installed in Vita3K under [root] (its title ID and name, from the game's own param.sfo). */
    fun installed(root: File): List<Installed> =
        File(root, "ux0/app").listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val sfo = File(dir, "sce_sys/param.sfo")
            val keys = runCatching { readSfo(sfo) }.getOrNull() ?: return@mapNotNull null
            val id = (keys["TITLE_ID"] ?: dir.name).trim()
            // retail games are 4 letters + 5 digits (PCSE00120); homebrew uses any 9 letters / digits (VITASHELL)
            if (!Regex("[A-Z0-9]{9}").matches(id)) return@mapNotNull null
            // "TITLE" can hold a line break ("Persona 4\nGolden"); the short title is the fallback
            val title = (keys["TITLE"] ?: keys["STITLE"] ?: id).replace(Regex("\\s+"), " ").trim()
            Installed(id, title)
        }.sortedBy { it.title.lowercase() }

    /** Text values of a param.sfo ("\u0000PSF": key table + data table, little-endian). */
    fun readSfo(f: File): Map<String, String> {
        val bytes = RandomAccessFile(f, "r").use { r ->
            if (r.length() > 64 * 1024) return emptyMap()
            ByteArray(r.length().toInt()).also { r.readFully(it) }
        }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 20 || b.getInt(0) != 0x46535000) return emptyMap() // "\0PSF"
        val keyTable = b.getInt(8); val dataTable = b.getInt(12); val count = b.getInt(16)
        val out = HashMap<String, String>()
        for (i in 0 until count.coerceAtMost(256)) {
            val e = 20 + i * 16
            if (e + 16 > bytes.size) break
            val keyOff = b.getShort(e).toInt() and 0xffff
            val fmt = b.getShort(e + 2).toInt() and 0xffff
            val len = b.getInt(e + 4)
            val dataOff = b.getInt(e + 12)
            val k = keyTable + keyOff
            var end = k
            while (end < bytes.size && bytes[end] != 0.toByte()) end++
            val key = String(bytes, k, end - k, Charsets.US_ASCII)
            val d = dataTable + dataOff
            // 0x0204 = UTF-8 text (null-terminated), 0x0004 = UTF-8 special
            if ((fmt == 0x0204 || fmt == 0x0004) && d >= 0 && len > 0 && d + len <= bytes.size)
                out[key] = String(bytes, d, len, Charsets.UTF_8).trimEnd('\u0000')
        }
        return out
    }

    data class Result(val made: Int, val removed: Int)

    /**
     * Makes "<Title>.psvita" in [dest] for each of [games] that has no shortcut yet (any .psvita in [lookIn] with that
     * title ID counts, yours included), and deletes shortcuts Eclipse made for games no longer installed. Yours are
     * never touched.
     */
    fun sync(games: List<Installed>, dest: File, lookIn: List<File>): Result {
        val existing = (lookIn + dest).distinct().flatMap { dir ->
            dir.walkTopDown().maxDepth(2).filter { it.isFile && it.extension.equals("psvita", true) }.toList()
        }.distinct()
        val have = existing.map { Library.shortcutId(it).uppercase() }.toSet()
        val installedIds = games.map { it.titleId }.toSet()
        var removed = 0
        for (f in existing) {
            val mine = runCatching { f.useLines { it.firstOrNull()?.trim() == MARK } }.getOrDefault(false)
            if (mine && Library.shortcutId(f).uppercase() !in installedIds && f.delete()) removed++
        }
        var made = 0
        for (g in games) {
            if (g.titleId in have) continue
            if (!dest.isDirectory && !dest.mkdirs()) break
            var f = File(dest, safeName(g.title) + ".psvita")
            if (f.exists()) f = File(dest, safeName(g.title) + " [${g.titleId}].psvita")
            if (f.exists()) continue
            if (runCatching { f.writeText("$MARK\n${g.titleId}\n") }.isSuccess) made++
        }
        return Result(made, removed)
    }

    /** A title as a file name: no characters Android's storage refuses, not too long. */
    private fun safeName(t: String): String =
        t.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(120).ifEmpty { "Vita game" }
}
