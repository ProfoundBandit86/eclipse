package com.eclipse.launcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/*
 * Free artwork sources that need no account: libretro thumbnails (box art, in-game and title shots, logos for almost
 * every retro system), ArcadeDB (arcade games by their MAME short name: flyers, marquees, shots and history) and
 * GameTDB (GameCube, Wii, Wii U, DS and 3DS covers and disc art, by the game's ID code read from the file).
 */

/** The text inside the brackets of a No-Intro style name: "Super Mario World (USA) (Rev 1)" -> [USA, Rev 1]. */
private fun tagsOf(name: String): List<String> = Regex("\\(([^)]*)\\)").findAll(name).map { it.groupValues[1] }.toList()

/** A title reduced to letters and digits, without its brackets or a leading / trailing "The". */
internal fun titleKey(name: String): String {
    var t = name.substringBefore(" (").substringBefore(" [").trim()
    t = t.replace(Regex(",\\s*The\\b", RegexOption.IGNORE_CASE), "")
    if (t.startsWith("The ", ignoreCase = true)) t = t.drop(4)
    return t.lowercase().filter { it.isLetterOrDigit() }
}

// ================================================================ libretro thumbnails

object Libretro {
    /** Eclipse's system id -> the libretro-thumbnails repository for it. */
    private val REPOS = mapOf(
        "2600" to "Atari_-_2600", "5200" to "Atari_-_5200", "7800" to "Atari_-_7800", "jag" to "Atari_-_Jaguar",
        "lynx" to "Atari_-_Lynx", "ast" to "Atari_-_ST", "32x" to "Sega_-_32X", "3do" to "The_3DO_Company_-_3DO",
        "3ds" to "Nintendo_-_Nintendo_3DS", "a2001" to "Emerson_-_Arcadia_2001", "amiga" to "Commodore_-_Amiga",
        "arcade" to "MAME", "mame" to "MAME", "fbneo" to "FBNeo_-_Arcade_Games", "cps1" to "FBNeo_-_Arcade_Games",
        "cps2" to "FBNeo_-_Arcade_Games", "cps3" to "FBNeo_-_Arcade_Games", "aw" to "Atomiswave", "ard" to "Arduboy_Inc_-_Arduboy",
        "c64" to "Commodore_-_64", "cdi" to "Philips_-_CD-i", "chf" to "Fairchild_-_Channel_F", "cpc" to "Amstrad_-_CPC",
        "cv" to "Coleco_-_ColecoVision", "dreamcast" to "Sega_-_Dreamcast", "dos" to "DOS", "nds" to "Nintendo_-_Nintendo_DS",
        "fc" to "Nintendo_-_Nintendo_Entertainment_System", "nes" to "Nintendo_-_Nintendo_Entertainment_System",
        "nes-hacks" to "Nintendo_-_Nintendo_Entertainment_System", "fds" to "Nintendo_-_Family_Computer_Disk_System",
        "gb" to "Nintendo_-_Game_Boy", "gb-hacks" to "Nintendo_-_Game_Boy", "gbc" to "Nintendo_-_Game_Boy_Color",
        "gbc-hacks" to "Nintendo_-_Game_Boy_Color", "gba" to "Nintendo_-_Game_Boy_Advance", "gba-hacks" to "Nintendo_-_Game_Boy_Advance",
        "gc" to "Nintendo_-_GameCube", "genesis" to "Sega_-_Mega_Drive_-_Genesis", "gen-hacks" to "Sega_-_Mega_Drive_-_Genesis",
        "md" to "Sega_-_Mega_Drive_-_Genesis", "md-hacks" to "Sega_-_Mega_Drive_-_Genesis", "gg" to "Sega_-_Game_Gear",
        "gg-hacks" to "Sega_-_Game_Gear", "intv" to "Mattel_-_Intellivision", "mark3" to "Sega_-_Master_System_-_Mark_III",
        "sms" to "Sega_-_Master_System_-_Mark_III", "mcd" to "Sega_-_Mega-CD_-_Sega_CD", "scd" to "Sega_-_Mega-CD_-_Sega_CD",
        "mini" to "Nintendo_-_Pokemon_Mini", "mo2" to "Magnavox_-_Odyssey2", "msx" to "Microsoft_-_MSX", "n64" to "Nintendo_-_Nintendo_64",
        "naomi" to "Sega_-_Naomi", "naomi2" to "Sega_-_Naomi_2", "neogeo" to "SNK_-_Neo_Geo", "ngcd" to "SNK_-_Neo_Geo_CD",
        "ngp" to "SNK_-_Neo_Geo_Pocket", "ngpc" to "SNK_-_Neo_Geo_Pocket_Color", "pc88" to "NEC_-_PC-8001_-_PC-8801",
        "pc98" to "NEC_-_PC-98", "pccd" to "NEC_-_PC_Engine_CD_-_TurboGrafx-CD", "tgcd" to "NEC_-_PC_Engine_CD_-_TurboGrafx-CD",
        "pce" to "NEC_-_PC_Engine_-_TurboGrafx_16", "tg16" to "NEC_-_PC_Engine_-_TurboGrafx_16", "pcfx" to "NEC_-_PC-FX",
        "pet" to "Commodore_-_PET", "pico" to "Sega_-_PICO", "plus4" to "Commodore_-_Plus-4", "ps1" to "Sony_-_PlayStation",
        "ps2" to "Sony_-_PlayStation_2", "ps3" to "Sony_-_PlayStation_3", "psp" to "Sony_-_PlayStation_Portable", "psvita" to "Sony_-_PlayStation_Vita",
        "saturn" to "Sega_-_Saturn", "satellaview" to "Nintendo_-_Satellaview", "sfc" to "Nintendo_-_Super_Nintendo_Entertainment_System",
        "sfc-hacks" to "Nintendo_-_Super_Nintendo_Entertainment_System", "snes" to "Nintendo_-_Super_Nintendo_Entertainment_System",
        "snes-hacks" to "Nintendo_-_Super_Nintendo_Entertainment_System", "sg1k" to "Sega_-_SG-1000", "sharpx68000" to "Sharp_-_X68000",
        "scummvm" to "ScummVM", "tic80" to "TIC-80", "vb" to "Nintendo_-_Virtual_Boy", "vect" to "GCE_-_Vectrex",
        "vic20" to "Commodore_-_VIC-20", "wasm4" to "WASM-4", "wii" to "Nintendo_-_Wii", "wiiu" to "Nintendo_-_Wii_U",
        "ws" to "Bandai_-_WonderSwan", "wsc" to "Bandai_-_WonderSwan_Color", "wsv" to "Watara_-_Supervision", "x1" to "Sharp_-_X1",
        "xbox" to "Microsoft_-_Xbox", "xbox360" to "Microsoft_-_Xbox_360", "zx81" to "Sinclair_-_ZX_81",
        "zxspectrum" to "Sinclair_-_ZX_Spectrum", "rpgmaker" to "RPG_Maker",
    )

