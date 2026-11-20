package dev.couchpad.domain

sealed interface ControllerEvent {
    val player: Int

    /** [pageVersion] is the controller page build the phone is running, to spot stale cached pages. */
    data class Connected(override val player: Int, val pageVersion: String? = null) : ControllerEvent
    data class Disconnected(override val player: Int) : ControllerEvent
    data class Button(override val player: Int, val change: ButtonChange) : ControllerEvent
    data class Latency(override val player: Int, val report: LatencyReport) : ControllerEvent
}
