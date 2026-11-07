package com.eclipse.launcher.data

import org.json.JSONObject
import java.io.File

/**
 * Finds out which cores you have in RetroArch.
 *
 * RetroArch keeps downloaded cores in its own private folder, which Android doesn't let other apps list. So this
 * gathers every clue that *is* readable:
 *  - core files in a cores folder you can see (if RetroArch's "Cores" directory was moved to shared storage),
 *    or under RetroArch/cores and RetroArch/downloads;
 *  - the cores named in RetroArch's playlists and history (every core you've ever run content with);
 *  - the last-used core in retroarch.cfg.
 * The result is a "known installed" list, not a complete one; a core you downloaded but never used can be missed.
 */
object RaCores {
    data class Found(
        /** core name (e.g. "snes9x") -> its file, when that file is readable (then Eclipse launches it from there). */
        val cores: Map<String, String?>,
        /** where they were found, for the settings screen */
        val sources: List<String>
    )

    private const val SD = "/storage/emulated/0"

    private fun coreName(path: String): String? {
        val n = path.substringAfterLast('/')
        return when {
            n.endsWith("_libretro_android.so") -> n.removeSuffix("_libretro_android.so")
            n.endsWith("_libretro.so") -> n.removeSuffix("_libretro.so")
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    /** key = "value" lines of a retroarch.cfg */
    private fun readCfg(f: File): Map<String, String> = try {
        f.readLines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim().trim('"')
        }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    fun scan(raPackages: List<String>): Found {
        val cores = LinkedHashMap<String, String?>()
        val sources = ArrayList<String>()

        // retroarch.cfg wherever it can be read (Android/data is readable on Android 10 and older only)
        val cfgFiles = listOf("$SD/RetroArch/retroarch.cfg", "$SD/RetroArch/config/retroarch.cfg") +
            raPackages.map { "$SD/Android/data/$it/files/retroarch.cfg" }
        val cfgs = cfgFiles.map { File(it) }.filter { it.canRead() }.map { readCfg(it) }

        // 1) core files we can actually see
        val dirs = LinkedHashSet<String>()
        cfgs.forEach { c -> c["libretro_directory"]?.takeIf { it.isNotBlank() && !it.startsWith("/data/") }?.let { dirs.add(it.replace("~", SD)) } }
        dirs += listOf("$SD/RetroArch/cores", "$SD/RetroArch/downloads")
        for (d in dirs) {
            val files = File(d).listFiles()?.filter { it.isFile } ?: continue
            var n = 0
            for (f in files) coreName(f.name)?.let { name -> cores[name] = f.absolutePath; n++ }
            if (n > 0) sources += "$n in $d"
        }

        // 2) cores used in playlists / history
        val listDirs = LinkedHashSet<String>()
        cfgs.forEach { c -> c["playlist_directory"]?.takeIf { it.isNotBlank() }?.let { listDirs.add(it.replace("~", SD)) } }
        listDirs += "$SD/RetroArch/playlists"
        var fromLists = 0
        for (d in listDirs) {
            File(d).walkTopDown().maxDepth(2).filter { it.isFile && it.extension.equals("lpl", true) }.forEach { lpl ->
                try {
                    val o = JSONObject(lpl.readText())
                    val paths = ArrayList<String>()
                    o.optString("default_core_path").takeIf { it.isNotBlank() }?.let(paths::add)
                    o.optJSONArray("items")?.let { arr ->
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("core_path")?.takeIf { it.isNotBlank() && it != "DETECT" }?.let(paths::add)
                    }
                    for (p in paths) coreName(p)?.let { name ->
                        if (name !in cores) { cores[name] = File(p).takeIf { it.canRead() }?.absolutePath; fromLists++ }
                    }
                } catch (e: Exception) {
                }
            }
        }
        if (fromLists > 0) sources += "$fromLists from your RetroArch playlists and history"

        // 3) the core loaded last
        cfgs.forEach { c ->
            c["libretro_path"]?.let { p -> coreName(p)?.let { name -> if (name !in cores) { cores[name] = File(p).takeIf { it.canRead() }?.absolutePath; sources += "last used: $name" } } }
        }
        return Found(cores, sources)
    }
}