    fun covers(platform: String) = platform in REPOS

    /** libretro's name for a system ("Nintendo - Super Nintendo Entertainment System"), as RetroArch's own icons are named. */
    fun systemName(platform: String): String? = REPOS[platform]?.replace('_', ' ')

    /** repo -> folder -> (title key -> file names without ".png") */
    private val indexes = ConcurrentHashMap<String, Map<String, Map<String, List<String>>>>()

    /**
     * Every picture a system's repository has, from GitHub's file list. Kept in [cacheDir] for two weeks, so it's one
     * request per system, not one per game.
     */
    private suspend fun index(repo: String, cacheDir: File): Map<String, Map<String, List<String>>>? {
        indexes[repo]?.let { return it }
        val f = File(File(cacheDir, "libretro").apply { mkdirs() }, "$repo.txt")
        val lines: List<String> = withContext(Dispatchers.IO) {
            if (f.isFile && System.currentTimeMillis() - f.lastModified() < 14L * 24 * 3600 * 1000) f.readLines() else null
        } ?: run {
            val body = Http.get("https://api.github.com/repos/libretro-thumbnails/$repo/git/trees/master?recursive=1") ?: return null
            val tree = try { JSONObject(body).optJSONArray("tree") } catch (e: Exception) { null } ?: return null
            val l = (0 until tree.length()).mapNotNull { i -> tree.optJSONObject(i)?.optString("path")?.takeIf { it.endsWith(".png") && it.count { c -> c == '/' } == 1 } }
            if (l.isEmpty()) return null
            withContext(Dispatchers.IO) { runCatching { f.writeText(l.joinToString("\n")) } }
            l
        }
        val byFolder = lines.groupBy({ it.substringBefore('/') }, { it.substringAfter('/').removeSuffix(".png") })
            .mapValues { (_, names) -> names.groupBy { titleKey(it) } }
        indexes[repo] = byFolder
        return byFolder
    }

