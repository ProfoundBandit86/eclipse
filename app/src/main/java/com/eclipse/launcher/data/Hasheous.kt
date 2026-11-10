package com.eclipse.launcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Hasheous (https://hasheous.org): identifies a game from the ROM file itself, by its hash, using the
 * No-Intro / Redump / TOSEC / MAME databases. Free, no key needed for lookups.
 *
 * A match gives the game's proper name plus exact IGDB and RetroAchievements ids, which Eclipse then uses so the
 * other sources find the right game even when the file name is odd.
 */
object Hasheous {
    private const val API = "https://hasheous.org/api/v1"
    private const val ORIGIN = "https://hasheous.org"
    private const val MAX_HASH_BYTES = 512L * 1024 * 1024 // bigger files (most disc images) are skipped

    data class Match(val name: String, val platform: String?, val igdbId: String?, val raId: Int?, val logo: String?) {
        fun encode(): String = JSONObject()
            .put("n", name).put("p", platform ?: "").put("igdb", igdbId ?: "").put("ra", raId ?: 0).put("logo", logo ?: "")
            .toString()
    }

    fun decode(s: String): Match? {
        if (s.isEmpty()) return null
        return try {
            val o = JSONObject(s)
            Match(
                o.getString("n"), o.optString("p").ifEmpty { null }, o.optString("igdb").ifEmpty { null },
                o.optInt("ra").takeIf { it > 0 }, o.optString("logo").ifEmpty { null }
            )
        } catch (e: Exception) {
            null
        }
    }

    /** (answered, match). answered = false means we couldn't ask (no hash / network error), so don't cache it. */
    suspend fun identify(path: String): Pair<Boolean, Match?> = withContext(Dispatchers.IO) {
        val (crc, md5) = hashes(path) ?: return@withContext false to null
        val headers = mapOf("User-Agent" to "Eclipse-Launcher", "Accept" to "application/json")
        var body: String? = null
        if (md5 != null) body = Http.get("$API/Lookup/ByHash/md5/$md5", headers)
        if (body == null && crc != null) body = Http.get("$API/Lookup/ByHash/crc/$crc", headers)
        // Http.get returns null both for "not found" (404) and for network errors; tell them apart with a health check
        if (body == null) {
            val online = Http.get("$API/HealthCheck", headers) != null
            return@withContext online to null
        }
        true to parse(body)
    }

    private fun parse(body: String): Match? = try {
        val o = JSONObject(body)
        var igdb: String? = null
        var ra: Int? = null
        o.optJSONArray("metadata")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val id = m.optString("immutableId").ifEmpty { null } ?: continue
                when (m.optString("source")) {
                    "IGDB" -> if (igdb == null) igdb = id
                    "RetroAchievements" -> if (ra == null) ra = id.toIntOrNull()
                }
            }
        }
        var logo: String? = null
        o.optJSONArray("attributes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val link = a.optString("link")
                if (a.optString("attributeName") == "Logo" && link.isNotEmpty()) {
                    logo = if (link.startsWith("http")) link else ORIGIN + link
                    break
                }
            }
        }
        val name = o.optString("name")
        if (name.isEmpty()) null
        else Match(name, o.optJSONObject("platform")?.optString("name")?.ifEmpty { null }, igdb, ra, logo)
    } catch (e: Exception) {
        null
    }

    /**
     * (crc32, md5) as lowercase hex.
     * .zip: the CRC of the largest file inside, read from the zip's own index (instant, no unpacking).
     * Other files up to 512 MB: CRC32 + MD5 in one pass. Returns null when there's nothing to hash.
     */
    fun hashes(path: String): Pair<String?, String?>? {
        val f = File(path)
        if (!f.isFile) return null
        if (f.extension.equals("zip", ignoreCase = true)) {
            return try {
                ZipFile(f).use { z ->
                    val e = z.entries().asSequence().filter { !it.isDirectory }.maxByOrNull { it.size } ?: return null
                    if (e.crc < 0) null else "%08x".format(e.crc) to null
                }
            } catch (e: Exception) {
                null
            }
        }
        if (f.length() > MAX_HASH_BYTES || f.extension.lowercase() in setOf("7z", "chd", "rvz", "m3u", "cue")) return null
        return try {
            val crc = CRC32()
            val md5 = MessageDigest.getInstance("MD5")
            val buf = ByteArray(1 shl 20)
            f.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                    md5.update(buf, 0, n)
                }
            }
            "%08x".format(crc.value) to md5.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
}
