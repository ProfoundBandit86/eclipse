package com.eclipse.launcher.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.media.Mirror
import kotlinx.coroutines.delay


/**
 * Bottom screen: a live copy of the top screen. Shows all of it, or a zoomed-in part (a minimap, a HUD), remembered
 * per game. Tap to show or hide the controls; "Pick area" lets you drag a box over the part you want.
 * [onPicking] tells the pager to stop swiping while a box is being drawn.
 */
@Composable
fun MirrorPage(vm: EclipseVM, onPicking: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val running by Mirror.running.collectAsState()
    Box(Modifier.fillMaxSize().background(if (running) Color(0xFF08080B) else HomeDark)) {
        if (!running) MirrorIntro { Mirror.request(ctx) } else MirrorLive(vm, onPicking)
    }
}

@Composable
private fun MirrorLive(vm: EclipseVM, onPicking: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {

        val src by Mirror.size.collectAsState()
        var region by remember(vm.mirrorKey()) { mutableStateOf(vm.mirrorRegion()) }
        var picking by remember { mutableStateOf(false) }
        var controls by remember { mutableStateOf(true) }
        var view by remember { mutableStateOf<TextureView?>(null) }
        var viewSize by remember { mutableStateOf(IntSize.Zero) }
        LaunchedEffect(picking) { onPicking(picking) }
        DisposableEffect(Unit) { onDispose { onPicking(false) } }
        // the controls hide themselves after a few seconds
        LaunchedEffect(controls, picking) { if (controls && !picking) { delay(4000); controls = false } }

        // while picking, show the whole screen so you can see what to pick
        val shown = if (picking) floatArrayOf(0f, 0f, 1f, 1f) else region
        LaunchedEffect(view, shown.toList(), viewSize, src) {
            view?.let { v -> v.setTransform(mirrorMatrix(viewSize.width.toFloat(), viewSize.height.toFloat(), src.first.toFloat(), src.second.toFloat(), shown)) }
        }

        AndroidView(
            factory = { c ->
                TextureView(c).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        var surface: Surface? = null
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            st.setDefaultBufferSize(Mirror.size.value.first, Mirror.size.value.second)
                            surface = Surface(st).also { Mirror.attach(it) }
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            surface?.let { Mirror.detach(it); it.release() }
                            surface = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                    view = this
                }
            },
            modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it }
        )

        if (picking) {
            PickArea(viewSize, src) { r -> region = r; vm.setMirrorRegion(r); picking = false; controls = true }
        } else {
            // tap anywhere: show / hide the controls
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { controls = !controls } })
        }

        AnimatedVisibility(visible = controls && !picking, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                    .padding(start = 14.dp, end = 14.dp, top = 28.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Full, Select (drag a box over the part you want), Hide (these buttons), Stop
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isFull = region.contentEquals(floatArrayOf(0f, 0f, 1f, 1f))
                    MirrorChip("Full", Modifier.weight(1f), selected = isFull) {
                        val r = floatArrayOf(0f, 0f, 1f, 1f); region = r; vm.setMirrorRegion(r); controls = true
                    }
                    MirrorChip("Select", Modifier.weight(1f), gold = true) { picking = true }
                    MirrorChip("Hide", Modifier.weight(1f)) { controls = false }
                    MirrorChip("Stop", Modifier.weight(1f)) { Mirror.stop(ctx) }
                }
            }
        }
    }
}

/** Shown before mirroring starts: what it does, and the button that asks Android for permission. */
@Composable
private fun MirrorIntro(start: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(C.accent.copy(alpha = 0.12f), Color.Transparent), radius = 900f)))
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(C.x(0x1FFFFFFFL)).border(1.dp, C.x(0x33FFFFFFL), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ScreenShare, null, tint = C.accent, modifier = Modifier.size(30.dp))
        }
        Text("Mirror the top screen", color = C.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Show the whole top screen here, or zoom in on one part of it: a map, a health bar, a scoreboard. " +
                "Eclipse remembers the area for each game.",
            color = C.x(0xB3FFFFFFL), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).widthIn(max = 420.dp)
        )
        Spacer(Modifier.height(20.dp))
        FocusBox(Modifier.height(46.dp).widthIn(min = 200.dp), shape = EclipseButtonShape, onClick = start) {
            Box(Modifier.matchParentSize().background(C.accent))
            Text("Start mirroring", color = C.onAccent, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp))
        }
        Text(
            "Android asks for permission on the top screen each time it starts.",
            color = C.sub, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)
        )
    }
}

