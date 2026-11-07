package com.eclipse.launcher

import android.app.Application
import android.os.Build
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import com.eclipse.launcher.data.Platforms
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One shared EclipseVM for the whole app, so the main screen (MainActivity) and the
 * bottom screen (the second-screen Presentation) always see the same games, session and settings.
 */
class EclipseApp : Application(), ImageLoaderFactory {
    val vm: EclipseVM by lazy { EclipseVM(this) }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
        /** Every crash is also kept here (Settings → General → Crash reports), the newest [CRASH_KEEP]. */
        const val CRASH_DIR = "crashes"
        const val CRASH_KEEP = 30
    }

    override fun onCreate() {
        super.onCreate()
        installCrashCatcher()
        try {
            Platforms.init(this) // systems + emulator catalog from assets/systems.json
        } catch (e: Throwable) {
            saveCrash("Loading the emulator catalog failed (Eclipse kept going without it)", e)
        }
    }

    /** One image loader for the whole app, which can also show SVG pictures and play animated GIF / WebP. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(SvgDecoder.Factory())
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(120)                 // short fade: long ones cost frames while scrolling
        .respectCacheHeaders(false)     // cached art is used as is, no re-checking with the server each time
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.30).build() }
        .build()

    /** Saves any crash to a file; MainActivity shows it on the next launch so it can be copied and sent. */
    private fun installCrashCatcher() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            saveCrash("Crash on thread ${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
    }

    fun saveCrash(title: String, e: Throwable) {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = buildString {
                appendLine("Eclipse crash report  $stamp")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
                if (ver.isNotEmpty()) appendLine("Version: $ver")
                val trail = CrashTrail.trail()
                if (trail.isNotEmpty()) appendLine("Screens: " + trail.joinToString(" \u203A "))
                appendLine(title)
                appendLine()
                append(Log.getStackTraceString(e))
            }
            File(filesDir, CRASH_FILE).writeText(text)
            // and kept for Settings → General → Crash reports (the oldest go past CRASH_KEEP)
            val dir = File(filesDir, CRASH_DIR).apply { mkdirs() }
            File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(text)
            dir.listFiles()?.sortedByDescending { it.name }?.drop(CRASH_KEEP)?.forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }
}


/** The last few screens you went to, written into crash reports ("Home › Platforms › SNES"). */
object CrashTrail {
    private val list = ArrayDeque<String>()
    @Synchronized fun note(where: String) {
        if (list.lastOrNull() == where) return
        list.addLast(where); while (list.size > 6) list.removeFirst()
    }
    @Synchronized fun trail(): List<String> = list.toList()
}
