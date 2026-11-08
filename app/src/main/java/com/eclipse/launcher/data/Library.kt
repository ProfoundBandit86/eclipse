package com.eclipse.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.os.StrictMode
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import java.io.File

object Scanner {
    /** [forced] = everything in this folder belongs to that system (a folder added for one system). */
    fun scan(rootPath: String, forced: String? = null): List<Game> {
        val root = File(rootPath)
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<Game>()

        fun walk(dir: File, hint: String?, depth: Int) {
            val kids = dir.listFiles() ?: return
            for (f in kids.sortedBy { it.name.lowercase() }) {
                if (f.name.startsWith(".")) continue
                // phone folders that never hold games (Android's app data above all): skip, unless one is named after a system
                if (f.isDirectory && forced == null && f.name.lowercase() in notGames && Platforms.matchFolder(f.name) == null) continue
                if (f.isDirectory) {
                    if (depth < 4) walk(f, forced ?: Platforms.matchFolder(f.name)?.id ?: hint, depth + 1)
                } else {
                    val ext = f.extension.lowercase()
                    // a .bin that belongs to a .cue sheet is not a separate game
                    if (ext == "bin" && File(f.parentFile, f.nameWithoutExtension + ".cue").exists()) continue
                    val p = Platforms.detect(hint, ext) ?: continue
                    // Switch updates and DLC sit next to the game; they aren't games of their own
                    if (p.id == "switch" && isSwitchAddon(f.name)) continue
                    // PICO-8: "celeste_classic-2.p8.png" is "Celeste Classic"
                    val title = if (p.id == "pico8") Pico8.title(f.name) else cleanTitle(f.nameWithoutExtension)
                    out.add(Game("g" + Integer.toHexString(f.absolutePath.hashCode()), title, p.id, f.absolutePath))
                }
            }
        }
        walk(root, forced ?: Platforms.matchFolder(root.name)?.id, 0)
        return hideM3uDiscs(out)
    }

    private val notGames = setOf(
        "android", "dcim", "pictures", "screenshots", "music", "movies", "ringtones", "notifications", "alarms",
        "podcasts", "recordings", "audiobooks", "lost.dir"
    )

    private val discTag = Regex("[\\(\\[]\\s*(disc|disk|cd|side)\\s*[0-9a-z]+[^\\)\\]]*[\\)\\]]", RegexOption.IGNORE_CASE)

    /**
     * A multi-disc game with an .m3u playlist shows up once, as the playlist: the discs it lists are dropped
     * (resolved next to the playlist, so discs in a subfolder count too), and so are "Game (Disc 2)" style files
     * beside it with the same title, in case the playlist names them differently.
     */
    private fun hideM3uDiscs(games: List<Game>): List<Game> {
        val playlists = games.filter { it.path.endsWith(".m3u", ignoreCase = true) }
        if (playlists.isEmpty()) return games
        val listed = HashSet<String>()
        val byTitle = HashSet<Pair<String, String>>() // (folder, title) of each playlist
        for (g in playlists) {
            val m3u = File(g.path)
            val dir = m3u.parentFile ?: continue
            try {
                m3u.readLines().map { it.trim().trim('"').replace('\\', '/') }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { entry ->
                        val f = File(entry).let { if (it.isAbsolute) it else File(dir, entry) }
                        listed += normPath(f)
                    }
            } catch (e: Exception) { }
            byTitle += dir.absolutePath to discTag.replace(m3u.nameWithoutExtension, "").trim().lowercase()
        }
        return games.filter { g ->
            if (g.path.endsWith(".m3u", ignoreCase = true)) return@filter true
            val f = File(g.path)
            if (normPath(f) in listed) return@filter false
            val isDisc = discTag.containsMatchIn(f.nameWithoutExtension)
            !(isDisc && ((f.parentFile?.absolutePath ?: "") to discTag.replace(f.nameWithoutExtension, "").trim().lowercase()) in byTitle)
        }
    }

    private fun normPath(f: File): String = try { f.canonicalPath } catch (e: Exception) { f.absolutePath }.lowercase()

    private val switchTitleId = Regex("\\[([0-9A-Fa-f]{16})\\]")

    /**
     * True for Switch update / DLC files. Uses the 16-digit title ID in the file name when present
     * (base games end in 000 with the 13 low bits clear; updates end in 800; DLC sets bit 12),
     * plus common [UPD] / [DLC] tags.
     */
    fun isSwitchAddon(fileName: String): Boolean {
        val n = fileName.lowercase()
        if ("[upd" in n || "[update" in n || "(update)" in n || "[dlc" in n || "(dlc)" in n) return true
        val tid = switchTitleId.find(fileName)?.groupValues?.get(1) ?: return false
        val low = tid.takeLast(4).toIntOrNull(16) ?: return false
        return (low and 0x1FFF) != 0
    }

