package com.eclipse.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.blur
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex

/** Colours for one look. The light look is for the Apps / Platforms screens; [GlassPal] is the dark frosted look. */
@Immutable
data class Palette(val bg: Color, val ink: Color, val sub: Color, val card: Color, val line: Color, val light: Boolean = false)

/** Eclipse's own look: black, with gold for whatever is selected or highlighted. */
val LightPal = Palette(Color(0xFF0B0B0D), Color(0xFFFAFAFA), Color(0xFFC4C4CA), Color(0xFF1A1A1E), Color(0x22FFFFFF))

/** Dark, semi-transparent "frosted glass": used by Settings, menus and pop-ups, over a blurred background. */
val GlassPal = Palette(
    bg = Color(0xC00A0A0C),     // panel / menu body
    ink = Color(0xFFFAFAFC),
    sub = Color(0xFFC8CAD2),
    card = Color(0x1FFFFFFF),   // rows and buttons on the glass
    line = Color(0x26FFFFFF)    // hairline edge that catches the light
)

/** Light mode's panels: warm off-white, white cards, near-black writing (Settings → Appearance → Mode). */
val GlassLightPal = Palette(
    bg = Color(0xEBF7F4EE),
    ink = Color(0xFF1B1A17),
    sub = Color(0xFF6F6A60),
    card = Color(0xFFFFFFFF),
    line = Color(0x17000000),
    light = true
)

val LocalPal = staticCompositionLocalOf { LightPal }

/** Light mode's home screens and dock (Settings → Appearance → Mode). */
val HomeLightPal = Palette(Color(0xFFEEEAE1), Color(0xFF1B1A17), Color(0xFF6F6A60), Color(0xFFFFFFFF), Color(0x17000000), light = true)

/** A full-screen backdrop behind panels: [dark] normally, warm off-white in light mode. */
@Composable
fun panelBackdrop(dark: Color, light: Color = Color(0xFFEEEAE1)): Color =
    if (ThemeColors.light.value) light.copy(alpha = maxOf(dark.alpha, 0.92f)) else dark

/** Theme colours. bg / ink / sub / card / line follow the surrounding look ([Glass] or light). */
object C {
    val bg: Color @Composable @ReadOnlyComposable get() = LocalPal.current.bg
    /** Text: your text colour (Settings → Appearance) if you picked one, else the look's own. */
    // (your colour is used as it is, dark ones too: a pale theme like Dune needs dark writing on its light glass)
    val ink: Color @Composable @ReadOnlyComposable get() = ThemeColors.text.value.takeIf { it.isSpecified } ?: LocalPal.current.ink
    val sub: Color @Composable @ReadOnlyComposable get() = ThemeColors.labels.value.takeIf { it.isSpecified } ?: LocalPal.current.sub
    val card: Color @Composable @ReadOnlyComposable get() = LocalPal.current.card
    val line: Color @Composable @ReadOnlyComposable get() = LocalPal.current.line
    /**
     * A colour written for the dark look, swapped for its light-mode partner when the surrounding panels are light:
     * see-through white becomes see-through black (or the writing colour, for text), near-black surfaces become
     * off-white, and light greys become the writing colours. Over the wallpaper (the home screen) nothing changes.
     */
    @Composable @ReadOnlyComposable
    fun x(argb: Long): Color {
        val c = Color(argb.toInt())
        val pal = LocalPal.current
        val a = c.alpha
        if (!pal.light) {
            // the light greys the UI writes with (names, labels, hints) follow your text / labels colours when set,
            // so the colour picker changes all the writing, not just the few places that ask for it by name
            if (a >= 0.5f && (ThemeColors.text.value.isSpecified || ThemeColors.labels.value.isSpecified)) {
                val lum = c.luminance()
                val spread = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
                if (spread < 0.12f && lum > 0.25f) return (if (lum > 0.6f) ink else sub).copy(alpha = a)
            }
            return brighter(c)
        }
        val rgb = argb and 0xFFFFFF
        if (rgb == 0xFFFFFFL) return if (a >= 0.5f) ink.copy(alpha = a) else Color.Black.copy(alpha = (a * 0.7f).coerceAtMost(1f))
        val lum = c.luminance()
        return when {
            lum < 0.05f -> Color(0xFFF7F4EE).copy(alpha = a)
            lum > 0.6f -> ink.copy(alpha = a)
            lum > 0.25f -> pal.sub.copy(alpha = a)
            else -> c
        }
    }
    /**
     * Writing on the dark look, made easier to read: light greys (text, labels, hints) are lifted towards white and
     * made more solid. Faint see-through fills and dark colours (the panels and rows themselves) are left alone.
     */
    private fun brighter(c: Color): Color {
        if (c.alpha < 0.5f || c.luminance() < 0.25f) return c
        val t = 0.4f
        return Color(c.red + (1f - c.red) * t, c.green + (1f - c.green) * t, c.blue + (1f - c.blue) * t, (c.alpha + 0.2f).coerceAtMost(1f))
    }
    /** Selected buttons. A theme can change it. */
    val accent: Color get() = ThemeColors.accent.value
    /** The highlight outline. A theme can change it. */
    val focus: Color get() = ThemeColors.focus.value
    /** Text / icons on top of [accent]: dark on a light accent like gold, white on a dark one. */
    val onAccent: Color get() = if (accent.luminance() > 0.45f) Color(0xFF111111) else Color.White
}

