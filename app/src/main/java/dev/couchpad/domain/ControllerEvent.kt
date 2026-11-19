package dev.couchpad.domain

sealed interface ControllerEvent {
    val player: Int

    data class Connected(override val player: Int) : ControllerEvent
    data class Disconnected(override val player: Int) : ControllerEvent
    data class Button(override val player: Int, val change: ButtonChange) : ControllerEvent
    data class Latency(override val player: Int, val report: LatencyReport) : ControllerEvent
}
