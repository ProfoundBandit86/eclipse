package com.eclipse.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.ArtKind
import com.eclipse.launcher.data.Game
import com.eclipse.launcher.data.Platforms

/*
 * Slide view (On Deck on Home, and a system's games): the highlighted game's backdrop across the whole screen, its
 * logo and when you last played it / for how long up top, and big covers along the bottom (the highlighted one
 * biggest, its top just under the details; the others lower, down by the button hints). + (Start) or X opens the
 * details, Y adds or removes a favourite.
 */

/**
 * [v], but only once it has stopped changing for [ms]: the backdrop behind a fast scroll stays on the picture you
 * started from instead of flashing through every game's.
 */
@Composable
fun <T> rememberSettled(v: T, ms: Long = 300L): T {
    var settled by remember { mutableStateOf(v) }
    LaunchedEffect(v) { if (v != settled) { kotlinx.coroutines.delay(ms); settled = v } }
    return settled
}

/** Poster size in slide view, relative to the other views. */
const val SLIDE_POSTER = 1.1f

/** Games' slide view: covers this much smaller again, so the logo and description fit above them. */
const val SLIDE_GAME = 0.62f

/**
 * Games' slide view backdrop: the highlighted game's screenshot (its backdrop, else its hero, else the wallpaper) in the
 * top-right corner, fading into the dark on its left and below it, where the logo, description and covers sit.
 */
@Composable
fun SlideCornerBackdrop(
    vm: EclipseVM, focusNow: Game?,
    /** a picture to show instead of the game's (the platforms' slide view: a theme's system background) */
    image: String? = null,
    /** false: don't make this the picture the glass panels blur */
    glass: Boolean = true
) {
    val focus = rememberSettled(focusNow)
    val wall = vm.wallpaperTop()
    val shot = image ?: focus?.let { vm.artOf(it, ArtKind.BACKDROP) ?: vm.artOf(it, ArtKind.HERO) } ?: wall
    SideEffect { if (glass) GlassSource.model = shot }
    val base = HomeDark
    Box(Modifier.fillMaxSize().background(if (vm.sysWall) Color.Transparent else base)) {
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.6f).fillMaxHeight(0.6f)) {
            if (shot != null) SteadyImage(shot, Modifier.fillMaxSize(), error = wall)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to base, 0.3f to base.copy(alpha = 0.6f), 0.65f to base.copy(alpha = 0f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to base.copy(alpha = 0f), 0.5f to base.copy(alpha = 0f), 1f to base)))
        }
    }
}

/** The highlighted game's backdrop (or your wallpaper) over the whole screen, shaded from the left and the bottom. */
@Composable
fun SlideBackdrop(
    vm: EclipseVM, focusNow: Game?, shelf: androidx.compose.ui.unit.Dp? = null,
    /** a picture to show instead of the game's (the platforms' slide view: a theme's system background) */
    image: String? = null,
    /** false: don't make this the picture the glass panels blur (another backdrop on screen is the main one) */
    glass: Boolean = true
) {
    val focus = rememberSettled(focusNow)
    val wall = vm.wallpaperTop()
    val sys = vm.sysWall // Android's wallpaper shows through instead
    // slide view (with a [shelf]): the game's hero banner above the line when it has one, else its backdrop
    val bg = if (vm.wallpaperAlways() && (wall != null || sys)) wall
        else image ?: focus?.let { (if (shelf != null) vm.artOf(it, ArtKind.HERO) else null) ?: vm.artOf(it, ArtKind.BACKDROP) } ?: wall
    SideEffect { if (glass) GlassSource.model = bg }
    val base = HomeDark
    Box(Modifier.fillMaxSize().background(if (sys) Color.Transparent else base)) {
        // a backdrop that won't load (gone, or offline) falls back to the wallpaper
        if (bg != null) SteadyImage(bg, Modifier.fillMaxSize(), error = wall)
        else if (!sys) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(placeholderColor(focus?.name ?: "Eclipse"), base))))
        if (ThemeColors.light.value || ThemeColors.dim.value >= 0f) Box(Modifier.fillMaxSize().background(WallpaperDim))
        // cinematic shade: dark down the left (where the logo and details sit) and along the bottom (the covers)
        // (with the gold line the details sit low, beside the cover: the hero above stays clear)
        if (shelf == null) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to base.copy(alpha = 0.92f), 0.32f to base.copy(alpha = 0.7f), 0.62f to base.copy(alpha = 0f)
        )))
        if (shelf == null) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to base.copy(alpha = 0.25f), 0.12f to base.copy(alpha = 0f), 0.45f to base.copy(alpha = 0f),
            0.7f to base.copy(alpha = 0.6f), 1f to base.copy(alpha = 0.96f)
        )))
        else {
            // above the gold line: the picture, shaded a little toward the line so the game's details read
            Box(Modifier.fillMaxSize().padding(bottom = shelf).background(Brush.verticalGradient(
                0f to base.copy(alpha = 0.2f), 0.15f to base.copy(alpha = 0f), 0.6f to base.copy(alpha = 0f), 1f to base.copy(alpha = 0.55f)
            )))
            // below it: frosted glass (the same picture, blurred) behind the covers, and the gold line on top
            Box(Modifier.fillMaxWidth().height(shelf).align(Alignment.BottomStart).clipToBounds()) {
                val blurOk = bg != null && android.os.Build.VERSION.SDK_INT >= 31 && !ThemeColors.lowPower.value
                if (blurOk) Box(
                    Modifier.fillMaxWidth().requiredHeight(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp).align(Alignment.BottomStart)
                        .blur(22.dp)
                ) { SteadyImage(bg, Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(base.copy(alpha = if (blurOk) 0.55f else 0.85f)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.01f)))))
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.accent.copy(alpha = 0.55f)))
            }
        }
    }
}