/** Colours set by the active theme (or Eclipse's own when there's none). */
object ThemeColors {
    val DefaultAccent = Color(0xFFD4A537) // gold
    val DefaultFocus = Color(0xFFFFD86B)  // bright gold outline
    val accent = mutableStateOf(DefaultAccent)
    val focus = mutableStateOf(DefaultFocus)
    /** The font everything is written in (Settings → Appearance, or the theme's). */
    val font = mutableStateOf<FontFamily>(FontFamily.Default)
    /** Your text colour, or Unspecified for the look's own. */
    val text = mutableStateOf(Color.Unspecified)
    /** Light mode: Settings, menus and panels use [GlassLightPal]. */
    val light = mutableStateOf(false)
    /** Softer second writing colour (labels, dates, hints), or Unspecified for the look's own. */
    val labels = mutableStateOf(Color.Unspecified)
    /** The glass panels' tint, or Unspecified for the look's own (warm charcoal, or off-white in light mode). */
    val glass = mutableStateOf(Color.Unspecified)
    /**
     * The colour the screens' shading is made of (the dark behind and over wallpapers and game art, slide view's
     * fades): yours, else the theme's, else Unspecified for Eclipse's near-black.
     */
    val backdrop = mutableStateOf(Color.Unspecified)
    /** How solid the glass panels are: 0 (clear) … 1 (solid). */
    val glassAlpha = mutableStateOf(0.7f)
    /** How much the wallpaper / game art behind is dimmed: 0 … 1, or -1 for the look's own. */
    val dim = mutableStateOf(-1f)
    /** Low power mode (Settings → Appearance): no frosted glass or blur, plain tinted panels. Much lighter on the GPU. */
    val lowPower = mutableStateOf(false)
    /** Text size (Settings → Appearance → Font): 1 = normal. Multiplies Android's own font size setting. */
    val textScale = mutableStateOf(1f)
}

/** Material's text styles, all switched to [f]. */
private fun typographyWith(f: FontFamily): Typography {
    val t = Typography()
    return Typography(
        displayLarge = t.displayLarge.copy(fontFamily = f), displayMedium = t.displayMedium.copy(fontFamily = f),
        displaySmall = t.displaySmall.copy(fontFamily = f), headlineLarge = t.headlineLarge.copy(fontFamily = f),
        headlineMedium = t.headlineMedium.copy(fontFamily = f), headlineSmall = t.headlineSmall.copy(fontFamily = f),
        titleLarge = t.titleLarge.copy(fontFamily = f), titleMedium = t.titleMedium.copy(fontFamily = f),
        titleSmall = t.titleSmall.copy(fontFamily = f), bodyLarge = t.bodyLarge.copy(fontFamily = f),
        bodyMedium = t.bodyMedium.copy(fontFamily = f), bodySmall = t.bodySmall.copy(fontFamily = f),
        labelLarge = t.labelLarge.copy(fontFamily = f), labelMedium = t.labelMedium.copy(fontFamily = f),
        labelSmall = t.labelSmall.copy(fontFamily = f)
    )
}