    /** The best name in [folder] for this game: the file's own name, else the same title in the region that suits it. */
    private fun pick(folder: Map<String, List<String>>, g: Game, alt: String?): String? {
        val base = File(g.path).nameWithoutExtension.replace(Regex("[&*/:`<>?\\\\|]"), "_")
        folder[titleKey(base)]?.firstOrNull { it == base }?.let { return it }
        val list = folder[titleKey(base)] ?: folder[titleKey(g.name)] ?: alt?.let { folder[titleKey(it)] } ?: return null
        val want = tagsOf(base).flatMap { it.split(',').map { s -> s.trim() } }.toSet()
        fun score(n: String): Int {
            val tags = tagsOf(n).flatMap { it.split(',').map { s -> s.trim() } }
            var s = 0
            if (tags.any { it in want }) s += 20
            if ("USA" in tags) s += 6
            if ("World" in tags) s += 5
            if ("Europe" in tags) s += 4
            if ("Japan" in tags) s += 2
            if (tags.any { it.startsWith("Beta") || it.startsWith("Proto") || it == "Sample" || it == "Demo" || it == "Kiosk" || it == "Pirate" || it == "Unl" }) s -= 15
            if (tags.any { it.startsWith("Rev") }) s -= 1
            return s
        }
        return list.maxByOrNull { score(it) }
    }

    /** Titles in this system's libretro box art that contain every word of [query] (for Match by hand). */
    suspend fun search(platform: String, query: String, cacheDir: File, limit: Int = 20): List<MatchHit> {
        val repo = REPOS[platform] ?: return emptyList()
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 || it.all(Char::isDigit) }
        if (words.isEmpty()) return emptyList()
        val folder = index(repo, cacheDir)?.get("Named_Boxarts") ?: return emptyList()
        return folder.values.flatten().asSequence()
            .filter { n -> val l = n.lowercase(); words.all { l.contains(it) } }
            .sortedBy { it.length }
            .take(limit)
            .map { n -> MatchHit(Scanner.cleanTitle(n), "libretro", tagsOf(n).joinToString(" · ")) }
            .toList()
    }

    /** Box art (front), in-game and title shots as backdrops, and logos. Discs / cartridges: none. */
    suspend fun art(g: Game, kind: ArtKind, cacheDir: File, alt: String? = null): List<ArtOption> {
        val repo = REPOS[g.platform] ?: return emptyList()
        val folders = when (kind) {
            ArtKind.BOX -> listOf("Named_Boxarts")
            ArtKind.BACKDROP -> listOf("Named_Snaps", "Named_Titles")
            ArtKind.LOGO -> listOf("Named_Logos")
            else -> return emptyList()
        }
        val idx = index(repo, cacheDir) ?: return emptyList()
        return folders.mapNotNull { folder ->
            val name = idx[folder]?.let { pick(it, g, alt) } ?: return@mapNotNull null
            ArtOption("https://raw.githubusercontent.com/libretro-thumbnails/$repo/master/$folder/" + android.net.Uri.encode(name) + ".png", "libretro")
        }
    }
}

// ================================================================ ArcadeDB

object ArcadeDb {
    private val ARCADE = setOf("arcade", "mame", "fbneo", "cps1", "cps2", "cps3", "neogeo", "naomi", "naomi2", "naomigd", "aw", "model3")
    fun covers(platform: String) = platform in ARCADE

    data class Entry(val title: String, val flyer: String?, val titleShot: String?, val ingame: String?, val marquee: String?, val history: String?)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** The game's MAME short name: its file name ("sf2.zip" -> "sf2"), when it looks like one. */
    private fun shortName(g: Game): String? =
        File(g.path).nameWithoutExtension.lowercase().takeIf { it.matches(Regex("[a-z0-9_]{1,20}")) }

    suspend fun lookup(g: Game): Entry? {
        val name = shortName(g) ?: return null
        cache[name]?.let { return it }
        if (name in missing) return null
        val body = Http.get("https://adb.arcadeitalia.net/service_scraper.php?ajax=query_mame&lang=en&use_parent=1&game_name=${Http.enc(name)}") ?: return null
        val o = try { JSONObject(body).optJSONArray("result")?.optJSONObject(0) } catch (e: Exception) { null }
        if (o == null || o.optString("title").isBlank()) { missing.add(name); return null }
        fun url(k: String) = o.optString(k).takeIf { it.startsWith("http") }
        // the history ends with technical notes and links: keep the story
        var h = o.optString("history").takeIf { it.isNotBlank() }
        if (h != null) {
            for (cut in listOf("- TECHNICAL", "- CONTRIBUTE", "- TRIVIA", "- SERIES", "- STAFF", "- PORTS")) if (h!!.contains(cut)) h = h!!.substringBefore(cut)
            h = h!!.trim().ifBlank { null }
        }
        val e = Entry(o.optString("title"), url("url_image_flyer"), url("url_image_title"), url("url_image_ingame"), url("url_image_marquee"), h)
        cache[name] = e
        return e
    }

    fun art(e: Entry, kind: ArtKind): List<ArtOption> = when (kind) {
        ArtKind.BOX -> listOfNotNull(e.flyer, e.titleShot)
        ArtKind.POSTER -> listOfNotNull(e.flyer)
        ArtKind.BACKDROP -> listOfNotNull(e.ingame, e.titleShot)
        ArtKind.LOGO -> listOfNotNull(e.marquee)
        else -> emptyList()
    }.map { ArtOption(it, "ArcadeDB") }
}