/**
 * The top of slide view: the highlighted game's logo (or name), then when you last played it and for how long.
 * The screen's clock sits at the top right on its own. With no game, [emptyTitle] / [emptyText] show instead.
 * ([chip], [systemKeys] and [clock] are no longer shown: the slide view keeps its top clear.)
 */
@Composable
fun SlideHero(
    vm: EclipseVM, g: Game?, chip: String, modifier: Modifier = Modifier,
    systemKeys: Boolean = false, clock: Boolean = true,
    emptyTitle: String = "Nothing here yet", emptyText: String = ""
) {
    GameKeys(vm, g)
    Column(modifier) {
        if (g == null) {
            Text(emptyTitle, color = C.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (emptyText.isNotEmpty()) Text(emptyText, color = C.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            return@Column
        }
        // top left: the logo, a few lines of the description, then year, genre, who made it and the rating
        Column(Modifier.widthIn(max = 380.dp)) {
            AdaptiveLogo(vm, g, 58.dp)
            val desc = vm.descOf(g)
            if (!desc.isNullOrBlank()) Text(
                desc, color = C.x(0xFFE6E3DAL), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
            val meta = listOf(vm.gameDetail(g, "y"), vm.gameDetail(g, "g"), vm.gameDetail(g, "d"), vm.gameDetail(g, "r").takeIf { it.isNotBlank() }?.let { "\u2605 " + it.substringBefore(" /") } ?: "")
                .filter { it.isNotBlank() }.joinToString("  \u00b7  ")
            Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (meta.isNotBlank()) {
                    Text(meta.uppercase(), color = C.x(0xFFB9B4AAL), fontSize = 8.5.sp, letterSpacing = 1.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(12.dp))
                }
                SlidePlayLine(vm, g)
            }
        }
    }
}

/**
 * The game's logo, with a soft shade behind it only when it would get lost: Eclipse looks at the logo's colour and
 * the picture behind it. A light logo on light art gets a dark shade; a dark logo on dark art a light glow; a logo the
 * same colour as the art a glow in the opposite colour. A logo that already stands out gets nothing.
 */
@Composable
fun AdaptiveLogo(vm: EclipseVM, g: Game, height: androidx.compose.ui.unit.Dp) {
    val logo = vm.artOf(g, ArtKind.LOGO)
    val bg = vm.artOf(g, ArtKind.BACKDROP) ?: vm.wallpaperTop()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var shade by remember(logo, bg) { mutableStateOf<Color?>(null) }
    LaunchedEffect(logo, bg) {
        shade = if (logo == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LogoShade.pick(ctx, logo, bg) }
    }
    val a by androidx.compose.animation.core.animateFloatAsState(if (shade != null) 1f else 0f, label = "logoShade")
    Box {
        val c = shade
        if (c != null) Box(
            Modifier.matchParentSize().graphicsLayer { scaleX = 1.3f; scaleY = 2.1f; alpha = a }
                .background(Brush.radialGradient(listOf(c, c.copy(alpha = c.alpha * 0.45f), Color.Transparent)))
        )
        GameHeading(vm, g, logoHeight = height)
    }
}

/** Works out [AdaptiveLogo]'s shade from small copies of the logo and the backdrop (kept per pair). */
object LogoShade {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Int>() // ARGB, 0 = none

    suspend fun pick(ctx: android.content.Context, logo: String, bg: String?): Color? {
        val key = "$logo|$bg"
        cache[key]?.let { return if (it == 0) null else Color(it) }
        val l = small(ctx, logo) ?: return null
        // the logo's average colour (its see-through parts don't count)
        var r = 0.0; var gg = 0.0; var b = 0.0; var n = 0.0
        for (y in 0 until l.height) for (x in 0 until l.width) {
            val p = l.getPixel(x, y); val al = (p ushr 24) / 255.0
            if (al < 0.5) continue
            r += (p shr 16 and 255) * al; gg += (p shr 8 and 255) * al; b += (p and 255) * al; n += al
        }
        if (n < 1) return null
        r /= n; gg /= n; b /= n
        val logoLum = lum(r, gg, b)
        // what's behind it: the left part of the backdrop, under the shade the slide view puts there
        val bgLum = bg?.let { small(ctx, it) }?.let { bm ->
            var s = 0.0; var c = 0
            for (y in (bm.height * 0.1).toInt() until (bm.height * 0.45).toInt()) for (x in 0 until (bm.width * 0.45).toInt()) {
                val p = bm.getPixel(x, y); s += lum((p shr 16 and 255).toDouble(), (p shr 8 and 255).toDouble(), (p and 255).toDouble()); c++
            }
            if (c == 0) null else (s / c) * 0.25 + 0.04 * 0.75
        } ?: 0.05
        val out: Color? = when {
            kotlin.math.abs(logoLum - bgLum) > 0.38 -> null                      // stands out already
            logoLum >= 0.55 -> Color(0f, 0f, 0f, 0.6f)                            // light logo: dark shade
            logoLum <= 0.3 -> Color(1f, 0.95f, 0.88f, 0.7f)                       // dark logo: light glow
            else -> {                                                             // coloured: the opposite colour
                val hsv = FloatArray(3)
                android.graphics.Color.RGBToHSV(r.toInt(), gg.toInt(), b.toInt(), hsv)
                hsv[0] = (hsv[0] + 180f) % 360f; hsv[1] = 0.45f; hsv[2] = 1f
                Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = 0.6f)
            }
        }
        cache[key] = out?.let { android.graphics.Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) } ?: 0
        return out
    }

    private fun lum(r: Double, g: Double, b: Double) = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0

    private suspend fun small(ctx: android.content.Context, data: Any): android.graphics.Bitmap? = runCatching {
        val req = coil.request.ImageRequest.Builder(ctx).data(data).size(48).allowHardware(false).build()
        val res = coil.Coil.imageLoader(ctx).execute(req) as? coil.request.SuccessResult ?: return null
        // always a small software copy (a hardware bitmap from the cache can't be read pixel by pixel)
        val d = res.drawable
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 48
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 48
        val scale = 48f / maxOf(w, h)
        val bw = maxOf(1, (w * scale).toInt()); val bh = maxOf(1, (h * scale).toInt())
        val src = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (src != null) {
            val soft = if (src.config == android.graphics.Bitmap.Config.HARDWARE) src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else src
            android.graphics.Bitmap.createScaledBitmap(soft, bw, bh, true)
        } else android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888).also { bm ->
            val cv = android.graphics.Canvas(bm); d.setBounds(0, 0, bw, bh); d.draw(cv)
        }
    }.getOrNull()
}

