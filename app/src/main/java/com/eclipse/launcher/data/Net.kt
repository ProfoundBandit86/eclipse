package com.eclipse.launcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Http {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            try {
                val rb = Request.Builder().url(url)
                headers.forEach { (k, v) -> rb.header(k, v) }
                client.newCall(rb.build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            } catch (e: Exception) {
                null
            }
        }

    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String? =
        withContext(Dispatchers.IO) {
            try {
                val rb = Request.Builder().url(url).post(body.toRequestBody("text/plain".toMediaType()))
                headers.forEach { (k, v) -> rb.header(k, v) }
                client.newCall(rb.build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            } catch (e: Exception) {
                null
            }
        }

    /** Streams [url] into [dest] (via a temp file, so a failed download never leaves a half image). */
    suspend fun download(url: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null) return@withContext false
                val tmp = File(dest.parentFile, dest.name + ".part")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                tmp.renameTo(dest)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** True when [url] answers (a HEAD request: nothing is downloaded). */
    suspend fun exists(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}

/** What IGDB knows about one game; fetched with a single request and reused for every artwork kind + description. */
data class IgdbGame(val coverId: String?, val backdropIds: List<String>, val summary: String?, val videoIds: List<String> = emptyList(), val info: GameInfo? = null)

/**
 * Artwork / metadata scrapers. Each returns an empty result when the service is unreachable or the key is wrong.
 * Lookups are cached per title for the life of the app, so scraping box art, backdrop, logo and description for
 * one game costs 1 SteamGridDB search + 1 request per artwork kind, and a single IGDB request.
 */
object Scrapers {
    private val sgdbIds = HashMap<String, Int>()          // title -> SteamGridDB game id (-1 = no match)
    private val igdbGames = HashMap<String, IgdbGame?>()  // title -> IGDB result (null = no match)

    // ---- SteamGridDB (https://www.steamgriddb.com/api/v2) ----
    private const val SGDB = "https://www.steamgriddb.com/api/v2"

    private suspend fun sgdbId(key: String, title: String): Int? {
        sgdbIds[title]?.let { return if (it < 0) null else it }
        val body = Http.get("$SGDB/search/autocomplete/${Http.enc(title)}", mapOf("Authorization" to "Bearer $key"))
            ?: return null // network error: don't cache, try again next time
        val id = try {
            JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optInt("id", -1) ?: -1
        } catch (e: Exception) { -1 }
        sgdbIds[title] = id
        return if (id < 0) null else id
    }

    /** SteamGridDB's titles for [query] (Match by hand). */
    suspend fun sgdbSearch(key: String, query: String): List<MatchHit> {
        val body = Http.get("$SGDB/search/autocomplete/${Http.enc(query)}", mapOf("Authorization" to "Bearer $key")) ?: return emptyList()
        val arr = try { JSONObject(body).optJSONArray("data") } catch (e: Exception) { null } ?: return emptyList()
        return (0 until minOf(arr.length(), 12)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").ifEmpty { return@mapNotNull null }
            val t = o.optLong("release_date", 0L)
            val year = if (t > 0) java.util.Calendar.getInstance().apply { timeInMillis = t * 1000 }.get(java.util.Calendar.YEAR).toString() else ""
            MatchHit(name, "SteamGridDB", year)
        }
    }

    /** IGDB's titles for [query], with year and systems (Match by hand). */
    suspend fun igdbSearch(id: String, secret: String, query: String): List<MatchHit> {
        val token = igdbAuth(id, secret) ?: return emptyList()
        val q = "search \"${query.replace("\"", "")}\"; fields name,first_release_date,platforms.abbreviation; limit 12;"
        val r = Http.post("https://api.igdb.com/v4/games", q, mapOf("Client-ID" to id, "Authorization" to "Bearer $token")) ?: return emptyList()
        val arr = try { JSONArray(r) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").ifEmpty { return@mapNotNull null }
            val t = o.optLong("first_release_date", 0L)
            val year = if (t > 0) java.util.Calendar.getInstance().apply { timeInMillis = t * 1000 }.get(java.util.Calendar.YEAR).toString() else ""
            val plats = o.optJSONArray("platforms")?.let { a -> (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.optString("abbreviation")?.ifEmpty { null } } }.orEmpty()
            MatchHit(name, "IGDB", listOf(year, plats.take(3).joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · "))
        }
    }

    /** True when SteamGridDB accepts [key] (a tiny search, used for the status dot in Connections). */
    suspend fun sgdbCheck(key: String): Boolean =
        Http.get("$SGDB/search/autocomplete/mario", mapOf("Authorization" to "Bearer $key")) != null

    suspend fun sgdb(key: String, g: Game, kind: ArtKind): List<ArtOption> {
        if (kind == ArtKind.MEDIA || kind == ArtKind.BOX3D) return emptyList() // SteamGridDB has no disc / cartridge or 3D box pictures
        val id = sgdbId(key, g.name) ?: return emptyList()
        val path = when (kind) {
            ArtKind.BOX, ArtKind.POSTER -> "grids/game/$id?dimensions=600x900"
            ArtKind.BACKDROP -> "heroes/game/$id"
            ArtKind.LOGO -> "logos/game/$id"
            ArtKind.HERO -> "heroes/game/$id"
            else -> return emptyList()
        }
        val body = Http.get("$SGDB/$path", mapOf("Authorization" to "Bearer $key")) ?: return emptyList()
        val arr = try { JSONObject(body).optJSONArray("data") } catch (e: Exception) { null } ?: return emptyList()
        val out = ArrayList<ArtOption>()
        for (i in 0 until minOf(arr.length(), 24)) {
            val o = arr.optJSONObject(i) ?: continue
            val full = o.optString("url")
            if (full.isEmpty()) continue
            out.add(ArtOption(full, "SteamGridDB", o.optString("thumb").ifEmpty { full }))
        }
        return out
    }

    // ---- IGDB (Twitch client credentials) ----
    private var igdbToken: String? = null
    private var igdbExpiry = 0L
    private const val IMG = "https://images.igdb.com/igdb/image/upload"

    private suspend fun igdbAuth(id: String, secret: String): String? {
        if (igdbToken != null && System.currentTimeMillis() < igdbExpiry) return igdbToken
        val r = Http.post(
            "https://id.twitch.tv/oauth2/token?client_id=${Http.enc(id)}&client_secret=${Http.enc(secret)}&grant_type=client_credentials",
            ""
        ) ?: return null
        val o = try { JSONObject(r) } catch (e: Exception) { return null }
        igdbToken = o.optString("access_token").ifEmpty { null }
        igdbExpiry = System.currentTimeMillis() + o.optLong("expires_in", 3600L) * 1000L - 60_000L
        return igdbToken
    }

    /** True when Twitch hands out a token for these IGDB credentials. */
    suspend fun igdbCheck(id: String, secret: String): Boolean {
        igdbToken = null // test these credentials, not a token from earlier ones
        return igdbAuth(id, secret) != null
    }

    /** [igdbId] (from Hasheous) gives an exact match; otherwise IGDB is searched by [title]. */
    private suspend fun igdbGame(id: String, secret: String, title: String, igdbId: String? = null): IgdbGame? {
        val cacheKey = igdbId?.let { "#$it" } ?: title
        if (igdbGames.containsKey(cacheKey)) return igdbGames[cacheKey]
        val token = igdbAuth(id, secret) ?: return null
        val fields = "fields name,summary,cover.image_id,artworks.image_id,screenshots.image_id,videos.video_id,first_release_date,genres.name,total_rating,involved_companies.developer,involved_companies.publisher,involved_companies.company.name; limit 1;"
        val q = if (igdbId != null && igdbId.all { it.isDigit() }) "where id = $igdbId; $fields"
        else "search \"${title.replace("\"", "")}\"; $fields"
        val r = Http.post("https://api.igdb.com/v4/games", q, mapOf("Client-ID" to id, "Authorization" to "Bearer $token"))
            ?: return null // network error: don't cache
        val o = try { JSONArray(r).optJSONObject(0) } catch (e: Exception) { null }
        val result = o?.let {
            val backdrops = ArrayList<String>()
            for (field in listOf("artworks", "screenshots")) {
                val a = it.optJSONArray(field) ?: continue
                for (i in 0 until a.length()) {
                    val iid = a.optJSONObject(i)?.optString("image_id").orEmpty()
                    if (iid.isNotEmpty()) backdrops.add(iid)
                }
            }
            val vids = ArrayList<String>()
            it.optJSONArray("videos")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("video_id")?.takeIf { v -> v.isNotEmpty() }?.let { v -> vids.add(v) } }
            // release year, genres, developer / publisher and the rating (out of 100) for the game page
            val released = it.optLong("first_release_date", 0L)
            val year = if (released > 0) java.util.Calendar.getInstance().apply { timeInMillis = released * 1000 }.get(java.util.Calendar.YEAR).toString() else null
            val genres = ArrayList<String>()
            it.optJSONArray("genres")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("name")?.takeIf { n -> n.isNotEmpty() }?.let { n -> genres.add(n) } }
            val devs = ArrayList<String>(); val pubs = ArrayList<String>()
            it.optJSONArray("involved_companies")?.let { a ->
                for (i in 0 until a.length()) {
                    val c = a.optJSONObject(i) ?: continue
                    val name = c.optJSONObject("company")?.optString("name").orEmpty()
                    if (name.isEmpty()) continue
                    if (c.optBoolean("developer")) devs.add(name)
                    if (c.optBoolean("publisher")) pubs.add(name)
                }
            }
            val info = GameInfo(
                year, devs.distinct().take(2).joinToString(", ").ifEmpty { null }, pubs.distinct().take(2).joinToString(", ").ifEmpty { null },
                genres.take(3).joinToString(" \u00b7 ").ifEmpty { null }, null,
                GameInfo.rating(it.optDouble("total_rating", 0.0), 100.0)
            ).takeUnless { i -> i.isEmpty }
            IgdbGame(
                it.optJSONObject("cover")?.optString("image_id")?.ifEmpty { null },
                backdrops,
                it.optString("summary").ifEmpty { null },
                vids,
                info
            )
        }
        igdbGames[cacheKey] = result
        return result
    }

    /** IGDB's year, genres, developer, publisher and rating for [title] (the same request as its artwork, so cached). */
    suspend fun igdbInfo(id: String, secret: String, title: String, igdbId: String? = null): GameInfo? = igdbGame(id, secret, title, igdbId)?.info

    suspend fun igdb(id: String, secret: String, g: Game, kind: ArtKind, igdbId: String? = null): ArtMeta {
        val r = igdbGame(id, secret, g.name, igdbId) ?: return ArtMeta(emptyList(), null)
        val art = when (kind) {
            ArtKind.BOX, ArtKind.POSTER -> listOfNotNull(r.coverId?.let { ArtOption("$IMG/t_720p/$it.jpg", "IGDB", "$IMG/t_cover_big/$it.jpg") })
            ArtKind.BACKDROP -> r.backdropIds.map { ArtOption("$IMG/t_1080p/$it.jpg", "IGDB", "$IMG/t_screenshot_med/$it.jpg") }
            else -> emptyList() // IGDB has no logos, disc, 3D box or hero pictures
        }
        return ArtMeta(art, r.summary)
    }

    /** Logos IGDB has for a system called [name] (Platform artwork). */
    suspend fun igdbPlatformLogos(id: String, secret: String, name: String): List<ArtOption> {
        val token = igdbAuth(id, secret) ?: return emptyList()
        val r = Http.post(
            "https://api.igdb.com/v4/platforms", "search \"${name.replace("\"", "")}\"; fields name,platform_logo.image_id; limit 6;",
            mapOf("Client-ID" to id, "Authorization" to "Bearer $token")
        ) ?: return emptyList()
        val arr = try { JSONArray(r) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val iid = o.optJSONObject("platform_logo")?.optString("image_id").orEmpty().ifEmpty { return@mapNotNull null }
            ArtOption("$IMG/t_logo_med/$iid.png", "IGDB")
        }
    }

    /** The game's trailer on YouTube, from IGDB. */
    suspend fun igdbTrailer(id: String, secret: String, g: Game, igdbId: String? = null): String? =
        igdbGame(id, secret, g.name, igdbId)?.videoIds?.firstOrNull()?.let { "https://www.youtube.com/watch?v=$it" }
}