// ================================================================ GameTDB

object GameTdb {
    fun covers(platform: String) = platform in setOf("gc", "wii", "nds", "3ds")

    private val ids = ConcurrentHashMap<String, String>()

    /** The game's ID code, read from its file: ID6 for GameCube / Wii discs, ID4 for DS and 3DS cartridges. */
    suspend fun idOf(g: Game): String? = ids[g.path] ?: withContext(Dispatchers.IO) {
        val f = File(g.path)
        if (!f.isFile) return@withContext null
        val ext = f.extension.lowercase()
        val id = try {
            when (g.platform) {
                "gc", "wii" -> when (ext) {
                    "iso", "gcm" -> read(f, 0L, 6)
                    "wbfs" -> read(f, 0x200L, 6)
                    "ciso" -> read(f, 0x8000L, 6)
                    "rvz", "wia" -> read(f, 0x58L, 6)
                    else -> null
                }?.takeIf { it.matches(Regex("[A-Z0-9]{6}")) }
                "nds" -> when (ext) {
                    "nds" -> read(f, 0x0CL, 4)
                    "zip" -> ZipFile(f).use { z ->
                        z.entries().asSequence().firstOrNull { it.name.endsWith(".nds", true) }?.let { en ->
                            z.getInputStream(en).use { s -> val b = ByteArray(0x10); var n = 0; while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) break; n += r }; String(b, 0x0C, 4, Charsets.US_ASCII) }
                        }
                    }
                    else -> null
                }?.takeIf { it.matches(Regex("[A-Z0-9]{4}")) }
                "3ds" -> when (ext) {
                    "3ds", "cci" -> read(f, 0x4150L, 10)
                    "cxi" -> read(f, 0x150L, 10)
                    else -> null
                }?.takeIf { it.startsWith("CTR-") }?.takeLast(4)?.takeIf { it.matches(Regex("[A-Z0-9]{4}")) }
                else -> null
            }
        } catch (e: Exception) { null }
        id?.also { ids[g.path] = it }
    }

    private fun read(f: File, at: Long, n: Int): String? = RandomAccessFile(f, "r").use { r ->
        if (r.length() < at + n) return null
        r.seek(at); val b = ByteArray(n); r.readFully(b); String(b, Charsets.US_ASCII)
    }

    /** GameTDB's region folders to try for an ID, from its last letter (E = USA, P = Europe, J = Japan …). */
    private fun regions(id: String): List<String> = when (id.getOrNull(3)) {
        'E' -> listOf("US")
        'P', 'X', 'Y', 'Z', 'L', 'M' -> listOf("EN", "FR", "DE", "ES", "IT", "NL")
        'J' -> listOf("JA")
        'K', 'Q', 'T' -> listOf("KO")
        'D' -> listOf("DE", "EN")
        'F' -> listOf("FR", "EN")
        'S' -> listOf("ES", "EN")
        'I' -> listOf("IT", "EN")
        'U' -> listOf("AU", "EN")
        'W', 'C' -> listOf("ZH")
        else -> listOf("US", "EN", "JA")
    } + listOf("US", "EN")

    /**
     * Covers (front, 3D box, full) and disc art. Each kind tries the regions in order and keeps the first that exists.
     * [first] = stop at the first picture found (bulk scraping) instead of listing every type (the picker).
     */
    suspend fun art(g: Game, kind: ArtKind, first: Boolean): List<ArtOption> {
        val id = idOf(g) ?: return emptyList()
        val sys = if (g.platform == "gc") "wii" else g.platform.let { if (it == "nds") "ds" else it }
        val types: List<Pair<String, String>> = when (kind) {
            ArtKind.BOX -> if (sys == "wii") listOf("cover" to "png", "cover3D" to "png", "coverfullHQ" to "png")
                else listOf("box" to "png", "cover" to "jpg", "coverfull" to "jpg")
            ArtKind.MEDIA -> if (sys == "wii") listOf("disc" to "png") else return emptyList()
            ArtKind.BOX3D -> if (sys == "wii") listOf("cover3D" to "png") else listOf("box" to "png")
            else -> return emptyList()
        }
        val out = ArrayList<ArtOption>()
        for ((type, ext) in types) {
            for (rg in regions(id).distinct()) {
                val url = "https://art.gametdb.com/$sys/$type/$rg/$id.$ext"
                if (Http.exists(url)) { out.add(ArtOption(url, "GameTDB")); break }
            }
            if (first && out.isNotEmpty()) break
        }
        return out
    }
}
