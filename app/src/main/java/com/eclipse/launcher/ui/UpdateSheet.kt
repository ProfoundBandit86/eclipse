package com.eclipse.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.Updater

/**
 * "Eclipse X is available": shown at start when GitHub has a newer release (and from Settings → General → Check for
 * updates). Update now downloads it with a progress bar and opens Android's installer; Later asks again next start;
 * Skip this version stays quiet until the one after.
 */
@Composable
fun UpdateSheet(vm: EclipseVM, r: Updater.Release) {
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    val p = vm.updateProgress
    MenuSheet("Update", "Eclipse ${r.version}", "You have ${vm.appVersion()}", onDismiss = { vm.updateLater() }) {
        // what's new: the release notes, plain (markdown marks dropped), scrolling if long
        val notes = remember(r.notes) {
            r.notes.lines().map { it.trim().trimStart('#', '*', '-', ' ').replace("**", "").replace("`", "") }
                .filter { it.isNotBlank() && !it.startsWith("Co-Authored-By") && !it.startsWith("Claude-Session") && !it.startsWith("Full Changelog") }
                .take(14).joinToString("\n")
        }
        if (notes.isNotEmpty()) Box(
            Modifier.fillMaxWidth().heightIn(max = 120.dp).clip(RoundedCornerShape(9.dp)).background(C.x(0x0DFFFFFFL))
                .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 9.dp)
        ) { Text(notes, color = C.sub, fontSize = 10.5.sp, lineHeight = 14.sp) }
        if (p != null) {
            Text(
                if (p >= 0f) "Downloading… ${(p * 100).toInt()}%" else "Downloading…",
                color = C.ink, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, start = 2.dp)
            )
            Box(Modifier.padding(top = 2.dp, bottom = 4.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.x(0x1FFFFFFFL))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(if (p >= 0f) p else 0.15f).background(C.accent))
            }
        }
        MenuRow(if (p != null) "Downloading…" else "Update now", gold = true, fr = first, enabled = p == null) { vm.startUpdate() }
        MenuRow("Later", value = "B") { vm.updateLater() }
        MenuRow("Skip this version") { vm.skipUpdate() }
    }
}
