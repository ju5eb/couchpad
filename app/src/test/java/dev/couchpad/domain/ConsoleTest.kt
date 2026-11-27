package dev.couchpad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConsoleTest {
    @Test
    fun `picks the console from the extension, case insensitive`() {
        assertEquals(Console.SNES, Console.forFile("Super Mario Kart (USA).sfc"))
        assertEquals(Console.NES, Console.forFile("Super Mario Bros. (World).NES"))
        assertEquals(Console.GENESIS, Console.forFile("Sonic.md"))
        assertEquals(Console.GENESIS, Console.forFile("Streets of Rage.gen"))
        assertEquals(Console.GBA, Console.forFile("Pokemon Emerald.gba"))
        assertEquals(Console.GBA, Console.forFile("Tetris.gb"))
        assertEquals(Console.GBA, Console.forFile("Pokemon Crystal.gbc"))
    }

    @Test
    fun `unknown files have no console`() {
        assertNull(Console.forFile("readme.txt"))
        assertNull(Console.forFile("noextension"))
    }

    @Test
    fun `game boy games get their own box art and badge`() {
        assertEquals("Nintendo_-_Game_Boy", Console.GBA.boxArtRepository("Tetris.gb"))
        assertEquals("Nintendo_-_Game_Boy_Color", Console.GBA.boxArtRepository("Zelda.gbc"))
        assertEquals("Nintendo_-_Game_Boy_Advance", Console.GBA.boxArtRepository("Zelda.gba"))
        assertEquals("GBC", Console.GBA.badge("Zelda.gbc"))
        assertEquals("GBA", Console.GBA.badge("Zelda.gba"))
        assertEquals("GENESIS", Console.GENESIS.badge("Sonic.md"))
    }

    @Test
    fun `supported extensions cover every console`() {
        assertEquals(setOf("sfc", "smc", "nes", "md", "gen", "smd", "gba", "gb", "gbc"), Console.supportedExtensions)
    }
}
