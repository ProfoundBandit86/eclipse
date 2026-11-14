package com.eclipse.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.Game
import com.eclipse.launcher.data.Platforms
import com.eclipse.launcher.data.Ports

/** The chips along the top of the Ports screen, before the systems. */
private val PORT_FILTERS = listOf("all" to "All", "installed" to "Installed", "updates" to "Updates", "dual" to "Dual screen")

/** What a port's row and buttons offer right now. */
private enum class PortState { GET, INSTALLED, UPDATE, DOWNLOADING, PAGE }

private fun stateOf(vm: EclipseVM, p: Ports.Port): PortState = when {
    vm.portProgress.containsKey(p.id) -> PortState.DOWNLOADING
    vm.portPkg(p) != null -> if (vm.portHasUpdate(p)) PortState.UPDATE else PortState.INSTALLED
    vm.portRels.containsKey(p.id) && vm.portRels[p.id]?.apkUrl == null -> PortState.PAGE
    else -> PortState.GET
}

/** A: open it, get it, update it, or (no APK in its releases) its page. */
private fun primary(vm: EclipseVM, p: Ports.Port) {
    when (stateOf(vm, p)) {
        PortState.INSTALLED -> vm.portPkg(p)?.let { vm.openApp(it) }
        PortState.GET, PortState.UPDATE -> vm.installPort(p)
        PortState.PAGE -> vm.openUrl(vm.portRels[p.id]?.page?.ifBlank { null } ?: p.url)
        PortState.DOWNLOADING -> {}
    }
}

private fun sysName(id: String) = Platforms.byId(id)?.name ?: when (id) { "amiga" -> "Amiga"; else -> id.uppercase() }
private fun sysShort(id: String) = Platforms.byId(id)?.short ?: id.uppercase()

/**
 * Ports & recomps, full screen: every native Android version of a game in the list, by the system it came from.
 * L1 / R1 (or a tap) change the filter; A gets / updates / opens the highlighted one; Y shows what's new in its newest
 * release; X checks again now. B closes.
 */
