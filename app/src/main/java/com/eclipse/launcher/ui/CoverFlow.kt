package com.eclipse.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.ArtKind
import com.eclipse.launcher.data.Game
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** How much bigger the middle cover is than its size setting (it "swells up" when chosen). */
private const val SWELL = 1.16f

/** Background of the cover flow (the reflections fade into it). */
val FlowBg: Color @Composable get() = if (ThemeColors.light.value) Color(0xFFEEEAE1) else Color(0xFF0A0A0C)

/**
 * Cover flow for games: the chosen game faces you in the middle, the others stand angled to either side.
 * Left / Right (or swipe) to browse, A (or tap the middle cover) to play, Select for the game menu.
 * [floor] is the colour behind it (reflections fade into it); [background] paints it, or leaves what's behind showing.
 */
@Composable
fun CoverFlow(
    vm: EclipseVM, games: List<Game>, focusRequester: FocusRequester, modifier: Modifier = Modifier,
    background: Boolean = true, floor: Color = FlowBg, onSelect: ((Game) -> Unit)? = null,
    scale: Float = vm.posterScale,
    /** (position, a counter): jump to that game (the letter list); the counter makes the same letter jump again */
    jumpTo: Pair<Int, Int>? = null,
    /** in place of the play stats, bottom left (the games screen puts its letter list there) */
    bottomBar: (@Composable BoxScope.() -> Unit)? = null,
    /** Down on the covers (the letter list) */
    onDown: (() -> Unit)? = null,
    /**
     * "detail" (Cover flow, on every screen: centred, logo + details + play line under the covers) or "stage" (Detail cover flow: the game's details and buttons on the left, the covers flowing on the right).
     */
    look: String = "detail"
) {
    if (games.isEmpty()) return
    val stage = look == "stage"
    val detail = look == "detail"
    val rowStyle = LocalArtStyle.current // (Recently Played's own Style, when it has one)
    Box(modifier) {
    Flow3D(
        items = games,
        itemKey = { it.id },
        startIndex = games.indexOfFirst { it.id == vm.focused?.id }.coerceAtLeast(0),
        jumpTo = jumpTo,
        focusRequester = focusRequester,
        modifier = Modifier.fillMaxSize(),
        onDown = onDown,
        aspect = 0.75f,
        corner = vm.artCorner,
        floor = floor,
        background = background,
        scale = scale,
        centerAt = if (stage) 0.68f else 0.5f,
        before = if (stage) 2 else 5,
        tilt = 60f,
        stepK = if (stage) 0.34f else 0.36f,
        onIndex = { vm.focused = it },
        onActivate = { vm.play(it) },
        onContext = { vm.menuGame = it },
        onSelect = onSelect,
        face = { g, m ->
            Box(m) {
                GameArt(vm, g, Modifier.fillMaxSize())
                GameBadge(vm, g, Modifier.align(Alignment.TopEnd))
                if (vm.isFavGame(g)) FavDot(Modifier.align(Alignment.TopStart))
            }
        },
        ownRing = { g -> ownOutline(vm, g, styleOf(vm, g, rowStyle)) },
        ownRingFace = { g, m, on ->
            Box(m) {
                GameArt(vm, g, Modifier.fillMaxSize(), on)
                GameBadge(vm, g, Modifier.align(Alignment.TopEnd))
            }
        },
        captionBottom = 40.dp,
        onFaceBounds = { g, r -> vm.artBounds[g.id] = r },
        // the logo (or name), its details and where you are in the list (the "Detail cover flow" look has them on the left)
        caption = { g, i, n ->
            if (!stage) {
                GameHeading(vm, g, center = true, logoHeight = 50.dp)
                GameMetaRow(vm, g, Modifier.padding(top = 7.dp), center = true)
                if (detail) SlidePlayLine(vm, g, Modifier.padding(top = 6.dp))
                FlowScrubber(i, n, Modifier.padding(top = if (detail) 8.dp else 10.dp))
            }
        }
    )
    // X: details, Y: favourite; play stats bottom left, button hints bottom right
    val cur = games.firstOrNull { it.id == vm.focused?.id }
    GameKeys(vm, cur)
    if (stage && cur != null) StageDetails(
        vm, cur, games.indexOf(cur), games.size,
        Modifier.align(Alignment.TopStart).fillMaxWidth(0.37f).fillMaxHeight().padding(start = 28.dp, top = 6.dp, bottom = 40.dp)
    )
    if (bottomBar != null) bottomBar() else if (!stage) FlowStats(vm, cur, Modifier.align(Alignment.BottomStart))
    if (stage) Row(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ButtonHint("Start", "Menu"); Spacer(Modifier.width(14.dp)); ButtonHint("R", "View")
    }
    else SlideHints(Modifier.align(Alignment.BottomEnd))
    }
}