@Composable
fun EclipseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = C.onAccent,
            background = LightPal.bg, surface = LightPal.card, onSurface = LightPal.ink, onSurfaceVariant = LightPal.sub
        ),
        typography = remember(ThemeColors.font.value) { typographyWith(ThemeColors.font.value) },
    ) {
        val d = androidx.compose.ui.platform.LocalDensity.current
        val scale = ThemeColors.textScale.value
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * scale),
            LocalPal provides if (ThemeColors.light.value) HomeLightPal else LightPal,
            content = content
        )
    }
}

/** Text inside keeps its size whatever Settings → Theme → Text size says (badges that have to fit their space). */
@Composable
fun FixedTextSize(content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val scale = ThemeColors.textScale.value.takeIf { it > 0f } ?: 1f
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale / scale), content = content)
}

/** Everything inside uses the dark frosted look (text, rows, buttons, switches, text fields). */
@Composable
fun Glass(content: @Composable () -> Unit) {
    val light = ThemeColors.light.value
    CompositionLocalProvider(LocalPal provides if (light) GlassLightPal else GlassPal) {
        MaterialTheme(
            colorScheme = if (light) androidx.compose.material3.lightColorScheme(
                primary = C.accent, onPrimary = C.onAccent,
                background = Color(0xFFF7F4EE), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEDE9E0),
                onSurface = GlassLightPal.ink, onSurfaceVariant = GlassLightPal.sub, outline = Color(0x40000000)
            ) else darkColorScheme(
                primary = C.accent, onPrimary = C.onAccent,
                background = Color(0xFF141519), surface = Color(0xFF1E1F24), surfaceVariant = Color(0xFF2A2B31),
                onSurface = GlassPal.ink, onSurfaceVariant = GlassPal.sub, outline = Color(0x55FFFFFF)
            ),
            content = content
        )
    }
}

/** Blur radius for whatever sits behind an open menu. (Blur needs Android 12+; older devices just get the dark tint.) */
@Composable
fun behindBlur(on: Boolean): Modifier {
    val r by animateDpAsState(if (on) 22.dp else 0.dp, label = "behindBlur")
    return if (r > 0.5.dp && !ThemeColors.lowPower.value) Modifier.blur(r) else Modifier
}

/** [blur], except in low power mode, where it does nothing. */
@Composable
fun Modifier.softBlur(radius: androidx.compose.ui.unit.Dp): Modifier = if (ThemeColors.lowPower.value) this else this.blur(radius)

/**
 * What the controller is on.
 *  - [context]: the Select-button action for the focused item (its long-press action).
 *  - [lastKey] / [restore]: where focus goes back to after a menu closes.
 */
object GamepadFocus {
    internal var owner: Any? = null
    /** The D-pad is being held (a repeat, not a fresh press): lists keep scrolling, but nothing jumps to the next screen. */
    @Volatile var held = false
    /** Start (and Select, unless [select] is set): the focused item's menu / info page (its long-press action). */
    var context: (() -> Unit)? = null
        internal set
    /** Select, when the focused item has its own Select action (On Deck: "remove from Recently Played?"). */
    var select: (() -> Unit)? = null
        internal set
    var lastKey: String? = null
        internal set
    var restoreKey by mutableStateOf<String?>(null)

    /** The main item of whatever is on screen; used when a D-pad press finds nothing focused. */
    var primary: FocusRequester? = null
        internal set
    /** A full-screen page over everything (the setup wizard): a lost controller goes back to it, never behind it. */
    var modal: FocusRequester? = null
    /** True while focus is somewhere inside the modal screen (the setup wizard). */
    @Volatile var modalHasFocus = false
    /** Set by Root: puts focus somewhere sensible (the screen's main item, else the dock). */
    var rescue: (() -> Unit)? = null

