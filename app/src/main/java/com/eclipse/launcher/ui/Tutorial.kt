package com.eclipse.launcher.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.HomeSection
import kotlinx.coroutines.delay

/** The left dock's open width (Root's DOCK_W) and the right-hand settings panel's (OptionsDrawer). */
private val TUT_DOCK = 201.dp
private val TUT_PANEL = 270.dp
private val GOLD = Color(0xFFE0B04A)

/** One step: what's lit (in dp from the screen's edges), and where the card sits. */
private class TutStep(val title: String, val text: AnnotatedString)

/** Bold words and gold button names inside a step's text. */
private fun tut(vararg parts: Pair<String, Int>): AnnotatedString = buildAnnotatedString {
    for ((t, kind) in parts) when (kind) {
        1 -> withStyle(SpanStyle(color = Color(0xFFF2EEE6), fontWeight = FontWeight.Medium)) { append(t) }
        2 -> withStyle(SpanStyle(color = GOLD, fontWeight = FontWeight.Bold)) { append(t) }
        else -> append(t)
    }
}

private val STEPS = listOf(
    TutStep(
        "This is your home screen",
        tut(
            "Eclipse fills it in as you play. " to 0, "Your recently played games" to 1,
            " appear up here after your first game, newest first. Until then, Home opens on your systems." to 0
        )
    ),
    TutStep(
        "The dock",
        tut(
            "Press " to 0, "LB" to 2, " to open it, or press Left at the edge. It's how you get around: " to 0,
            "Search" to 1, ", your " to 0, "game library" to 1, ", " to 0, "Apps" to 1, " and " to 0, "Settings" to 1, ".\n\n" to 0,
            "Pin apps you use often: in Apps, hold " to 0, "A" to 2, " on one → Pin app. They show up here." to 0
        )
    ),
    TutStep(
        "The settings panel",
        tut(
            "Press " to 0, "RB" to 2, " on any page to open it.\n\n" to 0,
            "Page" to 1, ": how this page looks and is sorted: view, style, order, size. With RomM connected, " to 0,
            "Source" to 1, " shows your RomM library: browse its games and download them right here.\n" to 0,
            "System" to 1, ": Wi-Fi, Bluetooth and notifications." to 0
        )
    )
)

/**
 * First-run tour of Home (once, after setup; again from Settings → General): the screen dims, a gold spotlight moves
 * from Home to the left dock (opened for it) to the settings panel (opened for it), with a card explaining each.
 * A next, B skip. The tour keeps the controller the whole time, so nothing behind it moves.
 */