/** "LAST PLAYED  Yesterday  •  PLAY TIME  2 h 14 m" (or "Not played yet"). */
@Composable
fun SlidePlayLine(vm: EclipseVM, g: Game, modifier: Modifier = Modifier) {
    val t = vm.playedSeconds(g)
    val last = vm.lastPlayedLabel(g)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (t <= 0 && last == null) {
            Text("NOT PLAYED YET", color = C.sub, fontSize = 8.sp, letterSpacing = 1.6.sp)
            return@Row
        }
        if (last != null) {
            Text("LAST PLAYED", color = C.sub, fontSize = 8.sp, letterSpacing = 1.6.sp)
            Text(last.uppercase(), color = C.x(0xFFE6E3DAL), fontSize = 10.sp, letterSpacing = 0.8.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
        if (last != null && t > 0) Box(Modifier.padding(horizontal = 10.dp).size(3.dp).clip(CircleShape).background(C.sub))
        if (t > 0) {
            Text("PLAY TIME", color = C.sub, fontSize = 8.sp, letterSpacing = 1.6.sp)
            Text(fmtDuration(t).uppercase(), color = C.x(0xFFE6E3DAL), fontSize = 10.sp, letterSpacing = 0.8.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** X: details (the game menu). Y: favourite. For whichever game [g] is highlighted. */
@Composable
fun GameKeys(vm: EclipseVM, g: Game?) {
    DisposableEffect(g?.id) {
        val x: () -> Unit = { if (g != null && vm.menuGame == null) vm.menuGame = g }
        val y: () -> Unit = { if (g != null && vm.menuGame == null && !g.id.startsWith("rm:")) vm.toggleFavGame(g) }
        GamepadFocus.buttonX = x; GamepadFocus.buttonY = y
        onDispose {
            if (GamepadFocus.buttonX === x) GamepadFocus.buttonX = null
            if (GamepadFocus.buttonY === y) GamepadFocus.buttonY = null
        }
    }
}

/** Where you are (a chip), the L2 / R2 hint when there are other systems, and the clock. */
@Composable
fun SlideTopBar(
    vm: EclipseVM, chip: String, systemKeys: Boolean = false, clock: Boolean = true, modifier: Modifier = Modifier,
    /** drawn in place of the chip (cover flow: the system's logo) */
    logo: (@Composable () -> Unit)? = null,
    extra: (@Composable RowScope.() -> Unit)? = null
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (logo != null) logo() else Row(
            Modifier.clip(RoundedCornerShape(50)).background(C.x(0x14FFFFFFL)).border(1.dp, C.x(0x1AFFFFFFL), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("\u25C6", color = C.accent, fontSize = 9.sp)
            Spacer(Modifier.width(7.dp))
            Text(chip.uppercase(), color = C.ink, fontSize = 10.sp, letterSpacing = 1.2.sp, maxLines = 1)
        }
        if (systemKeys) {
            Spacer(Modifier.width(10.dp))
            KeyCap("L2"); Text("SYSTEM", color = C.sub, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 5.dp)); KeyCap("R2")
        }
        extra?.invoke(this)
        Spacer(Modifier.weight(1f))
        if (clock) Clock(vm)
    }
}

/** System tag, then year, genres and rating where known. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameMetaRow(vm: EclipseVM, g: Game, modifier: Modifier = Modifier, center: Boolean = false) {
    val rom = vm.romFor(g)
    val plat = Platforms.byId(g.platform)
    val bits = listOfNotNull(rom?.year?.toString()) + (rom?.genres?.take(2) ?: emptyList()) +
        listOfNotNull(rom?.rating?.let { "\u2605 %.1f".format(it / 10f) })
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (center) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            (plat?.short ?: g.platform).uppercase(), color = C.accent, fontSize = 9.sp, letterSpacing = 1.5.sp,
            modifier = Modifier.align(Alignment.CenterVertically).border(1.dp, C.accent.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
        )
        bits.forEachIndexed { i, b ->
            if (i > 0) Box(Modifier.align(Alignment.CenterVertically).size(3.dp).clip(CircleShape).background(C.sub))
            Text(b.uppercase(), color = C.x(0xFFCFCAC0L), fontSize = 10.sp, letterSpacing = 0.8.sp, maxLines = 1, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

/** Cover flow's backdrop: the highlighted game's backdrop (or your wallpaper) blurred and darkened behind the covers. */
@Composable
fun FlowBackdrop(vm: EclipseVM, focusNow: Game?) {
    val focus = rememberSettled(focusNow)
    val wall = vm.wallpaperTop()
    val sys = vm.sysWall // Android's wallpaper shows through instead
    val bg = if (vm.wallpaperAlways() && (wall != null || sys)) wall
        else focus?.let { vm.artOf(it, ArtKind.BACKDROP) } ?: wall
    SideEffect { GlassSource.model = bg }
    val base = HomeDark
    Box(Modifier.fillMaxSize().background(if (sys) Color.Transparent else base)) {
        if (bg != null) SteadyImage(bg, Modifier.fillMaxSize().softBlur(16.dp).alpha(0.55f), error = wall)
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(base.copy(alpha = 0.1f), base.copy(alpha = 0.85f)), radius = 1400f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to base.copy(alpha = 0.5f), 0.2f to base.copy(alpha = 0f), 0.55f to base.copy(alpha = 0f), 0.92f to base
        )))
    }
}

/** A slim gold bar showing where you are in the list, and "3 / 13". */
@Composable
fun FlowScrubber(index: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier.width(240.dp), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.x(0x1FFFFFFFL))) {
            val frac = if (total <= 1) 0f else index / (total - 1f)
            val w = (maxWidth * 0.08f).coerceAtLeast(10.dp)
            Box(Modifier.offset(x = (maxWidth - w) * frac).width(w).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(C.accent))
        }
        Text("${index + 1} / $total", color = C.sub, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/** Played / last played, in one line (cover flow, bottom left). */
@Composable
fun FlowStats(vm: EclipseVM, g: Game?, modifier: Modifier = Modifier) {
    if (g == null) return
    val t = vm.playedSeconds(g); val last = vm.lastPlayedLabel(g)
    Row(modifier.padding(start = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t > 0) { Text("PLAYED", color = C.sub, fontSize = 9.sp, letterSpacing = 1.4.sp); Text(fmtDuration(t).uppercase(), color = C.ink, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 6.dp, end = 16.dp)) }
        if (last != null) { Text("LAST PLAYED", color = C.sub, fontSize = 9.sp, letterSpacing = 1.4.sp); Text(last.uppercase(), color = C.ink, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 6.dp)) }
    }
}

