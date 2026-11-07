package com.eclipse.launcher.data

/**
 * The art that comes with Eclipse for each system (assets/systems/<folder>/): a square icon, and the system's logo in
 * black, white and colour. A system card shows the icon on top and the logo under it; the logo is the one that suits
 * the card's look (black on a light card, white on a dark one, colour on the colour look) and never changes with the
 * icon you pick. Hack and variant systems use their parent's art.
 */
object SystemArt {
    /** Eclipse system id -> art folder. */
    private val folderOf: Map<String, String> = mapOf(
        "2600" to "atari2600",
        Platforms.FAVORITES to "favourites", // Eclipse's own: a gold heart
        "android" to "android",
        "32x" to "sega32x",
        "3do" to "3do",
        "3ds" to "n3ds",
        "5200" to "atari5200",
        "7800" to "atari7800",
        "cv" to "colecovision",
        "dreamcast" to "dreamcast",
        "nds" to "nds",
        "fc" to "nes",
        "nes" to "nes",
        "nes-hacks" to "nes",
        "gb" to "gb",
        "gb-hacks" to "gb",
        "gba" to "gba",
        "gba-hacks" to "gba",
        "gbc" to "gbc",
        "gbc-hacks" to "gbc",
        "gc" to "gc",
        "genesis" to "genesis",
        "gen-hacks" to "genesis",
        "md" to "genesis",
        "md-hacks" to "genesis",
        "gg" to "gamegear",
        "gg-hacks" to "gamegear",
        "gw" to "gameandwatch",
        "intv" to "intellivision",
        "jag" to "atarijaguar",
        "jagcd" to "atarijaguarcd",
        "lynx" to "atarilynx",
        "mame" to "mame",
        "arcade" to "arcade",
        "fbneo" to "arcade",
        "mark3" to "mark3",
        "mcd" to "segacd",
        "scd" to "segacd",
        "mini" to "pokemini",
        "mo2" to "odyssey2",
        "n64" to "n64",
        "neogeo" to "neogeo",
        "ngcd" to "neogeocd",
        "ngp" to "ngp",
        "ngpc" to "ngpc",
        "pce" to "pcengine",
        "pico8" to "pico8", // Eclipse's own: the palette as tiles, a pixel-font logo
        "pccd" to "pcenginecd",
        "tg16" to "tg16",
        "tgcd" to "tg-cd",
        "ps1" to "psx",
        "ps2" to "ps2",
        "ps3" to "ps3",
        "psp" to "psp",
        "saturn" to "saturn",
        "scummvm" to "scummvm",
        "sfc" to "snes",
        "sfc-hacks" to "snes",
        "snes" to "snes",
        "snes-hacks" to "snes",
        "sg1k" to "sg-1000",
        "steam" to "steam",
        "switch" to "switch",
        "vb" to "virtualboy",
        "vect" to "vectrex",
        "wii" to "wii",
        "wiiu" to "wiiu",
        "ws" to "wonderswan",
        "wsc" to "wonderswancolor",
        "wsv" to "supervision",
        "xbox" to "xbox",
        "xbox360" to "xbox360"
    )

    /** Folders that have a colour logo (the rest fall back to the white one). */
    private val colour = setOf(
        "favourites", "pico8", "3do", "android", "amigacd32", "android", "atari2600", "atari5200", "atari7800", "atarijaguar", "atarijaguarcd", "atarilynx", "colecovision", "dreamcast", "gameandwatch", "gamegear", "gb", "gba", "gbc", "gc", "genesis", "gx4000", "intellivision", "mame", "mark3", "n3ds", "n64", "n64dd", "nds", "neogeo", "neogeocd", "nes", "ngage", "ngp", "ngpc", "odyssey2", "pcengine", "pcenginecd", "pokemini", "ps2", "ps3", "ps4", "psp", "psvita", "psx", "saturn", "scummvm", "sega32x", "segacd", "sg-1000", "snes", "supergrafx", "supervision", "switch", "tg-cd", "tg16", "vectrex", "virtualboy", "wii", "wiiu", "wonderswan", "wonderswancolor", "xbox", "xbox360", "xboxone"
    )

    fun has(id: String) = id in folderOf

    /** Every system id with art. */
    fun ids(): Set<String> = folderOf.keys

    /** Path of the icon inside the app's assets (for the Stock icon pack). */
    fun iconAsset(id: String): String? = folderOf[id]?.let { "systems/$it/icon.webp" }

    private fun uri(id: String, file: String): String? = folderOf[id]?.let { "file:///android_asset/systems/$it/$file" }

    /** The bundled icon (a square picture). */
    fun icon(id: String): String? = uri(id, "icon.webp")

    /** The logo for a card look: "light" -> black, "dark" -> white, "color" -> colour (white if there isn't one). */
    fun logo(id: String, look: String): String? {
        val f = folderOf[id] ?: return null
        return when {
            look == "light" -> uri(id, "black.png")
            look == "color" && f in colour -> uri(id, "color.svg")
            else -> uri(id, "white.png")
        }
    }
}
