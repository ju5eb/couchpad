package dev.couchpad.server

import dev.couchpad.domain.ControllerEvent
import dev.couchpad.domain.ControllerProtocol
import dev.couchpad.domain.PlayerSlots
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.seconds

/**
 * Serves the phone controller page and receives button input over WebSocket.
 * Only phones that scanned the QR (and therefore know [token]) are accepted.
 */
class ControllerServer(
    private val port: Int,
    private val token: String,
    private val controllerPage: String,
    private val slots: PlayerSlots,
    private val onEvent: (ControllerEvent) -> Unit,
) {
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriod = 5.seconds
                timeout = 15.seconds
            }
            routing {
                get("/") { call.respondText(controllerPage, ContentType.Text.Html) }
                webSocket("/ws") { handleController() }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        server = null
    }

    private suspend fun DefaultWebSocketServerSession.handleController() {
        val params = call.request.queryParameters
        if (params["token"] != token) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid token"))
            return
        }
        val clientId = params["id"]?.takeIf { it.isNotBlank() } ?: run {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing id"))
            return
        }
        val lease = slots.assign(clientId) ?: run {
            send(Frame.Text(ControllerProtocol.FULL))
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "no free slot"))
            return
        }

        send(Frame.Text(ControllerProtocol.assigned(lease.player)))
        onEvent(ControllerEvent.Connected(lease.player))
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val change = ControllerProtocol.parse(frame.readText()) ?: continue
                onEvent(ControllerEvent.Button(lease.player, change))
            }
        } finally {
            if (slots.release(lease)) onEvent(ControllerEvent.Disconnected(lease.player))
        }
    }
}
