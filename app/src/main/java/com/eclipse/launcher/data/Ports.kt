package com.eclipse.launcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native Android ports and recompilations of console and PC games (Ports & recomps screen, and the card on a game's
 * page). The list is catalog/ports.json in Eclipse's repository, built from abduznik/awesome-android-native-ports
 * (CC0) with the system each game came from added; a copy ships in the app (assets/ports/ports.json) and a newer one
 * is fetched from the repository at most once a day. Releases come from GitHub's API, cached for 12 hours.
 */
object Ports {
    data class Port(
        val id: String, val name: String, val game: String, val sys: String, val repo: String,
        val author: String, val desc: String, val dual: Boolean, val kind: String, val pkg: String?
    ) {
        /** "2 Ship 2 Harkinian" from "Majora's Mask (2 Ship 2 Harkinian)"; the game's name when the list gives none. */
        val title: String get() = name.substringAfter("(", "").substringBefore(")").substringBefore(",").trim()
            .takeIf { name.startsWith(game) && it.length > 3 && !it.first().isDigit() && it.lowercase() !in NOT_A_NAME } ?: name
        val url: String get() = "https://github.com/$repo"
    }

    /** The newest release with an APK (or, if none has one, the newest release). */
    data class Release(
        val tag: String, val date: String, val apkUrl: String?, val apkName: String?, val size: Long,
        val notes: String, val page: String
    ) {
        val version: String get() = tag.removePrefix("v").removePrefix("V")
    }

    /** What's in brackets that isn't the port's own name ("Halo: Combat Evolved (Android)" keeps its full name). */
    private val NOT_A_NAME = setOf(
        "android", "android and aaos", "android and quest", "android shell", "android evolved", "phone and quest", "gamecube",
        "xbox", "switch", "dual screen", "enhanced", "plus", "godot", "sampreeth004"
    )

    private const val REMOTE = "https://raw.githubusercontent.com/ProfoundBandit86/eclipse/main/catalog/ports.json"
    private const val DAY = 86_400_000L
    private const val REL_TTL = 12 * 3_600_000L

    private fun dir(ctx: Context) = File(ctx.filesDir, "ports").apply { mkdirs() }

