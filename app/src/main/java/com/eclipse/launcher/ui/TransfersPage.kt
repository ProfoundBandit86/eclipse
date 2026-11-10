package com.eclipse.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.RommDownloads
import com.eclipse.launcher.data.ArtKind
import kotlinx.coroutines.delay

/** Is anything moving right now: a RomM download or upload, or a CHD conversion? The bottom screen's
 *  Transfers page is only there while this is true. */
fun transfersRunning(vm: EclipseVM): Boolean =
    RommDownloads.items.value.any { it.state != RommDownloads.State.FAILED } ||
        vm.upQueue != null || vm.uploads.isNotEmpty() || vm.chdJob != null

/** Bottom screen: live RomM downloads / uploads and CHD conversion, one card each.
 *  "Just finished" (the last few results) only with Settings → Dual screen → "Show finished transfers". */
@Composable
fun TransfersPage(vm: EclipseVM) {
    // ticks once a second so the time-left estimates move even between progress updates
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    if (tick < 0) return

    val downloads = RommDownloads.items.value.filter { it.state != RommDownloads.State.FAILED }
    val q = vm.upQueue
    val chd = vm.chdJob
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("TRANSFERS", color = C.accent, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(start = 2.dp))

        // ↓ From RomM: the first item is the one downloading, the rest wait
        downloads.firstOrNull()?.let { d ->
            val p = d.progress
            val size = d.rom.sizeBytes
            val eta = rememberEta("dl:${d.rom.id}", p)
            TxCard(
                Color(0xFF2E7D5B), Icons.Filled.CloudDownload, "FROM ROMM",
                if (downloads.size > 1) "1 of ${downloads.size}" else "",
                d.rom.name,
                when {
                    d.state == RommDownloads.State.QUEUED -> "Waiting to start"
                    p >= 0f && size > 0 -> "${vm.fmtBytes((size * p).toLong())} of ${vm.fmtBytes(size)}"
                    size > 0 -> vm.fmtBytes(size)
                    else -> "Downloading"
                },
                p.takeIf { it >= 0f }, etaText(eta),
                cover = { Cover(d.rom.name, d.rom.cover, it) },
                footer = downloads.getOrNull(1)?.let { "Next: ${it.rom.name}" + if (downloads.size > 2) " and ${downloads.size - 2} more" else "" },
                onStop = { RommDownloads.cancel(d.rom.id) }
            )
        }

        // ↑ To RomM: a run of uploads (bytes for the whole run), else single uploads by their own progress
        if (q != null) {
            val g = vm.games.firstOrNull { it.id == q.ids.getOrNull(q.index) }
            val p = if (q.bytesAll > 0) q.bytesDone.toFloat() / q.bytesAll else -1f
            val elapsed = System.currentTimeMillis() - q.startedAt
            val eta = if (q.bytesDone > 0 && elapsed > 3000) (elapsed.toDouble() * (q.bytesAll - q.bytesDone) / q.bytesDone).toLong() else null
            TxCard(
                Color(0xFF2F6FB5), Icons.Filled.CloudUpload, "TO ROMM",
                if (q.ids.size > 1) "${q.index + 1} of ${q.ids.size}" else "",
                q.name,
                if (q.bytesAll > 0) "${vm.fmtBytes(q.bytesDone)} of ${vm.fmtBytes(q.bytesAll)} sent" else "Sending",
                p.takeIf { it >= 0f }, etaText(eta),
                cover = { m -> Cover(q.name, g?.let { vm.artOf(it, ArtKind.BOX) }, m) },
                footer = null,
                onStop = if (vm.upStopping) null else ({ vm.stopBulkUpload() }),
                stopLabel = if (vm.upStopping) "Stopping…" else "Stop"
            )
        } else {
            vm.uploads.entries.toList().forEach { (id, p) ->
                val g = vm.games.firstOrNull { it.id == id }
                val name = g?.name ?: "Game"
                TxCard(
                    Color(0xFF2F6FB5), Icons.Filled.CloudUpload, "TO ROMM", "", name, "Sending",
                    p, etaText(rememberEta("up:$id", p)),
                    cover = { m -> Cover(name, g?.let { vm.artOf(it, ArtKind.BOX) }, m) },
                    footer = null, onStop = null
                )
            }
        }

        // Convert to CHD (RVZ for a GameCube / Wii disc, ZIP for a cartridge ROM)
        chd?.let { j ->
            val p = j.progress
            TxCard(
                Color(0xFF5B5BD6), Icons.Filled.Album, if (j.zip) "COMPRESS TO ZIP" else "CONVERT TO ${j.format}",
                if (j.total > 1) "${j.index} of ${j.total}" else "",
                j.name, j.phase.replaceFirstChar { it.uppercase() },
                p.takeIf { it >= 0f }, etaText(rememberEta("chd:${j.index}:${j.name}", p)),
                cover = { m -> DiscTile(m, remember(j.name, vm.games) { chdArt(vm, j.name) }) },
                footer = if (vm.chdSavedTotal > 0) "Saved by converting so far: ${vm.fmtBytes(vm.chdSavedTotal)}" else null,
                onStop = { vm.cancelChd() }
            )
        }

        // the last few results, only when turned on
        if (vm.flag("tx_done", true)) {
            val done = vm.transfers.take(3)
            if (done.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("JUST FINISHED", color = C.accent, fontSize = 9.sp, letterSpacing = 2.sp)
                    Box(Modifier.padding(start = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
                }
                done.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.x(0x0FFFFFFFL)).padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (t.ok) "✓" else "!", color = if (t.ok) Color(0xFF6FCF97) else Color(0xFFEF6B6B),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(t.name, color = C.ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (t.up) "Sent to RomM" else "From RomM") +
                                    (if (t.ok && t.bytes > 0) " · ${vm.fmtBytes(t.bytes)}" else "") +
                                    (if (!t.ok) " · " + (t.error ?: "failed") else ""),
                                color = if (t.ok) C.sub else Color(0xFFEF6B6B), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(shortAgo(t.time), color = C.sub, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

/** One live job: coloured glyph + kind + "n of m", then cover, name, detail, gold bar, % and time left. */
@Composable
private fun TxCard(
    color: Color, icon: ImageVector, kind: String, count: String,
    title: String, detail: String, progress: Float?, eta: String?,
    cover: @Composable (Modifier) -> Unit,
    footer: String?,
    onStop: (() -> Unit)?,
    stopLabel: String = "Stop"
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(C.x(0x14FFFFFFL)).border(1.dp, C.x(0x17FFFFFFL), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(color), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
            Text(kind, color = C.sub, fontSize = 9.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (count.isNotEmpty()) Text(count, color = C.sub, fontSize = 10.sp)
            Text(
                stopLabel, color = if (onStop != null) C.ink else C.sub, fontSize = 10.sp,
                modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(8.dp)).background(C.x(0x14FFFFFFL))
                    .then(if (onStop != null) Modifier.pointerInput(onStop) { detectTapGestures { onStop() } } else Modifier)
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            )
        }
        Row(Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            // the game's art, big (about twice the old size)
            cover(Modifier.size(92.dp, 124.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, color = C.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, color = C.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.x(0x1FFFFFFFL))) {
                    if (progress != null) Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(C.accent))
                }
                Row(Modifier.padding(top = 4.dp)) {
                    Text(if (progress != null) "${(progress * 100).toInt()}%" else "", color = C.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (eta != null) Text(eta, color = C.sub, fontSize = 10.sp)
                }
            }
        }
        if (footer != null) Text(footer, color = C.sub, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The game a CHD job's disc belongs to: its disc picture, else its box art (shown inside the disc). */
private fun chdArt(vm: EclipseVM, disc: String): String? {
    val title = com.eclipse.launcher.data.M3u.titleOf(disc)
    val g = vm.games.firstOrNull { java.io.File(it.path).nameWithoutExtension.let { n -> n.equals(disc, true) || n.equals(title, true) } }
        ?: vm.games.firstOrNull { it.name.equals(title, true) }
        ?: return null
    return vm.artOf(g, ArtKind.MEDIA) ?: vm.artOf(g, ArtKind.BOX)
}

/** The CHD job's disc: the game's own disc art (or its box art cut round), else a plain grey disc. */
@Composable
private fun DiscTile(m: Modifier, art: String? = null) {
    Box(m, contentAlignment = Alignment.Center) {
        val disc = Modifier.size(76.dp).clip(androidx.compose.foundation.shape.CircleShape)
        if (art != null) Cover("", art, disc) else Box(disc.background(Color(0xFF9EA3B0)))
        Box(Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF2A2B30)).border(3.dp, Color(0x88FFFFFF), androidx.compose.foundation.shape.CircleShape))
    }
}

/** Time left from how fast progress has moved since this job was first seen (null until there's enough to go on). */
@Composable
private fun rememberEta(key: String, progress: Float): Long? {
    val start = remember(key) { LongArray(1) { 0L } to FloatArray(1) { -1f } }
    if (progress < 0f) return null
    val now = System.currentTimeMillis()
    if (start.second[0] < 0f) { start.first[0] = now; start.second[0] = progress; return null }
    val dp = progress - start.second[0]
    val dt = now - start.first[0]
    if (dp < 0.01f || dt < 3000) return null
    return ((1f - progress) / dp * dt).toLong()
}

private fun etaText(ms: Long?): String? = when {
    ms == null -> null
    ms < 60_000 -> "under a minute left"
    ms < 3_600_000 -> "about ${(ms + 30_000) / 60_000} min left"
    else -> "about %.1f h left".format(ms / 3_600_000.0)
}

private fun shortAgo(t: Long): String {
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60} min"
        s < 86400 -> "${s / 3600} h"
        else -> "${s / 86400} d"
    }
}