/** RetroAchievements web API (https://api-docs.retroachievements.org). */
object RetroAch {
    private const val BASE = "https://retroachievements.org/API"
    private val gameLists = HashMap<Int, JSONArray>()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** True when RetroAchievements accepts this username + web API key. */
    suspend fun check(user: String, key: String): Boolean {
        val body = Http.get("$BASE/API_GetUserProfile.php?z=${Http.enc(user)}&y=${Http.enc(key)}&u=${Http.enc(user)}") ?: return false
        return try { JSONObject(body).optString("User").isNotEmpty() } catch (e: Exception) { false }
    }

    /** [knownId] (from Hasheous) is the exact RetroAchievements game; without it the game is matched by title. */
    suspend fun progress(user: String, key: String, g: Game, knownId: Int? = null): RaProgress? {
        val bestId = knownId ?: findGameId(user, key, g) ?: return null
        return fetchProgress(user, key, bestId)
    }

    private suspend fun findGameId(user: String, key: String, g: Game): Int? {
        val console = Platforms.byId(g.platform)?.raConsoleId ?: return null
        val list = gameLists[console] ?: run {
            val body = Http.get("$BASE/API_GetGameList.php?z=${Http.enc(user)}&y=${Http.enc(key)}&i=$console&h=0&f=1") ?: return null
            JSONArray(body).also { gameLists[console] = it }
        }
        val target = norm(g.name)
        var bestId = -1
        var bestLen = Int.MAX_VALUE
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val t = norm(Scanner.cleanTitle(o.optString("Title")))
            if (t == target) { bestId = o.optInt("ID"); break }
            if (t.startsWith(target) && t.length < bestLen) { bestId = o.optInt("ID"); bestLen = t.length }
        }
        return if (bestId < 0) null else bestId
    }

    private suspend fun fetchProgress(user: String, key: String, bestId: Int): RaProgress? {
        val body = Http.get("$BASE/API_GetGameInfoAndUserProgress.php?z=${Http.enc(user)}&y=${Http.enc(key)}&u=${Http.enc(user)}&g=$bestId") ?: return null
        val o = JSONObject(body)
        val achObj = o.optJSONObject("Achievements") ?: return null
        val items = ArrayList<Achievement>()
        val keys = achObj.keys()
        while (keys.hasNext()) {
            val a = achObj.optJSONObject(keys.next()) ?: continue
            val date = a.optString("DateEarnedHardcore").ifEmpty { a.optString("DateEarned") }.ifEmpty { null }
            items.add(
                Achievement(
                    a.optInt("ID"), a.optString("Title"), a.optString("Description"),
                    a.optString("BadgeName"), date != null, date, a.optInt("Points"),
                    a.optString("type").let { if (it == "null") "" else it }
                )
            )
        }
        val earned = items.count { it.earned }
        val sorted = items.sortedWith(compareByDescending<Achievement> { it.earned }.thenByDescending { it.date ?: "" })
        return RaProgress(o.optString("Title"), items.size, earned, sorted)
    }

    fun badgeUrl(badge: String) = "https://media.retroachievements.org/Badge/$badge.png"

}
