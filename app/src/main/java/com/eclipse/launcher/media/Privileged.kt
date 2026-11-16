package com.eclipse.launcher.media

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the few things Android only lets the system do (moving a running app to the other screen) with higher
 * rights, through whichever of these the device has:
 *
 * - **Shizuku**, when it's installed, running and Eclipse is allowed (Settings → App switcher): shell rights.
 * - **AYN's root service** (the Thor's own "Run Script as Root", the binder service "PServerBinder"): on the stock
 *   firmware with "Force SELinux" off, it runs a command as root for any app, with no setup. Eclipse only ever sends
 *   it its own fixed command (start [TaskMoveMain]). Can be turned off: Settings → App switcher.
 * - **su**, on a rooted device (Magisk asks the first time).
 *
 * Every call blocks: run it off the main thread.
 */
object Privileged {
    private const val AYN_SERVICE = "PServerBinder"
    private const val PREFS = "app_switcher"
    private const val AYN_KEY = "use_ayn_root"

    /** Whether Eclipse may use AYN's root service (on by default; Settings → App switcher). */
    fun aynAllowed(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AYN_KEY, true)
    fun setAynAllowed(ctx: Context, on: Boolean) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AYN_KEY, on).apply() }

    private fun aynService(): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, AYN_SERVICE) as? IBinder
    }.getOrNull()

    /** Whether AYN's service is there at all (it isn't on other devices, or with SELinux enforcing). */
    fun aynPresent(): Boolean = aynService()?.isBinderAlive == true

    /** AYN's service: code 0, the command and "0" as a string array, no interface token; the reply is its output. */
    private fun aynExec(cmd: String): String? {
        val b = aynService() ?: return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, "0"))
            if (!b.transact(0, data, reply, 0)) null else reply.createByteArray()?.let { String(it) } ?: ""
        } catch (t: Throwable) { null } finally { data.recycle(); reply.recycle() }
    }

    private fun suExec(cmd: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        // (Magisk's first-time question can take a while; the answer is one short line)
        if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroy(); null } else p.inputStream.bufferedReader().readText()
    }.getOrNull()

    /**
     * Runs Eclipse's own [TaskMoveMain] with root rights; its one-line answer ("OK …" / "FAIL …"), or null when
     * nothing could run it. The command goes in a small script: AYN's service only answers with the first line of
     * output and ignores long commands.
     */
    private fun runMover(ctx: Context, vararg args: String): String? {
        val apk = ctx.applicationInfo.sourceDir
        if (apk.contains('\'') || args.any { a -> a.any { !(it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') } }) return null
        val line = "CLASSPATH='$apk' app_process /system/bin ${TaskMoveMain::class.java.name} ${args.joinToString(" ")} 2>&1 | grep -m1 -E '^(OK|FAIL)'"
        // Shizuku first (it runs as the shell, which can't read Eclipse's files: the command goes straight in)
        if (shizukuAllowed()) shizukuExec(line)?.trim()?.lineSequence()?.firstOrNull { it.startsWith("OK") || it.startsWith("FAIL") }?.let { return it }
        val script = File(ctx.cacheDir, "move_${System.nanoTime()}.sh")
        return try {
            script.writeText(line + "\n")
            script.setReadable(true, false)
            val viaAyn = if (aynAllowed(ctx)) aynExec("sh ${script.absolutePath}")?.trim()?.takeIf { it.startsWith("OK") || it.startsWith("FAIL") } else null
            viaAyn ?: suExec("sh ${script.absolutePath}")?.trim()?.lineSequence()?.firstOrNull { it.startsWith("OK") || it.startsWith("FAIL") }
        } finally { script.delete() }
    }

    // ---- Shizuku ----
    /** Shizuku is running (Eclipse has its connection). */
    fun shizukuRunning(): Boolean = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
    /** Eclipse may use it. */
    fun shizukuAllowed(): Boolean = shizukuRunning() && runCatching {
        rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    /** Asks Shizuku for permission (its own pop-up); [done] runs with the answer, on the main thread. */
    fun askShizuku(done: (Boolean) -> Unit) {
        if (!shizukuRunning()) { done(false); return }
        if (shizukuAllowed()) { done(true); return }
        runCatching {
            val code = 4711
            val l = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != code) return
                    rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this)
                    done(grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
                }
            }
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(l)
            rikka.shizuku.Shizuku.requestPermission(code)
        }.onFailure { done(false) }
    }

    /** Runs [cmd] with shell rights through Shizuku (its newProcess, which the library keeps private). */
    private fun shizukuExec(cmd: String): String? = runCatching {
        val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroy(); null } else p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
    }.getOrNull()

    sealed class Moved {
        object Ok : Moved()
        /** Nothing with the rights to do it (no AYN service, not rooted). */
        object NoRights : Moved()
        data class Failed(val why: String) : Moved()
    }

    /**
     * The packages with an open task right now (on any display), or null when nothing with the rights to ask is
     * there (no Shizuku, AYN service or root) or the answer didn't come.
     */
    fun runningApps(ctx: Context): Set<String>? {
        val out = runMover(ctx, "--list") ?: return null
        if (!out.startsWith("OK")) return null
        return out.removePrefix("OK").trim().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Moves [pkg]'s running task to display [to] (from display [from], when known), keeping its state. */
    fun moveTask(ctx: Context, pkg: String, to: Int, from: Int?): Moved {
        val out = runMover(ctx, *listOfNotNull(pkg, to.toString(), from?.toString()).toTypedArray()) ?: return Moved.NoRights
        return if (out.startsWith("OK")) Moved.Ok else Moved.Failed(out.removePrefix("FAIL").trim())
    }
}
