package dev.couchpad.domain

data class ButtonChange(val button: ControllerButton, val pressed: Boolean)

/**
 * Compact text protocol over WebSocket, kept tiny to minimise latency.
 *
 * Phone -> TV: `d:<code>` (button down), `u:<code>` (button up).
 * TV -> Phone: `p:<n>` (assigned player n), `full` (no free slot).
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

    fun assigned(player: Int): String = "p:$player"
}
