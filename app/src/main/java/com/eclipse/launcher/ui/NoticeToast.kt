package com.eclipse.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import kotlinx.coroutines.delay

private val NoticeGold = Color(0xFFE0B04A)

/** Badge colour and icon of each kind of notice. */
private fun toneLook(tone: String): Pair<Color, ImageVector?> = when (tone) {
    "ok" -> Color(0xFF2E7D5B) to Icons.Filled.Check
    "down" -> Color(0xFF2E7D5B) to Icons.Filled.CloudDownload
    "up" -> Color(0xFF2F6FB5) to Icons.Filled.CloudUpload
    "chd" -> Color(0xFF5B5BD6) to Icons.Filled.Album
    "error" -> Color(0xFFB5463A) to Icons.Filled.PriorityHigh
    else -> NoticeGold to null
}

/**
 * Eclipse's own pop-up (replaces Android's grey toast while Eclipse is on screen): the game's cover with a coloured
 * badge, what happened in gold small caps, the game, a detail, and a thin gold line that runs down while it shows.
 * Plain messages use the same panel without a cover. Never takes focus.
 */
@Composable
fun NoticeHost(vm: EclipseVM) {
    val n = vm.notice
    // the last one stays drawn while it slides away
    var shown by remember { mutableStateOf<EclipseVM.Notice?>(null) }
    val left = remember { Animatable(1f) }
    LaunchedEffect(n) {
        if (n == null) return@LaunchedEffect
        shown = n
        val ms = if (n.kind.isEmpty() && n.title.length < 50) 2600 else 4200
        left.snapTo(1f)
        left.animateTo(0f, tween(ms, easing = LinearEasing))
        if (vm.notice === n) vm.notice = null
    }
    Box(Modifier.fillMaxSize().padding(bottom = 22.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = n != null,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            shown?.let { NoticeCard(vm, it, left.value) }
        }
    }
}

@Composable
private fun NoticeCard(vm: EclipseVM, n: EclipseVM.Notice, left: Float) {
    val shape = RoundedCornerShape(16.dp)
    val error = n.tone == "error"
    val (badge, icon) = toneLook(n.tone)
    val plain = n.kind.isEmpty() && n.art == null
    Box(
        Modifier.widthIn(min = 260.dp, max = 520.dp).clip(shape)
            .background(Color(0xDB121110))
            .border(1.dp, (if (error) Color(0xFFD66050) else NoticeGold).copy(alpha = if (error) 0.4f else 0.28f), shape)
    ) {
        Row(
            Modifier.padding(start = if (plain) 14.dp else 8.dp, end = 16.dp, top = 8.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            if (plain) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(NoticeGold))
            } else {
                Box(Modifier.size(38.dp, 50.dp)) {
                    val pic = Modifier.size(34.dp, 46.dp).clip(RoundedCornerShape(6.dp))
                    if (n.art != null || n.tone != "chd") Cover(n.title, n.art, pic)
                    else Box(pic.background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Album, null, tint = Color(0xFF9EA3B0), modifier = Modifier.size(26.dp))
                    }
                    if (icon != null) Box(
                        Modifier.align(Alignment.BottomEnd).size(19.dp).clip(CircleShape).background(Color(0xFF121110)).padding(2.dp)
                            .clip(CircleShape).background(badge),
                        contentAlignment = Alignment.Center
                    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(11.dp)) }
                }
            }
            Column {
                if (n.kind.isNotEmpty()) Text(
                    n.kind.uppercase(), color = if (error) Color(0xFFE08A6A) else NoticeGold,
                    fontSize = 8.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Medium
                )
                Text(
                    n.title, color = Color(0xFFF2EEE6), fontSize = if (plain) 12.sp else 12.5.sp, fontWeight = if (plain) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (n.kind.isNotEmpty()) 2.dp else 0.dp)
                )
                n.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color(0xFFA9A49A), fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        // time left, as a thin gold line along the bottom edge
        // (matchParentSize: the line follows the panel's width instead of stretching it)
        if (!error) Box(Modifier.matchParentSize()) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(left).height(2.dp).background(NoticeGold.copy(alpha = 0.85f)))
        }
    }
}
