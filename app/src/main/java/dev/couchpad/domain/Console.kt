package dev.couchpad.domain

/**
 * Consoles Couchpad can emulate. The ROM's extension picks the console, which
 * picks the libretro core, the box-art collection and the phone's button layout.
 *
 * Phones always send RetroPad positions (the SNES-named codes); each [layout]
 * only changes which buttons the phone shows and how they are labelled, e.g.
 * Genesis A/B/C sit on the RetroPad Y/B/A positions Genesis Plus GX expects.
 */
enum class Console(
    val label: String,
    /** libretro core name: `lib<core>_libretro_android.so` in jniLibs. */
    val core: String,
    val extensions: Set<String>,
    /** Controller layout id the phone understands. */
    val layout: String,
) {
    SNES("SNES", "snes9x", setOf("sfc", "smc"), "snes"),
    NES("NES", "fceumm", setOf("nes"), "nes"),
    GENESIS("Genesis", "genesis_plus_gx", setOf("md", "gen", "smd"), "genesis"),
    /** mGBA also runs Game Boy and Game Boy Color games. */
    GBA("GBA", "mgba", setOf("gba", "gb", "gbc"), "gba");

    /** libretro-thumbnails repository with this file's box art. */
    fun boxArtRepository(file: String): String = when (this) {
        SNES -> "Nintendo_-_Super_Nintendo_Entertainment_System"
        NES -> "Nintendo_-_Nintendo_Entertainment_System"
        GENESIS -> "Sega_-_Mega_Drive_-_Genesis"
        GBA -> when (extensionOf(file)) {
            "gb" -> "Nintendo_-_Game_Boy"
            "gbc" -> "Nintendo_-_Game_Boy_Color"
            else -> "Nintendo_-_Game_Boy_Advance"
        }
    }

    /** Short tag for the lobby card: GB and GBC games say so even though mGBA runs them. */
    fun badge(file: String): String = when (extensionOf(file)) {
        "gb" -> "GB"
        "gbc" -> "GBC"
        else -> label.uppercase()
    }

    companion object {
        val supportedExtensions: Set<String> = entries.flatMapTo(mutableSetOf()) { it.extensions }

        fun forFile(name: String): Console? = extensionOf(name).let { ext -> entries.firstOrNull { ext in it.extensions } }

        private fun extensionOf(name: String) = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    }
}
