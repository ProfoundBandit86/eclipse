package com.eclipse.launcher.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Eclipse themes: a .zip anyone can make by hand.
 *
 *   theme.json            name, author, colours, wallpaper settings (all optional except name)
 *   wallpaper.jpg         home screen wallpaper (top screen)
 *   bottom.jpg            bottom screen wallpaper
 *   platforms/snes.png    platform card images, named after the system (snes, gba, psx, switch, …)
 *   dock/home.png         dock icons: home, games, romm, apps, settings
 *
 * Imported themes are unpacked into the app's own storage, so the original zip can be deleted.
 */
object Themes {
    data class Theme(
        val id: String,
        val name: String,
        val author: String,
        val dir: File,
        val accent: Int?,
        val focus: Int?,
        val wallpaper: File?,
        val bottom: File?,
        /** true: the theme wallpaper is always shown; false: only where a game has no backdrop of its own. */
        val alwaysWallpaper: Boolean,
        val platforms: Map<String, File>,
        val dock: Map<String, File>,
        /** the theme's own font (.ttf / .otf), if it has one */
        val font: File? = null,
        /** a background per system (backgrounds/snes.jpg …), shown behind the platforms while that system is highlighted */
        val backgrounds: Map<String, File> = emptyMap(),
        /** a logo per system (logos/snes.png …), shown where Eclipse writes a system's name */
        val logos: Map<String, File> = emptyMap(),
        /** comes with Eclipse (can't be deleted; it's put back on the next start anyway) */
        val builtIn: Boolean = false,
        /** a line or two about the theme ("description" in theme.json), shown in the theme picker */
        val description: String = "",
        /** writing, softer labels, and the glass panels' tint ("text", "labels", "glass" as #RRGGBB) */
        val text: Int? = null,
        val labels: Int? = null,
        val glass: Int? = null,
        /** the colour of the screens' shading over wallpapers and game art ("background" or "overlay", #RRGGBB) */
        val background: Int? = null,
        /** how solid the glass is, 0..100 ("glassStrength"), and how much the wallpaper is dimmed, 0..100 ("dim") */
        val glassStrength: Int? = null,
        val dim: Int? = null,
        /** "light" or "dark": the panels' mode this theme is made for ("mode"); switched to when you pick the theme */
        val mode: String = ""
    )

    private val imageExts = setOf("png", "jpg", "jpeg", "webp", "gif")
    private val fontExts = setOf("ttf", "otf")
    val dockNames = listOf("home", "games", "romm", "apps", "settings")
    private const val MAX_BYTES = 800L * 1024 * 1024 // other frontends' themes can be big (videos are skipped)

    /** What the last import did, e.g. "Converted from ES-DE: 111 platform cards, 110 system backgrounds". */
    @Volatile var lastNote: String? = null

    /** Eclipse/Themes on your storage (see [EclipseHome]), or Eclipse's own storage without "All files access". */
    fun root(ctx: Context) = EclipseHome.themes(ctx)

    /**
     * Themes that ship with Eclipse (assets/themes/<id>/, marked by a builtin.json with a version): copied into the
     * themes folder the first time, and again whenever a new Eclipse version updates them.
     */
    fun installBuiltIns(ctx: Context) {
        val names = try { ctx.assets.list("themes") } catch (e: Exception) { null } ?: emptyArray()
        // a theme that used to ship with Eclipse and no longer does: remove the installed copy
        root(ctx).listFiles()?.forEach { dir ->
            if (File(dir, "builtin.json").isFile && dir.name !in names) dir.deleteRecursively()
        }
        for (n in names) {
            val ver = try { ctx.assets.open("themes/$n/builtin.json").bufferedReader().use { it.readText() } } catch (e: Exception) { continue }
            val dest = File(root(ctx), n)
            val marker = File(dest, "builtin.json")
            if (marker.isFile && runCatching { marker.readText() }.getOrNull() == ver) continue
            dest.deleteRecursively()
            try { copyAsset(ctx, "themes/$n", dest) } catch (e: Exception) { dest.deleteRecursively() }
        }
    }

