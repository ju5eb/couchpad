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
    fun `recognises pings`() {
        assertEquals(true, ControllerProtocol.isPing("t:42"))
        assertEquals(false, ControllerProtocol.isPing("t:"))
        assertEquals(false, ControllerProtocol.isPing("d:A"))
        assertEquals(false, ControllerProtocol.isPing("t:" + "9".repeat(40)))
    }

    @Test
    fun `parses latency reports`() {
        assertEquals(LatencyReport(12, 40), ControllerProtocol.parseLatencyReport("r:12:40"))
        listOf("r:12", "r:a:b", "r:50:10", "r:-1:5", "x:1:2").forEach {
            assertNull("expected null for '$it'", ControllerProtocol.parseLatencyReport(it))
        }
    }

    @Test
    fun `every button round-trips through its wire code`() {
        ControllerButton.entries.forEach {
            assertEquals(ButtonChange(it, true), ControllerProtocol.parse("d:${it.wire}"))
        }
    }
}
