package com.eclipse.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseApp
import com.eclipse.launcher.EclipseVM
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The crash reports Eclipse keeps (EclipseApp.saveCrash writes them), newest first. */
object CrashReports {
    class Report(val file: File, val time: Long, val text: String) {
        private val lines = text.lines()
        private fun field(name: String) = lines.firstOrNull { it.startsWith("$name: ") }?.removePrefix("$name: ")?.trim().orEmpty()
        val device: String get() = field("Device").substringBefore(" (API")
        val version: String get() = field("Version")
        val screens: String get() = field("Screens")
        /** The stack trace: everything after the first blank line. */
        val trace: String get() = text.substringAfter("\n\n", text).trim()
        /** "java.lang.IllegalStateException: …" → "IllegalStateException". */
        val kind: String get() = trace.lineSequence().firstOrNull().orEmpty().substringBefore(':').substringAfterLast('.').ifBlank { "Crash" }
        val message: String get() = trace.lineSequence().firstOrNull().orEmpty().substringAfter(':', "").trim()
        /** The last screen you were on. */
        val where: String get() = screens.split(" › ").lastOrNull().orEmpty()
    }

    fun list(ctx: Context): List<Report> {
        val dir = File(ctx.filesDir, EclipseApp.CRASH_DIR)
        return dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }.orEmpty()
            .mapNotNull { f ->
                val t = f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: f.lastModified()
                runCatching { Report(f, t, f.readText()) }.getOrNull()
            }
            .sortedByDescending { it.time }
    }
}

/**
 * Settings → General → Crash reports: the reports down the left (how many this week, how many kept), the chosen
 * one on the right (what crashed and where, when, version, device, the stack trace) with Copy, Share, Delete and
 * Clear all. X copies, Y shares, B goes back to General.
 */
