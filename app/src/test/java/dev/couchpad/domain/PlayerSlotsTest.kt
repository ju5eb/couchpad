package dev.couchpad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSlotsTest {
    @Test
    fun `assigns lowest free player and rejects when full`() {
        val slots = PlayerSlots(capacity = 2)
        assertEquals(1, slots.assign("a")?.player)
        assertEquals(2, slots.assign("b")?.player)
        assertNull(slots.assign("c"))
    }

    @Test
    fun `released slot is reused`() {
        val slots = PlayerSlots(capacity = 2)
        val first = slots.assign("a")!!
        slots.assign("b")
        assertTrue(slots.release(first))
        assertEquals(1, slots.assign("c")?.player)
    }

    @Test
    fun `reconnecting client keeps its player and stale lease cannot release it`() {
        val slots = PlayerSlots(capacity = 2)
        slots.assign("a")
        val stale = slots.assign("b")!!
        val fresh = slots.assign("b")!!

        assertEquals(stale.player, fresh.player)
        assertFalse(slots.release(stale))
        assertNull(slots.assign("c"))
        assertTrue(slots.release(fresh))
    }
}
