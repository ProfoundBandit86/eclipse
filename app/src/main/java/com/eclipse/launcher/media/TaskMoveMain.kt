package com.eclipse.launcher.media

import android.app.ActivityManager
import android.os.IBinder

/**
 * Moves a running app to another screen, keeping it exactly as it is (no restart). Not part of the app itself: it's
 * started on its own, with root (or shell) rights, by [Privileged] as
 * `CLASSPATH=<Eclipse's APK> app_process /system/bin com.eclipse.launcher.media.TaskMoveMain <package> <to> [from]`,
 * because the Android call that moves a task between displays (ActivityTaskManager's moveRootTaskToDisplay) is only
 * open to the system. It prints one line, "OK <task>" or "FAIL <why>", and exits.
 *
 * `… TaskMoveMain --list` instead prints "OK " and the packages that have a task right now (the apps really open, the
 * same list Android's own Recents works from), comma-separated, for the app switcher.
 */
object TaskMoveMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = try { run(args) } catch (t: Throwable) {
            println("FAIL " + generateSequence(t) { it.cause }.joinToString(" / ") { "${it.javaClass.simpleName}: ${it.message}" })
            1
        }
        System.out.flush()
        // a bare runtime keeps binder threads alive: end it outright
        Runtime.getRuntime().halt(code)
    }

    private fun run(args: Array<String>): Int {
        val pkg = args.getOrNull(0) ?: return fail("no package")
        if (pkg == "--list") {
            val running = allTasks() ?: return fail("no task manager")
            println("OK " + running.mapNotNull { (it.baseActivity ?: it.topActivity)?.packageName }.distinct().joinToString(","))
            return 0
        }
        val to = args.getOrNull(1)?.toIntOrNull() ?: return fail("no display")
        val from = args.getOrNull(2)?.toIntOrNull()
        val atm = taskManager() ?: return fail("no task manager")
        val tasks = (allTasks() ?: return fail("no getTasks"))
            .filter { it.baseActivity?.packageName == pkg || it.topActivity?.packageName == pkg }
        if (tasks.isEmpty()) return fail("not running")
        val task = tasks.firstOrNull { from != null && displayOf(it) == from } ?: tasks.firstOrNull { displayOf(it) != to } ?: tasks.first()
        // Android 12+: moveRootTaskToDisplay; 10 / 11: moveStackToDisplay (the same thing under its old name)
        val move = atm.javaClass.methods.firstOrNull { (it.name == "moveRootTaskToDisplay" || it.name == "moveStackToDisplay") && it.parameterTypes.size == 2 }
            ?: return fail("no move call")
        val id = if (move.name == "moveStackToDisplay") stackOf(task) ?: task.taskId else task.taskId
        move.invoke(atm, id, to)
        println("OK ${task.taskId}")
        return 0
    }

    private fun taskManager(): Any? {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "activity_task") as? IBinder ?: return null
        return Class.forName("android.app.IActivityTaskManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }

    /** Every task there is right now, on every display (null: the call isn't there). */
    private fun allTasks(): List<ActivityManager.RunningTaskInfo>? {
        val atm = taskManager() ?: return null
        // getTasks' parameters differ between Android versions and makers: fill them by type
        val getTasks = atm.javaClass.methods.firstOrNull { it.name == "getTasks" } ?: return null
        var firstInt = true
        val params = getTasks.parameterTypes.map { t ->
            when (t) {
                Int::class.javaPrimitiveType -> if (firstInt) { firstInt = false; 100 } else -1
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        return (getTasks.invoke(atm, *params) as? List<*>).orEmpty().filterIsInstance<ActivityManager.RunningTaskInfo>()
    }

    private fun fail(why: String): Int { println("FAIL $why"); return 2 }

    /** TaskInfo.displayId is hidden; there are no hidden-API limits in this process, so it's read directly. */
    @android.annotation.SuppressLint("BlockedPrivateApi") // (that limit is for apps; this runs outside one)
    private fun displayOf(t: ActivityManager.RunningTaskInfo): Int =
        runCatching { Class.forName("android.app.TaskInfo").getDeclaredField("displayId").apply { isAccessible = true }.getInt(t) }.getOrDefault(-1)

    private fun stackOf(t: ActivityManager.RunningTaskInfo): Int? =
        runCatching { ActivityManager.RunningTaskInfo::class.java.getField("stackId").getInt(t) }.getOrNull()
}
