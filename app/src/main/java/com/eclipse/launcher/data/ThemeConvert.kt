package com.eclipse.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.json.JSONObject
import java.io.File

/**
 * Turns themes made for other frontends into Eclipse themes, at import time.
 *
 *  - RetroHrai: reads its theme.json (icons / logos / backgrounds per platform, global and secondary wallpapers).
 *  - ES-DE, Cocoon and anything else: there's no single layout, so it looks for folders full of images named after
 *    systems (snes.png, psx.webp …) and works out what each folder is from the pictures' shape and the folder's name:
 *    square ones become platform cards, wide photos become system backgrounds, wide see-through ones are logos.
 *    When a theme only has logos, cards are drawn from the logo over that system's background.
 *
 * The result is written in Eclipse's own layout (theme.json, platforms/, backgrounds/, wallpaper, font) inside [out].
 */
object ThemeConvert {
    data class Result(val kind: String, val cards: Int, val backgrounds: Int, val note: String)

    private val imageExts = setOf("png", "jpg", "jpeg", "webp", "gif")
    private val fontExts = setOf("ttf", "otf")

    /** True when [dir] already is a Eclipse theme (its theme.json isn't another frontend's). */
    fun isNative(dir: File): Boolean {
        val j = File(dir, "theme.json")
        if (!j.isFile) return File(dir, "icons").isDirectory || File(dir, "platforms").isDirectory || File(dir, "dock").isDirectory
        val o = runCatching { JSONObject(j.readText()) }.getOrNull() ?: return false
        return !isRetroHrai(o)
    }

    /** A RetroHrai theme (its theme.json says where everything is, so there's nothing to ask). */
    fun isRetroHraiTheme(dir: File): Boolean =
        runCatching { JSONObject(File(dir, "theme.json").readText()) }.getOrNull()?.let { isRetroHrai(it) } == true

    private fun isRetroHrai(o: JSONObject) = o.has("schemaVersion") && (o.has("icons") || o.has("logos") || o.has("backgrounds") || o.has("contains"))

    /** Converts the theme in [src] into Eclipse's layout in [out]. Null when nothing usable was found. */
    fun convert(src: File, out: File, fallbackName: String): Result? {
        out.mkdirs()
        rasteriseSvgs(src)
        val json = runCatching { JSONObject(File(src, "theme.json").readText()) }.getOrNull()
        return if (json != null && isRetroHrai(json)) retroHrai(src, out, json, fallbackName) else byFolders(src, out, fallbackName)
    }

    // ---------------- RetroHrai ----------------

    private fun retroHrai(src: File, out: File, o: JSONObject, fallbackName: String): Result? {
        fun map(section: String): Map<String, File> {
            val m = HashMap<String, File>()
            val p = o.optJSONObject(section)?.optJSONObject("platforms") ?: return m
            for (k in p.keys()) {
                val v = p.opt(k)
                val rel = when (v) {
                    is String -> v
                    is JSONObject -> v.optString("image")
                    else -> ""
                }
                val f = safe(src, rel) ?: continue
                val id = system(k) ?: continue
                m.putIfAbsent(id, f)
            }
            return m
        }
        val icons = map("icons")
        val logos = map("logos")
        val bgs = map("backgrounds")
        val global = safe(src, o.optJSONObject("backgrounds")?.optString("global") ?: "")
        val secondary = safe(src, o.optJSONObject("wallpaper")?.optString("secondary") ?: "")
            ?: safe(src, o.optJSONObject("wallpaper")?.optString("primary") ?: "")
        val name = o.optString("name").ifBlank { fallbackName }
        return write(
            out, "RetroHrai", name, o.optString("author"),
            cards = icons, logos = logos, backgrounds = bgs,
            wallpaper = global ?: secondary, bottom = secondary,
            font = findFont(src)
        )
    }

    // ---------------- ES-DE / Cocoon / anything: by folder ----------------

    private class Folder(val dir: File, val files: Map<String, File>) {
        var aspect = 1f
        var alpha = false
        var width = 0
    }