@Composable
fun PortsScreen(vm: EclipseVM) = Glass {
    BackHandler { vm.portsOpen = false }
    LaunchedEffect(Unit) { vm.loadPorts() }
    val all = vm.ports
    var filter by remember { mutableStateOf("all") }
    // the systems, most ports first
    val systems = remember(all) { all.groupBy { it.sys }.entries.sortedByDescending { it.value.size }.map { it.key } }
    val chips = PORT_FILTERS.map { it.first } + systems
    DisposableEffect(chips) {
        val hook: (Int) -> Unit = { d -> filter = chips[(chips.indexOf(filter).coerceAtLeast(0) + d + chips.size) % chips.size] }
        GamepadFocus.pageTab = hook
        onDispose { if (GamepadFocus.pageTab === hook) GamepadFocus.pageTab = null }
    }
    // installed ones: check for updates once the list is in
    LaunchedEffect(all.size) { all.filter { vm.portPkg(it) != null }.forEach { vm.checkPort(it) } }

    val shown = all.filter { p ->
        when (filter) {
            "all" -> true
            "installed" -> vm.portPkg(p) != null
            "updates" -> vm.portHasUpdate(p)
            "dual" -> p.dual
            else -> p.sys == filter
        }
    }.sortedWith(compareBy<Ports.Port> { systems.indexOf(it.sys) }.thenBy { if (vm.portPkg(it) != null) 0 else 1 }.thenBy { it.name.lowercase() })
    // rows with a heading at the start of each system
    val rows: List<Any> = remember(shown) {
        val out = ArrayList<Any>(); var last: String? = null
        shown.forEach { p -> if (p.sys != last) { out += p.sys; last = p.sys }; out += p }
        out
    }
    var focusId by remember { mutableStateOf(vm.portsFocus) }
    val focused = shown.firstOrNull { it.id == focusId } ?: shown.firstOrNull()
    LaunchedEffect(focused?.id) { focused?.let { vm.checkPort(it) } }

    // X: check now; Y: what's new
    var notes by remember { mutableStateOf<Ports.Port?>(null) }
    val cur by rememberUpdatedState(focused)
    DisposableEffect(Unit) {
        val prevX = GamepadFocus.buttonX; val prevY = GamepadFocus.buttonY
        val x: () -> Unit = { vm.checkPortsNow(cur) }
        val y: () -> Unit = { cur?.let { notes = it } }
        GamepadFocus.buttonX = x; GamepadFocus.buttonY = y
        onDispose {
            if (GamepadFocus.buttonX === x) GamepadFocus.buttonX = prevX
            if (GamepadFocus.buttonY === y) GamepadFocus.buttonY = prevY
        }
    }

    // the controller starts on the port it was opened for (a game page's More), else the first one
    val listState = rememberLazyListState()
    val startFr = remember { FocusRequester() }
    val startId = remember(rows.isNotEmpty(), filter) { (focusId?.takeIf { id -> shown.any { it.id == id } }) ?: shown.firstOrNull()?.id }
    PrimaryFocus(startFr)
    LaunchedEffect(startId, rows.size) {
        val i = rows.indexOfFirst { it is Ports.Port && it.id == startId }
        if (i >= 0) { listState.scrollToItem((i - 1).coerceAtLeast(0)); withFrameNanos { }; withFrameNanos { }; runCatching { startFr.requestFocus() } }
    }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }.focusTrap()) {
        GlassScreenBackdrop(vm)
        Box(Modifier.fillMaxSize().background(Color(0x40000000)))
        Row(Modifier.fillMaxSize().padding(bottom = 30.dp)) {
            // ---- the list ----
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 22.dp, end = 12.dp, top = 14.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Ports & recomps", color = C.ink, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    val inst = all.count { vm.portPkg(it) != null }
                    val upd = all.count { vm.portHasUpdate(it) }
                    Text(
                        if (all.isEmpty()) "Loading…" else "${all.size} ports" + (if (inst > 0) " · $inst installed" else "") + (if (upd > 0) " · $upd ${if (upd == 1) "update" else "updates"}" else ""),
                        color = if (upd > 0) C.accent else C.sub, fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                    )
                }
                // the filters (L1 / R1, or tap)
                LazyRow(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(chips) { _, c ->
                        val on = c == filter
                        val label = PORT_FILTERS.firstOrNull { it.first == c }?.second ?: sysShort(c)
                        val n = when (c) {
                            "all" -> all.size; "installed" -> all.count { vm.portPkg(it) != null }; "updates" -> all.count { vm.portHasUpdate(it) }
                            "dual" -> all.count { it.dual }; else -> all.count { it.sys == c }
                        }
                        Row(
                            Modifier.height(22.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (on) C.accent else C.x(0x0FFFFFFFL))
                                .border(1.dp, if (on) C.accent else C.x(0x17FFFFFFL), RoundedCornerShape(11.dp))
                                .pointerInput(c) { detectTapGestures { filter = c } }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = if (on) C.onAccent else C.x(0xFFCFCAC0L), fontSize = 10.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                            Text("$n", color = if (on) C.onAccent.copy(alpha = 0.7f) else C.sub, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 5.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (all.isNotEmpty() && shown.isEmpty()) Text(
                    when (filter) { "installed" -> "No ports installed yet."; "updates" -> "Everything is up to date."; else -> "Nothing here." },
                    color = C.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)
                )
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 18.dp)) {
                    itemsIndexed(rows, key = { _, r -> if (r is Ports.Port) "p:" + r.id else "s:$r" }) { _, r ->
                        if (r is String) PortSection(r)
                        else if (r is Ports.Port) PortRow(vm, r, if (r.id == startId) startFr else null) { f -> if (f) focusId = r.id }
                    }
                }
            }
            // ---- the highlighted port ----
            Box(Modifier.width(265.dp).fillMaxHeight().background(cardGlass().copy(alpha = 0.78f)).border(1.dp, C.x(0x12FFFFFFL))) {
                focused?.let { PortPanel(vm, it) { notes = it } }
            }
        }
        // the buttons
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp).background(Color(0xD10A090D)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val st = focused?.let { stateOf(vm, it) }
            ButtonHint("A", when (st) { PortState.INSTALLED -> "Open"; PortState.UPDATE -> "Update"; PortState.PAGE -> "Its page"; PortState.DOWNLOADING -> "Downloading"; else -> "Get" })
            ButtonHint("Y", "What's new")
            ButtonHint("X", "Check now")
            ButtonHint("L1 R1", "Filter")
            Spacer(Modifier.weight(1f))
            ButtonHint("B", "Back")
        }
        notes?.let { p ->
            val r = vm.portRels[p.id]
            val text = when {
                r == null -> if (Ports.limited()) "GitHub asks to wait a while before checking again." else "Not checked yet. Press X to check now."
                r.tag.isBlank() -> "${p.title} has no releases on GitHub yet."
                else -> "${r.tag}${if (r.date.isNotBlank()) "  ·  " + r.date else ""}\n\n" + r.notes.ifBlank { "No notes with this release." }
            }
            DescriptionPopup(p.title, text) { notes = null }
        }
    }
}

