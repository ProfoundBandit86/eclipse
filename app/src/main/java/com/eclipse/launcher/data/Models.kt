package com.eclipse.launcher.data

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap

data class Game(val id: String, val name: String, val platform: String, val path: String)

/** [icon] is converted to a bitmap once, in the background, when the app list loads. */
data class AppEntry(
    val pkg: String,
    val label: String,
    val sysCategory: Int,
    val icon: ImageBitmap,
    val isGame: Boolean = false,
    val activity: String = ""   // launcher activity class, used to start the app directly
)

/**
 * What a game database knows about a game besides its pictures: release year, developer, publisher, genres (joined with
 * " · "), most players ("1-4") and the community rating ("4.1 / 5"). Any of it can be missing.
 */
data class GameInfo(
    val year: String? = null, val developer: String? = null, val publisher: String? = null,
    val genres: String? = null, val players: String? = null, val rating: String? = null
) {
    val isEmpty get() = listOf(year, developer, publisher, genres, players, rating).all { it.isNullOrBlank() }

    /** This one, with the gaps filled from [o]. */
    fun orElse(o: GameInfo?): GameInfo = if (o == null) this else GameInfo(
        year ?: o.year, developer ?: o.developer, publisher ?: o.publisher, genres ?: o.genres, players ?: o.players, rating ?: o.rating
    )

    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        year?.let { put("y", it) }; developer?.let { put("d", it) }; publisher?.let { put("pub", it) }
        genres?.let { put("g", it) }; players?.let { put("p", it) }; rating?.let { put("r", it) }
    }

    companion object {
        fun fromJson(o: org.json.JSONObject?): GameInfo? = o?.let {
            fun s(k: String) = it.optString(k).ifBlank { null }
            GameInfo(s("y"), s("d"), s("pub"), s("g"), s("p"), s("r"))
        }
        /** A rating out of [max] as "4.1 / 5" (the 0-5 scale the page shows), or null. */
        fun rating(value: Double?, max: Double): String? =
            value?.takeIf { it > 0.0 }?.let { String.format(java.util.Locale.US, "%.1f / 5", (it / max * 5.0).coerceIn(0.0, 5.0)) }
    }
}

data class ScrapeState(val done: Int, val total: Int, val current: String)

/** [url] = full image (saved when picked), [thumb] = small preview for the picker grid. */
data class ArtOption(val url: String, val source: String, val thumb: String = url)

/** One possible match for a game in the Match by hand list: the title, where it came from, and a short note (year, system). */
data class MatchHit(val name: String, val source: String, val note: String = "")

data class Session(val game: Game, val startMs: Long)

data class MediaInfo(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    /** playing or paused (not stopped, finished or idle): only then does the second screen show its media page */
    val active: Boolean = playing
)

data class Achievement(
    val id: Int,
    val title: String,
    val description: String,
    val badge: String,
    val earned: Boolean,
    val date: String?,
    val points: Int = 0
)

data class RaProgress(
    val title: String,
    val total: Int,
    val earned: Int,
    val achievements: List<Achievement>
)

data class ArtMeta(val art: List<ArtOption>, val summary: String?)

enum class ArtKind(val key: String, val label: String) {
    BOX("box", "Box art"),
    /**
     * Poster art, kept apart from Box art: SteamGridDB's 600×900 grids, IGDB's cover art, an arcade game's flyer.
     * Box art stays the real box (scans first; a poster only when no scan was found); this slot is always a poster.
     */
    POSTER("poster", "Poster"),
    BACKDROP("bg", "Backdrop"),
    LOGO("logo", "Logo"),
    /** a picture of the game's disc or cartridge */
    MEDIA("media", "Disc / cartridge"),
    /** the box photographed in 3D */
    BOX3D("box3d", "3D box"),
    /** a wide banner (SteamGridDB's heroes, LaunchBox's banners) */
    HERO("hero", "Hero")
}

/** The pictures a system (platform) can have: a small icon, its logo, and a backdrop. */
enum class PlatKind(val key: String, val label: String) {
    CARD("card", "Card image"),
    ICON("icon", "Icon"),
    LOGO("logo", "Logo"),
    BACKDROP("bg", "Backdrop")
}

enum class Screen { HOME, APPS, PLATFORMS, SETTINGS }

/** The two rows of the home screen: On Deck, and (press Down) the platform grid. */
enum class HomeSection { RECENTS, PLATFORMS, APPS }
