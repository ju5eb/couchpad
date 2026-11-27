package dev.couchpad.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiQrTest {
    @Test
    fun `encodes a wpa network`() {
        assertEquals("WIFI:T:WPA;S:AndroidShare_2219;P:secret123;;", WifiQr.encode("AndroidShare_2219", "secret123", WifiQr.Security.WPA))
    }

    @Test
    fun `escapes special characters`() {
        assertEquals("""WIFI:T:WPA;S:my\;net;P:a\:b\,c\\d\";;""", WifiQr.encode("my;net", """a:b,c\d"""", WifiQr.Security.WPA))
    }

    @Test
    fun `open networks carry no password`() {
        assertEquals("WIFI:T:nopass;S:Open;;", WifiQr.encode("Open", "ignored", WifiQr.Security.OPEN))
    }

    @Test
    fun `wpa3-only networks use SAE`() {
        assertEquals("WIFI:T:SAE;S:N;P:p;;", WifiQr.encode("N", "p", WifiQr.Security.SAE))
    }
}
