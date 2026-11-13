package com.eclipse.launcher.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * RomM (https://romm.app): your self-hosted ROM library.
 *
 * Talks to RomM's REST API with HTTP Basic sign-in (your RomM username and password). Written to cope with both the
 * 3.x and 4.x APIs: lists may come back as a plain array or as a page ({"items": [...]}), and a couple of field and
 * parameter names that changed are tried both ways.
 */
object RomM {
    data class Platform(val id: Int, val slug: String, val fsSlug: String, val name: String)

    /** One game on the server. [fsName] is its file (or folder, when [multi]) name. */
    data class Rom(
        val id: Int,
        val platformId: Int,
        val name: String,
        val fsName: String,
        val multi: Boolean,
        val sizeBytes: Long,
        val summary: String?,
        val cover: String?,
        val screenshot: String?,
        /** extra screenshots for the game's page */
        val shots: List<String> = emptyList(),
        val year: Int? = null,
        val genres: List<String> = emptyList(),
        /** when it was added to the server (for "Recently added") */
        val addedMs: Long = 0L,
        /** Eclipse's id for the system, and RomM's name for it (filled in by the sync) */
        val bp: String = "",
        val platformName: String = "",
        /** critic / user rating, 0..100 (from IGDB or ScreenScraper via RomM), when the server has one */
        val rating: Float? = null,
        /** developers / publishers, when the server's metadata has them (search finds games by them) */
        val companies: List<String> = emptyList()
    ) {
        fun encode(): String = JSONObject()
            .put("id", id).put("p", platformId).put("n", name).put("fs", fsName).put("m", multi).put("sz", sizeBytes)
            .put("sum", summary ?: "").put("cov", cover ?: "").put("shot", screenshot ?: "")
            .put("shots", JSONArray(shots)).put("y", year ?: 0).put("g", JSONArray(genres)).put("add", addedMs)
            .put("bp", bp).put("pn", platformName).put("r", rating?.toDouble() ?: -1.0).put("co", JSONArray(companies)).toString()

        companion object {
            fun decode(s: String): Rom? = try {
                val o = JSONObject(s)
                fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList()
                Rom(
                    o.getInt("id"), o.optInt("p"), o.getString("n"), o.getString("fs"), o.optBoolean("m"), o.optLong("sz"),
                    o.optString("sum").ifEmpty { null }, o.optString("cov").ifEmpty { null }, o.optString("shot").ifEmpty { null },
                    strs("shots"), o.optInt("y").takeIf { it > 0 }, strs("g"), o.optLong("add"), o.optString("bp"), o.optString("pn"),
                    o.optDouble("r", -1.0).takeIf { it >= 0 }?.toFloat(), strs("co")
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    data class Save(val fileName: String, val downloadPath: String, val updatedMs: Long)

    class Conn(rawUrl: String, user: String, pass: String) {
        val base: String = rawUrl.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
        val auth: String = "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        fun url(path: String) = base + path
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Writes a game's name and description back to the server (RomM's "update game" call, PUT /api/roms/{id},
     * sent as a form). True when the server accepted it.
     */
    suspend fun updateRom(
        c: Conn, id: Int, name: String, summary: String,
        /** box art to upload (a picture on this device) */
        artwork: File? = null,
        /** or a web address RomM fetches the cover from itself */
        coverUrl: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val b = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("name", name)
                .addFormDataPart("summary", summary)
            if (artwork != null && artwork.isFile) {
                val type = WebpShrink.mimeOf(artwork) // (from the bytes: shrunk pictures keep their old names)
                b.addFormDataPart("artwork", artwork.name, artwork.asRequestBody(type.toMediaType()))
            } else if (coverUrl != null && coverUrl.startsWith("http")) b.addFormDataPart("url_cover", coverUrl)
            val body = b.build()
            val req = Request.Builder().url(c.url("/api/roms/$id")).header("Authorization", c.auth).put(body).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Adds a screenshot to a RomM game (the game's backdrop). Tries RomM 5's POST /api/roms/{id}/screenshots, then the
     * older POST /api/screenshots?rom_id=…. True when one took it.
     */
    suspend fun uploadScreenshot(c: Conn, id: Int, bytes: ByteArray, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val type = (if (fileName.endsWith(".png", true)) "image/png" else if (fileName.endsWith(".webp", true)) "image/webp" else "image/jpeg").toMediaType()
        fun send(path: String, field: String): Boolean = try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart(field, fileName, bytes.toRequestBody(type)).build()
            val req = Request.Builder().url(c.url(path)).header("Authorization", c.auth).post(body).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
        send("/api/roms/$id/screenshots", "screenshots") || send("/api/roms/$id/screenshots", "screenshotFiles") ||
            send("/api/screenshots?rom_id=$id", "screenshotFile")
    }

    /** A picture's bytes: a file on this device or a web address. Null when it can't be read. */
    suspend fun pictureBytes(src: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (src.startsWith("http")) client.newCall(Request.Builder().url(src).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
            else File(src.removePrefix("file://")).takeIf { it.isFile }?.readBytes()
        } catch (e: Exception) { null }
    }

    /**
     * Asks RomM to scan its library for new files (after an upload), just the platform folder [folder] when it can.
     * Newer RomM: POST /api/tasks/scan (a quick scan of that platform). Older RomM: POST /api/tasks/run/scan_library.
     * Needs an admin account (the "run tasks" permission). Returns (task id or null, null) when the scan was queued,
     * else (null, what went wrong).
     */
    suspend fun scan(c: Conn, folder: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val json = "application/json".toMediaType()
        fun post(path: String, body: String?): Pair<Int, String> = try {
            val rb = (body ?: "").toRequestBody(json)
            val req = Request.Builder().url(c.url(path)).header("Authorization", c.auth).header("Accept", "application/json").post(rb).build()
            client.newCall(req).execute().use { r -> r.code to (r.body?.string() ?: "") }
        } catch (e: Exception) { -1 to (e.message ?: "") }
        fun taskId(t: String) = runCatching { JSONObject(t).optString("task_id").ifEmpty { null } }.getOrNull()

        // the platform's RomM id, if RomM already has it; else its folder name (a platform RomM hasn't seen yet)
        val pid = platforms(c)?.firstOrNull { it.fsSlug.equals(folder, true) || it.slug.equals(folder, true) }?.id
        val bodies = listOfNotNull(
            if (pid != null) JSONObject().put("type", "quick").put("platforms", JSONArray().put(pid)).toString()
            else JSONObject().put("type", "quick").put("platform_fs_slugs", JSONArray().put(folder)).toString(),
            JSONObject().put("type", "quick").toString() // a RomM without platform_fs_slugs: the whole library
        )
        for (b in bodies) {
            val (code, text) = post("/api/tasks/scan", b)
            when {
                code in 200..299 -> return@withContext taskId(text) to null
                code == 409 -> return@withContext null to null // a scan is already running: it'll pick the game up
                code == 422 -> continue // this RomM doesn't know one of the fields: try plainer
                code == 401 || code == 403 -> return@withContext null to "RomM only lets admin accounts start a scan"
                code == 503 -> return@withContext null to "RomM's task worker isn't running"
                else -> break // no such endpoint (older RomM): the scheduled task instead
            }
        }
        val (code, text) = post("/api/tasks/run/scan_library", null)
        when {
            code in 200..299 -> taskId(text) to null
            code == 409 -> null to null
            code == 401 || code == 403 -> null to "RomM only lets admin accounts start a scan"
            code == -1 -> null to "Couldn't reach RomM"
            else -> null to "This RomM can't be asked to scan (HTTP $code)"
        }
    }

    /** A queued task's state ("queued", "started", "finished", "failed" …), or null if it can't be read. */
    suspend fun taskStatus(c: Conn, id: String): String? = (getJson(c, "/api/tasks/$id") as? JSONObject)?.optString("status")?.ifEmpty { null }

    private suspend fun getJson(c: Conn, path: String): Any? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(c.url(path)).header("Authorization", c.auth).header("Accept", "application/json").build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val t = r.body?.string()?.trim() ?: return@withContext null
                if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** A list endpoint's items, whether the server sent a plain array or a page. */
    private fun items(x: Any?): JSONArray? = when (x) {
        is JSONArray -> x
        is JSONObject -> x.optJSONArray("items")
        else -> null
    }

    /** Signs in and reads the server version. Returns (ok, message). */
    suspend fun test(c: Conn): Pair<Boolean, String> {
        val beat = getJson(c, "/api/heartbeat") as? JSONObject
            ?: return false to "Couldn't reach RomM at ${c.base}. Check the address, and that this device is on the same network."
        val version = beat.optJSONObject("SYSTEM")?.optString("VERSION")?.ifEmpty { null }
            ?: beat.optString("VERSION").ifEmpty { null } ?: "unknown version"
        // the heartbeat doesn't need a sign-in, so ask for something that does
        if (items(getJson(c, "/api/platforms")) == null) return false to "Reached RomM ($version), but the sign-in was refused. Check your username and password."
        return true to "Connected to RomM $version"
    }

    suspend fun platforms(c: Conn): List<Platform>? {
        val arr = items(getJson(c, "/api/platforms")) ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Platform(
                o.optInt("id"), o.optString("slug"), o.optString("fs_slug").ifEmpty { o.optString("slug") },
                o.optString("display_name").ifEmpty { o.optString("name") }
            )
        }
    }

    /** All games on one platform, a page at a time. */
    suspend fun roms(c: Conn, platformId: Int): List<Rom>? {
        val out = ArrayList<Rom>()
        var offset = 0
        val page = 250
        while (true) {
            val raw = getJson(c, "/api/roms?platform_id=$platformId&platform_ids=$platformId&limit=$page&offset=$offset&order_by=name")
            val arr = items(raw) ?: return if (out.isEmpty()) null else out
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> parseRom(c, o)?.takeIf { it.platformId == platformId || it.platformId == 0 }?.let(out::add) }
            // a plain array means no paging; a short page means that was the last one
            if (raw is JSONArray || arr.length() < page) break
            offset += page
            if (offset > 50_000) break
        }
        return out
    }

    private fun parseRom(c: Conn, o: JSONObject): Rom? {
        val id = o.optInt("id").takeIf { it > 0 } ?: return null
        val fs = o.optString("fs_name").ifEmpty { o.optString("file_name") }.ifEmpty { return null }
        val name = o.optString("name").ifEmpty { o.optString("fs_name_no_tags") }.ifEmpty { fs.substringBeforeLast('.') }
        val multi = o.optBoolean("multi", false) || o.optBoolean("has_multiple_files", false)
        val cover = o.optString("url_cover").takeIf { it.startsWith("http") }
            ?: o.optString("path_cover_large").ifEmpty { o.optString("path_cover_l") }.ifEmpty { null }?.let { resource(c, it) }
        val shot = o.optJSONArray("url_screenshots")?.optString(0)?.takeIf { it.startsWith("http") }
            ?: o.optJSONArray("merged_screenshots")?.optString(0)?.ifEmpty { null }?.let { if (it.startsWith("http")) it else resource(c, it) }
        val shots = ArrayList<String>()
        o.optJSONArray("url_screenshots")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.startsWith("http") }?.let(shots::add) }
        if (shots.isEmpty()) o.optJSONArray("merged_screenshots")?.let { a ->
            for (i in 0 until a.length()) a.optString(i).ifEmpty { null }?.let { shots.add(if (it.startsWith("http")) it else resource(c, it)) }
        }
        // genres: plain strings on 3.x, or inside the metadata block / as objects on newer servers
        val genres = ArrayList<String>()
        val ga = o.optJSONArray("genres") ?: o.optJSONObject("metadatum")?.optJSONArray("genres") ?: o.optJSONObject("igdb_metadata")?.optJSONArray("genres")
        ga?.let { a -> for (i in 0 until a.length()) (a.optJSONObject(i)?.optString("name") ?: a.optString(i)).takeIf { it.isNotBlank() }?.let(genres::add) }
        // developers / publishers: strings or objects, on the rom or in its metadata block
        val companies = ArrayList<String>()
        val ca = o.optJSONArray("companies") ?: o.optJSONObject("metadatum")?.optJSONArray("companies") ?: o.optJSONObject("igdb_metadata")?.optJSONArray("companies")
        ca?.let { a -> for (i in 0 until a.length()) (a.optJSONObject(i)?.optString("name") ?: a.optString(i)).takeIf { it.isNotBlank() }?.let(companies::add) }
        // release date: seconds or milliseconds since 1970, from the rom or its metadata
        val rel = o.optLong("first_release_date", 0L).takeIf { it > 0 }
            ?: o.optJSONObject("metadatum")?.optLong("first_release_date", 0L)?.takeIf { it > 0 }
            ?: o.optJSONObject("igdb_metadata")?.optLong("first_release_date", 0L)?.takeIf { it > 0 }
        val year = rel?.let { r -> val ms = if (r < 100_000_000_000L) r * 1000 else r; java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.YEAR) }
        // rating: newer servers average their sources (0-10 or 0-100); 3.x has IGDB's total_rating; ScreenScraper scores 0-20
        fun num(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }?.takeIf { !it.isNaN() && it > 0 }
        val rating = (num(o.optJSONObject("metadatum")?.opt("average_rating")) ?: num(o.opt("average_rating")))?.let { if (it <= 10) it * 10 else it }
            ?: num(o.optJSONObject("igdb_metadata")?.opt("total_rating"))
            ?: num(o.optJSONObject("igdb_metadata")?.opt("aggregated_rating"))
            ?: num(o.optJSONObject("ss_metadata")?.opt("ss_score"))?.let { if (it <= 20) it * 5 else it }
        return Rom(
            id, o.optInt("platform_id"), name, fs, multi,
            o.optLong("fs_size_bytes", o.optLong("file_size_bytes", 0L)),
            o.optString("summary").ifEmpty { null }, cover, shot ?: shots.firstOrNull(),
            shots.take(6), year, genres.take(4), parseTime(o.optString("created_at")),
            rating = rating?.coerceIn(0.0, 100.0)?.toFloat(), companies = companies.distinct().take(4)
        )
    }

    /** RomM keeps covers under /assets/romm/resources/ (older servers return the path with or without that prefix). */
    private fun resource(c: Conn, path: String): String {
        val p = path.substringBefore('?').trimStart('/')
        return if (p.startsWith("assets/")) c.url("/$p") else c.url("/assets/romm/resources/$p")
    }

    /**
     * Downloads a game into [destDir]. A single file is saved as is; a multi-file game (RomM sends a zip) is unpacked
     * into a folder named after it. Returns the file to launch, or null. [progress] gets 0..1 (or -1 when the size is unknown).
     */
    /**
     * A file name the server sent, made safe to use on this device: only its last part (no folders, no "..") and no
     * characters Android's storage refuses. A hacked server can't make Eclipse write outside the folder it chose.
     */
    fun safeName(name: String, fallback: String = "game"): String {
        val last = name.replace('\\', '/').substringAfterLast('/').trim()
        val clean = last.replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim('.', ' ')
        return if (clean.isEmpty() || clean == "." || clean == "..") fallback else clean.take(200)
    }

    suspend fun download(c: Conn, rom: Rom, destDir: File, cancelled: () -> Boolean = { false }, progress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val fsName = safeName(rom.fsName)
        val req = Request.Builder().url(c.url("/api/roms/${rom.id}/content/${enc(rom.fsName)}")).header("Authorization", c.auth).build()
        try {
            client.newCall(req).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null) return@withContext null
                val total = body.contentLength().takeIf { it > 0 } ?: rom.sizeBytes.takeIf { it > 0 } ?: -1L
                val isZip = rom.multi || (r.header("Content-Type") ?: "").contains("zip") && !fsName.lowercase().endsWith(".zip")
                val tmp = File(destDir, fsName + ".part")
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var lastTick = 0L
                        while (true) {
                            if (cancelled()) break
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick > 200) {
                                lastTick = now
                                progress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                            }
                        }
                    }
                }
                if (cancelled()) { tmp.delete(); return@withContext null }
                if (!isZip) {
                    val dest = File(destDir, fsName)
                    dest.delete()
                    if (!tmp.renameTo(dest)) return@withContext null
                    return@withContext dest
                }
                // multi-file game: unpack straight into the system folder (no folder of its own). Read through the zip's
                // directory (ZipFile), not as a stream: RomM's zips (nginx mod_zip) store files uncompressed with sizes
                // after the data, which ZipInputStream can't read (it stopped after creating the first .bin, leaving it empty)
                val made = ArrayList<File>()
                try {
                    java.util.zip.ZipFile(tmp).use { zf ->
                        val entries = zf.entries().toList().filter { !it.isDirectory }
                        val totalBytes = entries.sumOf { it.size.coerceAtLeast(0L) }.coerceAtLeast(1L)
                        var unpacked = 0L
                        for (e in entries) {
                            if (cancelled()) break
                            val out = File(destDir, safeName(e.name, "file"))
                            if (out.extension.equals("m3u", true)) continue // RomM's own playlist isn't wanted
                            zf.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
                            made.add(out)
                            unpacked += e.size.coerceAtLeast(0L)
                            progress((unpacked.toFloat() / totalBytes).coerceIn(0f, 1f))
                        }
                    }
                } catch (e: Exception) {
                    made.forEach { it.delete() }
                    tmp.delete()
                    return@withContext null
                }
                tmp.delete()
                if (cancelled()) { made.forEach { it.delete() }; return@withContext null }
                made.firstOrNull { it.extension.equals("cue", true) } ?: made.maxByOrNull { it.length() }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- saves ----

    private val isoFormats = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSSSS", "yyyy-MM-dd'T'HH:mm:ss")

    private fun parseTime(s: String): Long {
        for (f in isoFormats) {
            try {
                val df = SimpleDateFormat(f, Locale.US)
                if (!f.endsWith("XXX")) df.timeZone = TimeZone.getTimeZone("UTC")
                return df.parse(s)?.time ?: continue
            } catch (e: Exception) {
            }
        }
        return 0L
    }

    /** Your saves for a game on the server (from the game's details, or the saves list on newer servers). */
    suspend fun saves(c: Conn, romId: Int): List<Save> {
        val detail = getJson(c, "/api/roms/$romId") as? JSONObject
        val arr = detail?.optJSONArray("user_saves") ?: items(getJson(c, "/api/saves?rom_id=$romId")) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("file_name").ifEmpty { return@mapNotNull null }
            val path = o.optString("download_path").ifEmpty { "/api/saves/${o.optInt("id")}/content" }
            Save(name, path, parseTime(o.optString("updated_at")))
        }
    }

    suspend fun downloadSave(c: Conn, s: Save, dest: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // a full address only when it's on the RomM server itself: the sign-in header never goes anywhere else
            val url = if (s.downloadPath.startsWith("http")) {
                val host = runCatching { java.net.URI(s.downloadPath).host }.getOrNull()
                val mine = runCatching { java.net.URI(c.url("/")).host }.getOrNull()
                if (host != null && host.equals(mine, ignoreCase = true)) s.downloadPath
                else c.url("/" + (runCatching { java.net.URI(s.downloadPath).rawPath }.getOrNull() ?: "").trimStart('/'))
            } else c.url("/" + s.downloadPath.trimStart('/'))
            client.newCall(Request.Builder().url(url).header("Authorization", c.auth).build()).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null) return@withContext false
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".part")
                tmp.outputStream().use { body.byteStream().copyTo(it) }
                dest.delete()
                tmp.renameTo(dest).also { if (it) dest.setLastModified(s.updatedMs.takeIf { t -> t > 0 } ?: System.currentTimeMillis()) }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Uploads a save file. RomM 3.x expects the form field "saves", newer servers "saveFile"; both are tried. */
    suspend fun uploadSave(c: Conn, romId: Int, emulator: String, f: File): Boolean = withContext(Dispatchers.IO) {
        for (field in listOf("saves", "saveFile")) {
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(field, f.name, f.asRequestBody("application/octet-stream".toMediaType()))
                    .build()
                val req = Request.Builder()
                    .url(c.url("/api/saves?rom_id=$romId&emulator=${enc(emulator)}"))
                    .header("Authorization", c.auth)
                    .post(body).build()
                val ok = client.newCall(req).execute().use { it.isSuccessful }
                if (ok) return@withContext true
            } catch (e: Exception) {
            }
        }
        false
    }
}
