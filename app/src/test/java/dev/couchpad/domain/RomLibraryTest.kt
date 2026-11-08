package dev.couchpad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RomLibraryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `sanitize strips paths and unsafe characters`() {
        assertEquals("evil.sfc", RomLibrary.sanitize("../../evil.sfc"))
        assertEquals("evil.sfc", RomLibrary.sanitize("C:\\games\\evil.sfc"))
        assertEquals("Super Game (USA).smc", RomLibrary.sanitize("Super Game (USA).SMC"))
        assertEquals("a_b.sfc", RomLibrary.sanitize("a*b.sfc"))
    }

    @Test
    fun `sanitize rejects unsupported or empty names`() {
        listOf("game.zip", "game", "..sfc", " .sfc", "notes.txt").forEach {
            assertNull("expected null for '$it'", RomLibrary.sanitize(it))
        }
    }

    @Test
    fun `saved rom is listed and resolvable`() {
        val library = RomLibrary(temp.newFolder("roms"))
        val result = library.save("Game.sfc", byteArrayOf(1, 2, 3))

        assertEquals(RomLibrary.SaveResult.Saved("Game.sfc"), result)
        assertEquals(listOf("Game.sfc"), library.list())
        assertEquals(3L, library.resolve("Game.sfc")?.length())
    }

    @Test
    fun `resolve ignores names outside the library`() {
        val library = RomLibrary(temp.newFolder("roms"))
        library.save("Game.sfc", byteArrayOf(1))

        assertNull(library.resolve("../Game.sfc"))
        assertNull(library.resolve("Missing.sfc"))
        assertNull(library.resolve(null))
    }

    @Test
    fun `rejects empty and oversized files`() {
        val library = RomLibrary(temp.newFolder("roms"))

        assertTrue(library.save("a.sfc", ByteArray(0)) is RomLibrary.SaveResult.Rejected)
        assertTrue(library.save("a.sfc", ByteArray(RomLibrary.MAX_BYTES + 1)) is RomLibrary.SaveResult.Rejected)
        assertEquals(emptyList<String>(), library.list())
    }

    @Test
    fun `uploading the same name replaces the file`() {
        val library = RomLibrary(temp.newFolder("roms"))
        library.save("Game.sfc", byteArrayOf(1))
        library.save("Game.sfc", byteArrayOf(1, 2))

        assertEquals(listOf("Game.sfc"), library.list())
        assertEquals(2L, library.resolve("Game.sfc")?.length())
    }
}