@Composable
private fun PortSection(sys: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(platformTint(sys)), contentAlignment = Alignment.Center) {
            Text(sysShort(sys).take(3), color = Color.White, fontSize = 6.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(sysName(sys).uppercase(), color = C.accent, fontSize = 8.5.sp, letterSpacing = 2.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        Box(Modifier.padding(start = 8.dp).weight(1f).height(1.dp).background(C.x(0x14FFFFFFL)))
    }
}

/** The port's icon: the installed app's own, else its initials on its system's colour. */
@Composable
private fun PortIcon(vm: EclipseVM, p: Ports.Port, size: Int) {
    val app = vm.portPkg(p)?.let { pk -> vm.apps.firstOrNull { it.pkg == pk } }
    if (app != null) AppIcon(app, Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)))
    else Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(platformTint(p.sys)), contentAlignment = Alignment.Center) {
        val initials = Ports.words(p.title).filter { it.length > 1 || it.all(Char::isDigit) }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { p.title.take(2) }
        Text(initials, color = Color.White, fontSize = (size / 3).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun PortRow(vm: EclipseVM, p: Ports.Port, fr: FocusRequester?, onFocus: (Boolean) -> Unit) {
    FocusBox(
        Modifier.fillMaxWidth().height(40.dp).padding(vertical = 1.dp), shape = RoundedCornerShape(10.dp),
        onClick = { primary(vm, p) }, onFocusChange = onFocus, focusRequester = fr, focusKey = "port:${p.id}"
    ) { focused ->
        Row(
            Modifier.fillMaxSize().background(if (focused) C.accent.copy(alpha = 0.16f) else Color.Transparent).padding(start = 7.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PortIcon(vm, p, 28)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(p.title, color = C.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (p.title != p.game) p.game + " · " else "") + p.author + if (p.dual) " · dual screen" else "",
                    color = C.sub, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            PortBadge(vm, p)
        }
    }
}

@Composable
private fun PortBadge(vm: EclipseVM, p: Ports.Port) {
    val shape = RoundedCornerShape(10.dp)
    val st = stateOf(vm, p)
    val (text, mod, color) = when (st) {
        PortState.DOWNLOADING -> Triple(vm.portProgress[p.id]?.let { if (it < 0) "…" else "${(it * 100).toInt()}%" } ?: "…", Modifier.border(1.dp, C.accent.copy(alpha = 0.6f), shape), C.accent)
        PortState.UPDATE -> Triple("UPDATE", Modifier.background(C.accent), C.onAccent)
        PortState.INSTALLED -> Triple("✓ INSTALLED", Modifier.background(Color(0x1A8FC79A)), Color(0xFF8FC79A))
        PortState.PAGE -> Triple("PAGE ›", Modifier.border(1.dp, C.x(0x33FFFFFFL), shape), C.sub)
        PortState.GET -> Triple("GET", Modifier.border(1.dp, C.accent.copy(alpha = 0.6f), shape), C.accent)
    }
    Box(Modifier.height(20.dp).clip(shape).then(mod).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = if (st == PortState.UPDATE) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

/** The right-hand panel: what the port is, its versions, and its buttons. */
@Composable
private fun PortPanel(vm: EclipseVM, p: Ports.Port, showNotes: () -> Unit) {
    val r = vm.portRels[p.id]
    val have = vm.portVersion(p)
    val st = stateOf(vm, p)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
        Text(
            sysShort(p.sys).uppercase() + " · " + when (p.kind) { "recomp" -> "RECOMP"; "decomp" -> "DECOMP"; else -> "PORT" } + if (p.dual) " · DUAL SCREEN" else "",
            color = C.accent, fontSize = 8.5.sp, letterSpacing = 2.sp, maxLines = 1
        )
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PortIcon(vm, p, 44)
            Column(Modifier.padding(start = 11.dp)) {
                Text(p.title, color = C.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
                Text(p.game, color = C.sub, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PortFact("Installed", have?.ifBlank { "Yes" } ?: "No")
            PortFact(
                "Newest", when {
                    r == null -> if (Ports.limited()) "GitHub busy, try later" else "Checking…"
                    r.tag.isBlank() -> "No releases yet"
                    else -> r.tag + if (r.date.isNotBlank()) " · " + r.date else ""
                }, gold = st == PortState.UPDATE
            )
            if (r != null && r.tag.isNotBlank()) PortFact("Download", if (r.apkUrl == null) "No APK, see its page" else if (r.size > 0) vm.fmtBytes(r.size) else "APK")
            PortFact("From", "github.com/" + p.repo.substringBefore('/'))
            PortFact("Shows under", sysName(p.sys))
        }
        Text(p.desc, color = C.x(0xFFCFCAC0L), fontSize = 9.5.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 9.dp))
        Box(
            Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(C.x(0x0DFFFFFFL))
                .border(1.dp, C.x(0x14FFFFFFL), RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 6.dp)
        ) {
            Text("Needs your own copy of the game: the app asks for its files the first time it starts.", color = C.x(0xFFCFCAC0L), fontSize = 9.sp, lineHeight = 12.sp)
        }
        Column(Modifier.padding(top = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val label = when (st) {
                PortState.INSTALLED -> "OPEN"
                PortState.UPDATE -> "UPDATE" + (r?.version?.takeIf { it.isNotBlank() }?.let { " TO $it" } ?: "")
                PortState.DOWNLOADING -> "DOWNLOADING " + (vm.portProgress[p.id]?.takeIf { it >= 0 }?.let { "${(it * 100).toInt()}%" } ?: "…")
                PortState.PAGE -> "OPEN ITS PAGE"
                PortState.GET -> "GET"
            }
            PortButton(label, "A", gold = true) { primary(vm, p) }
            PortButton("WHAT'S NEW", "Y") { showNotes() }
            PortButton("GITHUB PAGE", "›") { vm.openUrl(p.url) }
        }
    }
}

@Composable
private fun PortFact(k: String, v: String, gold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, color = C.sub, fontSize = 10.sp, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(v, color = if (gold) C.accent else C.x(0xFFE6E1D7L), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 165.dp).padding(start = 8.dp))
    }
}

@Composable
private fun PortButton(label: String, key: String, gold: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    FocusBox(Modifier.fillMaxWidth().height(30.dp), shape = shape, onClick = onClick) { focused ->
        Row(
            Modifier.fillMaxSize().background(if (gold) C.accent else if (focused) C.accent.copy(alpha = 0.16f) else C.x(0x0FFFFFFFL))
                .border(1.dp, if (gold) C.accent else C.x(0x17FFFFFFL), shape).padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = if (gold) C.onAccent else C.x(0xFFE6E1D7L), fontSize = 10.5.sp, letterSpacing = 0.6.sp, fontWeight = if (gold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, modifier = Modifier.weight(1f))
            Text(key, color = if (gold) C.onAccent else C.sub, fontSize = 9.5.sp, maxLines = 1)
        }
    }
}

/**
 * The card on a game's page when there's a native port of it: Get / Update / Open, and More (the Ports screen on it).
 */
@Composable
fun PortCard(vm: EclipseVM, g: Game, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { vm.loadPorts() }
    val p = vm.portsFor(g).firstOrNull() ?: return
    LaunchedEffect(p.id) { vm.checkPort(p) }
    val st = stateOf(vm, p)
    val r = vm.portRels[p.id]
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(shape)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(C.accent.copy(alpha = 0.20f), C.x(0x0DFFFFFFL))))
            .border(1.dp, C.accent.copy(alpha = 0.45f), shape).padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PortIcon(vm, p, 30)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                if (vm.portPkg(p) != null) "NATIVE PORT INSTALLED" + (vm.portVersion(p)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") else "ALSO AS A NATIVE ANDROID PORT",
                color = C.accent, fontSize = 8.sp, letterSpacing = 1.8.sp, maxLines = 1
            )
            Text(
                p.title + " · " + p.author + when {
                    st == PortState.UPDATE -> " · ${r?.version} is out"
                    vm.portPkg(p) != null -> " · plays this game"
                    r != null && r.size > 0 -> " · " + vm.fmtBytes(r.size)
                    else -> ""
                },
                color = C.ink, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp)
            )
        }
        if (st != PortState.INSTALLED) CardAction(
            when (st) {
                PortState.UPDATE -> "UPDATE"; PortState.PAGE -> "PAGE"
                PortState.DOWNLOADING -> vm.portProgress[p.id]?.takeIf { it >= 0 }?.let { "${(it * 100).toInt()}%" } ?: "…"
                else -> "GET"
            }, gold = st == PortState.GET, key = "pagecard:get"
        ) { primary(vm, p) }
        Spacer(Modifier.width(6.dp))
        CardAction("MORE ›", key = "pagecard:more") { vm.portsFocus = p.id; vm.portsOpen = true }
    }
}