/** A gold heart in a cover's corner: one of your favourites. */
@Composable
private fun FavDot(modifier: Modifier) {
    Box(modifier.padding(5.dp).size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.accent)) {
        CenteredGlyph("\u2665", C.onAccent, 10.sp, Modifier.fillMaxSize(), fill = 0.5f)
    }
}

/**
 * "Detail cover flow" look, left side: the game's logo, details, a few lines of its description, play stats and buttons you can
 * tap (the controller has the same on A, X and Y).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageDetails(vm: EclipseVM, g: Game, index: Int, total: Int, modifier: Modifier) {
    LaunchedEffect(g.id) { if (vm.descOf(g) == null) runCatching { vm.ensureDesc(g) } }
    Column(modifier) {
        Box(Modifier.heightIn(max = 76.dp)) { GameHeading(vm, g, logoHeight = 70.dp) }
        GameMetaRow(vm, g, Modifier.padding(top = 10.dp))
        val desc = vm.descOf(g)
        if (!desc.isNullOrBlank()) Text(
            desc, color = C.x(0xFFE9E5DCL), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 9.dp)
        )
        val t = vm.playedSeconds(g); val last = vm.lastPlayedLabel(g)
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StageStat("Last played", last ?: "Never")
            if (t > 0) StageStat("Play time", fmtDuration(t))
            StageStat("Game", "${index + 1} of $total")
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StageButton("A", "Play", gold = true) { vm.play(g) }
            StageButton("X", "Details") { if (vm.menuGame == null) vm.menuGame = g }
            if (!g.id.startsWith("rm:")) StageButton(null, if (vm.isFavGame(g)) "\u2665" else "\u2661", heart = true) { vm.toggleFavGame(g) }
        }
    }
}

@Composable
private fun StageStat(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = C.sub, fontSize = 7.5.sp, letterSpacing = 1.6.sp, maxLines = 1)
        Text(value, color = C.ink, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A button on the "Detail cover flow" details: tap it (it isn't a stop for the D-pad: A / X / Y do the same). */
@Composable
private fun StageButton(key: String?, label: String, gold: Boolean = false, heart: Boolean = false, onTap: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.height(30.dp).clip(shape)
            .background(if (gold) C.accent else C.x(0x99141416L))
            .border(1.dp, if (gold) C.accent else C.x(0x26FFFFFFL), shape)
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .padding(horizontal = if (heart) 11.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (key != null) {
            Box(
                Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .border(1.dp, if (gold) C.onAccent.copy(alpha = 0.6f) else C.x(0x8CFFFFFFL), androidx.compose.foundation.shape.CircleShape)
            ) { CenteredGlyph(key, if (gold) C.onAccent else C.ink, 7.5.sp, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(7.dp))
        }
        Text(
            if (heart) label else label.uppercase(), color = if (gold) C.onAccent else if (heart) C.accent else C.ink,
            fontSize = if (heart) 13.sp else 10.sp, letterSpacing = if (heart) 0.sp else 1.4.sp,
            fontWeight = if (gold) FontWeight.Bold else FontWeight.Normal, maxLines = 1
        )
    }
}

/**
 * Backdrop for the two new cover flow looks: the highlighted game's backdrop sharp and bright (not blurred like the
 * classic one), shaded where the writing goes: down the left for "Detail cover flow" ([side]), round the edges and along
 * the bottom for "Cover flow".
 */
@Composable
fun StageBackdrop(vm: EclipseVM, focusNow: Game?, side: Boolean, image: Any? = null) {
    val focus = rememberSettled(focusNow)
    val wall = vm.wallpaperTop()
    val sys = vm.sysWall
    // [image]: a picture of its own (a system's theme background on the systems row) in place of the game's backdrop
    val bg = if (vm.wallpaperAlways() && (wall != null || sys)) wall
        else image ?: focus?.let { vm.artOf(it, ArtKind.BACKDROP) } ?: wall
    SideEffect { GlassSource.model = bg }
    val base = HomeDark
    Box(Modifier.fillMaxSize().background(if (sys) Color.Transparent else base)) {
        if (bg != null) SteadyImage(bg, Modifier.fillMaxSize(), error = wall)
        if (side) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to base.copy(alpha = 0.9f), 0.36f to base.copy(alpha = 0.62f), 0.62f to base.copy(alpha = 0.12f), 1f to base.copy(alpha = 0.35f)
            )))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to base.copy(alpha = 0.35f), 0.18f to base.copy(alpha = 0f), 0.62f to base.copy(alpha = 0f), 1f to base.copy(alpha = 0.88f)
            )))
        } else {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(base.copy(alpha = 0.12f), base.copy(alpha = 0.78f)), radius = 1500f)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to base.copy(alpha = 0.55f), 0.22f to base.copy(alpha = 0f), 0.55f to base.copy(alpha = 0f), 0.82f to base.copy(alpha = 0.92f), 1f to base
            )))
        }
    }
}

