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
    fun `parses menu open and close`() {
        assertEquals(true, ControllerProtocol.parseMenu("m:1"))
        assertEquals(false, ControllerProtocol.parseMenu("m:0"))
        assertNull(ControllerProtocol.parseMenu("m:2"))
        assertNull(ControllerProtocol.parseMenu("d:A"))
    }

    @Test
    fun `parses fast join requests`() {
        assertEquals(true, ControllerProtocol.parseFastJoin("f:1"))
        assertEquals(false, ControllerProtocol.parseFastJoin("f:0"))
        assertNull(ControllerProtocol.parseFastJoin("f:x"))
    }

    @Test
    fun `encodes network info with escaping, or empty when off`() {
        assertEquals(
            "n:direct=192.168.162.251&home=192.168.1.14&ssid=AndroidShare_1&pass=a%26b%20c",
            ControllerProtocol.networkInfo("192.168.162.251", "192.168.1.14", "AndroidShare_1", "a&b c"),
        )
        assertEquals("n:", ControllerProtocol.networkInfo(null, "192.168.1.14", "x", "y"))
        assertEquals("n:direct=10.0.0.1", ControllerProtocol.networkInfo("10.0.0.1", null, null, null))
    }

    @Test
    fun `detects hosts on the same network`() {
        assertEquals(true, ControllerProtocol.sameSubnet("192.168.162.62", "192.168.162.251"))
        assertEquals(false, ControllerProtocol.sameSubnet("192.168.1.9", "192.168.162.251"))
        assertEquals(false, ControllerProtocol.sameSubnet(null, "192.168.162.251"))
        assertEquals(false, ControllerProtocol.sameSubnet("::1", "192.168.162.251"))
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
