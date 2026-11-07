package com.eclipse.launcher.data

import android.content.Context
import android.util.AtomicFile
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * LaunchBox Games Database support.
 *
 * LaunchBox has no public web API. Like the LaunchBox app itself (and RomM), Eclipse downloads the database file
 * (Metadata.zip) and keeps it, plus a small index of the entries that match games in your library, then works offline:
 * box art, fanart backdrops, clear logos, descriptions, and proper titles for MAME-named arcade ROMs.
 *
 * Games added later are looked up in the kept zip ([catchUp]) with nothing downloaded. [update] asks LaunchBox whether
 * its file changed since the copy we have (ETag / Last-Modified) and only downloads it again when it did.
 */
object LaunchBox {
    private const val ZIP_URL = "https://gamesdb.launchbox-app.com/Metadata.zip"
    private const val IMAGES = "https://images.launchbox-app.com/"
    private const val INDEX_FILE = "launchbox.json"
    private const val ZIP_FILE = "launchbox_metadata.zip"

    data class Entry(
        val id: String,
        val platform: String,
        val name: String,
        val overview: String?,
        val box: List<String>,
        val bg: List<String>,
        val logo: List<String>,
        val media: List<String> = emptyList(),
        val box3d: List<String> = emptyList(),
        val hero: List<String> = emptyList(),
        /** the game's video (a YouTube link), when LaunchBox has one */
        val video: String? = null,
        /** release year, developer, publisher, genres, players and rating (only in an index built by this version) */
        val info: com.eclipse.launcher.data.GameInfo? = null
    )

