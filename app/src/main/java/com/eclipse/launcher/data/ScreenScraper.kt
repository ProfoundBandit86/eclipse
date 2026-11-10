package com.eclipse.launcher.data

import com.eclipse.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ScreenScraper (screenscraper.fr): box art, 3D boxes, screenshots, logos ("wheels"), fanart, discs / cartridges,
 * descriptions, game details and instruction manuals. Games are found by the ROM's checksum (exact), else its file name.
 *
 * Two logins go with every request: Eclipse's developer login (built in from the repository secrets
 * SCREENSCRAPER_DEV_ID / _PASSWORD, never in the source) and your own ScreenScraper account (Settings → Connections),
 * which sets your daily quota.
 */
object ScreenScraper {
    private const val API = "https://api.screenscraper.fr/api2/"
    private const val SOFT = "Eclipse"

    /** This build has a developer login (without one, ScreenScraper stays greyed out). */
    val built: Boolean get() = BuildConfig.SS_DEV_ID.isNotBlank() && BuildConfig.SS_DEV_PASSWORD.isNotBlank()

    /** ScreenScraper's system numbers for Eclipse's systems (from ES-DE's list). */
    private val SYSTEMS = mapOf(
        "3do" to 29, "2600" to 26, "5200" to 40, "7800" to 41, "jag" to 27, "jagcd" to 171, "lynx" to 28,
        "a2" to 86, "a2001" to 94, "amiga" to 64, "c64" to 66, "plus4" to 99, "vic20" to 73, "pet" to 0, "cpc" to 65,
        "bbcmicro" to 37, "msx" to 113, "zxspectrum" to 76, "zx81" to 77, "sharpx68000" to 79, "x1" to 220,
        "pc88" to 221, "pc98" to 208, "dos" to 135, "windows" to 138, "steam" to 138, "scummvm" to 123,
        "arcade" to 75, "mame" to 75, "fbneo" to 75, "cps1" to 75, "cps2" to 75, "cps3" to 75, "naomi" to 56,
        "naomi2" to 56, "naomigd" to 56, "aw" to 53, "model3" to 75, "neogeo" to 142, "ngcd" to 70,
        "ngp" to 25, "ngpc" to 82, "cv" to 48, "intv" to 115, "chf" to 80, "vect" to 102, "duck" to 90,
        "wsv" to 207, "uze" to 216, "ws" to 45, "wsc" to 46, "pico8" to 234, "tic80" to 222, "wasm4" to 262,
        "nes" to 3, "nes-hacks" to 3, "fc" to 3, "fds" to 106, "snes" to 4, "snes-hacks" to 4, "sfc" to 4, "sfc-hacks" to 4,
        "satellaview" to 107, "n64" to 14, "gb" to 9, "gb-hacks" to 9, "gbc" to 10, "gbc-hacks" to 10,
        "gba" to 12, "gba-hacks" to 12, "nds" to 15, "3ds" to 17, "gc" to 13, "wii" to 16, "wiiu" to 18,
        "switch" to 225, "vb" to 11, "gw" to 52, "mini" to 211,
        "genesis" to 1, "md" to 1, "gen-hacks" to 1, "md-hacks" to 1, "mark3" to 2, "sms" to 2, "gg" to 21, "gg-hacks" to 21,
        "32x" to 19, "mcd" to 20, "scd" to 20, "saturn" to 22, "dreamcast" to 23, "sg1k" to 109, "pico" to 250,
        "pce" to 31, "tg16" to 31, "pccd" to 114, "tgcd" to 114, "pcfx" to 72, "cdi" to 133,
        "ps1" to 57, "ps2" to 58, "ps3" to 59, "psp" to 61, "pspminis" to 61, "psvita" to 62,
        "xbox" to 32, "xbox360" to 33, "palm" to 219,
    )

    /** Which ScreenScraper media type(s) each kind of artwork comes from, best first. */
    private fun mediaTypes(k: ArtKind): List<String> = when (k) {
        ArtKind.BOX -> listOf("box-2D")
        ArtKind.BOX3D -> listOf("box-3D")
        ArtKind.BACKDROP -> listOf("fanart", "ss")
        ArtKind.LOGO -> listOf("wheel-hd", "wheel")
        ArtKind.MEDIA -> listOf("support-2D")
        else -> emptyList()
    }

    /** Regions in order of preference for pictures and names (as ES-DE). */
    private val REGIONS = listOf("wor", "us", "ss", "eu", "jp")

    private fun auth(user: String, pass: String): String {
        val sb = StringBuilder("devid=").append(Http.enc(BuildConfig.SS_DEV_ID))
            .append("&devpassword=").append(Http.enc(BuildConfig.SS_DEV_PASSWORD))
            .append("&softname=").append(SOFT).append("&output=json")
        if (user.isNotBlank() && pass.isNotBlank()) sb.append("&ssid=").append(Http.enc(user)).append("&sspassword=").append(Http.enc(pass))
        return sb.toString()
    }

    // ---------------------------------------------------------------- your account

    data class Account(val name: String, val level: Int, val today: Int, val maxPerDay: Int)

    /** Signs in with [user] / [pass]: the account and today's quota, or null when it isn't accepted. */
    suspend fun account(user: String, pass: String): Account? = withContext(Dispatchers.IO) {
        if (!built || user.isBlank() || pass.isBlank()) return@withContext null
        val js = Http.get(API + "ssuserInfos.php?" + auth(user, pass)) ?: return@withContext null
        val u = runCatching { JSONObject(js).getJSONObject("response").getJSONObject("ssuser") }.getOrNull() ?: return@withContext null
        if (u.optString("niveau").isBlank()) return@withContext null
        Account(u.optString("id", user), u.optString("niveau").toIntOrNull() ?: 0,
            u.optString("requeststoday").toIntOrNull() ?: 0, u.optString("maxrequestsperday").toIntOrNull() ?: 0)
    }

