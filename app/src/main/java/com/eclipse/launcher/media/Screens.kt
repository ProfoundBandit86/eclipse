package com.eclipse.launcher.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display

/**
 * Which display is which, on any dual-screen handheld (AYN Thor, AYANEO Pocket DS, Retroid Pocket Duo, Retroid's
 * clip-on Dual Screen Add-on…).
 *
 * - [mainId]: the display Eclipse's main screen is on. Usually Android's default display; with the screens swapped
 *   (the add-on, whose built-in screen is the *bottom* one) it's the other one. MainActivity keeps it up to date, and
 *   everything that means "the main screen" (launching games and apps, screenshots, coming back to Eclipse) uses it
 *   instead of the default display.
 * - [companion]: the other screen. Only real panels count: private and virtual displays (screen recording, casting,
 *   Eclipse's own mirror) are skipped, built-in panels are picked before external ones (a TV on DisplayPort), and the
 *   one chosen in Settings → Dual screen wins when it's there.
 */
object Screens {
    @Volatile var mainId: Int = Display.DEFAULT_DISPLAY

    private val VIRTUAL = Regex("virtual|overlay|record|mirror|cast|miracast|wifi|wfd|projection", RegexOption.IGNORE_CASE)

    private fun dm(ctx: Context) = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** Could this display be the other screen of a handheld? */
    fun usable(d: Display): Boolean {
        if (d.flags and Display.FLAG_PRIVATE != 0) return false
        if (VIRTUAL.containsMatchIn(d.name ?: "")) return false
        return d.isValid
    }

    /** Built-in panels first (no FLAG_PRESENTATION), then external ones; never [exclude]. */
    fun candidates(ctx: Context, exclude: Int): List<Display> = runCatching {
        dm(ctx).displays.filter { it.displayId != exclude && usable(it) }
            .sortedBy { if (it.flags and Display.FLAG_PRESENTATION != 0) 1 else 0 }
    }.getOrDefault(emptyList())

    /** The other screen from [from]'s point of view: the one named [chosen] (Settings) if it's there, else the best one. */
    fun companion(ctx: Context, from: Int = mainId, chosen: String? = null): Display? {
        val all = candidates(ctx, from)
        return chosen?.takeIf { it.isNotBlank() }?.let { n -> all.firstOrNull { it.name == n } } ?: all.firstOrNull()
    }

    fun byId(ctx: Context, id: Int): Display? = runCatching { dm(ctx).getDisplay(id) }.getOrNull()

    /** Width / height in real pixels. */
    fun size(d: Display): Pair<Int, Int> {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /** Long side over short side: 1.78 for 16:9, 1.33 for 4:3, about 1.15 for the Thor's bottom screen. */
    fun aspect(d: Display): Float = size(d).let { (w, h) -> maxOf(w, h).toFloat() / minOf(w, h).coerceAtLeast(1) }

    /** "Built-in · 1280 × 960" / "External · 1920 × 1080", for Settings. */
    fun label(d: Display): String {
        val (w, h) = size(d)
        val kind = if (d.flags and Display.FLAG_PRESENTATION != 0) "External" else "Built-in"
        return "$kind · $w × $h"
    }

    /**
     * Should the main screen go on the other display? [mode] is "standard", "swapped" or "auto". Auto swaps only when the
     * default display is narrow (4:3 or squarer) and the other one is widescreen: a 4:3 handheld wearing a 16:9 add-on.
     */
    fun wantSwap(ctx: Context, mode: String, chosen: String?): Boolean = when (mode) {
        "swapped" -> companion(ctx, Display.DEFAULT_DISPLAY, chosen) != null
        "standard" -> false
        else -> {
            val main = byId(ctx, Display.DEFAULT_DISPLAY)
            val other = companion(ctx, Display.DEFAULT_DISPLAY, chosen)
            main != null && other != null && aspect(main) < 1.5f && aspect(other) >= 1.6f
        }
    }

    /** The display the main screen should be on right now. */
    fun wantedMain(ctx: Context, mode: String, chosen: String?): Int =
        if (wantSwap(ctx, mode, chosen)) companion(ctx, Display.DEFAULT_DISPLAY, chosen)?.displayId ?: Display.DEFAULT_DISPLAY
        else Display.DEFAULT_DISPLAY

    /**
     * Should taps on the bottom screen be able to take the controller? On the AYN Thor Eclipse keeps the controller on
     * the top screen (that's how it was designed); on devices with a "switch screen" key (Anbernic's RG key, AYANEO's
     * focus button, Retroid's focus switch) the controller has to work wherever the device sends it.
     */
    fun bottomFocusDefault(): Boolean {
        val who = (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.MODEL).lowercase()
        return !(who.contains("ayn") || who.contains("thor"))
    }
}