    /** L2 / R2 triggers (-1 / +1), set by the screen that uses them (a system's games: previous / next system). */
    var shoulder: ((Int) -> Unit)? = null
    /** A game's page: L1 / R1 move between its tabs (-1 / +1). */
    var pageTab: ((Int) -> Unit)? = null
    /** X and Y face buttons (slide view: details, favourite), set by the screen that uses them. */
    var buttonX: (() -> Unit)? = null
    var buttonY: (() -> Unit)? = null
    /** L bumper, for a screen that uses it for itself (the colour screen); null = the left dock ([dockOpener]). */
    var leftDock: (() -> Unit)? = null
    /** The left dock's own LB action (set only by Root). [leftDock], when a screen sets it, overrides it for that screen. */
    var dockOpener: (() -> Unit)? = null
    /** R bumper: open / close the current screen's right-hand options drawer (set by screens that have one). */
    var rightDock: (() -> Unit)? = null

    /** Put focus back on the item that was focused before a menu opened. */
    fun restore() { restoreKey = lastKey }
    /** When Left was last pressed (uptime ms): focus reaching the left dock soon after is on purpose, otherwise not. */
    @Volatile var lastLeftMs = 0L
}

/**
 * Focusable + clickable container with a Switch-style cyan focus ring. Works with touch, D-pad and gamepad.
 * [onLongClick] doubles as the Select-button action. Give it a [focusKey] so focus can return to it after a menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
    onSelect: (() -> Unit)? = null,
    /** false: no outline around the box (the content draws its own highlight, like a disc's contour) */
    ring: Boolean = true,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "focusScale")
    val req = focusRequester ?: remember { FocusRequester() }
    val token = remember { Any() }
    val contextAction by rememberUpdatedState(onLongClick)
    val selectAction by rememberUpdatedState(onSelect)

    if (focusKey != null) {
        val restore = GamepadFocus.restoreKey
        LaunchedEffect(restore) {
            if (restore == focusKey) {
                withFrameNanos { }
                runCatching { req.requestFocus() }
                GamepadFocus.restoreKey = null
            }
        }
    }
    DisposableEffect(token) {
        onDispose {
            if (GamepadFocus.owner === token) {
                GamepadFocus.owner = null
                GamepadFocus.context = null
                GamepadFocus.select = null
            }
        }
    }

    Box(
        modifier
            .scale(scale)
            .clip(shape)
            // only when focused: a 0 dp border still draws a 1-pixel line around everything
            .then(if (focused && ring) Modifier.border(3.dp, C.focus, shape) else Modifier)
            .focusRequester(req)
            .onFocusChanged {
                if (it.isFocused && !focused) com.eclipse.launcher.media.UiSound.tick()
                focused = it.isFocused
                if (it.isFocused) {
                    GamepadFocus.owner = token
                    GamepadFocus.context = if (contextAction != null) ({ contextAction?.invoke() }) else null
                    GamepadFocus.select = if (selectAction != null) ({ selectAction?.invoke() }) else null
                    if (focusKey != null) GamepadFocus.lastKey = focusKey
                } else if (GamepadFocus.owner === token) {
                    GamepadFocus.owner = null
                    GamepadFocus.context = null
                    GamepadFocus.select = null
                }
                onFocusChange?.invoke(it.isFocused)
            }
            // a long-press does what Select does (its own action when there is one, else the item's menu)
            .combinedClickable(onClick = onClick, onLongClick = if (onSelect != null) onSelect else onLongClick)
    ) { content(focused) }
}

/** Controller focus can't leave this area while it is shown (menus drawn over other screens). */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.focusTrap(): Modifier = this.focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()