    // ---------------------------------------------------------------- games

    data class Media(val type: String, val url: String, val region: String)
    data class Entry(val id: String, val name: String?, val synopsis: String?, val media: List<Media>, val info: GameInfo?)

    /** One lookup per ROM file per run (artwork kinds, description and the manual all come from the same answer). */
    private val cache = HashMap<String, Entry?>()
    private val lock = Mutex()

    /**
     * The game for the ROM at [path] (a [platform] game called [title]): by checksum and file name first, else by
     * title. Null when ScreenScraper doesn't know it (or isn't set up).
     */
    suspend fun lookup(user: String, pass: String, platform: String, path: String, title: String): Entry? {
        if (!built) return null
        val key = "$platform|$path|$title"
        lock.withLock { if (cache.containsKey(key)) return cache[key] }
        val e = withContext(Dispatchers.IO) { runCatching { find(user, pass, platform, path, title) }.getOrNull() }
        lock.withLock { cache[key] = e }
        return e
    }

    private suspend fun find(user: String, pass: String, platform: String, path: String, title: String): Entry? {
        val sys = SYSTEMS[platform]?.takeIf { it > 0 }
        val f = File(path)
        val base = StringBuilder(API + "jeuInfos.php?" + auth(user, pass) + "&romtype=rom")
        if (sys != null) base.append("&systemeid=").append(sys)
        if (f.isFile) {
            base.append("&romnom=").append(Http.enc(f.name)).append("&romtaille=").append(f.length())
            Hasheous.hashes(path)?.let { (crc, md5) ->
                crc?.let { base.append("&crc=").append(it.uppercase()) }
                md5?.let { base.append("&md5=").append(it) }
            }
            Http.get(base.toString())?.let { parse(it) }?.let { return it }
        }
        // not known by its file: search by name, then fetch the best hit in full
        if (title.isBlank()) return null
        val search = StringBuilder(API + "jeuRecherche.php?" + auth(user, pass) + "&recherche=" + Http.enc(title))
        if (sys != null) search.append("&systemeid=").append(sys)
        val js = Http.get(search.toString()) ?: return null
        val jeux = runCatching { JSONObject(js).getJSONObject("response").optJSONArray("jeux") }.getOrNull() ?: return null
        val id = (0 until jeux.length()).mapNotNull { jeux.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }.firstOrNull()
            ?: return null
        val full = API + "jeuInfos.php?" + auth(user, pass) + "&gameid=" + Http.enc(id) + (sys?.let { "&systemeid=$it" } ?: "")
        return Http.get(full)?.let { parse(it) }
    }

    private fun parse(js: String): Entry? {
        val jeu = runCatching { JSONObject(js).getJSONObject("response").getJSONObject("jeu") }.getOrNull() ?: return null
        val id = jeu.optString("id").ifBlank { return null }
        val name = byRegion(jeu.optJSONArray("noms"))
        val synopsis = byLang(jeu.optJSONArray("synopsis"))
        val medias = jeu.optJSONArray("medias")
        val media = if (medias == null) emptyList() else (0 until medias.length()).mapNotNull { i ->
            val m = medias.optJSONObject(i) ?: return@mapNotNull null
            val url = m.optString("url").ifBlank { return@mapNotNull null }
            Media(m.optString("type"), url, m.optString("region"))
        }
        val year = byRegion(jeu.optJSONArray("dates"))?.take(4)?.takeIf { it.all(Char::isDigit) }
        val genres = jeu.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optJSONArray("noms")?.let { n -> byLang(n) } }.take(3).joinToString(" · ")
        }?.ifBlank { null }
        val rating = jeu.optJSONObject("note")?.optString("text")?.toDoubleOrNull()?.let { GameInfo.rating(it, 20.0) }
        val info = GameInfo(
            year, jeu.optJSONObject("developpeur")?.optString("text")?.ifBlank { null },
            jeu.optJSONObject("editeur")?.optString("text")?.ifBlank { null }, genres,
            jeu.optJSONObject("joueurs")?.optString("text")?.ifBlank { null }, rating,
        )
        return Entry(id, name, synopsis, media, info.takeIf { !it.isEmpty })
    }

    /** The text for the first region in [REGIONS] that has one, else any. */
    private fun byRegion(arr: JSONArray?): String? {
        if (arr == null) return null
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        for (r in REGIONS) all.firstOrNull { it.optString("region") == r }?.optString("text")?.ifBlank { null }?.let { return it }
        return all.firstOrNull()?.optString("text")?.ifBlank { null }
    }

    /** The English text, else any. */
    private fun byLang(arr: JSONArray?): String? {
        if (arr == null) return null
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        return (all.firstOrNull { it.optString("langue") == "en" } ?: all.firstOrNull())?.optString("text")?.ifBlank { null }
    }

    /** The pictures of kind [k] for [e], your region first. */
    fun art(e: Entry, k: ArtKind): List<ArtOption> {
        val types = mediaTypes(k)
        if (types.isEmpty()) return emptyList()
        return types.flatMap { t ->
            e.media.filter { it.type == t }.sortedBy { m -> REGIONS.indexOf(m.region).let { if (it < 0) REGIONS.size else it } }
        }.map { ArtOption(it.url, "ScreenScraper") }
    }

    /** The instruction manual's PDF address for [e], or null. */
    fun manual(e: Entry): String? =
        e.media.filter { it.type == "manuel" }.minByOrNull { m -> REGIONS.indexOf(m.region).let { if (it < 0) REGIONS.size else it } }?.url
}
