package com.eclipse.launcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Companion (bottom screen → Now Playing → Companion): guide sites for a game, guides saved on the device, the
 * reader's text, and instruction manuals. Everything is kept per game under filesDir/companion/<game id>/.
 */
object Guides {

    // ---------------------------------------------------------------- guide sites

    /** A place to look for help: opened in the Companion's browser. [best] = the series' own site. */
    data class Site(val name: String, val note: String, val url: String, val badge: String, val color: Long, val best: Boolean = false)

    /**
     * Guide sites for [title]. A series with a better home than the general sites gets it first: Zelda → Zelda
     * Dungeon, Metroid → Omega Metroid, Pokémon → Bulbapedia, Minecraft → Chunkbase. Then GameFAQs and StrategyWiki.
     */
    fun sites(title: String): List<Site> {
        val t = title.lowercase()
        val q = Http.enc(title)
        val out = ArrayList<Site>()
        if ("zelda" in t)
            out += Site("Zelda Dungeon", "Walkthroughs, maps and items for every Zelda", "https://www.zeldadungeon.net/?s=" + Http.enc("$title walkthrough"), "ZD", 0xFF2E6B3A, true)
        if ("metroid" in t)
            out += Site("Omega Metroid", "Maps, item locations and walkthroughs", "https://lite.duckduckgo.com/lite/?q=" + Http.enc("site:omegametroid.com $title"), "OM", 0xFF7A2E2E, true)
        if (Regex("pok[eé]mon").containsMatchIn(t))
            out += Site("Bulbapedia", "Walkthrough, Pokédex and locations", "https://bulbapedia.bulbagarden.net/w/index.php?search=" + Http.enc("$title walkthrough"), "BP", 0xFF2D5F9E, true)
        if ("minecraft" in t)
            out += Site("Chunkbase", "Seed map, biome and structure finder", "https://www.chunkbase.com/apps/", "CB", 0xFF3E7A2A, true)
        out += Site("GameFAQs", "Classic guides · open one and tap Save", "https://gamefaqs.gamespot.com/search?game=$q", "FAQ", 0xFF1D3B6E)
        out += Site("StrategyWiki", "Community wiki guide", "https://strategywiki.org/w/index.php?search=$q", "SW", 0xFF2C5A3A)
        return out
    }

    /** A web search that already knows the game: its name goes in front of what you typed. */
    fun searchUrl(title: String, query: String): String =
        "https://lite.duckduckgo.com/lite/?q=" + Http.enc(("$title " + query.trim()).trim())

    // ---------------------------------------------------------------- saved guides

    data class Saved(val file: File, val title: String, val source: String, val url: String, val saved: Long, val html: Boolean)

    fun dir(root: File, gameId: String): File = File(root, "companion/" + gameId.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    private fun indexOf(root: File, gameId: String) = File(dir(root, gameId), "guides.json")

    /** The game's saved guides, newest first. */
    fun saved(root: File, gameId: String): List<Saved> {
        val arr = runCatching { JSONArray(indexOf(root, gameId).readText()) }.getOrNull() ?: return emptyList()
        val d = dir(root, gameId)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val f = File(d, o.optString("file"))
            if (!f.isFile) null
            else Saved(f, o.optString("title"), o.optString("source"), o.optString("url"), o.optLong("saved"), o.optBoolean("html"))
        }.sortedByDescending { it.saved }
    }