    private fun parse(text: String): List<Port>? = runCatching {
        val a = JSONObject(text).getJSONArray("ports")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Port(
                o.getString("id"), o.getString("name"), o.optString("game", o.getString("name")), o.getString("sys"),
                o.getString("repo"), o.optString("author"), o.optString("desc"), o.optBoolean("dual"),
                o.optString("kind", "port"), o.optString("pkg").ifBlank { null }
            )
        }.filter { it.repo.count { c -> c == '/' } == 1 }
    }.getOrNull()

    /** The list: the one fetched last if there is one, else the copy in the app. */
    fun load(ctx: Context): List<Port> {
        val saved = File(dir(ctx), "ports.json")
        if (saved.isFile) parse(saved.readText())?.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching { ctx.assets.open("ports/ports.json").bufferedReader().use { it.readText() } }.getOrNull()?.let(::parse).orEmpty()
    }

    /** Fetches the newest list (when the last try was over a day ago, or [force]); null when nothing changed. */
    suspend fun refresh(ctx: Context, force: Boolean = false): List<Port>? = withContext(Dispatchers.IO) {
        val stamp = File(dir(ctx), "checked")
        if (!force && System.currentTimeMillis() - stamp.lastModified() < DAY) return@withContext null
        stamp.writeText("")
        val text = get(REMOTE) ?: return@withContext null
        val list = parse(text)?.takeIf { it.size >= 10 } ?: return@withContext null
        File(dir(ctx), "ports.json").writeText(text)
        list
    }

    private fun open(u: URL) = (u.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false; connectTimeout = 10000; readTimeout = 20000
        setRequestProperty("User-Agent", "Eclipse-launcher")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun get(url: String): String? = runCatching {
        val c = open(URL(url))
        try {
            if (c.responseCode == 403 || c.responseCode == 429) {
                c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.let { limitedUntil = it * 1000 }
                return@runCatching null
            }
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }.getOrNull()

    /** GitHub said "too many requests" (60 an hour without a sign-in): no more asking until then. */
    @Volatile var limitedUntil = 0L
        private set
    fun limited(): Boolean = System.currentTimeMillis() < limitedUntil

    /** Picks the APK a phone wants: arm64 first, then a universal one; never x86 or 32-bit only when there's a choice. */
    private fun pickApk(assets: JSONArray): JSONObject? {
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk", true) }
        if (apks.isEmpty()) return null
        fun score(n: String): Int {
            val s = n.lowercase()
            return when {
                "arm64" in s || "aarch64" in s || "v8a" in s -> 0
                "x86" in s -> 9
                "armv7" in s || "armeabi" in s || "arm32" in s -> 5
                "debug" in s -> 4
                else -> 1
            }
        }
        return apks.minByOrNull { score(it.optString("name")) }
    }

    private fun relOf(o: JSONObject): Release {
        val apk = pickApk(o.optJSONArray("assets") ?: JSONArray())
        return Release(
            o.optString("tag_name").ifBlank { o.optString("name") }, o.optString("published_at").take(10),
            apk?.optString("browser_download_url"), apk?.optString("name"), apk?.optLong("size") ?: 0L,
            o.optString("body"), o.optString("html_url")
        )
    }

    private fun relToJson(r: Release) = JSONObject().put("tag", r.tag).put("date", r.date).put("apk", r.apkUrl ?: "")
        .put("apkName", r.apkName ?: "").put("size", r.size).put("notes", r.notes).put("page", r.page)

    private fun relFromJson(o: JSONObject) = Release(
        o.optString("tag"), o.optString("date"), o.optString("apk").ifBlank { null }, o.optString("apkName").ifBlank { null },
        o.optLong("size"), o.optString("notes"), o.optString("page")
    )

    /** What's cached for [p] (even if old), without asking GitHub. */
    fun cached(ctx: Context, p: Port): Release? = runCatching {
        val f = File(dir(ctx), "rel_" + p.id.replace('/', '_') + ".json")
        if (!f.isFile) null else relFromJson(JSONObject(f.readText()))
    }.getOrNull()

    /**
     * The newest release of [p]: from the cache when it's under 12 hours old (unless [force]), else from GitHub
     * (the last few releases, so a project whose newest release is a pre-release or has no APK still finds one).
     * A release with a blank tag when it has none; null when GitHub couldn't be asked and nothing is cached.
     */
    suspend fun latest(ctx: Context, p: Port, force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val f = File(dir(ctx), "rel_" + p.id.replace('/', '_') + ".json")
        if (!force && f.isFile && System.currentTimeMillis() - f.lastModified() < REL_TTL) cached(ctx, p)?.let { return@withContext it }
        if (limited()) return@withContext cached(ctx, p)
        val text = get("https://api.github.com/repos/${p.repo}/releases?per_page=6") ?: return@withContext cached(ctx, p)
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return@withContext cached(ctx, p)
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("draft") }
        val pick = all.firstOrNull { pickApk(it.optJSONArray("assets") ?: JSONArray()) != null } ?: all.firstOrNull()
        // no releases at all: remembered as a release without a tag (the screen says "No releases yet")
        val r = pick?.let(::relOf) ?: Release("", "", null, null, 0, "", p.url)
        f.writeText(relToJson(r).toString())
        r
    }

    /** Downloads [r]'s APK into the cache (progress 0..1, or -1 when the size isn't known). Null on failure. */
    suspend fun download(ctx: Context, p: Port, r: Release, progress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        val src = r.apkUrl ?: return@withContext null
        if (!src.startsWith("https://github.com/")) return@withContext null
        val d = File(ctx.cacheDir, "ports").apply { mkdirs() }
        val out = File(d, p.id.replace('/', '_') + ".apk")
        val part = File(d, out.name + ".part")
        runCatching {
            var url = URL(src)
            var c = open(url).apply { setRequestProperty("Accept", "application/octet-stream") }
            var hops = 0
            // GitHub sends the file from its download host: follow the redirects by hand (HTTPS only)
            while (c.responseCode in 300..399 && hops++ < 6) {
                val loc = c.getHeaderField("Location") ?: break
                val next = URL(url, loc)
                c.disconnect()
                if (next.protocol != "https") return@runCatching null
                url = next
                c = open(url).apply { setRequestProperty("Accept", "application/octet-stream") }
            }
            try {
                if (c.responseCode != 200) return@runCatching null
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                var done = 0L
                c.inputStream.use { i ->
                    part.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = i.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            progress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                        }
                    }
                }
                if (total > 0 && done != total) return@runCatching null
                out.delete()
                if (!part.renameTo(out)) return@runCatching null
                out
            } finally { c.disconnect() }
        }.getOrNull().also { if (it == null) part.delete() }
    }

    /** The Android id and version of a downloaded APK. */
    fun apkInfo(ctx: Context, apk: File): Pair<String, String>? = runCatching {
        @Suppress("DEPRECATION")
        val pi = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        pi.packageName to (pi.versionName ?: "")
    }.getOrNull()

    /** An installed app's version name, or null when it isn't installed. */
    fun installedVersion(ctx: Context, pkg: String): String? = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: ""
    }.getOrNull()

    // ---- matching a port to a game in the library, and to an installed app ----
    private val ROMAN = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9")
    private val SKIP = setOf("the", "of", "a", "an", "and", "legend", "zelda", "android", "port", "recomp", "recompiled", "native", "edition")

    fun words(s: String): List<String> = s.lowercase()
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace("&", " and ").replace("'", "")
        .split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.map { ROMAN[it] ?: it }

    private fun key(s: String) = words(s).filter { it !in SKIP }.toSet()

    /** Systems that count as the same for matching (a Genesis port for a Mega Drive game). */
    private val FAMILY = listOf(setOf("genesis", "md"), setOf("snes", "sfc"), setOf("nes", "fc"), setOf("windows", "steam", "dos"), setOf("gba", "gba-hacks"), setOf("gbc", "gbc-hacks"), setOf("gb", "gb-hacks"))
    fun sameSystem(a: String, b: String): Boolean = a == b || FAMILY.any { a in it && b in it }

    /**
     * Whether [p] is a port of the game called [name] on [system]: same system family, and the important words match
     * (all of the port's game words are in the name, and the numbers agree, so "Super Mario 64" isn't "Super Mario 64 DS").
     */
    fun matches(p: Port, name: String, system: String): Boolean {
        if (!sameSystem(p.sys, system)) return false
        val g = key(p.game); if (g.isEmpty()) return false
        val n = key(name)
        if (!n.containsAll(g)) return false
        val extra = n - g
        // a different number, or a word like "DS" / "Deluxe" that makes it another game, is another game
        if (extra.any { it.all(Char::isDigit) } && g.none { it.all(Char::isDigit) }) return false
        return extra.size <= 2
    }

    /** Whether the installed app labelled [label] looks like [p] (its own name, or its repository's name). */
    fun looksLike(p: Port, label: String): Boolean {
        val l = key(label); if (l.isEmpty()) return false
        val t = key(p.title)
        val r = key(p.repo.substringAfter('/').replace(Regex("(?i)[-_]?android"), ""))
        return (t.isNotEmpty() && l == t) || (r.isNotEmpty() && l == r)
    }
}
