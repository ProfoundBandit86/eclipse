package com.eclipse.launcher.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.RommDownloads
import com.eclipse.launcher.media.MediaListener
import com.eclipse.launcher.media.Notif

/*
 * The top of the right-hand drawer: the time, battery and network; Wi-Fi and Bluetooth buttons and a brightness slider;
 * then what's playing and your notifications (the newest one, or all of them when expanded). The screen's view
 * options sit at the bottom of the drawer (HomeAndDock.kt → OptionsDrawer).
 */

/** Wi-Fi, Bluetooth, brightness and battery, read straight from Android. */
object QuickSettings {
    fun wifiOn(ctx: Context): Boolean = runCatching {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
    }.getOrDefault(false)

    /** "Wi-Fi", "Mobile", "Ethernet" or "Offline": what the device is online through. */
    fun network(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
        when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            else -> "Online"
        }
    }.getOrDefault("Offline")

    @Suppress("DEPRECATION", "MissingPermission")
    fun bluetoothOn(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter?.isEnabled == true
    }.getOrDefault(false)

    fun battery(ctx: Context): Pair<Int, Boolean> = runCatching {
        val i = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (i?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        (if (level >= 0) level * 100 / scale.coerceAtLeast(1) else -1) to plugged
    }.getOrDefault(-1 to false)

    /** Wi-Fi signal, 0 (none) to 4 (full). */
    @Suppress("DEPRECATION")
    fun wifiLevel(ctx: Context): Int = runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        android.net.wifi.WifiManager.calculateSignalLevel(wm.connectionInfo.rssi, 5)
    }.getOrDefault(4)

    /** 0..1 */
    fun brightness(ctx: Context): Float = runCatching {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrDefault(0.5f)

    private fun start(ctx: Context, i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    /** Android's own Wi-Fi panel (apps can't switch Wi-Fi themselves since Android 10). */
    fun openWifi(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 29) start(ctx, Intent(Settings.Panel.ACTION_WIFI))
        else start(ctx, Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    fun openBluetooth(ctx: Context) = start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

}

/**
 * The top of the drawer's content: what is happening right now. RomM downloads and uploads and CHD conversions with
 * their progress, finished ones for a few seconds ("Done"), and system notifications that just arrived (a few
 * seconds each). It all leaves by itself; the full list is under the fold ("Notifications").
 */
@Composable
fun TransientArea(vm: EclipseVM, tick: Int) {
    val ctx = LocalContext.current
    val now = remember(tick) { System.currentTimeMillis() }
    val downloads = RommDownloads.items.value
    val allNotes by MediaListener.items.collectAsState()
    val ownPkg = ctx.packageName
    val items = ArrayList<NowItem>()
    downloads.filter { it.state != RommDownloads.State.FAILED }.forEach { d ->
        val p = d.progress
        items += NowItem(
            Color(0xFF2E7D5B), Icons.Filled.CloudDownload, "", if (d.state == RommDownloads.State.QUEUED) "ROMM \u00b7 QUEUED" else "ROMM \u00b7 DOWNLOAD",
            if (p >= 0f) "${(p * 100).toInt()}%" else "", d.rom.name, if (p >= 0f) p else null
        )
    }
    vm.uploads.entries.toList().forEach { (id, p) ->
        val name = vm.games.firstOrNull { it.id == id }?.name ?: "Game"
        items += NowItem(Color(0xFF2F6FB5), Icons.Filled.CloudUpload, "", "ROMM \u00b7 UPLOAD", "${(p * 100).toInt()}%", name, p)
    }
    vm.chdJob?.let { j ->
        items += NowItem(Color(0xFF5B5BD6), Icons.Filled.Album, "", "CHD \u00b7 ${j.phase.uppercase()}", if (j.total > 1) "${j.index} / ${j.total}" else "${(j.progress * 100).toInt().coerceAtLeast(0)}%", j.name, j.progress.takeIf { it >= 0f })
    }
    // finished transfers stay a few seconds
    vm.transfers.filter { now - it.time < 6_000 }.take(2).forEach { t ->
        items += NowItem(
            if (t.ok) Color(0xFF2E7D5B) else Color(0xFF8C3B3B), if (t.ok) Icons.Filled.Check else Icons.Filled.PriorityHigh, "",
            "ROMM \u00b7 " + (if (t.up) "UPLOAD" else "DOWNLOAD") + (if (t.ok) " DONE" else " FAILED"), "", t.name, null
        )
    }
    // system notifications that just came in
    allNotes.filter { it.pkg != ownPkg && now - it.time < 8_000 }.take(2).forEach { n ->
        items += NowItem(
            appColor(n.pkg), null, n.app.take(1).uppercase(), n.app.uppercase(), ago(n.time).uppercase(),
            listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": "), null
        )
    }
    if (items.isEmpty()) return
    // the header folds the section to one line (remembered: Settings flag "now_open")
    val open = vm.flag("now_open", true)
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusBox(onClick = { vm.setFlag("now_open", !open) }, shape = RoundedCornerShape(9.dp), focusKey = "dock:now") { _ ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NOW", color = DockGold, fontSize = 8.sp, letterSpacing = 2.sp)
                Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
                Text("${items.size}", color = C.sub, fontSize = 8.sp)
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (open) "Fold" else "Open",
                    tint = C.sub, modifier = Modifier.padding(start = 4.dp).size(14.dp))
            }
        }
        if (open) items.take(5).forEach { NowCard(it) }
        else NowSummary(items.first(), items.size - 1)
    }
}

