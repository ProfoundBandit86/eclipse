package com.eclipse.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.ArtKind
import com.eclipse.launcher.data.Expr
import com.eclipse.launcher.data.Game
import com.eclipse.launcher.data.Platforms
import com.eclipse.launcher.data.Views
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The view types you've made (Eclipse/views, the .json files), re-read within two seconds of saving a file. */
@Composable
fun rememberCustomViews(): List<Views.Spec> {
    var specs by remember { mutableStateOf(Views.list()) }
    LaunchedEffect(Unit) {
        var sig = withContext(Dispatchers.IO) { Views.signature() }
        while (true) {
            delay(2000)
            val now = withContext(Dispatchers.IO) { Views.signature() }
            if (now != sig) { sig = now; specs = withContext(Dispatchers.IO) { Views.list() } }
        }
    }
    return specs
}

private fun Expr.safe(vars: Map<String, Float>, def: Float): Float = runCatching { eval(vars) }.getOrDefault(def)

/** What a view's slots show about the highlighted item: its name, a small line under it, its logo and its backdrop. */
class SlotInfo(val title: String, val meta: String, val logo: String?, val backdrop: String?)

/**
 * A games list drawn from a view.json (see Views.kt / VIEWS.md): every cover near the highlighted one is placed by the
 * view's formulas (and animated when the highlight moves), under / over the slots the file lists. Eclipse still does
 * the controller, the focus, the art and the game menu: a card is a normal game tile.
 */
@Composable
fun CustomGamesView(vm: EclipseVM, spec: Views.Spec, games: List<Game>, focus: Game?, first: FocusRequester, modifier: Modifier = Modifier) =
    CustomItemsView(
        spec, games, { it.id }, games.indexOfFirst { it.id == focus?.id }.coerceAtLeast(0), first, { vm.focused = it },
        { g ->
            SlotInfo(
                g.name, Platforms.byId(g.platform)?.name ?: g.platform, vm.artOf(g, ArtKind.LOGO),
                vm.artOf(g, ArtKind.BACKDROP) ?: vm.artOf(g, ArtKind.HERO) ?: vm.wallpaperTop()
            )
        },
        modifier
    ) { g, m, fr -> GameTile(vm, g, m, fr) }

/**
 * Any list drawn from a view.json: games (above) or Home's systems. [tile] draws one item at the place and size the
 * view's formulas give it (a normal focusable tile, so the controller, Select's menu and A work as everywhere else);
 * [info] fills the slots for the highlighted one; [onStep] is told when Up / Down move by the view's "step".
 */