@Composable
private fun CardAction(label: String, gold: Boolean = false, key: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    FocusBox(Modifier.height(26.dp), shape = shape, onClick = onClick, focusKey = key) { focused ->
        Box(
            Modifier.fillMaxHeight().background(if (gold) C.accent else if (focused) C.accent.copy(alpha = 0.18f) else Color.Transparent)
                .border(1.dp, if (gold) C.accent else C.accent.copy(alpha = 0.55f), shape).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) { Text(label, color = if (gold) C.onAccent else C.accent, fontSize = 9.5.sp, letterSpacing = 1.sp, fontWeight = if (gold) FontWeight.Bold else FontWeight.Normal, maxLines = 1) }
    }
}

/** Play with: Ask each time. The port or the emulator, for this start only. */
@Composable
fun PlayWithSheet(vm: EclipseVM, g: Game) {
    val close = { vm.playAsk = null }
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    val port = vm.installedPortFor(g)?.first
    MenuSheet("Play with", g.name, "Change this in the game's Options → Plays with.", onDismiss = close) {
        if (port != null) MenuRow(port.title, value = "Port", fr = first) { close(); vm.play(g, "port") }
        MenuRow(vm.emuFor(g)?.label ?: "Emulator", value = "Emulator", fr = if (port == null) first else null) { close(); vm.play(g, "emu") }
    }
}
