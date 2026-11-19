package dev.couchpad.server

import dev.couchpad.domain.ControllerEvent
import dev.couchpad.domain.ControllerProtocol
import dev.couchpad.domain.PlayerSlots
import dev.couchpad.domain.RomLibrary
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
 * Serves the phone controller page, receives button input over WebSocket and
 * lets phones upload and pick games. Every endpoint except the page itself
 * requires [token], which phones only get by scanning the QR.
 */
class ControllerServer(
    private val port: Int,
    private val token: String,
    private val controllerPage: String,
    private val slots: PlayerSlots,
    private val library: RomLibrary,
    private val currentGame: () -> String?,
    private val onEvent: (ControllerEvent) -> Unit,
    private val onGameSelected: (String) -> Unit,
    private val onToggleOverlay: () -> Unit,
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
                get("/games") { if (call.authorized()) call.listGames() }
                post("/games") { if (call.authorized()) call.uploadGame() }
                post("/play") { if (call.authorized()) call.playGame() }
                post("/overlay") {
                    if (call.authorized()) {
                        onToggleOverlay()
                        call.respondText("OK")
                    }
                }
                webSocket("/ws") { handleController() }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        server = null
    }

    private suspend fun ApplicationCall.authorized(): Boolean {
        if (request.queryParameters["token"] == token) return true
        respondText("Invalid token", status = HttpStatusCode.Forbidden)
        return false
    }

    /** One name per line; the game currently loaded is prefixed with `*`. */
    private suspend fun ApplicationCall.listGames() {
        val current = currentGame()
        val body = library.list().joinToString("\n") { if (it == current) "*$it" else it }
        respondText(body, ContentType.Text.Plain)
    }

    private suspend fun ApplicationCall.uploadGame() {
        val name = request.queryParameters["name"].orEmpty()
        val length = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            ?: return respondText("Missing Content-Length", status = HttpStatusCode.LengthRequired)
        if (length > RomLibrary.MAX_BYTES) {
            return respondText("The file is larger than 8 MB", status = HttpStatusCode.PayloadTooLarge)
        }
        when (val result = library.save(name, receive<ByteArray>())) {
            is RomLibrary.SaveResult.Saved -> respondText(result.name, status = HttpStatusCode.Created)
            is RomLibrary.SaveResult.Rejected -> respondText(result.reason, status = HttpStatusCode.BadRequest)
        }
    }

    private suspend fun ApplicationCall.playGame() {
        val name = request.queryParameters["name"]
        if (library.resolve(name) == null) {
            return respondText("Unknown game", status = HttpStatusCode.NotFound)
        }
        onGameSelected(name!!)
        respondText("OK")
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
                val text = frame.readText()
                val change = ControllerProtocol.parse(text)
                when {
                    change != null -> onEvent(ControllerEvent.Button(lease.player, change))
                    ControllerProtocol.isPing(text) -> send(Frame.Text(text))
                    else -> ControllerProtocol.parseLatencyReport(text)
                        ?.let { onEvent(ControllerEvent.Latency(lease.player, it)) }
                }
            }
        } finally {
            if (slots.release(lease)) onEvent(ControllerEvent.Disconnected(lease.player))
        }
    }
}
