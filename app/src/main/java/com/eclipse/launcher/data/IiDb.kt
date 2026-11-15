package com.eclipse.launcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * iiDB, iiSU's game database (iisu.network/wiki/iidb-developers): box art, iiSU-style box art, logos, backgrounds,
 * heroes and screenshots, with your own API key (iiSU Accounts → profile). The key goes only in the X-API-Key header to
 * the API, never to the picture host. Games are found by title (iiDB has no platform filter, so every match is checked
 * against the game's system); the found game and its pictures are kept in memory, so a game's several kinds of art
 * cost one lookup. Limits are per second, by the account's tier: Eclipse waits when told to.
 * Catalog data: "Catalog data from iiDB (iisu.network), adapted from the Libretro Database, CC BY-SA 4.0" (Credits).
 */
object IiDb {
    private const val BASE = "https://iidb-api.iisu.network/api/v1"
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()
    private val gate = Mutex()
    @Volatile private var pauseUntil = 0L

    /** One game as iiDB knows it: its id and name, and its pictures by slot (best first). */
    data class Entry(val id: String, val name: String, val art: Map<String, List<ArtOption>>)

    private val cache = HashMap<String, Entry?>()

    /** GET / POST with the key; waits out "too many requests" once. Null on any failure. */
    private suspend fun call(key: String, path: String, body: JSONObject? = null): JSONObject? = withContext(Dispatchers.IO) {
        gate.withLock {
            repeat(2) { attempt ->
                val wait = pauseUntil - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                val rb = Request.Builder().url(BASE + path).header("X-API-Key", key).header("Accept", "application/json")
                if (body != null) rb.post(body.toString().toRequestBody(JSON))
                val r = try { client.newCall(rb.build()).execute() } catch (e: Exception) { return@withContext null }
                r.use { resp ->
                    // used up this second's allowance: the next call waits for the next second
                    if (resp.header("X-IIDB-Requests-Remaining-This-Second")?.toIntOrNull() == 0) pauseUntil = System.currentTimeMillis() + 1000
                    when {
                        resp.code == 429 -> {
                            val s = resp.header("Retry-After")?.toLongOrNull() ?: 1
                            pauseUntil = System.currentTimeMillis() + s.coerceIn(1, 30) * 1000
                            if (attempt == 1) return@withContext null
                        }
                        resp.isSuccessful -> return@withContext runCatching { JSONObject(resp.body?.string() ?: "") }.getOrNull()
                        else -> return@withContext null
                    }
                }
            }
            null
        }
    }

    /** Settings → Connections: whether the key works, and its tier ("Connected · <tier>"). */
    suspend fun check(key: String): Pair<Boolean, String> {
        val o = call(key, "/me/access") ?: return false to "Key not accepted, or no connection"
        val tier = o.opt("tier").let { t -> (t as? JSONObject)?.optString("name")?.ifBlank { null } ?: (t as? String) }
        return true to ("Connected" + (tier?.let { " · $it" } ?: ""))
    }

    private val SLOTS = listOf("boxart", "iisu_boxart", "logo", "background", "hero", "screenshot", "icon")

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Every platform name a game record carries (its primary platform, extra ones, the summary), in any shape. */
    private fun platformsOf(o: JSONObject?): List<String> {
        if (o == null) return emptyList()
        val out = ArrayList<String>()
        fun take(v: Any?) {
            when (v) {
                is String -> if (v.isNotBlank()) out += v
                is JSONObject -> listOf("name", "slug", "short_name", "abbreviation", "id", "console_id", "namespace").forEach { k -> v.opt(k)?.let { if (it !is JSONObject) take(it.toString()) } }
                is JSONArray -> for (i in 0 until v.length()) take(v.opt(i))
            }
        }
        listOf("primary_platform", "extra_platforms", "platform", "platforms", "platform_name", "console", "console_id").forEach { take(o.opt(it)) }
        o.optJSONObject("platform_summary")?.let { s -> s.keys().forEach { take(s.opt(it)) } }
        return out
    }

    /** What [system] is called, in every way iiDB might write it (Eclipse's id, name, short name, folder names). */
    private fun ourNames(system: String): Set<String> {
        val p = Platforms.byId(system)
        val base = listOfNotNull(system, p?.name, p?.short, Platforms.originalName(system)) + (p?.aliases ?: emptySet())
        val out = base.map(::norm).filter { it.length >= 2 }.toMutableSet()
        // "Nintendo Entertainment System" also as "nes", "Sony PlayStation 2" also as "playstation2"
        p?.name?.let { n -> out += norm(n.substringAfter(' ')); out += norm(n.split(' ').filter { it.firstOrNull()?.isUpperCase() == true }.joinToString("") { it.take(1) }) }
        return out
    }

    /** A record is for [system] when one of its platform names matches ours; with no platform info, null (unknown). */
    private fun sameSystem(o: JSONObject?, system: String): Boolean? {
        val theirs = platformsOf(o).map(::norm).filter { it.isNotBlank() }
        if (theirs.isEmpty()) return null
        val ours = ourNames(system)
        return theirs.any { t -> t in ours || ours.any { o2 -> o2.length >= 4 && (t.endsWith(o2) || o2.endsWith(t) && t.length >= 4) } }
    }

    /** The pictures in a resolve / assets answer: `artwork` as slot → items, or as a list of items with a type. */
    private fun artOf(o: JSONObject?): Map<String, List<ArtOption>> {
        val a = o?.opt("artwork") ?: return emptyMap()
        val out = LinkedHashMap<String, MutableList<ArtOption>>()
        fun add(slot: String, item: JSONObject) {
            val url = item.optString("url").takeIf { it.startsWith("https://") } ?: return
            val thumb = item.optString("thumbnail_url").takeIf { it.startsWith("https://") } ?: url
            out.getOrPut(slot) { ArrayList() } += ArtOption(url, "iiDB", thumb)
        }
        when (a) {
            is JSONObject -> a.keys().forEach { slot ->
                when (val v = a.opt(slot)) {
                    is JSONArray -> for (i in 0 until v.length()) v.optJSONObject(i)?.let { add(slot, it) }
                    is JSONObject -> (v.optJSONArray("items") ?: v.optJSONArray("candidates"))?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(slot, it) } }
                        ?: add(slot, v)
                }
            }
            is JSONArray -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(it.optString("type").ifBlank { "boxart" }, it) }
        }
        return out
    }

    private fun idOf(o: JSONObject?): String? = o?.let { (it.opt("id") ?: it.opt("parent_id"))?.toString()?.takeIf { s -> s.isNotBlank() && s != "null" } }

    /**
     * The iiDB game for [title] on [system] with its pictures, or null. [region]: "USA", "EU", "JP", "World".
     */
    suspend fun lookup(key: String, system: String, title: String, region: String = "World"): Entry? {
        val ck = "$system|${title.lowercase()}"
        synchronized(cache) { if (cache.containsKey(ck)) return cache[ck] }
        val req = JSONObject()
            .put("title", title).put("title_match", "fuzzy").put("max_candidates", 10)
            .put("artwork", "recommended").put("slots", JSONArray(SLOTS)).put("candidates_per_slot", 6)
            .put("preferred_region", region)
        val r = call(key, "/resolve", req)
        if (r == null) return null // no answer: not remembered, so it's asked again next time
        val match = r.optJSONObject("match")
        val status = match?.optString("status")
        val parent = match?.optJSONObject("parent")
        var found: Entry? = null
        if (status == "matched" && parent != null && sameSystem(parent, system) != false) {
            idOf(parent)?.let { id -> found = Entry(id, parent.optString("name"), artOf(r)) }
        }
        if (found == null) {
            // another candidate on the right system: its pictures by id
            val cands = r.optJSONArray("candidates") ?: match?.optJSONArray("candidates")
            val pick = (0 until (cands?.length() ?: 0)).mapNotNull { cands!!.optJSONObject(it) }.firstOrNull { sameSystem(it, system) == true }
            val id = idOf(pick)
            if (pick != null && id != null) {
                val a = call(key, "/assets/parents/$id?slots=${SLOTS.joinToString(",")}&candidates_per_slot=6&preferred_region=${java.net.URLEncoder.encode(region, "UTF-8")}")
                if (a != null) found = Entry(id, pick.optString("name"), artOf(a))
            }
        }
        synchronized(cache) { cache[ck] = found }
        return found
    }

    /** Eclipse's kinds of art from an iiDB game. */
    fun art(e: Entry, kind: ArtKind): List<ArtOption> {
        fun s(vararg slots: String) = slots.flatMap { e.art[it].orEmpty() }.distinctBy { it.url }
        return when (kind) {
            ArtKind.BOX -> s("boxart", "iisu_boxart")
            ArtKind.POSTER -> s("iisu_boxart")
            ArtKind.BACKDROP -> s("background", "hero", "banner", "screenshot")
            ArtKind.LOGO -> s("logo")
            ArtKind.HERO -> s("hero", "banner", "background")
            else -> emptyList()
        }
    }

    /** Match by hand: iiDB's games for a search (name, platform, year). */
    suspend fun search(key: String, q: String): List<MatchHit> {
        val r = call(key, "/search/parents?q=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=15&assets_only=true") ?: return emptyList()
        val arr = r.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            val plat = platformsOf(o).firstOrNull { it.any(Char::isLetter) } ?: ""
            val year = o.optString("initial_release_year").takeIf { it.isNotBlank() && it != "null" } ?: ""
            MatchHit(o.optString("name"), "iiDB", listOf(plat, year).filter { it.isNotBlank() }.joinToString(" · "))
        }.filter { it.name.isNotBlank() }
    }

    fun forget() = synchronized(cache) { cache.clear() }
}