/** One thing happening now, for the right panel's NOW section. [icon] or, without one, [glyph] (an app's first letter). */
private class NowItem(
    val color: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector?, val glyph: String,
    val kind: String, val right: String, val title: String, val progress: Float?
)

@Composable
private fun NowBadge(it: NowItem, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(it.color), contentAlignment = Alignment.Center) {
        if (it.icon != null) Icon(it.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
        else Text(it.glyph, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** The folded NOW section: the first item on one line with its percentage, and how many more. */
@Composable
private fun NowSummary(it: NowItem, more: Int) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (LocalPal.current.light) Color.White else C.x(0x12FFFFFFL))
            .border(1.dp, C.x(0x14FFFFFFL), shape).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NowBadge(it, 18.dp)
        Text(it.title, color = C.ink, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
        if (it.right.isNotEmpty()) Text(it.right, color = DockGold, fontSize = 9.sp, modifier = Modifier.padding(start = 6.dp))
        if (more > 0) Text("+$more", color = C.sub, fontSize = 8.5.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

/** One live card: a coloured icon, what it is and its percentage, the name, and a gold progress line. */
@Composable
private fun NowCard(it: NowItem) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (LocalPal.current.light) Color.White else C.x(0x12FFFFFFL))
            .border(1.dp, C.x(0x14FFFFFFL), shape).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NowBadge(it, 22.dp)
        Column(Modifier.weight(1f).padding(start = 9.dp)) {
            Row {
                Text(it.kind, color = C.sub, fontSize = 7.5.sp, letterSpacing = 1.3.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (it.right.isNotEmpty()) Text(it.right, color = C.sub, fontSize = 8.sp, letterSpacing = 1.sp)
            }
            Text(it.title, color = C.ink, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            val p = it.progress
            if (p != null) Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.x(0x1FFFFFFFL))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(C.accent))
            }
        }
    }
}

/** The drawer's light-mode colours: Settings' light panels with darker secondary text, so it reads over game art. */
val DockLightPal = GlassLightPal.copy(sub = Color(0xFF4A463F))

/** Gold for writing in the drawer: Eclipse's gold, or in light mode a deeper bronze that reads on off-white. */
val DockGold: Color @Composable get() =
    if (LocalPal.current.light && C.accent == ThemeColors.DefaultAccent) Color(0xFF8A6410) else C.accent

/** Wi-Fi tile and Bluetooth tile (gold when on), two across: a tap or A opens Android's own panel for it. */
@Composable
fun QuickTiles(tick: Int, fr: androidx.compose.ui.focus.FocusRequester? = null) {
    val ctx = LocalContext.current
    val wifi = remember(tick) { QuickSettings.wifiOn(ctx) }
    val net = remember(tick) { QuickSettings.network(ctx) }
    val bt = remember(tick) { QuickSettings.bluetoothOn(ctx) }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        QuickTile("Wi-Fi", if (!wifi) "Off" else if (net == "Wi-Fi") "Connected" else "On", wifi, Modifier.weight(1f), fr) { QuickSettings.openWifi(ctx) }
        QuickTile("Bluetooth", if (bt) "On" else "Off", bt, Modifier.weight(1f)) { QuickSettings.openBluetooth(ctx) }
    }
}