    private fun writeIndex(root: File, gameId: String, list: List<Saved>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("file", s.file.name).put("title", s.title).put("source", s.source).put("url", s.url)
                .put("saved", s.saved).put("html", s.html))
        }
        dir(root, gameId).mkdirs()
        indexOf(root, gameId).writeText(arr.toString())
    }

    /**
     * Keeps a page's text as a guide. [html]: it came from a web page (one line per paragraph) rather than a plain-text
     * guide. Saving the same address again replaces the old copy.
     */
    fun save(root: File, gameId: String, title: String, source: String, url: String, text: String, html: Boolean): Saved {
        val d = dir(root, gameId).apply { mkdirs() }
        val old = saved(root, gameId)
        old.filter { it.url == url }.forEach { it.file.delete() }
        val f = File(d, "g" + System.currentTimeMillis() + ".txt")
        f.writeText(text)
        val s = Saved(f, title.ifBlank { source }, source, url, System.currentTimeMillis(), html)
        writeIndex(root, gameId, listOf(s) + old.filter { it.url != url })
        return s
    }

    fun delete(root: File, gameId: String, s: Saved) {
        s.file.delete()
        writeIndex(root, gameId, saved(root, gameId).filter { it.file != s.file })
    }

    // ---------------------------------------------------------------- the reader's text

    /** A piece of a guide as the reader shows it. */
    sealed class Block {
        abstract val text: String
        /** a section title: listed under Contents */
        data class Heading(override val text: String) : Block()
        /** prose: the guide's hard line breaks joined, so it fits the screen */
        data class Para(override val text: String) : Block()
        /** a list: one line each, still wrapped to fit */
        data class Lines(override val text: String) : Block()
        /** tables and ASCII art: kept exactly, in a fixed-width font, scrolled sideways if wide */
        data class Pre(override val text: String) : Block()
    }

    private val RULE = Regex("^\\s*([=\\-*#~_+.:<>|/\\\\]\\s*){4,}$")
    private val COLUMNS = Regex("\\S {3,}\\S")
    private val BOXY = Regex("[|+]-{2,}|-{2,}[|+]|\\|.*\\||[/\\\\]{2,}|[_=]{4,}")
    private val LISTY = Regex("^\\s*([-*•o>+]|\\d{1,3}[.)]|[a-zA-Z][.)]|\\[[ xX]?])\\s+\\S")
    private val NUMBERED_TITLE = Regex("^\\s*(\\d+(\\.\\d+)*|[IVXLC]+)[.):]?\\s+\\S.{0,60}$")

    /**
     * Turns a guide's text into blocks for the reader. Old GameFAQs-style guides are written for 80 columns with hard
     * line breaks: prose is joined back into paragraphs, lists keep their lines, and tables / ASCII art are left as
     * they are. [html]: text taken from a web page, where every line is already a paragraph.
     */
    fun reflow(text: String, html: Boolean): List<Block> {
        val src = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
        val lines = if (html) src.split('\n').flatMap { listOf(it, "") } else src.split('\n')
        val out = ArrayList<Block>()
        val cur = ArrayList<String>()
        fun flush() {
            if (cur.isNotEmpty()) { out += classify(cur.toList()); cur.clear() }
        }
        for (l in lines) if (l.isBlank()) flush() else cur += l.trimEnd()
        flush()
        return out
    }

    private fun classify(ls: List<String>): List<Block> {
        val body = ls.filterNot { RULE.matches(it) }
        if (body.isEmpty()) return emptyList()
        val underlined = body.size < ls.size
        if (body.size == 1) {
            val s = body[0].trim()
            if (isHeading(s, underlined)) return listOf(Block.Heading(s.trim('=', '-', '*', '#', '~', ' ', '[', ']').ifBlank { s }))
        }
        val boxy = body.count { COLUMNS.containsMatchIn(it.trim()) || BOXY.containsMatchIn(it) }
        if (boxy * 3 >= body.size && (body.size > 1 || boxy > 0)) return listOf(Block.Pre(ls.joinToString("\n")))
        val listy = body.count { LISTY.containsMatchIn(it) }
        val avg = body.sumOf { it.trim().length } / body.size
        if (body.size > 1 && (listy * 2 >= body.size || avg < 38)) return listOf(Block.Lines(body.joinToString("\n") { it.trim() }))
        return listOf(Block.Para(body.joinToString(" ") { it.trim() }.replace(Regex(" {2,}"), " ")))
    }

    private fun isHeading(s: String, underlined: Boolean): Boolean {
        if (s.length > 72) return false
        val letters = s.count { it.isLetter() }
        if (letters < 2) return false
        if (underlined) return true
        val upper = s.count { it.isUpperCase() }
        if (letters >= 3 && upper * 10 >= letters * 8) return true
        return NUMBERED_TITLE.matches(s) && !s.trimEnd().endsWith(".") && s.length <= 48
    }

    // ---------------------------------------------------------------- reading position, bookmarks, text size

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("companion", Context.MODE_PRIVATE)

    fun position(ctx: Context, s: Saved): Int = prefs(ctx).getInt("pos_" + s.file.name, 0)
    fun setPosition(ctx: Context, s: Saved, block: Int, of: Int) {
        prefs(ctx).edit().putInt("pos_" + s.file.name, block).putInt("len_" + s.file.name, of).apply()
    }
    /** How far through [s] you are, 0..1 (0 = not opened yet). */
    fun progress(ctx: Context, s: Saved): Float {
        val p = prefs(ctx)
        val len = p.getInt("len_" + s.file.name, 0)
        return if (len <= 1) 0f else (p.getInt("pos_" + s.file.name, 0).toFloat() / (len - 1)).coerceIn(0f, 1f)
    }

    fun bookmarks(ctx: Context, s: Saved): Set<Int> =
        prefs(ctx).getString("marks_" + s.file.name, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.toSet()
    fun setBookmarks(ctx: Context, s: Saved, marks: Set<Int>) {
        prefs(ctx).edit().putString("marks_" + s.file.name, marks.sorted().joinToString(",")).apply()
    }

    /** The guide last opened for a game (the "Continue reading" card). */
    fun lastRead(ctx: Context, gameId: String): String? = prefs(ctx).getString("last_$gameId", null)
    fun setLastRead(ctx: Context, gameId: String, s: Saved) { prefs(ctx).edit().putString("last_$gameId", s.file.name).apply() }

    /** The reader's look: the theme's colours, or a fixed dark or sepia page. */
    val READER_LOOKS = listOf("theme", "dark", "sepia")
    fun readerLook(ctx: Context): String = prefs(ctx).getString("look", "theme")?.takeIf { it in READER_LOOKS } ?: "theme"
    fun setReaderLook(ctx: Context, look: String) { prefs(ctx).edit().putString("look", look).apply() }

    val SIZES = listOf(13, 15, 17, 19)
    fun textSize(ctx: Context): Int = prefs(ctx).getInt("size", 15)
    fun setTextSize(ctx: Context, sp: Int) { prefs(ctx).edit().putInt("size", sp).apply() }

    // ---------------------------------------------------------------- manuals

    /** The manual kept for a game (downloaded from the Internet Archive). */
    fun manualFile(root: File, gameId: String): File = File(dir(root, gameId), "manual.pdf")

    /**
     * Manuals already on the device for [game] (its file): "<name>.pdf" beside it, in a "manuals" folder beside it or
     * one level up, or in ES-DE's downloaded media for that system.
     */
    fun localManuals(game: File): List<File> {
        val dir = game.parentFile ?: return emptyList()
        val base = game.nameWithoutExtension
        val esde = File(android.os.Environment.getExternalStorageDirectory(), "ES-DE/downloaded_media/${dir.name}/manuals/$base.pdf")
        return listOfNotNull(
            File(dir, "$base.pdf"), File(dir, "manuals/$base.pdf"), dir.parentFile?.let { File(it, "manuals/$base.pdf") }, esde,
        ).filter { it.isFile }.distinct()
    }

    // ---------------------------------------------------------------- VideoGameManual.com (Kirkland's scans)

    /** A manual on videogamemanual.com: its name ("Golden Sun (USA)") and the PDF's address. */
    data class VgmHit(val name: String, val url: String)

    private const val VGM = "https://www.videogamemanual.com/"

    /**
     * The site has one page per console listing every PDF, named like No-Intro ROMs. These pages are known; for other
     * systems the usual folder names are tried once (index.html, then the folder itself).
     */
    private val VGM_PAGES = mapOf(
        "gb" to listOf(VGM + "gameboy/index.html"), "gb-hacks" to listOf(VGM + "gameboy/index.html"),
        "gbc" to listOf(VGM + "gbc/index.html"), "gbc-hacks" to listOf(VGM + "gbc/index.html"),
        "gba" to listOf(VGM + "gba/index.html"), "gba-hacks" to listOf(VGM + "gba/index.html"),
        "ps1" to listOf("https://ps1.videogamemanual.com/"),
        "ps2" to listOf(VGM + "PS2/", VGM + "PS2/index.html"),
        "xbox360" to listOf(VGM + "xbox360/index.html"),
    )
    private val VGM_FOLDERS = mapOf(
        "nes" to listOf("nes", "NES"), "snes" to listOf("snes", "SNES"), "n64" to listOf("n64", "N64"),
        "genesis" to listOf("genesis", "Genesis", "md"), "md" to listOf("genesis", "Genesis", "md"),
        "sms" to listOf("sms", "mastersystem"), "gg" to listOf("gamegear", "gg"), "nds" to listOf("nds", "ds", "DS"),
        "gc" to listOf("gamecube", "GameCube", "gc", "ngc"), "wii" to listOf("wii", "Wii"), "psp" to listOf("psp", "PSP"),
        "saturn" to listOf("saturn", "Saturn"), "dreamcast" to listOf("dreamcast", "Dreamcast", "dc"),
        "xbox" to listOf("xbox", "Xbox"), "3ds" to listOf("3ds", "3DS"), "ps3" to listOf("PS3", "ps3"),
        "2600" to listOf("2600", "atari2600"), "7800" to listOf("7800", "atari7800"), "5200" to listOf("5200", "atari5200"),
        "lynx" to listOf("lynx"), "jag" to listOf("jaguar"), "vb" to listOf("vb", "virtualboy"),
        "tg16" to listOf("tg16", "turbografx"), "pce" to listOf("tg16", "turbografx"), "32x" to listOf("32x"),
        "mcd" to listOf("segacd", "scd"), "scd" to listOf("segacd", "scd"), "3do" to listOf("3do"),
    )
    /** Each system's PDF list once found (an empty list = the site has no page for it). */
    private val vgmCache = HashMap<String, List<VgmHit>>()
    private val HREF = Regex("href\\s*=\\s*[\"']([^\"']+\\.pdf)[\"']", RegexOption.IGNORE_CASE)

    private suspend fun vgmList(platform: String): List<VgmHit> {
        vgmCache[platform]?.let { return it }
        val pages = VGM_PAGES[platform] ?: VGM_FOLDERS[platform]?.flatMap { f -> listOf("$VGM$f/index.html", "$VGM$f/") }.orEmpty()
        var found = emptyList<VgmHit>()
        for (page in pages) {
            val html = Http.get(page) ?: continue
            found = HREF.findAll(html).mapNotNull { m ->
                val href = m.groupValues[1].replace("&amp;", "&")
                val abs = runCatching { java.net.URL(java.net.URL(page), href.replace(" ", "%20")).toString() }.getOrNull() ?: return@mapNotNull null
                val name = runCatching { java.net.URLDecoder.decode(abs.substringAfterLast('/').replace("+", "%2B"), "UTF-8") }.getOrDefault(abs.substringAfterLast('/'))
                    .removeSuffix(".pdf").removeSuffix(".PDF")
                VgmHit(name, abs)
            }.distinctBy { it.url }.toList()
            if (found.isNotEmpty()) break
        }
        vgmCache[platform] = found
        return found
    }

    /** "The Legend of Zelda: The Minish Cap (USA) [!]" and "Legend of Zelda, The - The Minish Cap (USA)" fold alike. */
    private fun fold(s: String): String {
        var t = s.lowercase().replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
        Regex("^(.*?), the\\b").find(t)?.let { m -> t = "the " + m.groupValues[1] + t.substring(m.range.last + 1) }
        return t.replace("&", "and").replace(Regex("[^a-z0-9]"), "")
    }

    /**
     * Manuals for a game on videogamemanual.com: the one named exactly like the ROM file first, then close matches of
     * the title. Empty when the site has nothing for this system or game.
     */
    suspend fun vgmSearch(platform: String, title: String, romFile: String): List<VgmHit> = withContext(Dispatchers.IO) {
        val all = runCatching { vgmList(platform) }.getOrDefault(emptyList())
        if (all.isEmpty()) return@withContext emptyList()
        val rom = File(romFile).nameWithoutExtension
        val exact = all.filter { it.name.equals(rom, ignoreCase = true) }
        val want = fold(title).ifEmpty { fold(rom) }
        if (want.length < 3) return@withContext exact
        val close = all.filter { fold(it.name) == want } + all.filter { fold(it.name).startsWith(want) } +
            all.filter { want.length >= 5 && fold(it.name).contains(want) }
        (exact + close).distinctBy { it.url }.take(6)
    }

    data class ArchiveHit(val id: String, val title: String, val downloads: Int)

    /** Scanned manuals on the Internet Archive whose title matches [title]; most downloaded first. */
    suspend fun archiveSearch(title: String): List<ArchiveHit> = withContext(Dispatchers.IO) {
        val clean = title.replace(Regex("[\"():\\[\\]{}\\\\]"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return@withContext emptyList()
        val q = "title:(\"$clean\") AND mediatype:(texts) AND (manual OR instruction OR booklet)"
        val url = "https://archive.org/advancedsearch.php?q=" + Http.enc(q) +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=downloads&sort%5B%5D=downloads+desc&rows=20&output=json"
        val js = Http.get(url) ?: return@withContext emptyList()
        val docs = runCatching { JSONObject(js).getJSONObject("response").getJSONArray("docs") }.getOrNull() ?: return@withContext emptyList()
        (0 until docs.length()).mapNotNull { i ->
            val o = docs.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("identifier").ifBlank { return@mapNotNull null }
            ArchiveHit(id, o.optString("title").ifBlank { id }, o.optInt("downloads"))
        }
    }

    /** The PDF of an Internet Archive item (the original scan, not the derived text-only copies), or null. */
    suspend fun archivePdf(id: String): String? = withContext(Dispatchers.IO) {
        val js = Http.get("https://archive.org/metadata/" + Http.enc(id)) ?: return@withContext null
        val files = runCatching { JSONObject(js).getJSONArray("files") }.getOrNull() ?: return@withContext null
        val pdfs = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".pdf", ignoreCase = true) }
        val pick = pdfs.firstOrNull { !it.optString("name").contains("_text", ignoreCase = true) } ?: pdfs.firstOrNull()
            ?: return@withContext null
        val path = pick.optString("name").split('/').joinToString("/") { Http.enc(it).replace("+", "%20") }
        "https://archive.org/download/" + Http.enc(id) + "/" + path
    }
}
