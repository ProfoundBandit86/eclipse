package com.eclipse.launcher.data

import java.io.File

/**
 * .m3u playlists for multi-disc games: "Game (Disc 1).chd", "Game (Disc 2).chd" … become "Game.m3u", one disc per
 * line. Emulators use it to swap discs, and Eclipse's library then shows the game once (as the playlist).
 */
object M3u {

    /** One multi-disc game: its folder, title (the name without the disc tag), its discs in order, and its playlist if it has one. */
    data class DiscSet(val dir: File, val title: String, val discs: List<File>, val playlist: File?) {
        val key get() = File(dir, title).absolutePath
        /** the playlist already lists exactly these discs */
        val upToDate get() = playlist != null && runCatching { entries(playlist!!) == discs.map { it.name } }.getOrDefault(false)
    }

    /** "(Disc 1)", "(Disc 2 of 3)", "[CD2]", "(Disk B)" … */
    private val TAG = Regex("""\s*[\(\[]\s*(?:disc|disk|cd)\s*([0-9]{1,2}|[a-d])\s*(?:of\s*[0-9]{1,2})?\s*[\)\]]""", RegexOption.IGNORE_CASE)

    /** Disc image formats, best first: when a disc is there in two formats (say .cue and .chd), the playlist uses the first. */
    private val FORMATS = listOf("chd", "cue", "gdi", "ccd", "mds", "iso", "cso", "pbp", "rvz", "gcz")

    fun discNumber(name: String): Int? = TAG.find(name)?.groupValues?.get(1)?.let { v ->
        v.toIntOrNull() ?: (v.lowercase()[0] - 'a' + 1)
    }

    fun titleOf(name: String): String = TAG.replace(name, "").replace(Regex("\\s{2,}"), " ").trim()

    /** The lines of a playlist that name a disc. */
    fun entries(m3u: File): List<String> =
        m3u.readLines().map { it.trim().trim('"') }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Every multi-disc game in [roots] (and their sub-folders, [depth] levels down). */
    fun find(roots: List<File>, depth: Int = 4): List<DiscSet> {
        val out = ArrayList<DiscSet>()
        val seenDirs = HashSet<String>()
        for (root in roots) {
            root.walkTopDown().maxDepth(depth).onEnter { !it.name.startsWith(".") && it.name != "Android" }
                .filter { it.isDirectory }
                .forEach { dir -> if (seenDirs.add(dir.absolutePath)) out += inFolder(dir) }
        }
        return out.sortedWith(compareBy({ it.upToDate }, { it.title.lowercase() }))
    }

    /** The multi-disc games directly inside [dir]. */
    fun inFolder(dir: File): List<DiscSet> {
        val files = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        // (title, disc number) -> the best file for that disc
        val best = HashMap<Pair<String, Int>, File>()
        val titles = HashMap<String, String>() // lowercase -> as written
        for (f in files) {
            val rank = FORMATS.indexOf(f.extension.lowercase())
            if (rank < 0) continue
            val n = discNumber(f.nameWithoutExtension) ?: continue
            val title = titleOf(f.nameWithoutExtension)
            if (title.isEmpty()) continue
            val key = title.lowercase() to n
            titles.putIfAbsent(title.lowercase(), title)
            val cur = best[key]
            if (cur == null || rank < FORMATS.indexOf(cur.extension.lowercase())) best[key] = f
        }
        return best.entries.groupBy({ it.key.first }, { it.key.second to it.value })
            .filter { it.value.size >= 2 }
            .map { (t, discs) ->
                val title = titles[t] ?: t
                val playlist = files.firstOrNull { it.extension.equals("m3u", true) && it.nameWithoutExtension.equals(title, true) }
                DiscSet(dir, title, discs.sortedBy { it.first }.map { it.second }, playlist)
            }
    }

    /** Writes (or rewrites) the playlist for [set]. Returns the file. */
    fun write(set: DiscSet): File {
        val f = set.playlist ?: File(set.dir, "${set.title}.m3u")
        f.writeText(set.discs.joinToString("\n") { it.name } + "\n")
        return f
    }
}