@Composable
fun <T> CustomItemsView(
    spec: Views.Spec, items: List<T>, keyOf: (T) -> String, focusIndex: Int, first: FocusRequester,
    onStep: (T) -> Unit, info: (T) -> SlotInfo, modifier: Modifier = Modifier,
    tile: @Composable (T, Modifier, FocusRequester) -> Unit
) {
    val n = items.size
    if (n == 0) return
    val idx = focusIndex.coerceIn(0, n - 1)
    val reqs = remember(spec.id) { HashMap<String, FocusRequester>() }
    val window = maxOf(spec.window, spec.step)
    val cur = items[idx]
    val slot = info(cur)
    BoxWithConstraints(
        modifier.fillMaxSize().onPreviewKeyEvent { e ->
            // "step": how many items Up / Down move (0 leaves Up / Down to the screen)
            if (spec.step > 0 && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionDown || e.key == Key.DirectionUp)) {
                val to = (idx + if (e.key == Key.DirectionDown) spec.step else -spec.step).coerceIn(0, n - 1)
                items.getOrNull(to)?.let { it2 -> onStep(it2); reqs[keyOf(it2)]?.let { runCatching { it.requestFocus() } } }
                true
            } else false
        }
    ) {
        val w = maxWidth.value
        val h = maxHeight.value
        val slotVars = mapOf("w" to w, "h" to h, "n" to n.toFloat(), "i" to idx.toFloat(), "d" to 0f, "s" to 1f)
        spec.slots.filter { it.kind == "backdrop" }.forEach { s ->
            val art = slot.backdrop
            if (art != null) AsyncImage(art, null, Modifier.fillMaxSize().then(if (s.blur > 0f) Modifier.softBlur(s.blur.dp) else Modifier), contentScale = ContentScale.Crop)
            if (s.dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = s.dim.coerceIn(0f, 1f))))
        }
        val lo = (idx - window).coerceAtLeast(0)
        val hi = (idx + window).coerceAtMost(n - 1)
        for (k in lo..hi) {
            val item = items[k]
            key(keyOf(item)) {
                val own = reqs.getOrPut(keyOf(item)) { FocusRequester() }
                CustomCard(spec, item, k, idx, n, w, h, if (k == idx) first else own, tile)
            }
        }
        spec.slots.filter { it.kind != "backdrop" }.forEach { s ->
            val sx = s.x?.safe(slotVars, 0f) ?: 0f
            val sy = s.y?.safe(slotVars, 0f) ?: 0f
            val colour = s.color?.let { Color(it) } ?: Color.White
            val (shift, width, align) = when (s.align) {
                "center" -> Triple(sx - w / 2, w, TextAlign.Center)
                "right" -> Triple(sx - w, w, TextAlign.End)
                else -> Triple(sx, (w - sx).coerceAtLeast(40f), TextAlign.Start)
            }
            val at = Modifier.offset(shift.dp, sy.dp).width(width.dp)
            when (s.kind) {
                "title" -> Text(slot.title, color = colour, fontSize = s.size.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = at)
                "meta" -> Text(
                    slot.meta.uppercase(), color = colour.copy(alpha = 0.8f),
                    fontSize = s.size.sp, letterSpacing = 1.5.sp, maxLines = 1, textAlign = align, modifier = at
                )
                "logo" -> {
                    val logo = slot.logo
                    if (logo != null) Box(at, contentAlignment = when (align) { TextAlign.Center -> Alignment.TopCenter; TextAlign.End -> Alignment.TopEnd; else -> Alignment.TopStart }) {
                        AsyncImage(logo, slot.title, Modifier.height(s.size.dp), contentScale = ContentScale.Fit)
                    } else Text(slot.title, color = colour, fontSize = (s.size * 0.6f).sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = align, modifier = at)
                }
            }
        }
    }
}

/** One item: where the view's formulas put it, animated, drawn by [tile]. */
@Composable
private fun <T> BoxScope.CustomCard(
    spec: Views.Spec, item: T, k: Int, sel: Int, n: Int, w: Float, h: Float, fr: FocusRequester,
    tile: @Composable (T, Modifier, FocusRequester) -> Unit
) {
    val vars = mapOf("d" to (k - sel).toFloat(), "i" to k.toFloat(), "s" to if (k == sel) 1f else 0f, "n" to n.toFloat(), "w" to w, "h" to h)
    val cw = spec.cardW.safe(vars, 100f).coerceIn(20f, 2000f)
    val ch = spec.cardH.safe(vars, 140f).coerceIn(20f, 2000f)
    val anim = tween<Float>(240)
    val x by animateFloatAsState(spec.x.safe(vars, 0f), anim, label = "cx")
    val y by animateFloatAsState(spec.y.safe(vars, 0f), anim, label = "cy")
    val scale by animateFloatAsState(spec.scale.safe(vars, 1f), anim, label = "cs")
    val ry by animateFloatAsState(spec.rotateY.safe(vars, 0f), anim, label = "cry")
    val rx by animateFloatAsState(spec.rotateX.safe(vars, 0f), anim, label = "crx")
    val a by animateFloatAsState(spec.alpha.safe(vars, 1f), anim, label = "ca")
    val z = spec.z.safe(vars, 0f)
    val density = LocalDensity.current.density
    tile(
        item,
        Modifier.align(Alignment.Center).offset(x.dp, y.dp).size(cw.dp, ch.dp).zIndex(z)
            .graphicsLayer { scaleX = scale; scaleY = scale; rotationY = ry; rotationX = rx; alpha = a.coerceIn(0f, 1f); cameraDistance = 16f * density }
            .clip(RoundedCornerShape(spec.corner.dp)),
        fr
    )
}