/**
 * The cover-flow engine, for anything with a picture: games (3:4 covers) or platform cards (square).
 * [face] draws one item at the size it's given; [caption] goes under the row for the middle item.
 */
@Composable
fun <T> Flow3D(
    items: List<T>,
    itemKey: (T) -> Any,
    startIndex: Int,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    jumpTo: Pair<Int, Int>? = null,
    onDown: (() -> Unit)? = null,
    aspect: Float = 0.75f,
    /** corner radius of each face (default: 16 dp for square-ish ones, 8 dp for portrait) */
    corner: androidx.compose.ui.unit.Dp? = null,
    floor: Color = FlowBg,
    background: Boolean = true,
    /** poster size: 1 = normal (Settings → Appearance → Poster size) */
    scale: Float = 1f,
    onIndex: (T) -> Unit,
    onActivate: (T) -> Unit,
    onContext: ((T) -> Unit)? = null,
    /** Select, when it should do something other than [onContext] */
    onSelect: ((T) -> Unit)? = null,
    face: @Composable (T, Modifier) -> Unit,
    /** Items that draw their own highlight (a disc's outline) instead of the box ring: drawn with [ownRingFace] when chosen. */
    ownRing: (T) -> Boolean = { false },
    ownRingFace: (@Composable (T, Modifier, Boolean) -> Unit)? = null,
    /** room under the name (cover flow for games keeps a line free at the bottom for stats and button hints) */
    captionBottom: androidx.compose.ui.unit.Dp = 18.dp,
    /** where the middle item's face is on screen (root coordinates, swell included): the game menu flips out of it */
    onFaceBounds: ((T, androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    /** where the middle item sits across the width (0.5 = centre; the "Detail cover flow" look puts it right of its details) */
    centerAt: Float = 0.5f,
    /** how many items are drawn before the middle one (after it: always 5) */
    before: Int = 5,
    /** how far the side items turn (degrees), and how close they stand (fraction of a face's width) */
    tilt: Float = 52f,
    stepK: Float = 0.46f,
    caption: @Composable ColumnScope.(T, Int, Int) -> Unit
) {
    if (items.isEmpty()) return
    var idx by remember(items.size) { mutableIntStateOf(startIndex.coerceIn(0, items.lastIndex)) }
    LaunchedEffect(jumpTo) { jumpTo?.let { idx = it.first.coerceIn(0, items.lastIndex) } }
    val safe = idx.coerceIn(0, items.lastIndex)
    val current = items[safe]
    // position in "covers" (fractional while sliding); no bounce, so it never overshoots
    val pos = remember(items.size) { Animatable(safe.toFloat()) }
    val scope = rememberCoroutineScope()
    val currentItem by rememberUpdatedState(current)
    LaunchedEffect(safe) {
        onIndex(current)
        pos.animateTo(safe.toFloat(), spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f))
    }

    // Select = the context menu for the middle item, like the long-press on other tiles
    val token = remember { Any() }
    var hasFocus by remember { mutableStateOf(false) }
    DisposableEffect(token) {
        onDispose {
            if (GamepadFocus.owner === token) { GamepadFocus.owner = null; GamepadFocus.context = null; GamepadFocus.select = null }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .then(if (background) Modifier.background(floor) else Modifier)
            .focusRequester(focusRequester)
            .onFocusChanged {
                hasFocus = it.isFocused
                if (it.isFocused) {
                    GamepadFocus.owner = token
                    GamepadFocus.context = onContext?.let { f -> { f(currentItem) } }
                    GamepadFocus.select = onSelect?.let { f -> { f(currentItem) } }
                } else if (GamepadFocus.owner === token) {
                    GamepadFocus.owner = null
                    GamepadFocus.context = null
                    GamepadFocus.select = null
                }
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> if (idx > 0) { idx--; com.eclipse.launcher.media.UiSound.tick(); true } else false // at the start: let Left reach the dock
                    Key.DirectionRight -> if (idx < items.lastIndex) { idx++; com.eclipse.launcher.media.UiSound.tick(); true } else false // at the end: whatever's to the right (e.g. a drawer)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA -> { onActivate(currentItem); true }
                    Key.DirectionDown -> if (onDown != null) { onDown(); true } else false
                    else -> false
                }
            }
            .focusable()
    ) {
        val density = LocalDensity.current
        val faceH = ((maxHeight * 0.52f).coerceIn(120.dp, 320.dp) * scale).coerceAtMost(maxHeight * 0.72f)
        val faceW = faceH * aspect
        val reflH = faceH * 0.28f
        // more room around the middle (it swells) and between the side items
        val centerGap = faceW * (if (aspect >= 1f) 0.92f else 1.04f) // middle item to the first side item
        val sideStep = faceW * stepK                                 // between side items
        val shiftX = maxWidth * (centerAt - 0.5f)                    // the middle item off-centre
        val stepPx = with(density) { sideStep.toPx() }

        // touch: swipe to browse, tap the middle to open, tap a side to move that way
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(items.size) {
                    var acc = 0f
                    detectHorizontalDragGestures(onDragEnd = { acc = 0f }) { change, dx ->
                        change.consume()
                        acc += dx
                        while (acc <= -stepPx && idx < items.lastIndex) { idx++; acc += stepPx }
                        while (acc >= stepPx && idx > 0) { idx--; acc -= stepPx }
                    }
                }
                .pointerInput(items.size) {
                    detectTapGestures(onLongPress = { o ->
                        // long-press the middle cover: the same as Select
                        val half = with(density) { (faceW / 2).toPx() }
                        if (abs(o.x - size.width * centerAt) <= half) (onSelect ?: onContext)?.invoke(currentItem)
                    }) { o ->
                        val mid = size.width * centerAt
                        val half = with(density) { (faceW / 2).toPx() }
                        when {
                            abs(o.x - mid) <= half -> onActivate(currentItem)
                            o.x < mid -> if (idx > 0) idx--
                            else -> if (idx < items.lastIndex) idx++
                        }
                        scope.launch { runCatching { focusRequester.requestFocus() } }
                    }
                }
        ) {
            val p = pos.value
            val from = (p - before).roundToInt().coerceAtLeast(0)
            val to = (p + 5).roundToInt().coerceAtMost(items.lastIndex)
            for (i in from..to) {
                val item = items[i]
                val d = i - p                       // signed distance from the middle, in items
                val near = d.coerceIn(-1f, 1f)
                val x = if (abs(d) <= 1f) centerGap * d else (centerGap + sideStep * (abs(d) - 1f)) * sign(d)
                key(itemKey(item)) {
                    Column(
                        Modifier
                            .align(Alignment.Center)
                            .offset(x = x + shiftX, y = reflH / 2 - 18.dp)
                            .zIndex(-abs(d))
                            .graphicsLayer {
                                rotationY = -tilt * near    // side items turn to face the middle
                                // the middle one swells up and lifts a little; the sides sit back
                                val a = abs(near)
                                val s = SWELL + (0.82f - SWELL) * a
                                scaleX = s
                                scaleY = s
                                translationY = -faceH.toPx() * 0.05f * (1f - a)
                                cameraDistance = 14f * density.density
                                val lim = if (d < 0f) before.toFloat() else 5f // the last one shown on each side fades in / out
                                alpha = if (abs(d) > lim - 1f) (lim - abs(d)).coerceIn(0f, 1f) else 1f
                            },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val shape = RoundedCornerShape(corner ?: if (aspect >= 1f) 16.dp else 8.dp)
                        val ring = hasFocus && i == safe && abs(d) < 0.05f
                        val placed = if (onFaceBounds != null && i == safe) Modifier.onGloballyPositioned { onFaceBounds(item, it.boundsInRoot()) } else Modifier
                        if (ownRingFace != null && ownRing(item)) ownRingFace(item, Modifier.size(faceW, faceH).then(placed), ring)
                        else {
                            val dim = 0.35f * abs(near)
                            face(
                                item,
                                Modifier.size(faceW, faceH).then(placed)
                                    // the chosen cover glows in the highlight colour
                                    .then(if (ring) Modifier.shadow(28.dp, shape, ambientColor = C.focus, spotColor = C.focus) else Modifier)
                                    .clip(shape)
                                    .then(if (ring) Modifier.border(3.dp, C.focus, shape) else Modifier)
                                    // the side covers sit back a little
                                    .drawWithContent { drawContent(); if (dim > 0.01f) drawRect(Color.Black.copy(alpha = dim)) }
                            )
                        }
                        // mirror image on the "floor", fading out (only near the middle: far ones are small and dim anyway)
                        if (abs(d) <= 3f) Box(
                            Modifier.padding(top = 4.dp).size(faceW, reflH).clipToBounds()
                                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen; alpha = 0.3f }
                                .drawWithContent {
                                    drawContent()
                                    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent)), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                                }
                        ) {
                            Box(Modifier.align(Alignment.TopCenter).requiredSize(faceW, faceH).graphicsLayer { scaleY = -1f }) {
                                face(item, Modifier.fillMaxSize().clip(shape))
                            }
                        }
                    }
                }
            }
        }

        // name of the middle item under the row
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = captionBottom, start = 40.dp, end = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            caption(current, safe, items.size)
        }
    }
}
