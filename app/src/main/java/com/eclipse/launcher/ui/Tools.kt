package com.eclipse.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.ArtKind
import com.eclipse.launcher.data.Game
import com.eclipse.launcher.data.M3u
import com.eclipse.launcher.data.Platforms
import com.eclipse.launcher.data.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val TOOL_TABS = listOf("CHD", "Playlists", "Send to RomM")

/**
 * Tools, full screen: three tabs (L1 / R1, or touch) with a list on the left and what will happen on the right.
 * A ticks a game, X ticks all / none, Y starts. B closes.
 */
@Composable
fun ToolsScreen(vm: EclipseVM) = Glass {
    BackHandler { vm.toolsOpen = false }
    LaunchedEffect(Unit) {
        if (vm.chdItems == null) vm.scanForChd()
        if (vm.m3uSets == null) vm.scanM3u()
    }
    // L1 / R1: the tabs
    DisposableEffect(Unit) {
        val hook: (Int) -> Unit = { d -> vm.toolsTab = (vm.toolsTab + d + TOOL_TABS.size) % TOOL_TABS.size }
        GamepadFocus.pageTab = hook
        onDispose { if (GamepadFocus.pageTab === hook) GamepadFocus.pageTab = null }
    }
    // X / Y: each tab says what they do (all / none, start); what the screen behind had is put back afterwards
    val xAct = remember { mutableStateOf<() -> Unit>({}) }
    val yAct = remember { mutableStateOf<() -> Unit>({}) }
    DisposableEffect(Unit) {
        val prevX = GamepadFocus.buttonX; val prevY = GamepadFocus.buttonY
        val x: () -> Unit = { xAct.value() }
        val y: () -> Unit = { yAct.value() }
        GamepadFocus.buttonX = x; GamepadFocus.buttonY = y
        onDispose {
            if (GamepadFocus.buttonX === x) GamepadFocus.buttonX = prevX
            if (GamepadFocus.buttonY === y) GamepadFocus.buttonY = prevY
        }
    }
    // a new tab (or its list just loaded): the controller on its first row, else on the tab itself
    val first = remember { FocusRequester() }
    val tabFr = remember { FocusRequester() }
    PrimaryFocus(tabFr)
    LaunchedEffect(vm.toolsTab, vm.chdItems == null, vm.chdScanning, vm.m3uSets == null, vm.m3uScanning) {
        withFrameNanos { }
        if (runCatching { first.requestFocus() }.isFailure) runCatching { tabFr.requestFocus() }
    }

    // games by file, for names, systems and box art
    val byPath = remember(vm.games) { vm.games.associateBy { File(it.path).absolutePath } }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }.focusTrap()) {
        GlassScreenBackdrop(vm)
        Box(Modifier.fillMaxSize().background(Color(0x40000000)))
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 30.dp)) {
            ToolsHeader(vm, tabFr)
            Spacer(Modifier.height(9.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (vm.toolsTab) {
                    0 -> ChdTab(vm, byPath, first, xAct, yAct)
                    1 -> PlaylistTab(vm, byPath, first, xAct, yAct)
                    else -> RommTab(vm, first, xAct, yAct)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            ToolHint("A", "Tick", gold = true); ToolHint("X", "All / none"); ToolHint("Y", "Start"); ToolHint("B", "Close")
        }
    }
}

@Composable
private fun ToolsHeader(vm: EclipseVM, tabFr: FocusRequester) {
    val chdN = vm.chdItems?.count { !it.done }
    val m3uN = vm.m3uSets?.count { !it.upToDate }
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Handyman, contentDescription = null, tint = C.accent, modifier = Modifier.size(15.dp))
        Text("TOOLS", color = C.accent, fontSize = 9.sp, letterSpacing = 2.2.sp, modifier = Modifier.padding(start = 7.dp, end = 12.dp))
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.clip(shape).background(C.x(0x0DFFFFFFL)).border(1.dp, C.x(0x1AFFFFFFL), shape).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TOOL_TABS.forEachIndexed { i, t ->
                val n = when (i) { 0 -> chdN; 1 -> m3uN; else -> null }
                val on = vm.toolsTab == i
                FocusBox(Modifier.height(24.dp), shape = RoundedCornerShape(9.dp), onClick = { vm.toolsTab = i }, focusRequester = if (on) tabFr else null, focusKey = "tools:tab:$i") {
                    Row(
                        Modifier.fillMaxHeight().background(if (on) C.accent else Color.Transparent).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            t.uppercase(), color = if (on) C.onAccent else C.x(0xFFCFCAC0L), fontSize = 9.5.sp, letterSpacing = 1.4.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1
                        )
                        if (n != null && n > 0) Text(
                            "$n", color = if (on) C.onAccent else C.x(0xFFCFCAC0L), fontSize = 8.5.sp,
                            modifier = Modifier.padding(start = 7.dp).clip(RoundedCornerShape(7.dp))
                                .background(if (on) Color(0x2E000000) else C.x(0x1AFFFFFFL)).padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = 12.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
        ToolKey("LB"); Spacer(Modifier.width(5.dp)); ToolKey("RB")
        Text("TABS", color = C.sub, fontSize = 8.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

// ============================== CHD ==============================

@Composable
private fun RowScope.ChdTab(
    vm: EclipseVM, byPath: Map<String, Game>, first: FocusRequester,
    xAct: androidx.compose.runtime.MutableState<() -> Unit>, yAct: androidx.compose.runtime.MutableState<() -> Unit>
) {
    val items = vm.chdItems
    val todo = items.orEmpty().filter { !it.done }
    val sysOf = { ci: EclipseVM.ChdItem ->
        byPath[ci.src.absolutePath]?.let { g -> Platforms.byId(g.platform)?.name }
            ?: Platforms.matchFolder(ci.src.parentFile?.name ?: "")?.name ?: (ci.src.parentFile?.name ?: "Other")
    }
    var filter by remember { mutableStateOf<String?>(null) }
    val systems = todo.groupBy(sysOf).toSortedMap(String.CASE_INSENSITIVE_ORDER)
    val shown = if (filter == null) systems else systems.filterKeys { it == filter }
    val shownItems = shown.values.flatten()
    val picked = todo.filter { it.src.absolutePath in vm.chdPicked }
    val job = vm.chdJob

    val all by rememberUpdatedState(shownItems)
    xAct.value = {
        val paths = all.map { it.src.absolutePath }.toSet()
        vm.chdPicked = if (paths.all { it in vm.chdPicked }) vm.chdPicked - paths else vm.chdPicked + paths
    }
    yAct.value = { if (vm.chdJob == null) vm.startChd() }

    ToolList(
        "Disc images not in CHD", systems.keys.toList(), filter, { filter = it },
        Modifier.weight(1.62f)
    ) {
        when {
            items == null || vm.chdScanning -> ListMessage("Looking for disc images…")
            todo.isEmpty() -> ListMessage("Every disc image is already a CHD.", "Look again", first) { vm.scanForChd() }
            else -> LazyColumn(Modifier.fillMaxSize().stayInside(), state = stickListState(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                var n = 0
                shown.forEach { (sys, list) ->
                    item(key = "s:$sys") { SecLabel("$sys · ${list.size}") }
                    list.forEach { ci ->
                        val idx = n++
                        item(key = ci.src.absolutePath) {
                            val g = byPath[ci.src.absolutePath]
                            val running = job != null && job.name == ci.src.nameWithoutExtension
                            val kind = if (ci.src.extension.equals("cue", true)) "BIN / CUE · ${ci.parts.size} FILES" else ci.src.extension.uppercase()
                            ToolRow(
                                ci.src.absolutePath in vm.chdPicked, g?.name ?: ci.src.nameWithoutExtension, kind,
                                g?.let { gg -> vm.artOf(gg, ArtKind.BOX) }, "chd:${ci.src.absolutePath}", if (idx == 0) first else null,
                                progress = if (running) job!!.progress else null,
                                onClick = { vm.toggleChdPick(ci.src.absolutePath) }
                            ) {
                                if (running) Text("${job!!.phase.uppercase()} ${(job.progress * 100).toInt()} %", color = C.accent, fontSize = 9.5.sp)
                                else Text(vm.fmtBytes(ci.bytes), color = C.x(0xFFCFCAC0L), fontSize = 9.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    SidePanel(Modifier.weight(1f)) {
        if (job != null) {
            Label("Converting")
            BigCount("${job.index + 1}", "of ${job.total} discs")
            Text(job.name, color = C.ink, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${job.phase} · ${(job.progress * 100).toInt()} %", color = C.accent, fontSize = 10.sp)
            Meter(listOf((job.index + job.progress) / job.total.coerceAtLeast(1) to C.accent))
            Spacer(Modifier.weight(1f))
            Pill("Stop", Modifier.fillMaxWidth(), primary = true) { vm.cancelChd() }
            SideNote("Discs already finished are kept. It goes on in the background if you close this.")
        } else {
            Label("Selected")
            BigCount("${picked.size}", "of ${todo.size} discs")
            Text("${vm.fmtBytes(picked.sumOf { it.bytes })} to convert", color = C.x(0xFFCFCAC0L), fontSize = 10.sp)
            if (vm.chdDiscsTotal > 0) Text("Saved so far: ${vm.fmtBytes(vm.chdSavedTotal)} on ${vm.chdDiscsTotal} discs", color = C.accent, fontSize = 10.sp)
            Spacer(Modifier.height(2.dp))
            ToggleRow("Delete originals when done", null, vm.chdDelete) { vm.chooseChdDelete(it) }
            Spacer(Modifier.weight(1f))
            Pill(
                if (picked.isEmpty()) "Nothing ticked" else "Convert ${picked.size} disc${if (picked.size == 1) "" else "s"}",
                Modifier.fillMaxWidth(), primary = true
            ) { vm.startChd() }
            SideNote(vm.chdLog.lastOrNull() ?: "One at a time, each checked before the originals go. Playlists are pointed at the .chd.")
        }
    }
}

// ============================== Playlists ==============================

@Composable
private fun RowScope.PlaylistTab(
    vm: EclipseVM, byPath: Map<String, Game>, first: FocusRequester,
    xAct: androidx.compose.runtime.MutableState<() -> Unit>, yAct: androidx.compose.runtime.MutableState<() -> Unit>
) {
    val sets = vm.m3uSets
    var showAll by remember { mutableStateOf(false) }
    val list = sets.orEmpty().filter { showAll || !it.upToDate }
    val ok = { s: M3u.DiscSet -> vm.m3uMissing(s).isEmpty() }
    val picked = sets.orEmpty().filter { it.key in vm.m3uPicked && ok(it) }
    var preview by remember { mutableStateOf<String?>(null) }
    val previewSet = list.firstOrNull { it.key == preview } ?: picked.firstOrNull() ?: list.firstOrNull()

    val all by rememberUpdatedState(list.filter(ok))
    xAct.value = {
        val keys = all.map { it.key }.toSet()
        vm.m3uPicked = if (keys.all { it in vm.m3uPicked }) vm.m3uPicked - keys else vm.m3uPicked + keys
    }
    yAct.value = { vm.makePlaylists() }

    ToolList(
        "Multi-disc games", listOf("Needs one", "All"), if (showAll) "All" else "Needs one", { showAll = it == "All" },
        Modifier.weight(1.62f), allChip = false
    ) {
        when {
            sets == null || vm.m3uScanning -> ListMessage("Looking for multi-disc games…")
            list.isEmpty() -> ListMessage(if (sets.isEmpty()) "No multi-disc games found." else "Every multi-disc game has its playlist.", "Look again", first) { vm.scanM3u() }
            else -> LazyColumn(Modifier.fillMaxSize().stayInside(), state = stickListState(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                list.forEachIndexed { i, s ->
                    item(key = s.key) {
                        val missing = vm.m3uMissing(s)
                        val sys = Platforms.matchFolder(s.dir.name)?.short ?: s.dir.name.uppercase()
                        val g = s.playlist?.let { byPath[it.absolutePath] } ?: s.discs.firstNotNullOfOrNull { byPath[it.absolutePath] }
                        val exts = s.discs.map { it.extension.uppercase() }.distinct().joinToString(" / ")
                        ToolRow(
                            if (missing.isEmpty()) s.key in vm.m3uPicked else null, s.title, "$sys · ${s.discs.size} DISCS · $exts",
                            g?.let { gg -> vm.artOf(gg, ArtKind.BOX) }, "m3u:${s.key}", if (i == 0) first else null,
                            onFocus = { preview = s.key },
                            onClick = { if (missing.isEmpty()) vm.toggleM3uPick(s.key) else vm.message = "Disc ${missing.joinToString(", ")} of ${s.title} isn't there" }
                        ) {
                            when {
                                missing.isNotEmpty() -> Tag("Disc ${missing.joinToString(", ")} missing", C.x(0xFFFF8A80L))
                                s.playlist == null -> Tag("No playlist", C.x(0xFFCFCAC0L))
                                !s.upToDate -> Tag("Out of date", C.accent)
                                else -> Tag("Done", C.sub)
                            }
                        }
                    }
                }
            }
        }
    }
    SidePanel(Modifier.weight(1f)) {
        Label("Playlists to make")
        BigCount("${picked.size}", "games · ${picked.sumOf { it.discs.size }} discs")
        if (previewSet != null) {
            Label("${previewSet.title}.m3u")
            Text(
                previewSet.discs.take(5).joinToString("\n") { it.name } + if (previewSet.discs.size > 5) "\n…" else "",
                color = C.x(0xFFCFCAC0L), fontSize = 8.5.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Color(0x47000000))
                    .border(1.dp, C.x(0x12FFFFFFL), RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 7.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Pill(
            when {
                vm.m3uBusy -> "Writing…"
                picked.isEmpty() -> "Nothing ticked"
                else -> "Make ${picked.size} playlist${if (picked.size == 1) "" else "s"}"
            },
            Modifier.fillMaxWidth(), primary = true
        ) { vm.makePlaylists() }
        SideNote("Games with a missing disc are left out. Afterwards each game shows once in your library.")
    }
}

// ============================== Send to RomM ==============================

@Composable
private fun RowScope.RommTab(
    vm: EclipseVM, first: FocusRequester,
    xAct: androidx.compose.runtime.MutableState<() -> Unit>, yAct: androidx.compose.runtime.MutableState<() -> Unit>
) {
    // what's on this device and not on RomM (and each one's size): worked out away from the screen, it reads files
    var cands by remember { mutableStateOf<List<Pair<Game, Long>>?>(null) }
    val idle = vm.upQueue == null
    LaunchedEffect(vm.games, vm.rommSyncedAt, idle) {
        if (idle) cands = withContext(Dispatchers.IO) {
            vm.rommUpCandidates().map { g -> g to com.eclipse.launcher.data.SmbUpload.filesOf(File(g.path)).sumOf { it.length() } }
        }
    }
    val q = vm.upQueue
    val list = cands.orEmpty()
    val games = list.map { it.first }
    var filter by remember { mutableStateOf<String?>(null) }
    val sysName = { g: Game -> Platforms.byId(g.platform)?.name ?: g.platform }
    val systems = list.groupBy { sysName(it.first) }
    val shown = if (filter == null) systems else systems.filterKeys { it == filter }
    val picked = list.filter { vm.rommUpIsPicked(it.first.id) }

    val shownGames by rememberUpdatedState(shown.values.flatten().map { it.first })
    val allGames by rememberUpdatedState(games)
    val pickedGames by rememberUpdatedState(picked.map { it.first })
    xAct.value = {
        val ids = shownGames.map { it.id }.toSet()
        val cur = vm.rommUpPicked ?: allGames.map { it.id }.toSet()
        vm.rommUpPicked = if (ids.all { it in cur }) cur - ids else cur + ids
    }
    yAct.value = { if (vm.upQueue != null) vm.stopBulkUpload() else vm.startBulkUpload(pickedGames) }

    val ready = vm.isConfigured("romm") && vm.smbReady()
    ToolList("On this device, not on RomM", systems.keys.toList(), filter, { filter = it }, Modifier.weight(1.62f)) {
        when {
            !ready -> ListMessage(
                if (!vm.isConfigured("romm")) "Connect to RomM first." else "Set up RomM's library share first: games are copied into it.",
                "Open Connections", first
            ) { vm.toolsOpen = false; vm.settingsTab = SettingsTabs.CONNECTIONS; vm.go(Screen.SETTINGS) }
            cands == null -> ListMessage("Comparing your games with RomM…")
            list.isEmpty() -> ListMessage("RomM already has every game on this device.", "Sync RomM", first) { vm.rommSync() }
            else -> LazyColumn(Modifier.fillMaxSize().stayInside(), state = stickListState(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                var n = 0
                shown.forEach { (sys, rows) ->
                    item(key = "s:$sys") { SecLabel("$sys · ${rows.size}") }
                    rows.forEach { (g, size) ->
                        val idx = n++
                        item(key = g.id) {
                            val p = vm.uploads[g.id]
                            val waiting = q != null && p == null && g.id in q.ids.drop(q.index + 1)
                            val short = Platforms.byId(g.platform)?.short ?: g.platform.uppercase()
                            ToolRow(
                                vm.rommUpIsPicked(g.id), g.name, "$short · ${vm.fmtBytes(size)}", vm.artOf(g, ArtKind.BOX),
                                "up:${g.id}", if (idx == 0) first else null, progress = p,
                                onClick = { if (vm.upQueue == null) vm.toggleRommUp(g.id, games) }
                            ) {
                                when {
                                    p != null -> Text("↑ ${(p * 100).toInt()} %", color = C.accent, fontSize = 9.5.sp)
                                    waiting -> Text("waiting", color = C.sub, fontSize = 9.5.sp)
                                    vm.rommFolderIsNew(g.platform) -> Tag("New folder", C.accent)
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    SidePanel(Modifier.weight(1f)) {
        if (q != null) {
            Label("Sending")
            BigCount("${q.index + 1}", "of ${q.ids.size} games")
            Text(q.name, color = C.ink, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val frac = if (q.bytesAll > 0) q.bytesDone.toFloat() / q.bytesAll else 0f
            val secs = (System.currentTimeMillis() - q.startedAt) / 1000f
            val left = if (frac > 0.02f && secs > 5f) (secs / frac - secs).toLong() else -1L
            Text(
                "${vm.fmtBytes(q.bytesDone)} of ${vm.fmtBytes(q.bytesAll)}" + if (left >= 0) " · about ${fmtLeft(left)} left" else "",
                color = C.x(0xFFCFCAC0L), fontSize = 10.sp
            )
            Meter(listOf(frac to C.accent))
            Spacer(Modifier.weight(1f))
            Pill(if (vm.upStopping) "Stopping after this one…" else "Pause", Modifier.fillMaxWidth(), primary = true) { vm.stopBulkUpload() }
            SideNote("It goes on in the background if you close this. Finished ones are in Settings → Connections → RomM.")
        } else {
            Label("Selected")
            BigCount("${picked.size}", "games · ${vm.fmtBytes(picked.sumOf { it.second })}")
            if (ready) Text("To RomM's share on ${vm.smbHost()}", color = C.x(0xFFCFCAC0L), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            ToggleRow("Ask RomM to scan when done", null, vm.upScanAfter) { vm.chooseUpScan(it) }
            Spacer(Modifier.weight(1f))
            Pill(
                if (picked.isEmpty()) "Nothing ticked" else "Send ${picked.size} game${if (picked.size == 1) "" else "s"}",
                Modifier.fillMaxWidth(), primary = true
            ) { if (ready) vm.startBulkUpload(picked.map { it.first }) }
            SideNote("One at a time, into each system's RomM folder. \"New folder\": RomM hasn't got one for that system yet.")
        }
    }
}

private fun fmtLeft(s: Long): String = when {
    s < 90 -> "a minute"
    s < 3600 -> "${(s + 30) / 60} minutes"
    else -> "${s / 3600} h ${(s % 3600) / 60} min"
}

// ============================== pieces ==============================

/** The left panel: a gold title, chips (All + systems, or the tab's own), then the list. */
@Composable
private fun ToolList(
    title: String, chips: List<String>, chosen: String?, onChip: (String?) -> Unit, modifier: Modifier,
    allChip: Boolean = true, content: @Composable () -> Unit
) {
    GlassPanel(modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title.uppercase(), color = C.accent, fontSize = 8.5.sp, letterSpacing = 1.8.sp, maxLines = 1)
                Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (allChip) Chip("All", chosen == null) { onChip(null) }
                    // the four biggest groups get a chip (more wouldn't fit beside the title)
                    (if (allChip) chips.take(4) else chips).forEach { c -> Chip(c, chosen == c) { onChip(if (chosen == c && allChip) null else c) } }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    FocusBox(Modifier.height(20.dp), shape = shape, onClick = onClick, focusKey = "tools:chip:$text") {
        Box(
            Modifier.fillMaxHeight().border(1.dp, if (on) C.accent.copy(alpha = 0.55f) else C.x(0x1AFFFFFFL), shape).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                (Platforms.all.firstOrNull { it.name == text }?.short ?: text).uppercase(), color = if (on) C.accent else C.x(0xFFBDB8AEL),
                fontSize = 8.sp, letterSpacing = 1.2.sp, maxLines = 1
            )
        }
    }
}

/** One game in a list: tick, box art, name and a small line under it, then [trailing]. A thin gold line shows progress. */
@Composable
private fun ToolRow(
    checked: Boolean?, title: String, sub: String, art: String?, key: String, fr: FocusRequester?,
    progress: Float? = null, onFocus: (() -> Unit)? = null, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit
) {
    FocusBox(
        Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(9.dp), onClick = onClick, focusRequester = fr, focusKey = key,
        onFocusChange = { f -> if (f) onFocus?.invoke() }
    ) {
        Box(Modifier.fillMaxSize().setRowLook()) {
            Row(
                Modifier.fillMaxSize().padding(start = 9.dp, end = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Tick(checked)
                Cover(title, art, Modifier.size(24.dp, 30.dp).clip(RoundedCornerShape(4.dp)))
                Column(Modifier.weight(1f)) {
                    Text(title, color = C.ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub.uppercase(), color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                trailing()
            }
            if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(C.accent))
        }
    }
}

/** Round tick: gold with a check when ticked, an empty ring when not, a faint dash when it can't be ticked (null). */
@Composable
private fun Tick(on: Boolean?) {
    Box(
        Modifier.size(16.dp).clip(CircleShape)
            .background(if (on == true) C.accent else Color.Transparent)
            .border(1.5.dp, when (on) { true -> C.accent; false -> C.x(0x59FFFFFFL); null -> C.x(0x1FFFFFFFL) }, CircleShape),
        contentAlignment = Alignment.Center
    ) { if (on == true) Icon(Icons.Filled.Check, null, tint = C.onAccent, modifier = Modifier.size(11.dp)) }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text.uppercase(), color = color, fontSize = 7.5.sp, letterSpacing = 1.2.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun SecLabel(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = C.accent, fontSize = 8.sp, letterSpacing = 1.8.sp, maxLines = 1)
        Box(Modifier.padding(start = 8.dp).weight(1f).height(1.dp).background(C.x(0x17FFFFFFL)))
    }
}

/** Centred in an empty list: what's going on, and a button when there's something to do about it. */
@Composable
private fun ListMessage(text: String, action: String? = null, fr: FocusRequester? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = C.x(0xFFCFCAC0L), fontSize = 11.sp)
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            Pill(action, focusRequester = fr, onClick = onAction)
        }
    }
}

@Composable
private fun SidePanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), color = C.sub, fontSize = 8.sp, letterSpacing = 1.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun BigCount(n: String, rest: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(n, color = C.ink, fontSize = 30.sp, fontWeight = FontWeight.Light, lineHeight = 30.sp)
        Text(rest, color = C.x(0xFFCFCAC0L), fontSize = 10.sp, modifier = Modifier.padding(start = 7.dp, bottom = 4.dp))
    }
}

/** A thin bar, filled in parts. */
@Composable
private fun Meter(parts: List<Pair<Float, Color>>) {
    Row(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.x(0x14FFFFFFL))) {
        parts.forEach { (f, c) -> if (f > 0f) Box(Modifier.fillMaxHeight().weight(f.coerceIn(0.001f, 1f)).background(c)) }
        val rest = 1f - parts.sumOf { it.first.toDouble() }.toFloat()
        if (rest > 0.001f) Spacer(Modifier.weight(rest))
    }
}

@Composable
private fun SideNote(text: String) =
    Text(text, color = C.sub, fontSize = 8.5.sp, lineHeight = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)

@Composable
private fun ToolKey(text: String) {
    Box(
        Modifier.height(15.dp).clip(RoundedCornerShape(5.dp)).border(1.dp, C.x(0x59FFFFFFL), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = C.x(0xFFDDDDDDL), fontSize = 8.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ToolHint(key: String, label: String, gold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(15.dp).clip(CircleShape).background(if (gold) C.accent else Color.Transparent)
                .border(1.dp, if (gold) C.accent else C.x(0x80FFFFFFL), CircleShape)
        ) { CenteredGlyph(key, if (gold) C.onAccent else C.ink, 8.sp, Modifier.fillMaxSize()) }
        Text(label.uppercase(), color = C.x(0xFFD8D3C9L), fontSize = 8.5.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 6.dp))
    }
}