@Composable
private fun QuickTile(label: String, value: String, on: Boolean, modifier: Modifier, fr: androidx.compose.ui.focus.FocusRequester? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    FocusBox(modifier.height(46.dp), shape = shape, onClick = onClick, focusRequester = fr) {
        Column(
            Modifier.fillMaxSize()
                .background(if (on) C.accent.copy(alpha = 0.12f) else C.x(0x0FFFFFFFL))
                .border(1.dp, if (on) C.accent.copy(alpha = 0.45f) else C.x(0x12FFFFFFL), shape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label.uppercase(), color = C.x(0xFFCFCAC0L), fontSize = 8.sp, letterSpacing = 1.3.sp, maxLines = 1)
            Text(value, color = if (on) DockGold else C.sub, fontSize = 10.sp, maxLines = 1)
        }
    }
}

/**
 * The Page | System pill: Left / Right (or A, or a tap on a half) switches between this screen's options and the
 * Android side (Wi-Fi, Bluetooth, notifications). One focus stop, so the controller's Left / Right don't close the drawer.
 */
@Composable
fun DockSwitch(system: Boolean, onChange: (Boolean) -> Unit, fr: androidx.compose.ui.focus.FocusRequester, page: String = "Page") {
    val shape = RoundedCornerShape(50)
    FocusBox(
        Modifier.fillMaxWidth().height(34.dp).onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent e.key == Key.DirectionLeft || e.key == Key.DirectionRight
            when (e.key) { Key.DirectionLeft -> { onChange(false); true }; Key.DirectionRight -> { onChange(true); true }; else -> false }
        },
        shape = shape, onClick = { onChange(!system) }, focusRequester = fr
    ) {
        Row(
            Modifier.fillMaxSize().background(C.x(0x0FFFFFFFL)).border(1.dp, C.x(0x1AFFFFFFL), shape).padding(3.dp),
        ) {
            listOf(false to page, true to "System").forEach { (isSystem, name) ->
                val on = system == isSystem
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                        .background(if (on) C.accent.copy(alpha = 0.16f) else Color.Transparent)
                        .then(if (on) Modifier.border(1.dp, C.accent.copy(alpha = 0.45f), RoundedCornerShape(50)) else Modifier)
                        .pointerInput(isSystem) { detectTapGestures { onChange(isSystem) } },
                    contentAlignment = Alignment.Center
                ) { Text(name.uppercase(), color = if (on) C.ink else C.x(0xB3FFFFFFL), fontSize = 10.sp, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp)) }
            }
        }
    }
}

/** A gold small-caps heading with a hairline: "SORT", "VIEW", "QUICK". */
@Composable
fun DockHeading(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = C.sub.copy(alpha = 0.8f), fontSize = 8.sp, letterSpacing = 2.sp, maxLines = 1)
        Box(Modifier.padding(start = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
    }
}

/**
 * Now playing, then notifications: the newest one, and "+N more" to expand the rest (with Clear all). A opens a
 * notification (its app), X dismisses it. Without notification access, a line saying how to turn it on.
 */