    /** What LaunchBox lists for one game, from its Metadata.xml fields. */
    private fun infoOf(f: Map<String, String>): com.eclipse.launcher.data.GameInfo? {
        fun s(k: String) = f[k]?.trim()?.ifBlank { null }
        val year = s("ReleaseDate")?.take(4)?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } } ?: s("ReleaseYear")
        val info = com.eclipse.launcher.data.GameInfo(
            year, s("Developer"), s("Publisher"), s("Genres")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(3)?.joinToString(" \u00b7 "),
            s("MaxPlayers")?.takeIf { it != "0" }, com.eclipse.launcher.data.GameInfo.rating(s("CommunityRating")?.toDoubleOrNull(), 5.0)
        )
        return info.takeUnless { it.isEmpty }
    }

    @Volatile private var index: Map<String, List<Entry>> = emptyMap()   // folded title -> entries
    @Volatile private var mame: Map<String, String> = emptyMap()         // arcade ROM file name -> real title
    @Volatile var builtAt: Long = 0L
        private set
    /** When we last asked LaunchBox whether its file changed (0 = never). */
    @Volatile var checkedAt: Long = 0L
        private set
    /** The kept zip's ETag / Last-Modified, sent back so LaunchBox can answer "not changed". */
    @Volatile private var etag: String? = null
    @Volatile private var lastModified: String? = null
    /** Every game already looked for (see [keyOf]), found or not: only games outside this need the zip read again. */
    @Volatile private var looked: Set<String> = emptySet()

    private fun zipFile(ctx: Context) = File(ctx.filesDir, ZIP_FILE)
    /** Is the full database kept on the device (so new games need no download)? */
    fun hasZip(ctx: Context): Boolean = zipFile(ctx).let { it.exists() && it.length() > 0 }
    fun zipSize(ctx: Context): Long = zipFile(ctx).takeIf { it.exists() }?.length() ?: 0L

    /** What a game was looked for by: its ROM file for arcade sets (their titles change once found), else its title. */
    private fun keyOf(g: Game): String = if (g.platform in arcadeSystems) "m:" + stem(g) else fold(g.name)

    /** Games LaunchBox hasn't been searched for yet (added since the index was built). */
    fun unlooked(games: List<Game>): List<Game> = games.filter { it.platform != "android" && keyOf(it) !in looked }

    val loaded: Boolean get() = index.isNotEmpty()
    val gameCount: Int get() = index.values.sumOf { it.size }

    /** Arcade-style systems whose ROM files use MAME short names (e.g. simpsons2p). */
    private val arcadeSystems = setOf("arcade", "mame", "fbneo", "cps1", "cps2", "cps3", "neogeo", "naomi", "naomi2", "naomigd", "aw", "model3")

    /** Eclipse system id -> LaunchBox platform name(s). Anything not listed is matched by name similarity. */
    private val lbPlatforms = mapOf(
        "nes" to listOf("Nintendo Entertainment System"), "fc" to listOf("Nintendo Famicom Disk System", "Nintendo Entertainment System"),
        "fds" to listOf("Nintendo Famicom Disk System"), "snes" to listOf("Super Nintendo Entertainment System"),
        "sfc" to listOf("Super Nintendo Entertainment System"), "gb" to listOf("Nintendo Game Boy"),
        "gbc" to listOf("Nintendo Game Boy Color"), "gba" to listOf("Nintendo Game Boy Advance"), "n64" to listOf("Nintendo 64"),
        "nds" to listOf("Nintendo DS"), "3ds" to listOf("Nintendo 3DS"), "gc" to listOf("Nintendo GameCube"), "wii" to listOf("Nintendo Wii"),
        "wiiu" to listOf("Nintendo Wii U"), "switch" to listOf("Nintendo Switch"), "vb" to listOf("Nintendo Virtual Boy"),
        "ps1" to listOf("Sony Playstation"), "ps2" to listOf("Sony Playstation 2"), "ps3" to listOf("Sony Playstation 3"),
        "psp" to listOf("Sony PSP"), "psvita" to listOf("Sony Playstation Vita"), "pspminis" to listOf("Sony PSP Minis", "Sony PSP"),
        "genesis" to listOf("Sega Genesis"), "md" to listOf("Sega Genesis"), "sms" to listOf("Sega Master System"),
        "gg" to listOf("Sega Game Gear"), "saturn" to listOf("Sega Saturn"), "dreamcast" to listOf("Sega Dreamcast"),
        "32x" to listOf("Sega 32X"), "scd" to listOf("Sega CD"), "mcd" to listOf("Sega CD"), "sg1k" to listOf("Sega SG-1000"),
        "pce" to listOf("NEC TurboGrafx-16"), "tg16" to listOf("NEC TurboGrafx-16"), "pccd" to listOf("NEC TurboGrafx-CD"),
        "tgcd" to listOf("NEC TurboGrafx-CD"), "2600" to listOf("Atari 2600"), "5200" to listOf("Atari 5200"),
        "7800" to listOf("Atari 7800"), "lynx" to listOf("Atari Lynx"), "jag" to listOf("Atari Jaguar"), "jagcd" to listOf("Atari Jaguar CD"),
        "ngp" to listOf("SNK Neo Geo Pocket"), "ngpc" to listOf("SNK Neo Geo Pocket Color"), "ngcd" to listOf("SNK Neo Geo CD"),
        "ws" to listOf("WonderSwan"), "wsc" to listOf("WonderSwan Color"), "neogeo" to listOf("Arcade", "SNK Neo Geo AES", "SNK Neo Geo MVS"),
        "arcade" to listOf("Arcade"), "mame" to listOf("Arcade"), "fbneo" to listOf("Arcade"), "cps1" to listOf("Arcade"),
        "cps2" to listOf("Arcade"), "cps3" to listOf("Arcade"), "naomi" to listOf("Sega Naomi", "Arcade"), "aw" to listOf("Sammy Atomiswave", "Arcade"),
        "3do" to listOf("3DO Interactive Multiplayer"), "cv" to listOf("ColecoVision"), "intv" to listOf("Mattel Intellivision"),
        "msx" to listOf("Microsoft MSX", "Microsoft MSX2"), "c64" to listOf("Commodore 64"), "amiga" to listOf("Commodore Amiga"),
        "dos" to listOf("MS-DOS"), "xbox" to listOf("Microsoft Xbox"), "xbox360" to listOf("Microsoft Xbox 360")
    )

    /** LaunchBox's platform name(s) for an Eclipse system (its own name when not listed); none for Favourites / All games. */
    fun platformNames(platform: String, name: String): List<String> =
        if (platform == Platforms.FAVORITES || platform == Platforms.ALL) emptyList() else lbPlatforms[platform] ?: listOf(name)

    /** Title folding so "Legend of Zelda, The - A Link to the Past (USA)" matches "The Legend of Zelda: A Link to the Past". */
    fun fold(title: String): String {
        var t = Scanner.cleanTitle(title).lowercase().replace("&", " and ")
        t = t.replace(Regex("\\b(the|a|an)\\b"), " ")
        return t.replace(Regex("[^a-z0-9]"), "")
    }

    private fun stem(g: Game) = File(g.path).nameWithoutExtension.lowercase()

    /** Real title for an arcade ROM with a MAME short name, if known. */
    fun titleFor(g: Game): String? = if (g.platform in arcadeSystems) mame[stem(g)] else null

    fun lookup(g: Game): Entry? {
        val list = index[fold(titleFor(g) ?: g.name)] ?: return null
        val wanted = lbPlatforms[g.platform]?.map { it.lowercase() }
        if (wanted != null) list.firstOrNull { it.platform.lowercase() in wanted }?.let { return it }
        val ours = Platforms.byId(g.platform)?.name?.let { fold(it) }
        if (ours != null) list.firstOrNull { val p = fold(it.platform); p.contains(ours) || ours.contains(p) }?.let { return it }
        return list.singleOrNull() ?: list.firstOrNull()
    }

    /** Entries whose title holds [query] (only games already matched to the library are kept offline). */
    fun search(query: String, limit: Int = 12): List<MatchHit> {
        val q = fold(query)
        if (q.isEmpty()) return emptyList()
        return index.entries.asSequence().filter { it.key.contains(q) || q.contains(it.key) }
            .flatMap { it.value.asSequence() }.take(limit)
            .map { MatchHit(it.name, "LaunchBox", it.platform) }.toList()
    }

    fun art(e: Entry, kind: ArtKind): List<ArtOption> =
        (when (kind) { ArtKind.BOX -> e.box; ArtKind.BACKDROP -> e.bg; ArtKind.LOGO -> e.logo; ArtKind.MEDIA -> e.media; ArtKind.BOX3D -> e.box3d; ArtKind.HERO -> e.hero; ArtKind.POSTER -> emptyList() }).map { ArtOption(IMAGES + it, "LaunchBox") }

    // ---------------- persistence of the (small) per-library index ----------------

    fun load(ctx: Context) {
        val f = AtomicFile(File(ctx.filesDir, INDEX_FILE))
        if (!f.baseFile.exists()) return
        try {
            val o = JSONObject(String(f.readFully(), Charsets.UTF_8))
            val games = HashMap<String, List<Entry>>()
            val go = o.getJSONObject("games")
            for (k in go.keys()) {
                val arr = go.getJSONArray(k)
                games[k] = List(arr.length()) { i ->
                    val e = arr.getJSONObject(i)
                    Entry(
                        e.getString("id"), e.getString("p"), e.getString("n"), e.optString("o").ifEmpty { null },
                        strs(e.optJSONArray("box")), strs(e.optJSONArray("bg")), strs(e.optJSONArray("logo")),
                        strs(e.optJSONArray("media")), strs(e.optJSONArray("box3d")), strs(e.optJSONArray("hero")),
                        e.optString("v").ifEmpty { null },
                        com.eclipse.launcher.data.GameInfo.fromJson(e.optJSONObject("i"))
                    )
                }
            }
            val mo = o.optJSONObject("mame") ?: JSONObject()
            mame = mo.keys().asSequence().associateWith { mo.getString(it) }
            index = games
            builtAt = o.optLong("built")
            checkedAt = o.optLong("checked")
            etag = o.optString("etag").ifEmpty { null }
            lastModified = o.optString("lastmod").ifEmpty { null }
            looked = o.optJSONArray("looked")?.let { strs(it).toHashSet() } ?: emptySet()
        } catch (e: Exception) {
            // corrupt index: ignore, the user can update again
        }
    }

    private fun save(ctx: Context) {
        val go = JSONObject()
        index.forEach { (k, list) ->
            go.put(k, JSONArray(list.map { e ->
                JSONObject().put("id", e.id).put("p", e.platform).put("n", e.name).put("o", e.overview ?: "")
                    .put("box", JSONArray(e.box)).put("bg", JSONArray(e.bg)).put("logo", JSONArray(e.logo)).put("media", JSONArray(e.media))
                    .put("box3d", JSONArray(e.box3d)).put("hero", JSONArray(e.hero)).put("v", e.video ?: "")
                    .also { o -> e.info?.let { i -> o.put("i", i.toJson()) } }
            }))
        }
        val text = JSONObject().put("built", builtAt).put("checked", checkedAt).put("etag", etag ?: "").put("lastmod", lastModified ?: "")
            .put("looked", JSONArray(looked.toList())).put("games", go).put("mame", JSONObject(mame)).toString()
        val f = AtomicFile(File(ctx.filesDir, INDEX_FILE))
        val out = f.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
        }
    }

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    // ---------------- download + build ----------------

    /** [matched]: your games LaunchBox knows. [downloaded]: false when LaunchBox's file hadn't changed (nothing fetched). */
    data class Result(val matched: Int, val downloaded: Boolean)

    /**
     * Asks LaunchBox whether Metadata.zip changed since the copy we keep, downloads it only if it did (or we have none),
     * then rebuilds the index for [games] from the kept zip. [progress] gets a status line and 0..1. Background thread.
     */
    suspend fun update(ctx: Context, games: List<Game>, progress: (String, Float) -> Unit): Result = withContext(Dispatchers.IO) {
        File(ctx.cacheDir, ZIP_FILE).delete()   // older versions downloaded into the cache and deleted it afterwards
        val zip = zipFile(ctx)
        val have = hasZip(ctx)
        progress(if (have) "Checking for a newer LaunchBox file\u2026" else "Downloading LaunchBox data\u2026", 0.02f)
        val changed = download(zip, if (have) etag else null, if (have) lastModified else null) { done, total ->
            val mb = done / 1_048_576
            progress(if (total > 0) "Downloading LaunchBox data\u2026 $mb / ${total / 1_048_576} MB" else "Downloading LaunchBox data\u2026 $mb MB",
                if (total > 0) done.toFloat() / total * 0.4f else 0.1f)
        }
        checkedAt = System.currentTimeMillis()
        if (changed != null) { etag = changed.first; lastModified = changed.second }
        coroutineContext.ensureActive()
        ZipFile(zip).use { z -> build(z, games, progress) }
        builtAt = System.currentTimeMillis()
        save(ctx)
        Result(gameCount, changed != null)
    }

    /**
     * New games: reads the kept zip again for [games] (no download). Returns the matched count, or null when there is
     * no kept zip (an index from an older version: Update downloads it once).
     */
    suspend fun catchUp(ctx: Context, games: List<Game>, progress: (String, Float) -> Unit): Int? = withContext(Dispatchers.IO) {
        if (!hasZip(ctx)) return@withContext null
        try {
            ZipFile(zipFile(ctx)).use { z -> build(z, games, progress) }
        } catch (e: java.util.zip.ZipException) {
            zipFile(ctx).delete()   // damaged: the next Update downloads a fresh one
            return@withContext null
        }
        builtAt = System.currentTimeMillis()
        save(ctx)
        gameCount
    }

    /**
     * Fetches [ZIP_URL] into [dest], or nothing when LaunchBox says it hasn't changed since [etag] / [since] (HTTP 304).
     * Returns the new (ETag, Last-Modified), or null when not changed. The old file stays until the new one is complete.
     */
    private suspend fun download(dest: File, etag: String?, since: String?, onProgress: (Long, Long) -> Unit): Pair<String?, String?>? {
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(ZIP_URL).apply {
            etag?.let { header("If-None-Match", it) }
            since?.let { header("If-Modified-Since", it) }
        }.build()
        client.newCall(req).execute().use { r ->
            if (r.code == 304) return null
            val body = r.body
            if (!r.isSuccessful || body == null) error("LaunchBox download failed (HTTP ${r.code})")
            val total = body.contentLength()
            val tmp = File(dest.parentFile, dest.name + ".part")
            try {
                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 2_000_000) {
                                lastReport = done
                                coroutineContext.ensureActive()
                                onProgress(done, total)
                            }
                        }
                    }
                }
                if (!tmp.renameTo(dest)) error("Couldn't save the LaunchBox download")
            } finally {
                tmp.delete()
            }
            return r.header("ETag") to r.header("Last-Modified")
        }
    }

    private suspend fun build(z: ZipFile, games: List<Game>, progress: (String, Float) -> Unit) {
        // 1) arcade ROM names -> real titles (only for ROMs you have)
        val wantedStems = games.filter { it.platform in arcadeSystems }.map { stem(it) }.toHashSet()
        val newMame = HashMap<String, String>()
        if (wantedStems.isNotEmpty()) {
            z.getEntry("Mame.xml")?.let { e ->
                progress("Reading arcade titles…", 0.42f)
                parse(z.getInputStream(e), setOf("MameFile"), setOf("FileName", "Name")) { _, f ->
                    val file = f["FileName"]?.lowercase()?.removeSuffix(".zip") ?: return@parse
                    if (file in wantedStems) f["Name"]?.let { newMame[file] = it }
                }
            }
        }
        mame = newMame

        // 2) games: match your library by folded title (and alternate titles), collect their images
        val wanted = games.map { fold(titleFor(it) ?: it.name) }.filter { it.isNotEmpty() }.toHashSet()
        val meta = z.getEntry("Metadata.xml") ?: error("LaunchBox data is missing Metadata.xml")
        val found = HashMap<String, Entry>()                 // db id -> entry (without images yet)
        val altIds = HashMap<String, String>()               // db id -> folded alternate title it matched
        val images = HashMap<String, MutableList<Triple<String, String, String?>>>() // db id -> (type, file, region)
        val fields = setOf("Name", "Platform", "DatabaseID", "Overview", "AlternateName", "FileName", "Type", "Region", "VideoURL",
            "ReleaseDate", "ReleaseYear", "Developer", "Publisher", "Genres", "MaxPlayers", "CommunityRating")

        fun pass(label: String, start: Float, span: Float, onlyIds: Set<String>?) {
            val size = meta.size.takeIf { it > 0 } ?: 1L
            val counting = Counting(z.getInputStream(meta)) { read ->
                progress(label, start + span * (read.toFloat() / size).coerceAtMost(1f))
            }
            parse(counting, setOf("Game", "GameAlternateName", "GameImage"), fields) { tag, f ->
                val id = f["DatabaseID"]?.trim().orEmpty()
                if (id.isEmpty()) return@parse
                when (tag) {
                    "Game" -> if (onlyIds == null) {
                        val k = fold(f["Name"].orEmpty())
                        if (k in wanted) found[id] = Entry(id, f["Platform"].orEmpty(), f["Name"].orEmpty(), f["Overview"], emptyList(), emptyList(), emptyList(), video = f["VideoURL"]?.ifBlank { null }, info = infoOf(f))
                    } else if (id in onlyIds && id !in found) {
                        found[id] = Entry(id, f["Platform"].orEmpty(), f["Name"].orEmpty(), f["Overview"], emptyList(), emptyList(), emptyList(), video = f["VideoURL"]?.ifBlank { null }, info = infoOf(f))
                    }
                    "GameAlternateName" -> if (onlyIds == null) {
                        val k = fold(f["AlternateName"].orEmpty())
                        if (k in wanted) altIds.putIfAbsent(id, k)
                    }
                    "GameImage" -> if (id in found || id in altIds) {
                        val file = f["FileName"] ?: return@parse
                        images.getOrPut(id) { ArrayList() }.add(Triple(f["Type"].orEmpty(), file, f["Region"]))
                    }
                }
            }
        }

        pass("Matching your games…", 0.45f, 0.35f, null)
        coroutineContext.ensureActive()
        // games matched only by an alternate title (or whose images came before them in the file): one more pass
        val missing = altIds.keys.filter { it !in found }.toSet() + found.keys.filter { it !in images }.toSet()
        if (missing.isNotEmpty()) pass("Finishing up…", 0.8f, 0.18f, missing)

        val byFold = HashMap<String, MutableList<Entry>>()
        for ((id, e) in found) {
            val imgs = images[id].orEmpty()
            val entry = e.copy(
                box = pick(imgs, listOf("Box - Front", "Box - Front - Reconstructed", "Fanart - Box - Front")),
                bg = pick(imgs, listOf("Fanart - Background", "Screenshot - Gameplay", "Screenshot - Game Title")),
                logo = pick(imgs, listOf("Clear Logo")),
                media = pick(imgs, listOf("Disc", "Cart - Front", "Cart - 3D")),
                box3d = pick(imgs, listOf("Box - 3D")),
                hero = pick(imgs, listOf("Banner", "Steam Banner"))
            )
            val keys = setOf(fold(e.name)) + listOfNotNull(altIds[id])
            keys.filter { it in wanted }.forEach { byFold.getOrPut(it) { ArrayList() }.add(entry) }
        }
        index = byFold
        looked = games.map { keyOf(it) }.toHashSet()
        progress("Done", 1f)
    }

    /** Files of the given types, best type first, North America / world releases before other regions. */
    private fun pick(imgs: List<Triple<String, String, String?>>, types: List<String>): List<String> {
        fun regionRank(r: String?) = when (r) { null, "", "North America", "United States", "World" -> 0; "Europe" -> 1; else -> 2 }
        return imgs.filter { it.first in types }
            .sortedWith(compareBy({ types.indexOf(it.first) }, { regionRank(it.third) }))
            .map { it.second }.distinct().take(8)
    }

    /** Streams top-level records (<Game>, <GameImage>, …) and hands their wanted child fields to [onRecord]. */
    private fun parse(input: InputStream, tags: Set<String>, wantedFields: Set<String>, onRecord: (String, Map<String, String>) -> Unit) {
        input.use { stream ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(stream, "UTF-8")
            var depth = 0
            var record: String? = null
            var field: String? = null
            var fields = HashMap<String, String>()
            val text = StringBuilder()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth == 2 && p.name in tags) {
                            record = p.name
                            fields = HashMap()
                        } else if (depth == 3 && record != null && p.name in wantedFields) {
                            field = p.name
                            text.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT -> if (field != null) text.append(p.text)
                    XmlPullParser.END_TAG -> {
                        if (depth == 3 && field != null) {
                            fields[field] = text.toString()
                            field = null
                        } else if (depth == 2 && record != null) {
                            onRecord(record, fields)
                            record = null
                        }
                        depth--
                    }
                }
                ev = p.next()
            }
        }
    }

    /** Reports bytes read every ~4 MB (for the progress bar). */
    private class Counting(input: InputStream, private val report: (Long) -> Unit) : FilterInputStream(input) {
        private var read = 0L
        private var last = 0L
        private fun add(n: Long) {
            if (n <= 0) return
            read += n
            if (read - last > 4_000_000) { last = read; report(read) }
        }
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it.toLong()) }
    }
}
