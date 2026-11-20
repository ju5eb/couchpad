package dev.couchpad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxArtNamesTest {
    @Test
    fun `no-intro names are tried exactly first`() {
        assertEquals("Super Mario Kart (USA)", BoxArtNames.candidates("Super Mario Kart (USA).sfc").first())
    }

    @Test
    fun `goodtools tags and junk map to a no-intro name`() {
        val names = BoxArtNames.candidates("Super Bomberman 5 Gold Cartridge (J) ___.smc")
        assertEquals("Super Bomberman 5 Gold Cartridge (Japan)", names[1])
        assertTrue("shortened title should be tried", "Super Bomberman 5 (Japan)" in names)
    }

    @Test
    fun `untagged files try the common regions`() {
        val names = BoxArtNames.candidates("Chrono Trigger.sfc")
        assertEquals(listOf("Chrono Trigger", "Chrono Trigger (USA)", "Chrono Trigger (Europe)"), names.take(3))
    }

    @Test
    fun `shortening stops at two words`() {
        val names = BoxArtNames.candidates("Super Mario World 2 (U).sfc")
        assertTrue("Super Mario (USA)" in names)
        assertTrue(names.none { it.startsWith("Super (") })
    }

    @Test
    fun `unsafe characters are replaced like libretro-thumbnails does`() {
        assertTrue("Tom _ Jerry (USA)" in BoxArtNames.candidates("Tom & Jerry (U).sfc"))
    }

    @Test
    fun `candidate list is bounded and empty titles give nothing`() {
        assertTrue(BoxArtNames.candidates("A B C D E F G H I J K L M (U).sfc").size <= 12)
        assertEquals(emptyList<String>(), BoxArtNames.candidates("(U).sfc"))
    }
}
