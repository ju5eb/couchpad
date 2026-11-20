package dev.couchpad.domain

/**
 * Candidate thumbnail names for a ROM file, most exact first.
 *
 * libretro-thumbnails names box art after the No-Intro title, e.g.
 * "Super Mario Kart (USA)". ROM files often use older GoodTools tags
 * ("(J)", "(U)"), extra words ("Gold Cartridge") or junk ("___"), so we try:
 *  1. the file name as is,
 *  2. the clean title with the region from its tag, then the common regions,
 *  3. the title shortened one word at a time (keeping at least two words),
 *     with the tagged region only, to limit false matches.
 */
object BoxArtNames {
    private val REGIONS = listOf("USA", "Europe", "Japan", "World", "USA, Europe")
    private val TAG_REGIONS = mapOf(
        "u" to "USA", "us" to "USA", "usa" to "USA",
        "e" to "Europe", "eu" to "Europe", "europe" to "Europe",
        "j" to "Japan", "jp" to "Japan", "japan" to "Japan",
        "w" to "World", "world" to "World",
    )
    private val GROUP = Regex("""\s*[(\[]([^)\]]*)[)\]]""")
    private val TRAILING_JUNK = Regex("""[\s_\-.]+$""")
    private val UNSAFE = Regex("""[&*/:`<>?\\|"]""")   // replaced with "_" by libretro-thumbnails
    private const val MAX_CANDIDATES = 12

    fun candidates(romFile: String): List<String> {
        val base = romFile.substringBeforeLast('.').trim()
        val title = base.replace(GROUP, "").replace(TRAILING_JUNK, "").trim()
        if (title.isEmpty()) return emptyList()
        val tagged = GROUP.findAll(base).mapNotNull { TAG_REGIONS[it.groupValues[1].trim().lowercase()] }.firstOrNull()

        val names = LinkedHashSet<String>()
        names += base
        (listOfNotNull(tagged) + REGIONS).forEach { names += "$title ($it)" }
        var words = title.split(' ').filter { it.isNotBlank() }
        while (words.size > 2) {
            words = words.dropLast(1)
            names += "${words.joinToString(" ")} (${tagged ?: REGIONS.first()})"
        }
        return names.map { it.replace(UNSAFE, "_") }.take(MAX_CANDIDATES)
    }
}
