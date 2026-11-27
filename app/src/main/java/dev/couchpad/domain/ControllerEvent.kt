package dev.couchpad.domain

sealed interface ControllerEvent {
    val player: Int

    /** [pageVersion] is the controller page build the phone is running, to spot stale cached pages. */
    data class Connected(
        override val player: Int,
        val pageVersion: String? = null,
        /** The phone's IP as seen by the TV: tells home Wi-Fi from the TV's own network. */
        val remoteHost: String? = null,
    ) : ControllerEvent

    /** The player asked to see ([show] = true) or dismissed the TV's own Wi-Fi join panel. */
    data class FastJoin(override val player: Int, val show: Boolean) : ControllerEvent
    data class Disconnected(override val player: Int) : ControllerEvent
    data class Button(override val player: Int, val change: ButtonChange) : ControllerEvent
    data class Latency(override val player: Int, val report: LatencyReport) : ControllerEvent

    /** The player's phone menu opened ([open] = true) or closed; an open menu pauses the game. */
    data class Menu(override val player: Int, val open: Boolean) : ControllerEvent
}