@Composable
fun CrashReportsPage(vm: EclipseVM, modifier: Modifier) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val reports = remember(tick) { CrashReports.list(ctx) }
    var chosen by remember { mutableStateOf<File?>(null) }
    val cur = reports.firstOrNull { it.file == chosen } ?: reports.firstOrNull()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    BackHandler { vm.crashPage = false; GamepadFocus.restoreKey = "st:Crash reports" }

    fun copy(r: CrashReports.Report) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Eclipse crash report", r.text))
        vm.message = "Crash report copied"
    }
    fun share(r: CrashReports.Report) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.text)
        runCatching { ctx.startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    // X copies, Y shares the report on the right
    DisposableEffect(cur?.file) {
        val x: () -> Unit = { cur?.let { copy(it) } }
        val y: () -> Unit = { cur?.let { share(it) } }
        GamepadFocus.buttonX = x; GamepadFocus.buttonY = y
        onDispose {
            if (GamepadFocus.buttonX === x) GamepadFocus.buttonX = null
            if (GamepadFocus.buttonY === y) GamepadFocus.buttonY = null
        }
    }

    Box(modifier) {
    Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- left: counts, then the reports
        Column(Modifier.width(300.dp).fillMaxHeight().focusGroup()) {
            val week = reports.count { System.currentTimeMillis() - it.time < 7 * 86_400_000L }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CrashStat("$week", "This week", Modifier.weight(1f))
                CrashStat("${reports.size}", "Kept", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            DropSection("Reports")
            Spacer(Modifier.height(6.dp))
            if (reports.isEmpty()) {
                Note("No crashes. If Eclipse ever closes by itself, the report shows up here.")
                // something to hold the controller
                FocusBox(Modifier.size(1.dp), onClick = { }, focusRequester = first) { }
            }
            Column(Modifier.weight(1f).menuScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                reports.forEachIndexed { i, r ->
                    val on = r.file == cur?.file
                    val shape = RoundedCornerShape(10.dp)
                    FocusBox(
                        Modifier.fillMaxWidth(), shape = shape, onClick = { chosen = r.file },
                        onFocusChange = { f -> if (f) chosen = r.file },
                        focusRequester = if (i == 0) first else null, focusKey = "crash:${r.time}"
                    ) {
                        Column(
                            Modifier.fillMaxWidth().clip(shape).background(if (on) C.x(0x12FFFFFFL) else C.x(0x0AFFFFFFL))
                                .border(1.dp, C.x(0x12FFFFFFL), shape).padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val recent = System.currentTimeMillis() - r.time < 2 * 86_400_000L
                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (recent) Color(0xFFFF453A) else Color(0xFF8E8E93)))
                                Text(crashWhen(r.time), color = C.ink, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                            }
                            Text(
                                listOf(r.kind, r.where).filter { it.isNotBlank() }.joinToString(" · "),
                                color = C.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
            }
        }
        // ---- right: the chosen report
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier.weight(1f).fillMaxHeight().clip(shape).background(C.x(0x09FFFFFFL)).border(1.dp, C.accent.copy(alpha = 0.35f), shape)
        ) {
            if (cur == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing to show", color = C.sub, fontSize = 13.sp) }
                return@Column
            }
            Column(Modifier.fillMaxWidth().background(C.accent.copy(alpha = 0.07f)).padding(horizontal = 14.dp, vertical = 11.dp)) {
                Text(cur.kind, color = C.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (cur.screens.isNotBlank()) "Crashed while on ${cur.screens}" else cur.message.ifBlank { "No details" },
                    color = C.x(0xFFB9B4AAL), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.x(0x12FFFFFFL)))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                CrashField("When", SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(Date(cur.time)), Modifier.weight(1f))
                CrashField("Version", cur.version.ifBlank { "—" }.let { if (it == "—") it else "Eclipse $it" }, Modifier.weight(1f))
                CrashField("Device", cur.device.ifBlank { "—" }, Modifier.weight(1f))
            }
            // the stack trace: the error in red, Eclipse's own lines in gold
            val accent = C.accent
            val trace = remember(cur.file) {
                buildAnnotatedString {
                    cur.trace.lines().take(80).forEachIndexed { i, line ->
                        if (i > 0) append('\n')
                        when {
                            i == 0 -> withStyle(SpanStyle(color = Color(0xFFFF8A80))) { append(line) }
                            "com.eclipse.launcher" in line -> withStyle(SpanStyle(color = accent)) { append(line) }
                            else -> append(line)
                        }
                    }
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF0F0F12)).border(1.dp, C.x(0x12FFFFFFL), RoundedCornerShape(9.dp))
            ) {
                val sc = rememberScrollState()
                RightStick.Register(sc)
                Text(
                    trace, color = Color(0xFFD8D4CC), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 15.sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(sc).padding(10.dp)
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Copy report", primary = true) { copy(cur) }
                Pill("Share") { share(cur) }
                Pill("Delete") { cur.file.delete(); chosen = null; tick++ }
                Spacer(Modifier.weight(1f))
                Pill("Clear all") {
                    reports.forEach { it.file.delete() }
                    chosen = null; tick++
                }
            }
        }
    }
    Row(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ButtonHint("A", "Open"); ButtonHint("X", "Copy"); ButtonHint("Y", "Share"); ButtonHint("B", "Back")
    }
    }
}

@Composable
private fun CrashStat(n: String, label: String, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.clip(shape).background(C.x(0x0AFFFFFFL)).border(1.dp, C.x(0x12FFFFFFL), shape).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(n, color = C.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label.uppercase(), color = C.sub, fontSize = 10.sp, letterSpacing = 1.4.sp)
    }
}

@Composable
private fun CrashField(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(end = 8.dp)) {
        Text(label.uppercase(), color = C.sub, fontSize = 10.sp, letterSpacing = 1.3.sp)
        Text(value, color = C.ink, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** "Today · 9:41 PM", "Yesterday · 6:12 PM", "Oct 30 · 11:03 AM". */
private fun crashWhen(t: Long): String {
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
    val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    val today = day.format(Date())
    val yesterday = day.format(Date(System.currentTimeMillis() - 86_400_000L))
    return when (day.format(Date(t))) {
        today -> "Today · $time"
        yesterday -> "Yesterday · $time"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(t)) + " · $time"
    }
}