    fun cleanTitle(raw: String): String =
        raw.replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "").replace('_', ' ').trim().ifEmpty { raw }

    /** Converts a SAF tree uri (from the folder picker) to a real file path. */
    fun treeToPath(uri: Uri): String? {
        val docId = try { DocumentsContract.getTreeDocumentId(uri) } catch (e: Exception) { return null }
        val parts = docId.split(":", limit = 2)
        val vol = parts[0]
        val rel = parts.getOrNull(1).orEmpty()
        val base = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}

object AppsLoader {
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, AppEntry>()
        for (cat in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(i, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || out.containsKey(pkg)) continue
                val ai = ri.activityInfo.applicationInfo
                @Suppress("DEPRECATION")
                val flaggedGame = (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                out[pkg] = AppEntry(
                    pkg,
                    ri.loadLabel(pm).toString(),
                    ai.category,
                    ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap(),
                    ai.category == ApplicationInfo.CATEGORY_GAME || flaggedGame,
                    ri.activityInfo.name
                )
            }
        }
        return out.values.sortedBy { it.label.lowercase() }
    }

    /**
     * Starts an app. Tries, in order: its launcher activity directly, the system's launch intent,
     * the TV launch intent, then the LauncherApps service. Returns null on success, or the reason it failed.
     * [opts] lets the caller pick a display (bottom-screen dock).
     */
    /** Which screen each app was last opened on (while Eclipse is running). */
    private val lastDisplay = HashMap<String, Int>()

    fun launch(ctx: Context, pkg: String, activity: String? = null, opts: Bundle? = null, displayId: Int = com.eclipse.launcher.media.Screens.mainId): String? {
        com.eclipse.launcher.media.SecondScreenHost.lastLaunchMs = System.currentTimeMillis()
        // Android brings an app that's already running back up on the screen it's on, whichever screen was asked for.
        // If it was last opened on the other screen, start it in a new window on this one.
        // On the bottom screen an app with no known screen is treated as moved too: it may already be running on the
        // top one (opened from elsewhere, or before Eclipse restarted), and Android would just bring that window back.
        val moved = lastDisplay[pkg].let { if (displayId != com.eclipse.launcher.media.Screens.mainId) it != displayId else it != null && it != displayId }
        lastDisplay[pkg] = displayId
        @Suppress("NAME_SHADOWING")
        val opts = opts ?: android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val pm = ctx.packageManager
        val attempts = ArrayList<Intent>()
        if (!activity.isNullOrEmpty()) {
            attempts.add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, activity))
        }
        pm.getLaunchIntentForPackage(pkg)?.let { attempts.add(it) }
        pm.getLeanbackLaunchIntentForPackage(pkg)?.let { attempts.add(it) }
        var last: Exception? = null
        // started through Eclipse's helper when it's on: Android can quietly block Eclipse's own launches while a
        // game or app covers the top screen (Eclipse then counts as "in the background")
        val starter = com.eclipse.launcher.media.ScreenshotService.starter() ?: ctx
        for (i in attempts) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            // open as a separate window on this screen (clearing the old task would just restart it on the other screen)
            if (moved) i.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            try {
                try { starter.startActivity(i, opts) } catch (e: Exception) { if (starter === ctx) throw e; ctx.startActivity(i, opts) }
                return null
            } catch (e: Exception) {
                last = e
            }
        }
        try {
            val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val info = la.getActivityList(pkg, Process.myUserHandle()).firstOrNull()
            if (info != null) {
                la.startMainActivity(info.componentName, Process.myUserHandle(), null, opts)
                return null
            }
        } catch (e: Exception) {
            last = e
        }
        return last?.message ?: "it has no screen to open"
    }
}

object Launch {
    private const val RETROARCH_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"

    /**
     * Starts [game] with the emulator [choice] (from the catalog). Returns null on success, or why it failed.
     * Handles RetroArch cores and every standalone emulator's own intent format (action, data, extras, flags).
     */
    /**
     * Emulators that only open games through Android's Storage Access Framework (a "content://…externalstorage…"
     * link they can check), not a file path or a plain shared file. Flycast is one: it rejects anything else with
     * "Storage access failed: Invalid URI".
     */
    private val safOnly = setOf("com.flycast.emulator")

    /** The ROM folder as picked in Settings (a SAF folder link Eclipse keeps permission for), and its file path. */
    @Volatile var romTree: String? = null
    @Volatile var romTreePath: String? = null

