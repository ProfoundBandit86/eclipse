package com.eclipse.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.focusable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.ArtKind
import com.eclipse.launcher.data.Game
import com.eclipse.launcher.data.Platforms
import com.eclipse.launcher.data.RomM
import com.eclipse.launcher.data.SearchQuery
import kotlinx.coroutines.delay

/**
 * Search (Up on On Deck, or the dock's search): a glass search bar, a strip of letters to type with the controller,
 * filter chips (All, Games, Apps, RomM), then the best match as a big card beside the other results grouped by kind.
 * Letters: Left / Right to pick, A types it, X deletes, Down jumps to the results (Up comes back), B closes.
 * Touch: tap a letter, or tap the bar for the on-screen keyboard. Recent searches sit at the bottom.
 * Voice (on by default, right dock): Up onto the bar, or Up on it, or the mic, opens Android's speech prompt; what
 * you say becomes the search and the controller moves to the top result. The letter strip is off by default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchOverlay(vm: EclipseVM) = Glass {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() } // the top result
    val chipFr = remember { FocusRequester() } // the first "understood" chip
    val keyboard = LocalSoftwareKeyboardController.current
    val letters = remember { FocusRequester() }
    var onLetters by remember { mutableStateOf(false) }
    var barFocused by remember { mutableStateOf(false) }
    var drawer by remember { mutableStateOf(false) }
    val close = { vm.searchOpen = false }
    val showLetters = vm.searchLetters
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // the last key pressed was Up: focus that lands on the bar that way starts voice search
    var cameUp by remember { mutableStateOf(false) }
    // ---- voice search: Android's speech prompt; what you say becomes the search ----
    var jumpToResults by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val speech = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        listening = false
        val said = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (r.resultCode == android.app.Activity.RESULT_OK && !said.isNullOrEmpty()) {
            query = said
            vm.addRecentSearch(said)
            jumpToResults = true
        }
    }
    val speak: () -> Unit = speak@{
        cameUp = false
        if (listening) return@speak
        keyboard?.hide()
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a game or app")
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (runCatching { listening = true; speech.launch(i) }.isFailure) {
            listening = false
            vm.message = "Voice search needs Google's speech service (the Google app)"
        }
    }
    // where the controller rests: the letters, or the search bar when they're hidden
    val home = { runCatching { if (vm.searchLetters) letters.requestFocus() else field.requestFocus() } }
    // controller starts on the letters (no on-screen keyboard); without them, on the bar
    LaunchedEffect(Unit) {
        withFrameNanos { }
        home()
        // no letter picker, voice on: start listening straight away (B on the prompt leaves you on the bar to type)
        if (!vm.searchLetters && vm.searchVoice) speak()
    }
    // R bumper: the right dock (where results come from, letters on / off)
    DisposableEffect(Unit) {
        val prev = GamepadFocus.rightDock
        val r: () -> Unit = { drawer = !drawer }
        GamepadFocus.rightDock = r
        onDispose { if (GamepadFocus.rightDock === r) GamepadFocus.rightDock = prev }
    }

    val q = query.trim().lowercase()
    val src = if (!vm.rommOn()) "local" else vm.searchSource
    // ---- understanding the search (data/SearchQuery.kt): systems, years, genres, players, favourites … become chips
    // under the bar; the words left over are the title. A sentence nothing matches can go to the Assistant.
    val parsed = remember(q) { SearchQuery.parse(q, Platforms.all) }
    var aiChips by remember(q) { mutableStateOf<List<SearchQuery.Chip>?>(null) }
    var asking by remember(q) { mutableStateOf(false) }
    var removed by remember(q) { mutableStateOf(setOf<String>()) }
    val chips = (aiChips ?: parsed.chips).filter { it.id !in removed }
    val structured = chips.any { it.key != "title" }
    // a plain search (just a name): the title chip's words, or what was typed
    val tq = if (aiChips != null || removed.isNotEmpty()) chips.firstOrNull { it.key == "title" }?.value ?: "" else q
    val typing = q.isNotEmpty()
    // what each game offers to match: name, system, year, genre, players, developer, publisher, favourite, played
    val facts = remember(vm.games, vm.gameInfo, vm.favGames, vm.playtime, typing) {
        if (!typing) emptyMap() else vm.games.associate { g ->
            g.id to SearchQuery.Facts(
                g.name, g.platform, SearchQuery.yearOf(vm.gameDetail(g, "y")), vm.gameDetail(g, "g"), SearchQuery.playersOf(vm.gameDetail(g, "p")),
                vm.gameDetail(g, "d"), vm.gameDetail(g, "pub"), vm.isFavGame(g), vm.playedSeconds(g) > 0
            )
        }
    }
    fun romFacts(r: RomM.Rom) = SearchQuery.Facts(r.name, r.bp, r.year, r.genres.joinToString(" "), null, r.companies.joinToString(" "), r.companies.joinToString(" "), false, false)
    fun details(f: SearchQuery.Facts) = "${f.year ?: ""} ${f.genre} ${f.developer} ${f.publisher}".lowercase()
    val local = remember(q, vm.games, src, facts, chips, tq) {
        when {
            q.isEmpty() || src == "romm" -> emptyList()
            structured -> vm.games.filter { it.id !in vm.hiddenGames && facts[it.id]?.let { f -> SearchQuery.matches(f, chips) } == true }
                .sortedBy { it.name.lowercase() }.take(120)
            tq.isEmpty() -> emptyList()
            // besides the name: the year, genre, developer and publisher; name matches first
            else -> vm.games.filter { it.id !in vm.hiddenGames && (tq in it.name.lowercase() || facts[it.id]?.let { f -> tq in details(f) } == true) }
                .sortedWith(compareBy<Game> { tq !in it.name.lowercase() }.thenBy { !it.name.lowercase().startsWith(tq) }.thenBy { it.name.lowercase() }).take(60)
        }
    }
    val remote = remember(q, vm.rommCatalog, vm.rommLink, src, chips, tq) {
        when {
            q.isEmpty() || src == "local" -> emptyList()
            structured -> vm.rommCatalog.filter { vm.installedRom(it.id) == null && SearchQuery.matches(romFacts(it), chips) }.sortedBy { it.name.lowercase() }.take(120)
            tq.isEmpty() -> emptyList()
            else -> vm.rommCatalog.filter { vm.installedRom(it.id) == null && (tq in it.name.lowercase() || tq in details(romFacts(it))) }
                .sortedWith(compareBy<RomM.Rom> { tq !in it.name.lowercase() }.thenBy { !it.name.lowercase().startsWith(tq) }.thenBy { it.name.lowercase() }).take(60)
        }
    }
    // a sentence (3+ words) nothing matched, with words left over and the Assistant set up: ask it for the filters
    val noneFound = local.isEmpty() && remote.isEmpty()
    LaunchedEffect(q, noneFound) {
        if (aiChips != null || !noneFound || parsed.words < 3 || parsed.leftover.isBlank() || !vm.aiReady()) return@LaunchedEffect
        delay(700) // still typing?
        asking = true
        val ans = runCatching {
            com.eclipse.launcher.data.AiHelper.plain(vm.aiProvider, vm.aiKey(), vm.aiModel(), SearchQuery.aiSystem(Platforms.all), query.trim())
        }
        asking = false
        val got = ans.getOrNull()?.let { SearchQuery.fromAi(it, Platforms.all) }
        if (!got.isNullOrEmpty()) { aiChips = got; removed = emptySet() }
        else if (ans.isFailure) vm.message = "The Assistant couldn't read that search: ${ans.exceptionOrNull()?.message ?: "no answer"}"
    }
    val apps = remember(q, vm.apps, vm.renames, src) {
        if (q.isEmpty() || src == "romm") emptyList() else vm.apps.filter { q in vm.nameOf(it).lowercase() }
            .sortedWith(compareBy<com.eclipse.launcher.data.AppEntry> { !vm.nameOf(it).lowercase().startsWith(q) }.thenBy { vm.nameOf(it).lowercase() }).take(20)
    }
    val showGames = filter == "all" || filter == "games"
    val showApps = filter == "all" || filter == "apps"
    val showRomm = filter == "all" || filter == "romm"
    val total = local.size + remote.size + apps.size
    val topGame = if (showGames) local.firstOrNull() else null
    val hasResults = (showGames && local.isNotEmpty()) || (showRomm && remote.isNotEmpty()) || (showApps && apps.isNotEmpty())

    LaunchedEffect(jumpToResults, hasResults) {
        if (jumpToResults) {
            jumpToResults = false
            withFrameNanos { }
            if (hasResults) runCatching { firstResult.requestFocus() }
        }
    }

    fun saveTerm(term: String) { if (term.isNotBlank()) vm.addRecentSearch(term.trim()) }
    val openGame = { g: Game -> saveTerm(query); close(); vm.focused = g; vm.menuGame = g }
    val openRom = { r: RomM.Rom ->
        saveTerm(query); close()
        // (the RomM store has been removed: a RomM game opens its game menu)
        vm.menuGame = Game("rm:${r.id}", r.name, r.bp, "romm:${r.id}")
    }

    // B on the letters (or on the bar, without letters) closes; anywhere else goes back there
    BackHandler { if (onLetters || (!showLetters && barFocused)) close() else home() }
    // (taps land here rather than on the screen behind; the keyboard closes with Back or Search)
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown) cameUp = e.key == Key.DirectionUp; false }
            .focusTrap()
    ) {
        // the top result's backdrop, blurred, behind everything
        if (topGame != null) FlowBackdrop(vm, topGame) else Box(Modifier.fillMaxSize().background(panelBackdrop(Color(0xE6000000))))
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(HomeDark.copy(alpha = 0.5f), HomeDark.copy(alpha = 0.85f)))))

        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 34.dp)) {
            // ---- the search bar ----
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(shape)
                    // a tap anywhere on the bar puts the cursor in it and brings up the keyboard
                    .pointerInput(Unit) { detectTapGestures { runCatching { field.requestFocus() }; keyboard?.show() } }
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(C.x(0x24FFFFFFL), C.x(0x0FFFFFFFL))))
                    .border(if (barFocused) 2.dp else 1.dp, if (barFocused) C.focus else C.x(0x29FFFFFFL), shape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = if (barFocused) C.focus else C.x(0xFFCFCAC0L), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(
                        if (listening) "Listening\u2026" else if (vm.searchVoice && barFocused) "Press Up to speak, A to type" else "Games, apps, genre, year, publisher",
                        color = C.sub, fontSize = 15.sp, fontFamily = FontFamily.Default
                    )
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = C.ink, fontSize = 16.sp, fontFamily = FontFamily.Default),
                        cursorBrush = SolidColor(C.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            if (hasResults) runCatching { firstResult.requestFocus() } else if (showLetters) runCatching { letters.requestFocus() }
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(field)
                            .onFocusChanged {
                                // reached by pressing Up (from the letters or the results): voice search
                                if (it.isFocused && !barFocused && cameUp && vm.searchVoice) { cameUp = false; speak() }
                                barFocused = it.isFocused
                            }
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    // Down: the letters, or straight to the results without them
                                    Key.DirectionDown -> {
                                        keyboard?.hide()
                                        if (showLetters) runCatching { letters.requestFocus() } else if (hasResults) runCatching { firstResult.requestFocus() }
                                        true
                                    }
                                    // A on the bar: the on-screen keyboard
                                    Key.DirectionCenter, Key.ButtonA -> { keyboard?.show(); true }
                                    // Up on the bar: voice search (when it's on)
                                    Key.DirectionUp -> { if (vm.searchVoice) speak(); true }
                                    else -> false
                                }
                            }
                    )
                }
                if (q.isNotEmpty()) Text("$total RESULT${if (total == 1) "" else "S"}", color = C.sub, fontSize = 8.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(end = 10.dp))
                if (query.isNotEmpty()) Box(
                    Modifier.size(24.dp).clip(CircleShape).background(C.x(0x1FFFFFFFL)).pointerInput(Unit) { detectTapGestures { query = "" } },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Close, contentDescription = "Clear", tint = C.x(0xFFCFCAC0L), modifier = Modifier.size(14.dp)) }
                // the mic: tap to speak
                if (vm.searchVoice) Box(
                    Modifier.padding(start = 8.dp).size(30.dp).clip(CircleShape)
                        .background(if (listening) C.accent else C.x(0x1FFFFFFFL))
                        .pointerInput(Unit) { detectTapGestures { speak() } },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Mic, contentDescription = "Voice search", tint = if (listening) C.onAccent else C.accent, modifier = Modifier.size(18.dp)) }
            }

            // ---- letters (can be hidden from the right dock) ----
            if (showLetters) {
                LetterStrip(
                    letters,
                    onFocus = { onLetters = it },
                    onType = { c -> query += c },
                    onDelete = { if (query.isNotEmpty()) query = query.dropLast(1) },
                    onDown = { if (hasResults) runCatching { firstResult.requestFocus() } },
                    onUp = { runCatching { field.requestFocus() } }
                )
                Text(
                    "◀ ▶ PICK · A TYPE · X DELETE · ▲ KEYBOARD · ▼ RESULTS", color = C.sub.copy(alpha = 0.7f), fontSize = 7.5.sp, letterSpacing = 1.4.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
                )
            } else {
                Text(
                    "A ON THE BAR: KEYBOARD · ▼ RESULTS", color = C.sub.copy(alpha = 0.7f), fontSize = 7.5.sp, letterSpacing = 1.4.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 8.dp)
                )
            }

            if (q.isEmpty()) {
                Spacer(Modifier.weight(1f))
            } else {
                // ---- what the search understood: one chip each (A removes it); blue ones came from the Assistant ----
                if (structured || asking || aiChips != null) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text("UNDERSTOOD", color = C.sub.copy(alpha = 0.7f), fontSize = 7.5.sp, letterSpacing = 1.4.sp, modifier = Modifier.align(Alignment.CenterVertically))
                        chips.forEachIndexed { i, c -> UnderstoodChip(c, if (i == 0) chipFr else null) { removed = removed + c.id } }
                        if (asking) Text(
                            "\u2726 Asking the Assistant\u2026", color = AiBlue, fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.CenterVertically).border(1.dp, AiBlue.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                // ---- filter chips ----
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    (listOf("all" to ("All" to total), "games" to ("Games" to local.size), "apps" to ("Apps" to apps.size), "romm" to ("RomM" to remote.size)))
                        .filter { (it.first != "romm" || (vm.rommOn() && src != "local")) && (src != "romm" || it.first == "all" || it.first == "romm") }
                        .forEach { (id, lc) ->
                            val on = filter == id
                            FocusBox(shape = RoundedCornerShape(50), onClick = { filter = id }) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(50))
                                        .background(if (on) C.accent.copy(alpha = 0.18f) else C.x(0x12FFFFFFL))
                                        .border(1.dp, if (on) C.accent.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(50))
                                        .padding(horizontal = 11.dp, vertical = 5.dp)
                                ) {
                                    Text(lc.first.uppercase(), color = if (on) C.accent else C.x(0xFFB9B4AAL), fontSize = 8.sp, letterSpacing = 1.2.sp)
                                    Text(" ${lc.second}", color = C.sub.copy(alpha = 0.8f), fontSize = 8.sp)
                                }
                            }
                        }
                }

                if (!hasResults) {
                    Text("Nothing matches “${query.trim()}”", color = C.sub, fontFamily = FontFamily.Default, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.weight(1f))
                } else {
                    // ---- every match in one grid: games here, on RomM and apps together, best matches first ----
                    val hits = remember(local, remote, apps, filter, q) {
                        val l = ArrayList<SearchHit>()
                        if (showGames) local.forEach { g -> l.add(SearchHit("g:" + g.id, g.name, vm.artOf(g, ArtKind.BOX), Platforms.byId(g.platform)?.short ?: g.platform, "This device", game = g)) }
                        if (showRomm) remote.forEach { r -> l.add(SearchHit("r:" + r.id, r.name, r.cover, Platforms.byId(r.bp)?.short ?: r.platformName, "RomM", rom = r)) }
                        if (showApps) apps.forEach { a -> l.add(SearchHit("a:" + a.pkg, vm.nameOf(a), null, "App", "Installed", app = a)) }
                        l.sortedWith(compareBy<SearchHit> { !it.name.lowercase().startsWith(q) }.thenBy { it.app != null }.thenBy { it.name.lowercase() })
                    }
                    val cols = 6
                    var focusedIdx by remember { mutableIntStateOf(-1) }
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        androidx.compose.foundation.lazy.grid.GridCells.Fixed(cols),
                        Modifier.weight(1f).fillMaxWidth()
                            // Up from the top row: straight back to the search bar (voice search, when it's on)
                            .onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && focusedIdx in 0 until cols) {
                                    if (chips.isNotEmpty() && (structured || aiChips != null)) runCatching { chipFr.requestFocus() }
                                    else { cameUp = true; runCatching { field.requestFocus() } }
                                    true
                                } else false
                            },
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        gridItemsIndexed(hits, key = { _, h -> h.key }) { i, h ->
                            Box(Modifier.onFocusChanged { if (it.hasFocus) focusedIdx = i }) {
                                HitCard(vm, h, q, if (i == 0) firstResult else null) {
                                    when {
                                        h.game != null -> openGame(h.game)
                                        h.rom != null -> openRom(h.rom)
                                        h.app != null -> { saveTerm(query); close(); vm.openApp(h.app.pkg) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- recent searches, bottom left; hints, bottom right ----
        if (vm.recentSearches.isNotEmpty()) Row(
            Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("RECENT", color = C.sub.copy(alpha = 0.7f), fontSize = 7.5.sp, letterSpacing = 1.4.sp)
            vm.recentSearches.take(4).forEach { t ->
                FocusBox(shape = RoundedCornerShape(50), onClick = { query = t; filter = "all" }) {
                    Text(t, color = C.x(0xFFB9B4AAL), fontSize = 10.sp, fontFamily = FontFamily.Default, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(C.x(0x0FFFFFFFL)).padding(horizontal = 9.dp, vertical = 3.dp))
                }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // the same button badges as slide view
            ButtonHint("A", "Open")
            if (showLetters) ButtonHint("X", "Delete")
            ButtonHint("R", "Options")
            ButtonHint("B", if (onLetters || (!showLetters && barFocused)) "Close" else if (showLetters) "Letters" else "Search bar")
        }

        // ---- the right dock: where results come from, and the letters on or off ----
        if (!drawer) {
            Box(
                Modifier.align(Alignment.CenterEnd).size(18.dp, 72.dp).pointerInput(Unit) { detectTapGestures { drawer = true } },
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(Modifier.padding(end = 5.dp).size(5.dp, 48.dp).clip(RoundedCornerShape(3.dp)).background(C.x(0x99A0A0A0L)))
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = drawer,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut()
        ) {
            val sources = listOf("all" to "All", "local" to "Local", "romm" to "RomM")
            OptionsDrawer(
                vm, "Search",
                // Source only with RomM connected
                (if (vm.rommOn()) listOf(DrawerList("Source", sources, src) { vm.chooseSearchSource(it); filter = "all" }) else emptyList()) + listOf(
                    DrawerList("Letter picker", listOf("on" to "Shown", "off" to "Hidden"), if (vm.searchLetters) "on" else "off") { vm.chooseSearchLetters(it == "on") },
                    DrawerList("Voice search", listOf("on" to "On", "off" to "Off"), if (vm.searchVoice) "on" else "off") { vm.chooseSearchVoice(it == "on") }
                )) {
                drawer = false
                home()
            }
        }
    }
}

/** A name with the part you typed in gold. */
@Composable
private fun highlight(name: String, q: String): androidx.compose.ui.text.AnnotatedString {
    val gold = C.accent
    return androidx.compose.ui.text.buildAnnotatedString {
        val i = if (q.isEmpty()) -1 else name.lowercase().indexOf(q)
        if (i < 0) append(name) else {
            append(name.substring(0, i))
            pushStyle(androidx.compose.ui.text.SpanStyle(color = gold)); append(name.substring(i, i + q.length)); pop()
            append(name.substring(i + q.length))
        }
    }
}




