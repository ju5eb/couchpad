package dev.couchpad.domain

data class ButtonChange(val button: ControllerButton, val pressed: Boolean)

/** Round-trip latency the phone measured over its last pings, in milliseconds. */
data class LatencyReport(val averageMs: Int, val maxMs: Int)

/**
 * Compact text protocol over WebSocket, kept tiny to minimise latency.
 *
 * Phone -> TV: `d:<code>` (button down), `u:<code>` (button up),
 *              `t:<id>` (ping, echoed back verbatim), `r:<avg>:<max>` (latency report).
 * TV -> Phone: `p:<n>` (assigned player n), `full` (no free slot), `t:<id>` (pong).
 */
object ControllerProtocol {
    const val FULL = "full"

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