    /** A SAF link to [path] inside the picked ROM folder, granted to [pkg]; null when the folder link isn't known. */
    private fun safUri(ctx: Context, pkg: String, path: String): Uri? {
        val tree = romTree?.let { Uri.parse(it) } ?: return null
        val base = romTreePath?.trimEnd('/') ?: return null
        if (path != base && !path.startsWith("$base/")) return null
        return try {
            val treeDoc = DocumentsContract.getTreeDocumentId(tree)
            val rel = path.removePrefix(base).trimStart('/')
            val docId = when {
                rel.isEmpty() -> treeDoc
                treeDoc.endsWith(":") -> treeDoc + rel
                else -> "$treeDoc/$rel"
            }
            DocumentsContract.buildDocumentUriUsingTree(tree, docId).also {
                ctx.grantUriPermission(pkg, it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Starts [game] with [choice]; on [displayId] (the bottom screen) when given. */
    fun launch(ctx: Context, game: Game, choice: EmuChoice, displayId: Int? = null): String? {
        com.eclipse.launcher.media.SecondScreenHost.lastLaunchMs = System.currentTimeMillis()
        val spec = choice.spec
        val pkg = choice.pkg
        var path = game.path
        if (spec.m3uFirstDisc && path.endsWith(".m3u", ignoreCase = true)) firstDisc(path)?.let { path = it }
        val file = File(path)

        val intent = Intent()
        if (spec.core != null) {
            val dataDir = try { ctx.packageManager.getApplicationInfo(pkg, 0).dataDir } catch (e: Exception) { "/data/data/$pkg" }
            intent.setClassName(pkg, RETROARCH_ACTIVITY)
            intent.putExtra("ROM", path)
            intent.putExtra("LIBRETRO", choice.corePath ?: "$dataDir/cores/${spec.core}_libretro_android.so")
        } else {
            var contentUri: Uri? = null
            fun fill(v: String): String {
                var r = v.replace("{path}", path).replace("{fileuri}", Uri.fromFile(file).toString())
                if (r.contains("{content}")) r = r.replace("{content}", shortcutId(file))
                if (r.contains("{uri}")) {
                    val u = contentUri ?: (if (pkg in safOnly) safUri(ctx, pkg, path) else null)?.also { contentUri = it }
                        ?: FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file).also {
                            contentUri = it
                            ctx.grantUriPermission(pkg, it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                    r = r.replace("{uri}", u.toString())
                }
                return r
            }
            if (pkg in safOnly && spec.data?.contains("{uri}") == true && safUri(ctx, pkg, path) == null) {
                return "${spec.name} needs folder access. Choose your ROM folder again in Settings → Library (once), then try again."
            }
            intent.setClassName(pkg, spec.activity ?: return "no activity known for ${spec.name}")
            spec.action?.let { intent.action = it }
            spec.categories.forEach { intent.addCategory(it) }
            val data = spec.data?.let { Uri.parse(fill(it)) }
            when {
                data != null && spec.type != null -> intent.setDataAndType(data, spec.type)
                data != null -> intent.data = data
                spec.type != null -> intent.type = spec.type
            }
            for (x in spec.extras) {
                val v = fill(x.value)
                when (x.kind) {
                    'z' -> intent.putExtra(x.key, v == "1" || v.equals("true", ignoreCase = true))
                    'i' -> intent.putExtra(x.key, v.toIntOrNull() ?: 0)
                    'l' -> intent.putExtra(x.key, v.toLongOrNull() ?: 0L)
                    'a' -> intent.putExtra(x.key, v.split(",").toTypedArray())
                    else -> intent.putExtra(x.key, v)
                }
            }
            for (f in spec.flags) {
                when (f) {
                    "clear_task" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    "clear_top" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    "no_history" -> intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                }
            }
            if (contentUri != null) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            // some emulators only accept file:// links, which Android blocks by default
            if (data?.scheme == "file") StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            if (displayId != null && displayId != com.eclipse.launcher.media.Screens.mainId) {
                intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                ctx.startActivity(intent, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
                // the bottom screen's overlay steps aside for the game
                com.eclipse.launcher.media.SecondScreenHost.hideForApp?.invoke(pkg, displayId)
            } else if (com.eclipse.launcher.media.Screens.mainId != android.view.Display.DEFAULT_DISPLAY) {
                // screens swapped: the main screen is the other display, so say so (Android would use its default one)
                ctx.startActivity(intent, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(com.eclipse.launcher.media.Screens.mainId).toBundle())
            } else ctx.startActivity(intent)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * The game id inside a PC-game shortcut file (.steam, .localgameid, .gog, .epic): its first line that isn't
     * blank or a # comment, with a Daijishou-style "[steamappid] 45730" tag stripped. An empty file falls back to the
     * file name (so "504230.steam" works too).
     */
    fun shortcutId(file: File): String {
        val line = try {
            file.readLines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        } catch (e: Exception) { null }
        val id = line?.let { if (it.startsWith("[") && it.contains("]")) it.substringAfter("]").trim() else it }
        return id?.takeIf { it.isNotEmpty() } ?: file.nameWithoutExtension.trim()
    }

    /** First disc listed in an .m3u playlist, resolved next to the playlist. */
    private fun firstDisc(m3u: String): String? = try {
        val f = File(m3u)
        f.readLines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { entry ->
            val disc = File(entry)
            if (disc.isAbsolute) disc.path else File(f.parentFile, entry).path
        }
    } catch (e: Exception) {
        null
    }
}