/** One search result of any kind (a game here, a game on RomM, an app). */
private class SearchHit(
    val key: String, val name: String, val art: String?, val system: String, val source: String,
    val game: Game? = null, val rom: RomM.Rom? = null, val app: com.eclipse.launcher.data.AppEntry? = null
)


/** A result: its cover (or app icon), the name with the typed part in gold, and system · where it's from under it. */
@Composable
private fun HitCard(vm: EclipseVM, h: SearchHit, q: String, fr: FocusRequester?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        FocusBox(Modifier.fillMaxWidth().aspectRatio(0.75f), shape = RoundedCornerShape(8.dp), onClick = onClick, focusRequester = fr) {
            if (h.app != null) Box(Modifier.fillMaxSize().background(C.x(0x0FFFFFFFL)), contentAlignment = Alignment.Center) {
                AppIcon(h.app, Modifier.fillMaxSize(0.55f).clip(RoundedCornerShape(12.dp)))
            } else Cover(h.name, h.art, Modifier.fillMaxSize())
            // the same badges as Recently Played: the red cloud / download circle, upload progress, disc number
            if (h.rom != null) RommBadgeFor(h.rom.id, Modifier.align(Alignment.TopEnd))
            else if (h.game != null) GameBadge(vm, h.game, Modifier.align(Alignment.TopEnd))
        }
        Text(highlight(h.name, q), color = C.x(0xFFCFCAC0L), fontSize = 11.sp, fontFamily = FontFamily.Default, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        Text(
            (h.system + "  \u00B7  " + h.source).uppercase(), color = if (h.rom != null) C.accent.copy(alpha = 0.85f) else C.sub.copy(alpha = 0.8f),
            fontSize = 7.5.sp, letterSpacing = 0.6.sp, fontFamily = FontFamily.Default, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 "

/**
 * Every letter in one row: the highlighted one is a gold tile (a little bigger), its neighbours faintly lit.
 * Left / Right move (hold to keep going), A types, X deletes, Down leaves. A physical keyboard types straight in too.
 */
@Composable
private fun LetterStrip(
    fr: FocusRequester,
    onFocus: (Boolean) -> Unit,
    onType: (Char) -> Unit,
    onDelete: () -> Unit,
    onDown: () -> Unit,
    onUp: () -> Unit
) {
    var idx by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp).height(30.dp)
            .focusRequester(fr)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { if (idx > 0) idx--; true }
                    Key.DirectionRight -> { if (idx < LETTERS.lastIndex) idx++; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA -> { onType(LETTERS[idx]); true }
                    Key.ButtonX, Key.Backspace, Key.Delete -> { onDelete(); true }
                    Key.DirectionDown -> { onDown(); true }
                    Key.DirectionUp -> { onUp(); true }
                    else -> {
                        val ch = e.nativeKeyEvent.unicodeChar.toChar()
                        if (ch.isLetterOrDigit() || ch == ' ') {
                            onType(ch.uppercaseChar())
                            LETTERS.indexOf(ch.uppercaseChar()).takeIf { it >= 0 }?.let { idx = it }
                            true
                        } else false
                    }
                }
            }
            .focusable(),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LETTERS.forEachIndexed { i, c ->
            val d = kotlin.math.abs(i - idx)
            val on = d == 0
            val near = d in 1..2
            val space = c == ' '
            val sc by androidx.compose.animation.core.animateFloatAsState(if (on && focused) 1.25f else 1f, label = "letter")
            Box(
                Modifier.then(if (space) Modifier.width(34.dp) else Modifier.width(15.dp)).height(22.dp)
                    .graphicsLayer { scaleX = sc; scaleY = sc }
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) (if (focused) C.accent else C.accent.copy(alpha = 0.35f)) else if (near && focused) C.x(0x0FFFFFFFL) else Color.Transparent)
                    .pointerInput(i) { detectTapGestures { idx = i; onType(c); runCatching { fr.requestFocus() } } },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (space) "SPACE" else c.toString(),
                    color = if (on) C.onAccent else if (near && focused) C.x(0xFFCFCAC0L) else C.sub.copy(alpha = 0.8f),
                    fontSize = if (space) 6.sp else 9.sp, letterSpacing = if (space) 1.sp else 0.sp
                )
            }
        }
    }
}