/** Drag a box over the full screen to choose the area; it keeps the box inside the screen and not too small. */
@Composable
private fun PickArea(viewSize: IntSize, src: Pair<Int, Int>, done: (FloatArray) -> Unit) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    val dens = LocalDensity.current
    // where the full screen actually sits inside the view (letterboxed to keep its shape)
    val vw = viewSize.width.toFloat(); val vh = viewSize.height.toFloat()
    val s = if (vw > 0 && vh > 0) minOf(vw / src.first, vh / src.second) else 1f
    val cw = src.first * s; val ch = src.second * s
    val ox = (vw - cw) / 2; val oy = (vh - ch) / 2
    Box(
        Modifier.fillMaxSize().pointerInput(viewSize, src) {
            detectDragGestures(
                onDragStart = { start = it; end = it },
                onDrag = { change, _ -> change.consume(); end = change.position },
                onDragEnd = {
                    val a = start; val b = end
                    if (a != null && b != null && cw > 0) {
                        var x0 = ((minOf(a.x, b.x) - ox) / cw).coerceIn(0f, 1f)
                        var y0 = ((minOf(a.y, b.y) - oy) / ch).coerceIn(0f, 1f)
                        var x1 = ((maxOf(a.x, b.x) - ox) / cw).coerceIn(0f, 1f)
                        var y1 = ((maxOf(a.y, b.y) - oy) / ch).coerceIn(0f, 1f)
                        if (x1 - x0 < 0.06f) { x1 = minOf(1f, x0 + 0.06f); x0 = x1 - 0.06f }
                        if (y1 - y0 < 0.06f) { y1 = minOf(1f, y0 + 0.06f); y0 = y1 - 0.06f }
                        done(floatArrayOf(x0, y0, x1 - x0, y1 - y0))
                    }
                    start = null; end = null
                }
            )
        }
    ) {
        Text(
            "Drag a box over the part you want",
            color = C.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xB3000000)).padding(horizontal = 14.dp, vertical = 7.dp)
        )
        val a = start; val b = end
        if (a != null && b != null) {
            with(dens) {
                Box(
                    Modifier.offset(x = minOf(a.x, b.x).toDp(), y = minOf(a.y, b.y).toDp())
                        .size(width = kotlin.math.abs(b.x - a.x).toDp(), height = kotlin.math.abs(b.y - a.y).toDp())
                        .border(2.dp, C.accent, RoundedCornerShape(4.dp))
                        .background(C.accent.copy(alpha = 0.13f))
                )
            }
        }
    }
}

@Composable
private fun MirrorChip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, gold: Boolean = false, onClick: () -> Unit) {
    FocusBox(modifier.height(36.dp), shape = RoundedCornerShape(18.dp), onClick = onClick) {
        Box(
            Modifier.matchParentSize()
                .background(if (selected) C.accent else if (gold) C.accent.copy(alpha = 0.15f) else Color(0x33FFFFFF))
                .border(1.dp, if (gold) C.accent.copy(alpha = 0.4f) else Color(0x26FFFFFF), RoundedCornerShape(18.dp))
        )
        Text(
            text, color = if (selected) C.onAccent else if (gold) C.accent else Color.White,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp)
        )
    }
}

/**
 * Maps the mirrored frame onto the view so [r] (x, y, w, h as fractions of the top screen) fills as much of the
 * view as it can while keeping its shape, centred. (A TextureView first stretches the whole frame over the view.)
 */
private fun mirrorMatrix(vw: Float, vh: Float, sw: Float, sh: Float, r: FloatArray): Matrix {
    val m = Matrix()
    if (vw <= 0f || vh <= 0f) return m
    val rx = r[0] * vw; val ry = r[1] * vh; val rw = r[2] * vw; val rh = r[3] * vh
    val s = minOf(vw / (r[2] * sw), vh / (r[3] * sh))
    val dw = r[2] * sw * s; val dh = r[3] * sh * s
    val sx = dw / rw; val sy = dh / rh
    m.setScale(sx, sy)
    m.postTranslate((vw - dw) / 2f - rx * sx, (vh - dh) / 2f - ry * sy)
    return m
}
