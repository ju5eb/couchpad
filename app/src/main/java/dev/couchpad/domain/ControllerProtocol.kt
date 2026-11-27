package dev.couchpad.domain

data class ButtonChange(val button: ControllerButton, val pressed: Boolean)

/** Round-trip latency the phone measured over its last pings, in milliseconds. */
data class LatencyReport(val averageMs: Int, val maxMs: Int)

/**
 * Compact text protocol over WebSocket, kept tiny to minimise latency.
 *
 * Phone -> TV: `d:<code>` (button down), `u:<code>` (button up),
 *              `t:<id>` (ping, echoed back verbatim), `r:<avg>:<max>` (latency report),
 *              `m:1` / `m:0` (phone menu opened / closed: pauses the game).
 * TV -> Phone: `p:<n>` (assigned player n), `full` (no free slot), `t:<id>` (pong),
 *              `s:lobby` / `s:game` (which screen the TV shows).
 */
object ControllerProtocol {
    const val FULL = "full"
    const val SCREEN_LOBBY = "s:lobby"
    const val SCREEN_GAME = "s:game"

    /** True when the phone opened its menu, false when it closed it, null if not a menu message. */
    fun parseMenu(message: String): Boolean? = when (message) {
        "m:1" -> true
        "m:0" -> false
        else -> null
    }

    /** `f:1` = the player wants the TV to show how to join its own Wi-Fi, `f:0` = cancel. */
    fun parseFastJoin(message: String): Boolean? = when (message) {
        "f:1" -> true
        "f:0" -> false
        else -> null
    }

    /**
     * Tells phones where the TV is on each network so they can move to the
     * faster one by themselves: `n:direct=…&home=…&ssid=…&pass=…`, or `n:` when
     * the TV's own Wi-Fi is off.
     */
    fun networkInfo(direct: String?, home: String?, ssid: String?, password: String?): String {
        if (direct == null) return "n:"
        val fields = listOf("direct" to direct, "home" to home, "ssid" to ssid, "pass" to password)
            .filter { it.second != null }
            .joinToString("&") { (key, value) -> "$key=${java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")}" }
        return "n:$fields"
    }

    /** Whether [host] is on the same /24 network as [network], e.g. a phone on the TV's own Wi-Fi. */
    fun sameSubnet(host: String?, network: String?): Boolean {
        if (host == null || network == null) return false
        val a = host.split('.')
        val b = network.split('.')
        return a.size == 4 && b.size == 4 && a.take(3) == b.take(3)
    }

    fun parse(message: String): ButtonChange? {
        val separator = message.indexOf(':')
        if (separator != 1) return null
        val pressed = when (message[0]) {
            'd' -> true
            'u' -> false
            else -> return null
        }
        val button = ControllerButton.fromWire(message.substring(2)) ?: return null
        return ButtonChange(button, pressed)
    }

    /** Pings are echoed unchanged so the phone can time the round trip. */
    fun isPing(message: String): Boolean = message.length in 3..32 && message.startsWith("t:")

    fun parseLatencyReport(message: String): LatencyReport? {
        if (!message.startsWith("r:")) return null
        val parts = message.substring(2).split(':')
        if (parts.size != 2) return null
        val average = parts[0].toIntOrNull() ?: return null
        val max = parts[1].toIntOrNull() ?: return null
        if (average < 0 || max < average) return null
        return LatencyReport(average, max)
    }

    fun assigned(player: Int): String = "p:$player"
}