/** The Assistant's colour on the search chips (blue, so its guesses stand apart from what Eclipse understood itself). */
private val AiBlue = Color(0xFF9DB2E6)

/** One thing the search understood ("SYSTEM SNES ✕"): A (or a tap) removes it. */
@Composable
private fun UnderstoodChip(c: SearchQuery.Chip, fr: FocusRequester?, onRemove: () -> Unit) {
    val tint = if (c.ai) AiBlue else C.accent
    val shape = RoundedCornerShape(50)
    FocusBox(shape = shape, onClick = onRemove, focusRequester = fr, focusKey = "chip:${c.id}") {
        Row(
            Modifier.clip(shape).background(tint.copy(alpha = 0.14f)).border(1.dp, tint.copy(alpha = 0.55f), shape)
                .padding(start = 10.dp, end = 7.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (c.ai) Text("\u2726 ", color = tint, fontSize = 9.sp)
            Text(SearchQuery.keyOf(c), color = tint.copy(alpha = 0.85f), fontSize = 7.5.sp, letterSpacing = 1.2.sp)
            Text(" " + SearchQuery.shown(c), color = C.ink, fontSize = 11.sp, maxLines = 1, fontFamily = FontFamily.Default)
            Text("  \u2715", color = C.sub, fontSize = 9.sp)
        }
    }
}
