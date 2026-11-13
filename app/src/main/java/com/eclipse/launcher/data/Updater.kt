package com.eclipse.launcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Eclipse's own updater: the same GitHub releases Obtainium reads. Looks up the latest release, compares it with this
 * version, downloads its APK into Eclipse's cache and hands it to Android's installer (which asks you to confirm;
 * Android only installs it over this app when it's signed with the same key).
 */
object Updater {
    private const val REPO = "ProfoundBandit86/eclipse"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    /** Downloads are only ever taken from this repository's releases. */
    private const val ALLOWED = "https://github.com/$REPO/releases/download/"

    data class Release(val version: String, val tag: String, val apkUrl: String, val size: Long, val notes: String)

    /** A release's APK on this repository, or null when it has none (or it's a draft). */
    private fun releaseOf(o: JSONObject): Release? {
        if (o.optBoolean("draft")) return null
        val tag = o.optString("tag_name").ifEmpty { return null }
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val u = a.optString("browser_download_url")
            if (u.endsWith(".apk", true) && u.startsWith(ALLOWED)) return Release(tag.removePrefix("v"), tag, u, a.optLong("size"), o.optString("body").trim())
        }
        return null
    }

    /**
     * The newest version for an update channel: [daily] false = the latest full release (what everyone gets);
     * true = the newest of the full releases and the daily test builds (pre-releases like 0.1.27-daily.4, made from
     * the daily branch). Null when GitHub can't be reached.
     */
    suspend fun latest(daily: Boolean): Release? = if (!daily) latest() else withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Eclipse-launcher")
            }
            try {
                if (c.responseCode != 200) return@runCatching null
                val arr = org.json.JSONArray(c.inputStream.bufferedReader().readText())
                var best: Release? = null
                for (i in 0 until arr.length()) {
                    val r = releaseOf(arr.getJSONObject(i)) ?: continue
                    if (best == null || isNewer(r.version, best.version)) best = r
                }
                best
            } finally { c.disconnect() }
        }.getOrNull()
    }

    /** The latest published (non-pre-release) version, or null when it can't be reached. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Eclipse-launcher")
            }
            try {
                if (c.responseCode != 200) return@runCatching null
                val o = JSONObject(c.inputStream.bufferedReader().readText())
                if (o.optBoolean("prerelease") || o.optBoolean("draft")) return@runCatching null
                val tag = o.optString("tag_name")
                val assets = o.optJSONArray("assets") ?: return@runCatching null
                var url = ""; var size = 0L
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val u = a.optString("browser_download_url")
                    if (u.endsWith(".apk", true) && u.startsWith(ALLOWED)) { url = u; size = a.optLong("size"); break }
                }
                if (url.isEmpty()) null
                else Release(tag.removePrefix("v"), tag, url, size, o.optString("body").trim())
            } finally { c.disconnect() }
        }.getOrNull()
    }

    /**
     * True when [latest] is a higher version than [current] ("0.1.10" > "0.1.9"). A daily build comes before the
     * release it leads up to, and later daily builds after earlier ones: 0.1.27-daily.3 < 0.1.27-daily.4 < 0.1.27 <
     * 0.1.28-daily.1. (Any other "-" suffix counts like a daily build numbered by its digits.)
     */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        // same version number: a full release beats its daily builds; between daily builds, the higher number wins
        fun pre(v: String): Int? = if ('-' !in v) null else v.substringAfter('-').filter(Char::isDigit).takeLast(9).toIntOrNull() ?: 0
        val pa = pre(latest); val pb = pre(current)
        return when {
            pa == null -> pb != null
            pb == null -> false
            else -> pa > pb
        }
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates")

    private fun open(u: URL) = (u.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false; connectTimeout = 10000; readTimeout = 20000
        setRequestProperty("User-Agent", "Eclipse-launcher")
    }

    /** Downloads the release's APK (progress 0..1, or -1 when the size isn't known). Null on failure. */
    suspend fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        if (!r.apkUrl.startsWith(ALLOWED)) return@withContext null
        val d = dir(ctx).apply { deleteRecursively(); mkdirs() }
        val out = File(d, "Eclipse-${r.version}.apk")
        val part = File(d, out.name + ".part")
        runCatching {
            var url = URL(r.apkUrl)
            // GitHub answers with a redirect to its file host: follow it by hand (HTTPS only)
            var c = open(url)
            var hops = 0
            while (c.responseCode in 300..399 && hops++ < 5) {
                val loc = c.getHeaderField("Location") ?: break
                val next = URL(url, loc)
                c.disconnect()
                if (next.protocol != "https") return@runCatching null
                url = next
                c = open(url)
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
                if (!part.renameTo(out)) return@runCatching null
                out
            } finally { c.disconnect() }
        }.getOrNull().also { if (it == null) part.delete() }
    }

    /** Android lets Eclipse open its installer (Settings → Apps → Eclipse → Install unknown apps). */
    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" switch for Eclipse. */
    fun askInstallPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Hands the downloaded APK to Android's installer (it shows its own "update this app?" screen). */
    fun install(ctx: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        ctx.startActivity(i)
        true
    }.getOrDefault(false)

    /** Leftover downloads from an earlier update. */
    fun cleanUp(ctx: Context) { runCatching { dir(ctx).deleteRecursively() } }
}