    /** A folder of pictures named after systems, as offered on the import screen, with Eclipse's guess at its role. */
    /**
     * [files]: pictures of systems Eclipse has (by its id). [others]: the folder's other pictures, for systems Eclipse
     * doesn't have yet (by [otherSystem] name): imported too, unused until such a system exists.
     */
    data class FolderChoice(val rel: String, val dir: File, val files: Map<String, File>, val guess: String, val others: Map<String, File> = emptyMap())

    /** The other pictures in [dir] (see [FolderChoice.others]): every picture not already among [known]. */
    fun othersIn(dir: File, known: Map<String, File>): Map<String, File> {
        val used = known.values.toHashSet()
        val m = LinkedHashMap<String, File>()
        dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in imageExts && it !in used && system(it.nameWithoutExtension) == null }
            ?.sortedBy { it.name.lowercase() }?.forEach { f ->
                otherSystem(f.nameWithoutExtension)?.let { k -> if (k !in known) m.putIfAbsent(k, f) }
            }
        return m
    }

    /** The roles a folder can play: platform icons, logos (drawn onto icons), system backdrops, or left out. */
    const val ICONS = "icons"; const val LOGOS = "logos"; const val BACKDROPS = "backdrops"; const val SKIP = "skip"

    /** Every folder in [src] with at least 5 pictures named after systems, and what each one looks like it is. */
    fun scanFolders(src: File): List<FolderChoice> {
        rasteriseSvgs(src)
        val folders = src.walkTopDown().filter { it.isDirectory }.mapNotNull { d ->
            val m = HashMap<String, File>()
            d.listFiles()?.filter { it.isFile && it.extension.lowercase() in imageExts }?.sortedBy { it.name }?.forEach { f ->
                system(f.nameWithoutExtension)?.let { id -> m.putIfAbsent(id, f) }
            }
            if (m.size >= 5) Folder(d, m) else null
        }.toList()
        for (f in folders) measure(f)
        fun words(f: Folder) = f.dir.relativeTo(src).path.lowercase()
        val skip = listOf("controller", "overlay", "console", "screenshot", "video", "marquee", "bezel", "cart", "mix")
        fun skipped(f: Folder) = skip.any { it in words(f) }
        // the old automatic pick, now only a suggestion: one folder per role
        val cardDir = folders.filter { it.aspect in 0.75f..1.33f && !skipped(it) && "logo" !in words(it) }
            .maxByOrNull { it.files.size + bonus(words(it), "icon", "card", "artwork", "system", "tile", "platform") }
        val bgDir = folders.filter { it != cardDir && it.aspect > 1.4f && !it.alpha && it.width >= 480 && !skipped(it) && "logo" !in words(it) }
            .maxByOrNull { it.files.size + bonus(words(it), "background", "fanart", "wallpaper", "bg", "art") }
        val logoDir = folders.filter { it != cardDir && it != bgDir && it.aspect > 1.3f && (it.alpha || "logo" in words(it)) && !skipped(it) }
            .maxByOrNull { it.files.size + bonus(words(it), "logo", "wheel") }
        return folders.map { f ->
            val guess = when (f) { cardDir -> ICONS; bgDir -> BACKDROPS; logoDir -> LOGOS; else -> SKIP }
            FolderChoice(f.dir.relativeTo(src).path.ifEmpty { "(top of the zip)" }, f.dir, f.files, guess, othersIn(f.dir, f.files))
        }.sortedWith(compareBy({ it.guess == SKIP }, { it.rel.lowercase() }))
    }

    /** Converts with the roles you chose on the import screen ([roles]: folder rel path -> role). */
    fun convertWith(src: File, out: File, fallbackName: String, folders: List<FolderChoice>, roles: Map<String, String>): Result? {
        out.mkdirs()
        val kind = if (src.walkTopDown().maxDepth(2).any { it.name.equals("theme.xml", true) || it.name.equals("capabilities.xml", true) }) "ES-DE" else "a frontend theme"
        fun merged(role: String): Map<String, File> {
            val m = LinkedHashMap<String, File>()
            val picked = folders.filter { (roles[it.rel] ?: it.guess) == role }
            picked.forEach { f -> f.files.forEach { (id, file) -> m.putIfAbsent(id, file) } }
            // and the pictures of systems Eclipse doesn't have yet, kept under their own names
            picked.forEach { f -> f.others.forEach { (k, file) -> m.putIfAbsent(k, file) } }
            return m
        }
        val bgFolders = folders.filter { (roles[it.rel] ?: it.guess) == BACKDROPS }
        val wallpaper = bgFolders.firstNotNullOfOrNull { f -> f.dir.listFiles()?.firstOrNull { it.nameWithoutExtension.trimStart('_').lowercase() in setOf("default", "global", "all") } }
            ?: src.walkTopDown().maxDepth(3).firstOrNull {
                it.isFile && it.extension.lowercase() in imageExts &&
                    it.nameWithoutExtension.lowercase().let { n -> n == "wallpaper" || n == "background" || n == "bg" } && width(it) >= 1000
            }
        return write(
            out, kind, esdeName(src) ?: fallbackName, "",
            cards = merged(ICONS), logos = merged(LOGOS), backgrounds = merged(BACKDROPS),
            wallpaper = wallpaper, bottom = null, font = findFont(src)
        )
    }

    private fun byFolders(src: File, out: File, fallbackName: String): Result? {
        val folders = scanFolders(src)
        if (folders.isEmpty()) return null
        return convertWith(src, out, fallbackName, folders, emptyMap())
    }

    private fun bonus(w: String, vararg keys: String) = if (keys.any { it in w }) 1000 else 0

    private fun measure(f: Folder) {
        val sample = f.files.values.take(6)
        val dims = sample.mapNotNull { bounds(it) }
        if (dims.isEmpty()) return
        f.aspect = dims.map { it.first.toFloat() / it.second.coerceAtLeast(1) }.sorted()[dims.size / 2]
        f.width = dims.map { it.first }.sorted()[dims.size / 2]
        f.alpha = sample.take(3).any { hasAlpha(it) }
    }

    private fun bounds(f: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    private fun width(f: File) = bounds(f)?.first ?: 0

    /** Does the picture have see-through parts? (checked on a small copy) */
    private fun hasAlpha(f: File): Boolean {
        if (f.extension.lowercase() in setOf("jpg", "jpeg")) return false
        val b = decode(f, 64) ?: return false
        try {
            if (!b.hasAlpha()) return false
            for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) if (Color.alpha(b.getPixel(x, y)) < 200) return true
            return false
        } finally {
            b.recycle()
        }
    }

    private fun decode(f: File, target: Int): Bitmap? {
        val (w, h) = bounds(f) ?: return null
        var sample = 1
        while (w / (sample * 2) >= target && h / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** "name: …" from the comment at the top of an ES-DE theme.xml. */
    private fun esdeName(src: File): String? {
        val xml = src.walkTopDown().maxDepth(2).firstOrNull { it.name.equals("theme.xml", true) } ?: return null
        val head = runCatching { xml.readText().take(2000) }.getOrNull() ?: return null
        return Regex("name:\\s*(.+)").find(head)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it.length < 60 }
    }

    private fun findFont(src: File): File? {
        val fonts = src.walkTopDown().filter { it.isFile && it.extension.lowercase() in fontExts }.toList()
        if (fonts.isEmpty()) return null
        val bad = listOf("bold", "light", "thin", "black", "italic", "icon", "symbol", "pad", "players")
        return fonts.firstOrNull { f -> bad.none { it in f.name.lowercase() } } ?: fonts.first()
    }

    // ---------------- SVG ----------------

    /**
     * Many ES-DE themes draw their logos and icons as SVG. Each one (without a PNG / JPG of the same name next to it)
     * is turned into a PNG up to 1024 px wide, so the rest of the converter can treat it like any other picture.
     */
    fun rasteriseSvgs(src: File) {
        val svgs = src.walkTopDown().filter { it.isFile && it.extension.equals("svg", true) }.take(3000).toList()
        for (f in svgs) {
            val stem = f.nameWithoutExtension
            val dir = f.parentFile ?: continue
            if (listOf("png", "jpg", "jpeg", "webp").any { File(dir, "$stem.$it").exists() }) continue
            svgToPng(f, File(dir, "$stem.png"), 1024)
        }
    }

    private fun svgToPng(svgFile: File, dest: File, maxSide: Int): Boolean {
        return try {
            val svg = svgFile.inputStream().use { com.caverock.androidsvg.SVG.getFromInputStream(it) }
            val vb = svg.documentViewBox
            var w = svg.documentWidth.takeIf { it > 0 } ?: vb?.width() ?: 512f
            var h = svg.documentHeight.takeIf { it > 0 } ?: vb?.height() ?: 512f
            if (vb == null) svg.setDocumentViewBox(0f, 0f, w, h)
            val scale = maxSide / maxOf(w, h)
            w *= scale
            h *= scale
            val bw = w.toInt().coerceIn(1, 4096)
            val bh = h.toInt().coerceIn(1, 4096)
            svg.setDocumentWidth(bw.toFloat())
            svg.setDocumentHeight(bh.toFloat())
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            try {
                svg.renderToCanvas(Canvas(bmp))
                dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bmp.recycle()
            }
            true
        } catch (e: Throwable) {
            false
        }
    }

    // ---------------- helpers ----------------

    /** Eclipse's id for a system name used by another frontend (snes, psx, megadrive, sega32x …). */
    fun system(name: String): String? {
        val n = name.lowercase()
        // Eclipse's own Favourites and All games, under the names other frontends give them (ES-DE: auto-favorites,
        // auto-allgames)
        val bare = n.trimStart('_').removePrefix("auto-").removePrefix("auto_").replace(Regex("[^a-z0-9]"), "")
        if (bare in FAV_NAMES) return Platforms.FAVORITES
        if (bare in ALL_NAMES) return Platforms.ALL
        if (n.startsWith("_") || n.startsWith("auto-") || n.endsWith("_collection")) return null
        ART_ALIASES[bare]?.let { return it }
        return (Platforms.byId(n) ?: Platforms.matchFolder(n))?.id ?: if (n == "android") "android" else null
    }

    /**
     * Names other frontends give their pictures for systems Eclipse files under one name (ES-DE's Android apps and
     * games, its Windows 3.x / 9x). Pictures only: these don't change which folders are scanned for games.
     */
    private val ART_ALIASES = mapOf(
        "androidapps" to "android", "androidgames" to "android",
        "windows3x" to "windows", "windows9x" to "windows",
    )

    /** Pictures of other frontends' own screens and collections, not systems: never kept. */
    private val NOT_SYSTEMS = setOf(
        "completed", "nowplaying", "customcollections", "collections", "desktop", "emulators", "tools", "settings",
        "default", "global", "all", "background", "wallpaper", "logo", "icon", "folder", "space", "placeholder", "unknown",
    )

    /**
     * A picture in a folder of system pictures whose name isn't a system Eclipse has (yet): ES-DE themes carry
     * art for ~200 systems. It's kept under its own name (lowercase, letters and digits, "-" and "_"), so the day
     * Eclipse adds that system, or you name a system of your own after it, the picture is already there. Null for
     * names that are clearly not systems.
     */
    fun otherSystem(name: String): String? {
        val n = name.lowercase().trim()
        if (n.startsWith("_") || n.startsWith("auto-") || n.startsWith(".") || n.endsWith("_collection")) return null
        val bare = n.replace(Regex("[^a-z0-9]"), "")
        if (bare.length < 2 || bare in NOT_SYSTEMS) return null
        // theme screen parts (badge-favorite, icon-battery …): not systems
        if (Regex("^(badge|icon|help|button|background|bg|frame|overlay|controller|logo)[-_ ]").containsMatchIn(n)) return null
        return n.replace(Regex("[^a-z0-9_-]"), "")
    }

    private val FAV_NAMES = setOf("favorites", "favourites", "favorite", "favourite", "favs", "eclipsefavorites")
    private val ALL_NAMES = setOf("allgames", "all", "allsystems", "eclipseall")

    /** [rel] inside [base] (never outside it), if it's a file. */
    private fun safe(base: File, rel: String): File? {
        if (rel.isBlank()) return null
        val f = File(base, rel)
        return f.takeIf { it.isFile && it.canonicalPath.startsWith(base.canonicalPath) }
    }

    private fun write(
        out: File, kind: String, name: String, author: String,
        cards: Map<String, File>, logos: Map<String, File>, backgrounds: Map<String, File>,
        wallpaper: File?, bottom: File?, font: File?
    ): Result? {
        val pDir = File(out, "icons").apply { mkdirs() }
        val bDir = File(out, "backdrops").apply { mkdirs() }
        // the logos themselves too: shown where Eclipse writes a system's name (Look & Feel → Logos)
        if (logos.isNotEmpty()) {
            val lDir = File(out, "logos").apply { mkdirs() }
            for ((id, f) in logos) f.copyTo(File(lDir, "$id.${f.extension.lowercase()}"), overwrite = true)
        }
        var nCards = 0
        var nBgs = 0
        // pictures kept for systems Eclipse doesn't have yet (counted apart: they don't show anywhere until it does)
        val later = HashSet<String>()
        fun isLater(id: String) = system(id) == null
        for ((id, f) in cards) { f.copyTo(File(pDir, "$id.${f.extension.lowercase()}"), overwrite = true); if (isLater(id)) later += id else nCards++ }
        // systems with a logo but no card: draw one (logo over its background, or over a plain colour)
        for ((id, logo) in logos) {
            if (id in cards) continue
            if (drawCard(logo, backgrounds[id], File(pDir, "$id.png"))) { if (isLater(id)) later += id else nCards++ }
        }
        for ((id, f) in backgrounds) { f.copyTo(File(bDir, "$id.${f.extension.lowercase()}"), overwrite = true); if (isLater(id)) later += id else nBgs++ }
        if (nCards == 0 && nBgs == 0 && wallpaper == null && later.isEmpty()) return null

        val j = JSONObject().put("name", name).put("author", author.ifBlank { "Converted from $kind" }).put("wallpaperMode", "fallback")
        wallpaper?.let { it.copyTo(File(out, "wallpaper.${it.extension.lowercase()}"), overwrite = true); j.put("wallpaper", "wallpaper.${it.extension.lowercase()}") }
        bottom?.let { it.copyTo(File(out, "bottom.${it.extension.lowercase()}"), overwrite = true); j.put("bottomWallpaper", "bottom.${it.extension.lowercase()}") }
        font?.let { it.copyTo(File(out, "font.${it.extension.lowercase()}"), overwrite = true); j.put("font", "font.${it.extension.lowercase()}") }
        File(out, "theme.json").writeText(j.toString(2))
        val parts = listOfNotNull(
            "$nCards platform cards",
            nBgs.takeIf { it > 0 }?.let { "$it system backgrounds" },
            logos.keys.count { !isLater(it) }.takeIf { it > 0 }?.let { "$it logos" },
            later.size.takeIf { it > 0 }?.let { "pictures for $it more systems (kept for when Eclipse has them)" },
            wallpaper?.let { "a wallpaper" },
            font?.let { "its font" }
        )
        return Result(kind, nCards, nBgs, "Converted from $kind: " + parts.joinToString(", "))
    }

    /** A square card: the system's background (cropped and darkened) or a dark tile, with the logo in the middle. */
    private fun drawCard(logoFile: File, bgFile: File?, dest: File): Boolean {
        val size = 512
        val logo = decode(logoFile, 512) ?: return false
        val card = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(card)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val bg = bgFile?.let { decode(it, size) }
            if (bg != null) {
                // centre-crop the background into the square
                val s = minOf(bg.width, bg.height)
                val srcRect = Rect((bg.width - s) / 2, (bg.height - s) / 2, (bg.width + s) / 2, (bg.height + s) / 2)
                c.drawBitmap(bg, srcRect, Rect(0, 0, size, size), paint)
                c.drawColor(Color.argb(110, 0, 0, 0))
                bg.recycle()
            } else {
                c.drawColor(Color.rgb(36, 38, 46))
            }
            // logo: fit inside 80% of the width and 45% of the height
            val maxW = size * 0.8f
            val maxH = size * 0.45f
            val scale = minOf(maxW / logo.width, maxH / logo.height)
            val w = logo.width * scale
            val h = logo.height * scale
            c.drawBitmap(logo, null, RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), paint)
            dest.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            logo.recycle()
            card.recycle()
        }
    }
}