@Composable
fun Tutorial(vm: EclipseVM) {
    val step = vm.tutorialStep.coerceIn(0, STEPS.lastIndex)
    val fr = remember { FocusRequester() }
    fun next() { if (step >= STEPS.lastIndex) vm.finishTutorial() else vm.tutorialStep = step + 1 }
    // closing: the controller goes back to Home
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { GamepadFocus.rescue?.invoke() } }

    // open what this step talks about (the dock, the settings panel of the row on screen), then take the controller
    // back: opening them moves focus into them
    LaunchedEffect(step) {
        vm.dockPeek = step == 1
        val panel = step == 2
        when (vm.homeSection) {
            HomeSection.APPS -> vm.appsDrawer = panel
            HomeSection.PLATFORMS -> vm.platDrawer = panel
            else -> if (vm.homeGames().isEmpty()) vm.platDrawer = panel else vm.deckDrawer = panel
        }
        repeat(4) { withFrameNanos { }; runCatching { fr.requestFocus() } }
        delay(250)
        runCatching { fr.requestFocus() }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
            .focusRequester(fr).focusable()
            .onKeyEvent { e ->
                // everything stays with the tour: A / Enter next, B / Back skip, the rest does nothing
                if (e.type == KeyEventType.KeyDown) when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA, Key.DirectionRight -> next()
                    Key.Back, Key.Escape, Key.ButtonB -> vm.finishTutorial()
                    Key.DirectionLeft -> if (step > 0) vm.tutorialStep = step - 1
                    else -> {}
                }
                true
            }
    ) {
        val w = maxWidth; val h = maxHeight
        // the lit area for each step
        val (tl, tt, tw, th) = when (step) {
            0 -> listOf(84.dp, 40.dp, w - 104.dp, h - 94.dp)
            1 -> listOf(0.dp, 0.dp, TUT_DOCK, h)
            else -> listOf(w - TUT_PANEL, 0.dp, TUT_PANEL, h)
        }
        val anim = tween<Dp>(600)
        val l by animateDpAsState(tl, anim, label = "spotL")
        val t by animateDpAsState(tt, anim, label = "spotT")
        val sw by animateDpAsState(tw, anim, label = "spotW")
        val sh by animateDpAsState(th, anim, label = "spotH")
        val radius by animateDpAsState(if (step == 0) 22.dp else 0.dp, anim, label = "spotR")

        // the dim layer with the lit area cut out, and a gold edge round it
        Canvas(Modifier.fillMaxSize()) {
            val rr = RoundRect(l.toPx(), t.toPx(), (l + sw).toPx(), (t + sh).toPx(), CornerRadius(radius.toPx()))
            val hole = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                addRoundRect(rr)
            }
            drawPath(hole, Color.Black.copy(alpha = 0.74f))
            val edge = Path().apply { addRoundRect(rr) }
            drawPath(edge, GOLD.copy(alpha = 0.18f), style = Stroke(8.dp.toPx()))
            drawPath(edge, GOLD.copy(alpha = 0.9f), style = Stroke(1.5.dp.toPx()))
        }

        // the card: up top for Home, beside the dock, beside the settings panel
        val cardW = 300.dp
        val (cx, cy) = when (step) {
            0 -> (w - cardW) / 2 to 52.dp
            1 -> TUT_DOCK + 24.dp to h * 0.24f
            else -> w - TUT_PANEL - cardW - 24.dp to h * 0.2f
        }
        val x by animateDpAsState(cx, anim, label = "cardX")
        val y by animateDpAsState(cy, anim, label = "cardY")
        val s = STEPS[step]
        Column(
            Modifier.offset(x, y).width(cardW).clip(RoundedCornerShape(16.dp)).background(Color(0xF50E0E10))
                .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(16.dp)).padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp)
        ) {
            Text("${step + 1} OF ${STEPS.size}", color = GOLD, fontSize = 8.sp, letterSpacing = 2.2.sp)
            Text(s.title, color = Color(0xFFF2EEE6), fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
            Text(s.text, color = Color(0xFFC9C4BA), fontSize = 10.5.sp, lineHeight = 15.5.sp, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    STEPS.indices.forEach { i ->
                        val dw by animateDpAsState(if (i == step) 16.dp else 4.dp, label = "tutDot")
                        Box(Modifier.size(dw, 4.dp).clip(RoundedCornerShape(2.dp)).background(if (i == step) GOLD else Color(0x40FFFFFF)))
                    }
                }
                Spacer(Modifier.width(12.dp))
                TutKey("B", "Skip", gold = false) { vm.finishTutorial() }
                Spacer(Modifier.weight(1f))
                TutKey("A", if (step == STEPS.lastIndex) "Got it" else "Next", gold = true) { next() }
            }
        }
    }
}

/** A button hint you can also tap: the key in a ring, then its label (gold pill for the main one). */
@Composable
private fun TutKey(key: String, label: String, gold: Boolean, onTap: () -> Unit) {
    val ink = if (gold) Color(0xFF1A1408) else Color(0xFFD8D3C9)
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(if (gold) GOLD else Color.Transparent)
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .padding(horizontal = if (gold) 14.dp else 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(13.dp).clip(CircleShape).border(1.dp, ink.copy(alpha = 0.7f), CircleShape)) { CenteredGlyph(key, ink, 7.sp, Modifier.fillMaxSize()) }
        Text(label.uppercase(), color = ink, fontSize = 8.5.sp, letterSpacing = 1.4.sp, fontWeight = if (gold) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 7.dp))
    }
}