    private fun copyAsset(ctx: Context, path: String, dest: File) {
        val kids = ctx.assets.list(path) ?: emptyArray()
        if (kids.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(path).use { i -> dest.outputStream().use { i.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (k in kids) copyAsset(ctx, "$path/$k", File(dest, k))
        }
    }

    fun list(ctx: Context): List<Theme> =
        root(ctx).listFiles()?.filter { it.isDirectory }?.mapNotNull { load(it) }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun byId(ctx: Context, id: String): Theme? = File(root(ctx), id).takeIf { it.isDirectory }?.let { load(it) }

    private fun color(s: String?): Int? =
        s?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }

    /** An image in [dir] named [stem] (any image extension), or the exact file named in theme.json. */
    private fun image(dir: File, named: String?, vararg stems: String): File? {
        named?.takeIf { it.isNotBlank() }?.let { n -> File(dir, n).takeIf { it.isFile && it.canonicalPath.startsWith(dir.canonicalPath) }?.let { return it } }
        for (stem in stems) for (ext in imageExts) File(dir, "$stem.$ext").takeIf { it.isFile }?.let { return it }
        return null
    }

    fun load(dir: File): Theme? {
        val json = File(dir, "theme.json")
        val o = try { if (json.isFile) JSONObject(json.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
        val platforms = HashMap<String, File>()
        // icons/ and backdrops/ (older themes: platforms/ and backgrounds/)
        (File(dir, "icons").takeIf { it.isDirectory } ?: File(dir, "platforms")).listFiles()?.filter { it.isFile && it.extension.lowercase() in imageExts }?.forEach { f ->
            // file named after a system id, or any name Eclipse recognises for that system (pictures of systems it
            // doesn't have yet are kept in the folder and picked up once it does)
            ThemeConvert.system(f.nameWithoutExtension)?.let { platforms.putIfAbsent(it, f) }
        }
        val backgrounds = HashMap<String, File>()
        (File(dir, "backdrops").takeIf { it.isDirectory } ?: File(dir, "backgrounds")).listFiles()?.filter { it.isFile && it.extension.lowercase() in imageExts }?.forEach { f ->
            ThemeConvert.system(f.nameWithoutExtension)?.let { backgrounds.putIfAbsent(it, f) }
        }
        val logos = HashMap<String, File>()
        File(dir, "logos").listFiles()?.filter { it.isFile && it.extension.lowercase() in imageExts }?.forEach { f ->
            ThemeConvert.system(f.nameWithoutExtension)?.let { logos.putIfAbsent(it, f) }
        }
        val dock = HashMap<String, File>()
        val dockDir = File(dir, "dock")
        for (n in dockNames) image(dockDir, null, n)?.let { dock[n] = it }
        val wallpaper = image(dir, o.optString("wallpaper"), "wallpaper", "background")
        val bottom = image(dir, o.optString("bottomWallpaper"), "bottom", "wallpaper_bottom")
        if (!json.isFile && wallpaper == null && bottom == null && platforms.isEmpty() && dock.isEmpty() && backgrounds.isEmpty()) return null
        return Theme(
            id = dir.name,
            name = o.optString("name").ifBlank { dir.name },
            author = o.optString("author"),
            description = o.optString("description"),
            dir = dir,
            accent = color(o.optString("accent")),
            focus = color(o.optString("focus")),
            wallpaper = wallpaper,
            bottom = bottom,
            alwaysWallpaper = o.optString("wallpaperMode").equals("always", ignoreCase = true),
            platforms = platforms,
            dock = dock,
            font = o.optString("font").takeIf { it.isNotBlank() }?.let { File(dir, it) }
                ?.takeIf { it.isFile && it.extension.lowercase() in fontExts && it.canonicalPath.startsWith(dir.canonicalPath) },
            backgrounds = backgrounds,
            logos = logos,
            builtIn = File(dir, "builtin.json").isFile,
            text = color(o.optString("text")),
            labels = color(o.optString("labels")),
            glass = color(o.optString("glass")),
            background = color(o.optString("background").ifBlank { o.optString("overlay") }),
            glassStrength = o.optInt("glassStrength", -1).takeIf { it in 0..100 },
            dim = o.optInt("dim", -1).takeIf { it in 0..100 },
            mode = o.optString("mode").lowercase().takeIf { it == "light" || it == "dark" } ?: ""
        )
    }

    /** A theme from another frontend, unpacked and waiting for you to say which folder is which (see [importFinish]). */
    class Pending(val tmp: File, val base: File, val name: String, val folders: List<ThemeConvert.FolderChoice>)

    /** What [importStart] did: the theme is in, it failed, or there are folders for you to sort out first. */
    sealed class Step {
        class Done(val theme: Theme) : Step()
        class Failed(val message: String) : Step()
        class Choose(val pending: Pending) : Step()
    }

    /**
     * Unpacks a theme zip (works whether the files sit at the top of the zip or inside one folder; only images, fonts
     * and theme files are taken). Eclipse themes and RetroHrai themes go straight in. Themes for other frontends
     * (ES-DE, Cocoon …) don't say which folder holds what, so their picture folders come back as [Step.Choose] for
     * the import screen, where you pick icons, logos and backdrops.
     */
    fun importStart(ctx: Context, uri: Uri): Step {
        val tmp = File(root(ctx), ".import_${System.currentTimeMillis()}")
        tmp.mkdirs()
        var keep = false
        try {
            var total = 0L
            val input = ctx.contentResolver.openInputStream(uri) ?: return Step.Failed("Couldn't open that file")
            ZipInputStream(input.buffered()).use { zin ->
                while (true) {
                    val e: ZipEntry = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/')
                    if (name.startsWith("__MACOSX/") || name.substringAfterLast('/').startsWith(".")) continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val keepIt = ext in imageExts || ext in fontExts || ext == "svg" || name.endsWith("theme.json", ignoreCase = true) ||
                        name.endsWith("theme.xml", ignoreCase = true) || name.endsWith("capabilities.xml", ignoreCase = true) // ES-DE markers
                    if (!keepIt) continue
                    val out = File(tmp, name)
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IllegalStateException("too big")
                            os.write(buf, 0, n)
                        }
                    }
                }
            }
            // everything inside a single top folder? use that folder as the theme
            val kids = tmp.listFiles()?.toList() ?: emptyList()
            val base = if (kids.size == 1 && kids[0].isDirectory && !File(tmp, "theme.json").exists()) kids[0] else tmp
            lastNote = null
            if (ThemeConvert.isNative(base)) return install(ctx, base)
            val name = displayName(ctx, uri)
            if (!ThemeConvert.isRetroHraiTheme(base)) {
                val folders = ThemeConvert.scanFolders(base)
                if (folders.isNotEmpty()) { keep = true; return Step.Choose(Pending(tmp, base, name, folders)) }
            }
            // RetroHrai, or nothing to choose between (just a wallpaper, say): convert as before
            val conv = File(tmp, ".eclipse")
            val r = ThemeConvert.convert(base, conv, name)
                ?: return Step.Failed("Couldn't find platform pictures, system backgrounds or a wallpaper in that theme")
            lastNote = r.note
            return install(ctx, conv)
        } catch (e: IllegalStateException) {
            return Step.Failed("That theme is too big (over 800 MB of pictures and fonts)")
        } catch (e: Exception) {
            return Step.Failed("Couldn't read that theme: ${e.message ?: "not a zip file"}")
        } finally {
            if (!keep) tmp.deleteRecursively()
        }
    }

    /** Finishes a [Pending] import with the roles you picked ([roles]: folder -> ThemeConvert.ICONS / LOGOS / BACKDROPS / SKIP). */
    fun importFinish(ctx: Context, p: Pending, roles: Map<String, String>): Step = try {
        val conv = File(p.tmp, ".eclipse")
        val r = ThemeConvert.convertWith(p.base, conv, p.name, p.folders, roles)
        if (r == null) Step.Failed("Nothing to import: every folder is set to Skip, and there's no wallpaper")
        else { lastNote = r.note; install(ctx, conv) }
    } catch (e: Exception) {
        Step.Failed("Couldn't import that theme: ${e.message ?: e.javaClass.simpleName}")
    } finally {
        p.tmp.deleteRecursively()
    }

    fun importCancel(p: Pending) { p.tmp.deleteRecursively() }

    /** Moves a ready theme folder into place (replacing an older copy of the same theme). */
    private fun install(ctx: Context, ready: File): Step {
        val probe = load(ready) ?: return Step.Failed("That zip doesn't look like a theme Eclipse can use")
        val id = probe.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "theme" }
        val dest = File(root(ctx), id)
        dest.deleteRecursively() // importing again replaces the old copy
        if (!ready.renameTo(dest)) { ready.copyRecursively(dest, overwrite = true) }
        return load(dest)?.let { Step.Done(it) } ?: Step.Failed("That zip doesn't look like a theme Eclipse can use")
    }