@Composable
fun NotificationsSection(vm: EclipseVM) {
    val ctx = LocalContext.current
    val allNotes by MediaListener.items.collectAsState()
    // Eclipse's own notifications (RomM download progress) aren't shown here: Settings → RomM has them
    val ownPkg = androidx.compose.ui.platform.LocalContext.current.packageName
    val items = remember(allNotes, ownPkg) { allNotes.filter { it.pkg != ownPkg } }
    val media by vm.media.info.collectAsState()
    val access by vm.media.hasAccess.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NOTIFICATIONS", color = DockGold, fontSize = 8.sp, letterSpacing = 2.sp)
            if (items.isNotEmpty()) Box(
                Modifier.padding(start = 6.dp).height(13.dp).widthIn(min = 13.dp).clip(RoundedCornerShape(7.dp)).background(C.accent).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) { Text("${items.size}", color = C.onAccent, fontSize = 7.5.sp, fontWeight = FontWeight.Bold) }
            Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
            if (expanded && items.any { it.clearable }) SmallLink("Clear all") { MediaListener.dismissAll(); expanded = false }
            else if (items.size > 1) SmallLink(if (expanded) "Show less" else "Show all", if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore) { expanded = !expanded }
        }
        if (expanded && items.size > 1) SmallLink("Show less", Icons.Filled.ExpandLess, Modifier.align(Alignment.End)) { expanded = false }
        media?.takeIf { it.active }?.let { m -> NowPlayingCard(vm, m) }
        when {
            !access -> Text("Turn on notification access (Settings → General → Permissions) to see them here.", color = C.sub, fontSize = 10.sp)
            items.isEmpty() -> Text("No notifications", color = C.sub, fontSize = 10.sp)
            else -> {
                (if (expanded) items else items.take(1)).forEach { n -> NotifCard(n) }
                if (!expanded && items.size > 1) {
                    val more = items.drop(1)
                    val apps = more.map { it.app }.distinct().take(2).joinToString(", ") + if (more.map { it.app }.distinct().size > 2) "…" else ""
                    FocusBox(Modifier.fillMaxWidth().height(24.dp), shape = RoundedCornerShape(8.dp), onClick = { expanded = true }) {
                        val dash = C.x(0x26FFFFFFL)
                        Box(
                            Modifier.fillMaxSize().drawBehind {
                                drawRoundRect(dash, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
                            },
                            contentAlignment = Alignment.Center
                        ) { Text("+${more.size} MORE · ${apps.uppercase()}", color = C.sub, fontSize = 7.5.sp, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallLink(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(modifier, shape = RoundedCornerShape(6.dp), onClick = onClick) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text.uppercase(), color = C.sub, fontSize = 7.5.sp, letterSpacing = 1.2.sp)
            if (icon != null) Icon(icon, null, tint = C.sub, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun NowPlayingCard(vm: EclipseVM, m: com.eclipse.launcher.data.MediaInfo) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(C.accent.copy(alpha = 0.2f), C.x(0x0DFFFFFFL))))
            .border(1.dp, C.accent.copy(alpha = 0.3f), shape).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(C.x(0x1FFFFFFFL))) {
            m.art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(m.title, color = C.ink, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.artist.isNotBlank()) Text(m.artist, color = C.sub, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MiniBtn(Icons.Filled.SkipPrevious) { vm.media.prev() }
        MiniBtn(if (m.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow) { vm.media.toggle() }
        MiniBtn(Icons.Filled.SkipNext) { vm.media.next() }
    }
}

@Composable
private fun MiniBtn(icon: ImageVector, onClick: () -> Unit) {
    FocusBox(Modifier.size(24.dp), shape = CircleShape, onClick = onClick) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(icon, null, tint = C.ink, modifier = Modifier.size(15.dp)) }
    }
}

/** One notification: app initial, app name, how long ago, title and text. A opens it, X dismisses it. */
@Composable
private fun NotifCard(n: Notif) {
    val ctx = LocalContext.current
    val dismiss = remember(n.key) { { MediaListener.dismiss(n) } }
    var prevX by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(n.key) { onDispose { if (GamepadFocus.buttonX === dismiss) GamepadFocus.buttonX = prevX } }
    FocusBox(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
        onClick = { MediaListener.open(ctx, n) },
        onFocusChange = { f ->
            if (f) { prevX = GamepadFocus.buttonX; GamepadFocus.buttonX = dismiss }
            else if (GamepadFocus.buttonX === dismiss) GamepadFocus.buttonX = prevX
        }
    ) {
        Row(Modifier.fillMaxWidth().background(if (LocalPal.current.light) Color.White else C.x(0x12FFFFFFL)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(appColor(n.pkg)), contentAlignment = Alignment.Center) {
                Text(n.app.take(1).uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Row {
                    Text(n.app, color = C.ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(ago(n.time), color = C.sub, fontSize = 8.5.sp)
                }
                Text(
                    listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": "), color = C.x(0xFFB9B4AAL),
                    fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun appColor(pkg: String): Color = Color.hsv(((pkg.hashCode() and 0x7fffffff) % 360).toFloat(), 0.45f, 0.62f)

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 24 * 60 -> "${m / 60}h"; else -> "${m / (24 * 60)}d" }
}

