package dev.couchpad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControllerProtocolTest {
    @Test
    fun `parses button down and up`() {
        assertEquals(ButtonChange(ControllerButton.A, true), ControllerProtocol.parse("d:A"))
        assertEquals(ButtonChange(ControllerButton.SHOULDER_L, false), ControllerProtocol.parse("u:SL"))
    }

    @Test
    fun `rejects malformed messages`() {
        listOf("", "d", "d:", "x:A", "d:Z", "dd:A", "A").forEach {
            assertNull("expected null for '$it'", ControllerProtocol.parse(it))
        }
    }

    @Test
    fun `every button round-trips through its wire code`() {
        ControllerButton.entries.forEach {
            assertEquals(ButtonChange(it, true), ControllerProtocol.parse("d:${it.wire}"))
        }
    }
}