    fun delete(t: Theme) { t.dir.deleteRecursively() }

    /** The picked file's name without ".zip", used as a theme name when the theme doesn't give one. */
    fun displayName(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.substringBeforeLast('.')?.replace(Regex("[-_]+"), " ")?.trim()?.ifEmpty { null } ?: "Imported theme"
    } catch (e: Exception) {
        "Imported theme"
    }

    /**
     * What Export theme puts in the zip. [colours] are theme.json's colour and number fields as they are now
     * ("accent" -> "#RRGGBB", "glassStrength" -> 70 …). Pictures are by system id. Wallpapers and the font are
     * anything Eclipse can open: a file, a file:// or content:// address, or one of its own (file:///android_asset/…).
     */
    class Export(
        val name: String,
        val author: String,
        val colours: Map<String, Any>,
        val wallpaper: String?,
        val bottom: String?,
        val dock: Map<String, File>,
        val icons: Map<String, String>?,
        val backdrops: Map<String, String>?,
        val logos: Map<String, String>?,
        val font: File?
    )

    /** What [export] wrote, and how much of it. */
    class Exported(val file: File, val icons: Int, val backdrops: Int, val logos: Int, val font: Boolean)

    /** A file name made safe for any storage ("My theme: v2" -> "My theme v2"). */
    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]+"), " ").replace(Regex("\\s+"), " ").trim().take(60)

    /** Opens a picture or font Eclipse is using, wherever it lives. */
    private fun open(ctx: Context, src: String): java.io.InputStream? = try {
        when {
            src.startsWith("file:///android_asset/") -> ctx.assets.open(src.removePrefix("file:///android_asset/"))
            src.startsWith("file://") -> Uri.parse(src).path?.let { File(it) }?.takeIf { it.isFile }?.inputStream()
            src.startsWith("content://") -> ctx.contentResolver.openInputStream(Uri.parse(src))
            src.startsWith("/") -> File(src).takeIf { it.isFile }?.inputStream()
            else -> null // a web address: not ours to pack
        }
    } catch (e: Exception) { null }

    /** The extension a picture should keep in the zip (".png" …), from its name; ".jpg" when it can't tell. */
    private fun extOf(src: String): String {
        val e = src.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return if (e in imageExts || e == "svg") e else "jpg"
    }

    /**
     * Export theme: today's look as an Eclipse theme zip in Eclipse/export, ready to import on another device or to
     * share. Holds theme.json (name, author, colours, wallpaper settings), the wallpapers and dock icons, and, if you
     * chose them, the icons, backdrops, logos and font in use.
     */
    fun export(ctx: Context, x: Export): Exported {
        val dir = EclipseHome.export()
        val base = safeName(x.name).ifEmpty { "My theme" }
        var f = File(dir, "$base.zip")
        var n = 2
        while (f.exists()) f = File(dir, "$base ($n).zip").also { n++ }
        var icons = 0; var backdrops = 0; var logos = 0; var font = false
        val names = HashSet<String>()
        try {
            ZipOutputStream(f.outputStream().buffered()).use { z ->
                fun put(name: String, input: java.io.InputStream?): Boolean {
                    input ?: return false
                    if (!names.add(name)) { input.close(); return false }
                    input.use { z.putNextEntry(ZipEntry(name)); it.copyTo(z); z.closeEntry() }
                    return true
                }
                val json = JSONObject().put("name", x.name.trim().ifEmpty { "My theme" })
                if (x.author.isNotBlank()) json.put("author", x.author.trim())
                for ((k, v) in x.colours) json.put(k, v)
                x.wallpaper?.let { w -> val e = extOf(w); if (put("wallpaper.$e", open(ctx, w))) json.put("wallpaper", "wallpaper.$e") }
                x.bottom?.let { w -> val e = extOf(w); if (put("bottom.$e", open(ctx, w))) json.put("bottomWallpaper", "bottom.$e") }
                for ((k, v) in x.dock) put("dock/$k.${v.extension.lowercase()}", v.inputStream())
                x.icons?.forEach { (id, src) -> if (put("icons/$id.${extOf(src)}", open(ctx, src))) icons++ }
                x.backdrops?.forEach { (id, src) -> if (put("backdrops/$id.${extOf(src)}", open(ctx, src))) backdrops++ }
                x.logos?.forEach { (id, src) -> if (put("logos/$id.${extOf(src)}", open(ctx, src))) logos++ }
                x.font?.takeIf { it.isFile }?.let { ff ->
                    val e = ff.extension.lowercase().takeIf { it in fontExts } ?: "ttf"
                    if (put("font.$e", ff.inputStream())) { json.put("font", "font.$e"); font = true }
                }
                z.putNextEntry(ZipEntry("theme.json")); z.write(json.toString(2).toByteArray()); z.closeEntry()
            }
        } catch (e: Exception) {
            f.delete()
            throw e
        }
        return Exported(f, icons, backdrops, logos, font)
    }
}