/** Centred menu card over a dimmed screen. Focus stays inside; B or a tap outside closes it. */
@Composable
fun ModalCard(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    BackHandler { dismiss() }
    Box(
        Modifier.fillMaxSize().background(Color(0x59000000)).pointerInput(Unit) { detectTapGestures { dismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Glass {
            Column(
                Modifier
                    .widthIn(min = 300.dp, max = 480.dp)
                    .dockPanel(RoundedCornerShape(22.dp))
                    .pointerInput(Unit) { detectTapGestures { } } // taps inside the card don't close it
                    .focusTrap()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

/**
 * A small menu in the right dock's look: an optional picture, a gold label, a title and a grey line, then its rows
 * ([MenuRow]). B or a tap outside closes it.
 */
@Composable
fun MenuSheet(
    kicker: String, title: String, sub: String? = null, onDismiss: () -> Unit,
    image: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit
) {
    ModalCard(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (image != null) { image(); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(kicker.uppercase(), color = C.accent, fontSize = 9.sp, letterSpacing = 2.sp)
                Text(title, color = C.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (sub != null) Text(sub, color = C.sub, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.x(0x1AFFFFFFL)))
        Column(verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
    }
}

/** One choice in a [MenuSheet]: the name in small capitals, a gold value or arrow; [gold] fills it (the main action). */
@Composable
fun MenuRow(
    label: String, value: String? = null, gold: Boolean = false, danger: Boolean = false, selected: Boolean = false,
    fr: FocusRequester? = null, note: String? = null, enabled: Boolean = true, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(9.dp)
    val light = ThemeColors.light.value
    val rose = Color(0xFFF08AA0)
    FocusBox(Modifier.fillMaxWidth().heightIn(min = 36.dp), shape = shape, onClick = { if (enabled) onClick() }, focusRequester = fr) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp)
                .background(when { gold -> C.accent; danger -> rose.copy(alpha = 0.12f); selected -> C.accent.copy(alpha = if (light) 0.14f else 0.10f); light -> Color.White; else -> C.x(0x0FFFFFFFL) })
                .border(1.dp, when { gold -> C.accent; danger -> rose.copy(alpha = 0.35f); selected -> C.accent.copy(alpha = 0.45f); else -> C.x(0x12FFFFFFL) }, shape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) Text("\u2713", color = C.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label.uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when { gold -> C.onAccent; danger -> rose; !enabled -> C.sub.copy(alpha = 0.5f); else -> C.x(0xFFCFCAC0L) },
                    fontSize = 9.5.sp, letterSpacing = 1.5.sp, fontWeight = if (gold) FontWeight.Bold else FontWeight.Normal
                )
                if (note != null) Text(note, color = if (gold) C.onAccent.copy(alpha = 0.7f) else C.sub, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (value != null) Text(value.uppercase(), color = if (gold) C.onAccent else C.accent, fontSize = 9.sp, letterSpacing = 1.2.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            else if (!gold && !danger && enabled) Text("\u203A", color = C.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Requests focus on [req] once the first frame is laid out (and makes it the screen's main item). */
@Composable
fun FocusOnShow(req: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        withFrameNanos { }
        runCatching { req.requestFocus() }
    }
    PrimaryFocus(req)
}

/** Registers [req] as where focus goes if a D-pad press finds nothing focused. Restores the previous one on exit. */
@Composable
fun PrimaryFocus(req: FocusRequester) {
    DisposableEffect(req) {
        val prev = GamepadFocus.primary
        GamepadFocus.primary = req
        onDispose { if (GamepadFocus.primary === req) GamepadFocus.primary = prev }
    }
}

fun placeholderColor(name: String): Color {
    val h = (name.hashCode() and 0x7fffffff) % 360
    return Color.hsv(h.toFloat(), 0.45f, 0.55f)
}

@Composable
fun Cover(name: String, url: String?, modifier: Modifier = Modifier) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(C.card)
        )
    } else {
        Box(modifier.background(placeholderColor(name)).padding(8.dp), contentAlignment = Alignment.Center) {
            Text(
                name, color = C.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    val gold = primary && !LocalPlainButtons.current
    // the right dock's button: a slim rounded row, small capitals; gold when it's the main action
    val shape = RoundedCornerShape(9.dp)
    // as wide as its label unless the caller gives it a width (weight, fillMaxWidth, width): without this the fill
    // below stretched a bare Pill across the whole row and pushed the buttons after it off the screen
    FocusBox(modifier.height(36.dp).width(IntrinsicSize.Max), shape = shape, onClick = onClick, focusRequester = focusRequester) {
        // the fill, with the label centred inside it (a full-width pill keeps its label in the middle)
        Box(
            Modifier.fillMaxSize().background(if (gold) C.accent else if (ThemeColors.light.value) Color.White else C.x(0x14FFFFFFL))
                .border(1.dp, if (gold) C.accent else C.x(0x14FFFFFFL), shape)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    androidx.compose.material3.Icon(icon, null, tint = if (gold) C.onAccent else C.accent, modifier = Modifier.padding(end = 7.dp).size(15.dp))
                }
                Text(
                    text.uppercase(),
                    color = if (gold) C.onAccent else C.x(0xFFCFCAC0L),
                    fontSize = 10.sp, letterSpacing = 1.3.sp,
                    fontWeight = if (gold) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** true: every [Pill] looks the same, none gold (the setup wizard, where a gold button read as the selected one). */
val LocalPlainButtons = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Eclipse's button shape: all four corners cut at 45 degrees, each of the eight points slightly rounded.
 * [cut] = how far the cut reaches along each edge, [radius] = how much each point is softened.
 */
class ChamferedShape(private val cut: androidx.compose.ui.unit.Dp = 10.dp, private val radius: androidx.compose.ui.unit.Dp = 3.dp) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        val w = size.width
        val h = size.height
        val c = with(density) { cut.toPx() }.coerceAtMost(kotlin.math.min(w, h) / 2f)
        val r = with(density) { radius.toPx() }.coerceAtMost(c * 0.7f) // keep the diagonal edge visible
        val d = r * 0.7071f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(c + r, 0f)
            lineTo(w - c - r, 0f); quadraticTo(w - c, 0f, w - c + d, d)
            lineTo(w - d, c - d); quadraticTo(w, c, w, c + r)
            lineTo(w, h - c - r); quadraticTo(w, h - c, w - d, h - c + d)
            lineTo(w - c + d, h - d); quadraticTo(w - c, h, w - c - r, h)
            lineTo(c + r, h); quadraticTo(c, h, c - d, h - d)
            lineTo(d, h - c + d); quadraticTo(0f, h - c, 0f, h - c - r)
            lineTo(0f, c + r); quadraticTo(0f, c, d, c - d)
            lineTo(c - d, d); quadraticTo(c, 0f, c + r, 0f)
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

val EclipseButtonShape = ChamferedShape(cut = 10.dp, radius = 3.dp)

fun fmtDuration(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m" else "${sec}s"
}


/**
 * Right joystick → scrolls the list in the menu that's open. Each menu's list registers itself while it's on screen;
 * the most recently shown one (the top-most menu) is the one the stick scrolls. [y] is written by MainActivity.
 */
object RightStick {
    @Volatile var y = 0f
    private val stack = ArrayList<androidx.compose.foundation.gestures.ScrollableState>()
    fun push(s: androidx.compose.foundation.gestures.ScrollableState) { stack.remove(s); stack.add(s) }
    fun pop(s: androidx.compose.foundation.gestures.ScrollableState) { stack.remove(s) }
    fun target(): androidx.compose.foundation.gestures.ScrollableState? = stack.lastOrNull()

    /** Lets the right stick scroll [state] while this is on screen. */
    @Composable
    fun Register(state: androidx.compose.foundation.gestures.ScrollableState) {
        DisposableEffect(state) { push(state); onDispose { pop(state) } }
    }

    /** Runs for as long as the screen is up: while the stick is pushed, scroll (faster the further it's pushed). */
    @Composable
    fun Driver() {
        val px = with(androidx.compose.ui.platform.LocalDensity.current) { 26.dp.toPx() }
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos { }
                val v = y
                if (kotlin.math.abs(v) > 0.18f) {
                    val k = (kotlin.math.abs(v) - 0.18f) / 0.82f
                    runCatching { target()?.dispatchRawDelta(kotlin.math.sign(v) * k * k * px + kotlin.math.sign(v) * 2f) }
                }
            }
        }
    }
}

/** verticalScroll that the right joystick can also scroll (for menus and Settings pages). */
@Composable
fun Modifier.menuScroll(): Modifier {
    val st = rememberScrollState()
    RightStick.Register(st)
    return this.verticalScroll(st)
}

/**
 * Scrolling that keeps the focused row in the middle of the page (instead of just inside its edge) for as long as
 * the page can scroll that far: Settings uses it, so the highlight stays near the centre of the screen.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val CenterBringIntoView: androidx.compose.foundation.gestures.BringIntoViewSpec = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val smaller = size <= containerSize
        val lead = 0.5f * containerSize - 0.5f * size
        val target = if (smaller && containerSize - lead < size) containerSize - size else lead
        return offset - target
    }
}

/**
 * A full-screen picture (wallpaper, backdrop) that never flashes blank when it changes: the picture on screen stays
 * until the next one has finished loading, then the new one fades in over it. Two fixed slots take turns, so the one
 * showing is never reloaded. [error] is shown in place of a picture that won't load.
 */
@Composable
fun SteadyImage(model: Any?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, error: Any? = null) {
    val slots = remember { mutableStateListOf<Any?>(model, null) }
    var front by remember { mutableIntStateOf(0) }
    val alphas = remember { listOf(androidx.compose.animation.core.Animatable(1f), androidx.compose.animation.core.Animatable(0f)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) {
        val back = 1 - front
        when {
            model == slots[front] -> { alphas[back].snapTo(0f); slots[back] = null }
            model == null -> { slots[0] = null; slots[1] = null }
            else -> { alphas[back].snapTo(0f); slots[back] = model }
        }
    }
    val errorPainter = error?.let { coil.compose.rememberAsyncImagePainter(it) }
    Box(modifier) {
        for (i in 0..1) {
            val m = slots[i] ?: continue
            key(i) {
                val shown = {
                    if (i != front && slots[i] == m) scope.launch {
                        alphas[i].animateTo(1f, androidx.compose.animation.core.tween(220))
                        alphas[1 - i].snapTo(0f)
                        front = i
                    }
                }
                AsyncImage(
                    m, null,
                    // the incoming picture sits on top of the one showing
                    Modifier.fillMaxSize().zIndex(if (i == front) 0f else 1f).graphicsLayer { alpha = alphas[i].value },
                    contentScale = contentScale, error = errorPainter,
                    onSuccess = { shown() }, onError = { shown() }
                )
            }
        }
    }
}

/** List / grid states the right joystick can scroll. */
@Composable
fun stickListState(): androidx.compose.foundation.lazy.LazyListState =
    androidx.compose.foundation.lazy.rememberLazyListState().also { RightStick.Register(it) }

@Composable
fun stickGridState(): androidx.compose.foundation.lazy.grid.LazyGridState =
    androidx.compose.foundation.lazy.grid.rememberLazyGridState().also { RightStick.Register(it) }

/**
 * Up / Down move between the things inside this area and stop at its first and last: reaching the end of a list
 * doesn't throw the controller out into whatever is beside it (the left dock, above all).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.stayInside(): Modifier {
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val scrollUp = LocalScrollUp.current
    // Up / Down never leave the list (the first item's Up doesn't jump across to the column beside it)
    return this.focusProperties {
        exit = { d -> if (d == androidx.compose.ui.focus.FocusDirection.Up || d == androidx.compose.ui.focus.FocusDirection.Down) FocusRequester.Cancel else FocusRequester.Default }
    }.focusGroup().onKeyEvent { e ->
        if (e.key != Key.DirectionUp && e.key != Key.DirectionDown) return@onKeyEvent false
        if (e.type == KeyEventType.KeyDown) {
            val up = e.key == Key.DirectionUp
            val moved = fm.moveFocus(if (up) androidx.compose.ui.focus.FocusDirection.Up else androidx.compose.ui.focus.FocusDirection.Down)
            if (up) scrollUp?.invoke(!moved)
        }
        true
    }
}

/**
 * Set by a scrolling page (Settings): told after every Up inside a [stayInside] list, with true when there was
 * nothing further up. The page then scrolls right back to the top, so the headings above the first row show again.
 */
val LocalScrollUp = androidx.compose.runtime.staticCompositionLocalOf<((Boolean) -> Unit)?> { null }