/**
 * Games' slide view: the buttons along the bottom edge, on a dark strip (R options, Select menu, A play, B back,
 * X details, Y favourite). The letter list, when there is one, sits at its left end.
 */
@Composable
fun SlideHintBar(
    modifier: Modifier = Modifier,
    hints: List<Pair<String, String>> = listOf("R" to "Options", "Select" to "Menu", "A" to "Play", "B" to "Back", "X" to "Details", "Y" to "Favourite")
) {
    Row(
        modifier.fillMaxWidth().height(28.dp).background(Color(0x8C000000)).padding(start = 18.dp, end = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically
    ) {
        hints.forEach { (k, label) -> ButtonHint(k, label) }
    }
}

/** A  Play   +  Details   R  View, bottom right. (X still opens the details too, and Y still toggles a favourite.) */
@Composable
fun SlideHints(modifier: Modifier = Modifier, fav: Boolean = true) {
    Row(modifier.padding(end = 24.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        ButtonHint("A", "Play"); ButtonHint("Start", "Details"); ButtonHint("R", "View")
    }
}

/**
 * A button letter drawn from its outline: centred by its actual ink (some fonts' capitals sit high in their line) and
 * scaled so its height is [fill] of the box, whatever the font or text size setting. Always fits its circle.
 */
@Composable
fun CenteredGlyph(text: String, color: Color, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, fill: Float = 0.46f) {
    // drawn with Android's own text painter: it measures the exact ink of the characters, so whatever the font's
    // spacing above and below, the glyph lands dead centre
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    val r = remember { android.graphics.Rect() }
    androidx.compose.foundation.Canvas(modifier) {
        if (text.isEmpty() || size.width <= 0f || size.height <= 0f) return@Canvas
        paint.textSize = 100f
        paint.getTextBounds(text, 0, text.length, r)
        if (r.height() <= 0 || r.width() <= 0) return@Canvas
        // as tall as [fill] of the box, but never wider than 76% of it (numbers and two-letter keys)
        val k = minOf(size.height * fill / r.height(), size.width * 0.76f / r.width())
        paint.textSize = 100f * k
        paint.color = color.toArgb()
        paint.getTextBounds(text, 0, text.length, r)
        drawContext.canvas.nativeCanvas.drawText(text, size.width / 2f - r.exactCenterX(), size.height / 2f - r.exactCenterY(), paint)
    }
}

/**
 * A controller button drawn as a small badge, the same everywhere in Eclipse:
 *  - A, B, X, Y: round, with the letter centred by its ink (A in the accent colour: it's the main action)
 *  - L, R, LB, RB, L1, R1, L2, R2: a bumper-shaped tab, rounder on its outer top corner
 *  - Start (also "+", "Menu") and Select (also "Sel", "-", "View"): a pill with the symbol drawn (three lines / two squares)
 *  - L3, R3: a ring around the stick's letter
 *  - anything else: a pill with the name
 */
@Composable
fun ButtonBadge(key: String, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 17.dp) {
    val k = key.trim().uppercase()
    val ink = C.ink
    val fill = C.x(0x2EFFFFFFL)
    val edge = C.x(0x52FFFFFFL)
    when {
        k == "A" || k == "B" || k == "X" || k == "Y" -> {
            val gold = k == "A"
            Box(
                modifier.size(height).clip(CircleShape).background(if (gold) C.accent else fill)
                    .border(1.dp, if (gold) C.accent else edge, CircleShape)
            ) { CenteredGlyph(k, if (gold) C.onAccent else ink, 9.sp, Modifier.fillMaxSize(), fill = 0.5f) }
        }
        k == "L3" || k == "R3" -> {
            Box(modifier.size(height).clip(CircleShape).background(fill).border(1.dp, edge, CircleShape).padding(2.dp)) {
                Box(Modifier.fillMaxSize().border(1.dp, edge, CircleShape)) {
                    CenteredGlyph(k.take(1), ink, 8.sp, Modifier.fillMaxSize(), fill = 0.5f)
                }
            }
        }
        k in setOf("L", "R", "LB", "RB", "L1", "R1", "L2", "R2", "ZL", "ZR") -> {
            val left = k.startsWith("L") || k == "ZL"
            val round = height * 0.62f
            val small = 3.dp
            val shape = if (left) RoundedCornerShape(topStart = round, topEnd = small, bottomEnd = small, bottomStart = small)
                else RoundedCornerShape(topStart = small, topEnd = round, bottomEnd = small, bottomStart = small)
            Box(
                modifier.size(height * 1.75f, height * 0.88f).clip(shape).background(fill).border(1.dp, edge, shape)
            ) { CenteredGlyph(k, ink, 8.sp, Modifier.fillMaxSize(), fill = 0.5f) }
        }
        k == "START" || k == "+" || k == "MENU" -> {
            val shape = RoundedCornerShape(50)
            Box(
                modifier.size(height * 1.6f, height * 0.9f).clip(shape).background(fill).border(1.dp, edge, shape)
                    .drawBehind {
                        // three short lines, like the button's own marking
                        val w = size.width * 0.34f; val x0 = (size.width - w) / 2f; val gap = size.height * 0.2f; val sw = 1.3.dp.toPx()
                        for (i in -1..1) {
                            val y = size.height / 2f + i * gap
                            drawLine(ink, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x0 + w, y), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
            )
        }
        k == "SELECT" || k == "SEL" || k == "-" || k == "VIEW" -> {
            val shape = RoundedCornerShape(50)
            Box(
                modifier.size(height * 1.6f, height * 0.9f).clip(shape).background(fill).border(1.dp, edge, shape)
                    .drawBehind {
                        // two overlapping squares
                        val s = size.height * 0.36f; val sw = 1.2.dp.toPx(); val r = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx())
                        val cx = size.width / 2f; val cy = size.height / 2f
                        val off = s * 0.3f
                        drawRoundRect(ink, androidx.compose.ui.geometry.Offset(cx - s / 2f - off, cy - s / 2f - off), androidx.compose.ui.geometry.Size(s, s), r, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                        drawRoundRect(ink, androidx.compose.ui.geometry.Offset(cx - s / 2f + off, cy - s / 2f + off), androidx.compose.ui.geometry.Size(s, s), r, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                    }
            )
        }
        else -> {
            val shape = RoundedCornerShape(50)
            Box(
                modifier.height(height * 0.9f).widthIn(min = height).clip(shape).background(fill).border(1.dp, edge, shape).padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center
            ) { Text(k, color = ink, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
        }
    }
}

/** A button badge (or several, "L2 R2") and what it does, for the hints along the bottom of a screen. */
@Composable
fun ButtonHint(key: String, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        key.split(' ').filter { it.isNotEmpty() }.forEachIndexed { i, k ->
            if (i > 0) Spacer(Modifier.width(3.dp))
            ButtonBadge(k)
        }
        Text(label.uppercase(), color = C.x(0xFFCFCAC0L), fontSize = 8.5.sp, letterSpacing = 1.1.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun KeyCap(k: String) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(C.x(0x21FFFFFFL)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(k, color = C.ink, fontSize = 8.sp)
    }
}



/** What the glass panels blur: the backdrop currently on screen (set by the backdrops as they draw). */
object GlassSource {
    var model by mutableStateOf<Any?>(null)
}

/**
 * Frosted glass: the backdrop behind a panel, blurred, with a soft light sheen over it. Put it first inside the panel
 * (it fills it). [align] is the screen edge the panel sits against, so the blurred picture lines up with the real one
 * behind it. Blur needs Android 12+; older devices just get the tint.
 */
@Composable
fun GlassFill(align: Alignment = Alignment.TopStart, shiftX: androidx.compose.ui.unit.Dp = 0.dp) {
    val light = ThemeColors.light.value
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val model = GlassSource.model
    val low = ThemeColors.lowPower.value // low power: no blurred picture, a plainer, more solid tint
    Box(Modifier.fillMaxSize().clipToBounds().background(if (light) Color(if (low) 0xF2F7F4EE else 0xB3F7F4EE) else Color(if (low) 0xF2121218 else 0x8C121218))) {
        if (model != null && android.os.Build.VERSION.SDK_INT >= 31 && !low) {
            Box(
                Modifier.fillMaxSize().wrapContentSize(align, unbounded = true).offset(x = shiftX)
                    .requiredSize(cfg.screenWidthDp.dp, cfg.screenHeightDp.dp)
                    .blur(24.dp)
                    .alpha(if (light) 0.35f else 0.6f)
            ) { SteadyImage(model, Modifier.fillMaxSize()) }
        }
        // the frost: a little light, brighter toward the top corner
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(
            listOf(Color.White.copy(alpha = if (light) 0.35f else 0.12f), Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.06f))
        )))
        Box(Modifier.fillMaxSize().background(if (light) Color(0x40F7F4EE) else Color(0x38000000)))
        // your glass colour (Appearance → Colours), when you've picked one
        val g = ThemeColors.glass.value
        if (g.isSpecified) Box(Modifier.fillMaxSize().background(g.copy(alpha = ThemeColors.glassAlpha.value * 0.55f)))
    }
}


/**
 * A floating panel of the right dock's glass: the backdrop blurred behind it, a warm tint over that (about 70% solid;
 * off-white in light mode), a soft sheen in the top corner and a thin light edge. Settings and the setup wizard use it.
 */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(18.dp), content: @Composable BoxScope.() -> Unit) {
    val light = ThemeColors.light.value
    Box(modifier.clip(shape).border(1.dp, if (light) C.line else Color(0x24FFFFFF), shape)) {
        GlassFill(Alignment.Center)
        Box(Modifier.matchParentSize().background(glassTint(light)))
        Box(Modifier.matchParentSize().background(Brush.linearGradient(0f to Color.White.copy(alpha = if (light) 0.25f else 0.07f), 0.4f to Color.Transparent)))
        content()
    }
}

/**
 * The tint over the glass panels: your glass colour (Appearance → Colours) or the look's own (warm charcoal; off-white
 * in light mode), as solid as Glass strength says (70% unless you or the theme change it).
 */
@Composable
fun glassTint(light: Boolean): Brush {
    val a = ThemeColors.glassAlpha.value
    val g = ThemeColors.glass.value
    fun Color.at(x: Float) = copy(alpha = x.coerceIn(0f, 1f))
    return when {
        g.isSpecified -> Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(g, Color.White, 0.08f).at(a - 0.02f), g.at(a), g.at(a + 0.05f)))
        light -> Brush.linearGradient(listOf(Color(0xFFF7F4EE).at(a + 0.10f), Color(0xFFF2EEE6).at(a + 0.14f), Color(0xFFF2EEE6).at(a + 0.18f)))
        else -> Brush.linearGradient(listOf(Color(0xFF2C2924).at(a - 0.02f), Color(0xFF121214).at(a), Color(0xFF161514).at(a + 0.04f)))
    }
}

/** A pop-up panel in the right dock's look: its tinted glass over a dark base (readable over anything), a thin light edge. */
@Composable
fun Modifier.dockPanel(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(22.dp)): Modifier {
    val light = ThemeColors.light.value
    return this.clip(shape)
        .background(if (light) Color(0xF2F7F4EE) else Color(0xE6121214))
        .background(glassTint(light))
        .border(1.dp, if (light) C.line else Color(0x29FFFFFF), shape)
}

/** Behind floating glass panels: the current backdrop (or your wallpaper), lightly dimmed, filling the screen. */
@Composable
fun GlassScreenBackdrop(vm: EclipseVM) {
    val model = GlassSource.model ?: vm.wallpaperTop()
    SideEffect { if (GlassSource.model == null && model != null) GlassSource.model = model }
    val light = ThemeColors.light.value
    Box(Modifier.fillMaxSize().background(if (vm.sysWall) Color.Transparent else HomeDark)) {
        if (model != null) SteadyImage(model, Modifier.fillMaxSize())
        val d = ThemeColors.dim.value
        Box(Modifier.fillMaxSize().background(
            if (d >= 0f) (if (light) Color(0xFFEEEAE1) else Color.Black).copy(alpha = d)
            else if (light) Color(0x80EEEAE1) else Color(0x4D000000)
        ))
    }
}

// ================================================================ letter lists

/** The letter a game files under: its first letter, or # for anything else (digits, symbols). */
fun letterOf(g: Game): Char = g.name.trimStart().firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'

/** Each letter in [games] with where it first appears (after the first [skip] games: favourites at the top). */
fun letterIndex(games: List<Game>, skip: Int = 0): List<Pair<Char, Int>> {
    val out = LinkedHashMap<Char, Int>()
    for (i in skip until games.size) out.putIfAbsent(letterOf(games[i]), i)
    return out.entries.map { it.key to it.value }.sortedBy { if (it.first == '#') ' ' else it.first }
}

/**
 * A strip of letters that jumps through a list of games: [vertical] down the right of the grid and list views, or
 * across the bottom of slide view and cover flow. Moving onto a letter jumps there ([onJump] gets the game's
 * position); A, or the D-pad back toward the games, goes back to them ([onLeave]). The current letter is in gold.
 */
@Composable
fun LetterList(
    letters: List<Pair<Char, Int>>, current: Char?, vertical: Boolean, onJump: (Int) -> Unit, onLeave: () -> Unit,
    modifier: Modifier = Modifier, onRight: (() -> Unit)? = null,
    /** lets the covers send Down straight here */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null
) {
    if (letters.size < 2) return
    // one focusable strip that scrolls: it shows a few letters either side of the one you're on, not all 26
    var focused by remember { mutableStateOf(false) }
    val curIdx = letters.indexOfFirst { it.first == current }.coerceAtLeast(0)
    var idx by remember { mutableStateOf(curIdx) }
    // until you arrive on it, it follows the game you're on
    LaunchedEffect(curIdx, focused) { if (!focused) idx = curIdx }
    fun step(d: Int) {
        val n = (idx + d).coerceIn(0, letters.lastIndex)
        if (n != idx) { idx = n; onJump(letters[n].second) }
    }
    val window = 7
    val start = (idx - window / 2).coerceIn(0, (letters.size - window).coerceAtLeast(0))
    val shown = letters.subList(start, minOf(letters.size, start + window))
    val slot = 18.dp
    val strip = Modifier
        .onKeyEvent { e ->
            val dirs = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent e.key in dirs
            when (e.key) {
                Key.DirectionUp -> { if (vertical) step(-1) else onLeave(); true }
                Key.DirectionDown -> { if (vertical) step(1); true }
                Key.DirectionLeft -> { if (vertical) onLeave() else step(-1); true }
                Key.DirectionRight -> { if (vertical) onRight?.invoke() else step(1); true }
                else -> false
            }
        }
    val shape = RoundedCornerShape(10.dp)
    FocusBox(modifier.then(strip), shape = shape, ring = false, focusRequester = focusRequester, onFocusChange = { f -> focused = f }, onClick = { onJump(letters[idx].second); onLeave() }) { _ ->
        val body: @Composable (Char, Boolean, Int) -> Unit = { c, on, i ->
            Box(
                Modifier.size(slot).clip(CircleShape)
                    .background(if (on && focused) C.accent else Color.Transparent)
                    .pointerInput(i) { detectTapGestures { idx = i; onJump(letters[i].second); onLeave() } }
            ) {
                CenteredGlyph(
                    c.toString(),
                    when { on && focused -> C.onAccent; on -> C.accent; else -> C.x(0xFFB9B4AAL) },
                    9.sp, Modifier.fillMaxSize(), fill = 0.46f
                )
            }
        }
        val bg = Modifier.clip(shape).background(C.x(0x8C0E0E12L)).border(1.dp, if (focused) C.accent.copy(alpha = 0.6f) else C.x(0x1FFFFFFFL), shape)
        if (vertical) Column(bg.padding(vertical = 4.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (start > 0) "\u25B4" else " ", color = C.sub, fontSize = 7.sp)
            shown.forEachIndexed { k, (c, _) -> body(c, start + k == idx, start + k) }
            Text(if (start + window < letters.size) "\u25BE" else " ", color = C.sub, fontSize = 7.sp)
        } else Row(bg.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (start > 0) "\u25C2" else " ", color = C.sub, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 2.dp))
            shown.forEachIndexed { k, (c, _) -> body(c, start + k == idx, start + k) }
            Text(if (start + window < letters.size) "\u25B8" else " ", color = C.sub, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

// ================================================================ grid view

/** Grid view's sort / filter chips. */
val GRID_SORTS = listOf("az" to "A\u2013Z", "recent" to "Recent", "played" to "Most played", "fav" to "\u2665 Favourites")

/** The sort chips, for the grid view's top bar. */
@Composable
fun GridSortChips(vm: EclipseVM) {
    Row(Modifier.padding(start = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        GRID_SORTS.forEach { (id, label) ->
            val on = vm.gameSort == id
            FocusBox(shape = RoundedCornerShape(50), onClick = { vm.chooseGameSort(id) }, focusKey = "sort:$id") {
                Text(
                    label.uppercase(), color = if (on) C.accent else C.sub, fontSize = 8.sp, letterSpacing = 1.2.sp, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .then(if (on) Modifier.background(C.accent.copy(alpha = 0.16f)).border(1.dp, C.accent.copy(alpha = 0.45f), RoundedCornerShape(50)) else Modifier)
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/**
 * Grid view: covers with their names in a grid (favourites in their own section when sorted A-Z), a letter rail to
 * jump through the alphabet, and a glass panel on the right with the highlighted game's details. Sorting: A-Z,
 * recently played, most played, or favourites only (the chips in the top bar).
 */
@Composable
fun GameGridView(vm: EclipseVM, games: List<Game>, focus: Game?, first: androidx.compose.ui.focus.FocusRequester, cols: Int, modifier: Modifier = Modifier) {
    GameKeys(vm, focus)
    val sort = vm.gameSort
    val sorted = remember(games, sort, vm.favGames, vm.lastPlayed, vm.playtime) {
        when (sort) {
            "recent" -> games.sortedWith(compareByDescending<Game> { vm.lastPlayed[it.id]?.toLongOrNull() ?: 0L }.thenBy { it.name.lowercase() })
            "played" -> games.sortedWith(compareByDescending<Game> { vm.playedSeconds(it) }.thenBy { it.name.lowercase() })
            "fav" -> games.filter { vm.isFavGame(it) }
            else -> games.sortedWith(compareBy({ !vm.isFavGame(it) }, { it.name.lowercase() }))
        }
    }
    val favCount = if (sort == "az") sorted.count { vm.isFavGame(it) } else 0
    val sections = favCount in 1 until sorted.size
    val state = stickGridState()
    val scope = rememberCoroutineScope()
    // grid position of a game (section headings take a row each)
    fun gridIndex(i: Int) = if (!sections) i else if (i < favCount) i + 1 else i + 2

    Box(modifier.fillMaxSize()) {
        // laid out like the systems grid: covers only (the highlighted game's name is in the header), the same gaps,
        // the others a little dimmer; the button hints on a dark bar along the bottom
        Row(Modifier.fillMaxSize().padding(start = 28.dp, end = 14.dp)) {
            if (sorted.isEmpty()) {
                Text(
                    if (sort == "fav") "No favourites here yet. Press Y on a game (or tap its heart) to add it." else "No games here yet.",
                    color = C.sub, modifier = Modifier.weight(1f).padding(top = 20.dp)
                )
            } else LazyVerticalGrid(
                GridCells.Fixed(cols), Modifier.weight(1f).fillMaxHeight(), state = state,
                contentPadding = PaddingValues(top = 10.dp, bottom = 40.dp, start = 2.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (sections) item(span = { GridItemSpan(maxLineSpan) }, key = "h-fav") { GridHeading("Favourites", heart = true) }
                sorted.forEachIndexed { i, g ->
                    if (sections && i == favCount) item(span = { GridItemSpan(maxLineSpan) }, key = "h-all") { GridHeading("All games") }
                    item(key = g.id) {
                        Box(Modifier.alpha(if (g.id == focus?.id) 1f else 0.84f)) {
                            GameTile(vm, g, Modifier.fillMaxWidth().aspectRatio(3f / 4f), if (i == 0) first else null)
                            if (vm.isFavGame(g)) Box(
                                Modifier.align(Alignment.TopStart).padding(5.dp).size(16.dp).clip(CircleShape).background(Color(0x99000000)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.Favorite, null, tint = C.accent, modifier = Modifier.size(10.dp)) }
                        }
                    }
                }
            }
            // the letter list: moving down it scrolls the grid to that letter; A or Left goes back to the games
            if (sort == "az" || sort == "fav") {
                val letters = remember(sorted, favCount, sections) { letterIndex(sorted, if (sections) favCount else 0) }
                Spacer(Modifier.width(8.dp))
                LetterList(
                    letters, focus?.let { letterOf(it) }, vertical = true,
                    onJump = { i -> vm.focused = sorted[i]; scope.launch { state.scrollToItem(gridIndex(i)) } },
                    onLeave = { vm.focused?.let { GamepadFocus.restoreKey = "g:${it.id}" } },
                    modifier = Modifier.align(Alignment.CenterVertically).padding(bottom = 28.dp),
                    onRight = { vm.gamesDrawer = true }
                )
            }
        }
        if (!vm.gamesDrawer) SlideHintBar(Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun GridHeading(title: String, heart: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (heart) { Icon(Icons.Filled.Favorite, null, tint = C.accent, modifier = Modifier.size(10.dp)); Spacer(Modifier.width(6.dp)) }
        Text(title.uppercase(), color = C.sub, fontSize = 9.sp, letterSpacing = 2.sp)
        Box(Modifier.padding(start = 10.dp).weight(1f).height(1.dp).background(C.x(0x14FFFFFFL)))
    }
}

