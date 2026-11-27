package dev.couchpad.server

import dev.couchpad.domain.ControllerEvent
import dev.couchpad.domain.ControllerProtocol
import dev.couchpad.domain.Lease
import dev.couchpad.domain.PlayerSlots
import dev.couchpad.domain.RomLibrary
import java.util.concurrent.ConcurrentHashMap
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receive
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
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
    private val onReturnToLobby: () -> Unit,
    private val onResetGame: () -> Unit,
    /** Message telling a phone which screen the TV shows (`s:lobby` / `s:game`). */
    private val currentScreen: () -> String,
    /** Message telling a phone where the TV is on each network (`n:…`). */
    private val currentNetwork: () -> String,
) {
    private var server: EmbeddedServer<*, *>? = null
    private val sessions = ConcurrentHashMap<Lease, DefaultWebSocketServerSession>()

    /** Sends [message] to every connected phone without suspending the caller. */
    fun broadcast(message: String) {
        sessions.values.forEach { it.outgoing.trySend(Frame.Text(message)) }
    }

    fun start() {
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriod = 5.seconds
                timeout = 15.seconds
            }
            routing {
                get("/") {
                    // Never cache: phones must pick up a new controller page right after an update.
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondText(controllerPage, ContentType.Text.Html)
                }
                // Diagnostics for devices without adb: last uncaught exception plus device info.
                // Phones on the home Wi-Fi poll this on the TV's own network to know when they can switch.
                get("/ping") {
                    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondText("ok")
                }
                get("/crash") {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondText(dev.couchpad.CrashLog.read() ?: "no crash recorded", ContentType.Text.Plain)
                }
                get("/diag") {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    // Tuning knob for weak devices: /diag?renderHeight=540 (0 = native) restarts the game with it.
                    call.request.queryParameters["renderHeight"]?.toIntOrNull()?.let { height ->
                        dev.couchpad.Diagnostics.renderHeightOverride = height.coerceIn(0, 2160)
                        currentGame()?.let(onGameSelected)
                    }
                    // /diag?core=snes9x2010 restarts the game with another bundled core.
                    call.request.queryParameters["core"]?.takeIf { it in dev.couchpad.Diagnostics.cores }?.let { core ->
                        dev.couchpad.Diagnostics.coreOverride = core
                        currentGame()?.let(onGameSelected)
                    }
                    call.respondText(dev.couchpad.Diagnostics.report(), ContentType.Text.Plain)
                }
                get("/games") { if (call.authorized()) call.listGames() }
                post("/games") { if (call.authorized()) call.uploadGame() }
                post("/play") { if (call.authorized()) call.playGame() }
                post("/overlay") { if (call.authorized()) call.run(onToggleOverlay) }
                post("/lobby") { if (call.authorized()) call.run(onReturnToLobby) }
                post("/reset") { if (call.authorized()) call.run(onResetGame) }
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

    private suspend fun ApplicationCall.run(action: () -> Unit) {
        action()
        respondText("OK")
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
            return respondText("The file is larger than ${RomLibrary.MAX_MB} MB", status = HttpStatusCode.PayloadTooLarge)
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
        send(Frame.Text(currentScreen()))
        send(Frame.Text(currentNetwork()))
        sessions[lease] = this
        onEvent(ControllerEvent.Connected(lease.player, params["v"]?.take(32), call.request.origin.remoteHost))
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                val change = ControllerProtocol.parse(text)
                val menu = ControllerProtocol.parseMenu(text)
                val fastJoin = ControllerProtocol.parseFastJoin(text)
                when {
                    change != null -> onEvent(ControllerEvent.Button(lease.player, change))
                    ControllerProtocol.isPing(text) -> send(Frame.Text(text))
                    menu != null -> onEvent(ControllerEvent.Menu(lease.player, menu))
                    fastJoin != null -> onEvent(ControllerEvent.FastJoin(lease.player, fastJoin))
                    else -> ControllerProtocol.parseLatencyReport(text)
                        ?.let { onEvent(ControllerEvent.Latency(lease.player, it)) }
                }
            }
        } finally {
            sessions.remove(lease)
            if (slots.release(lease)) onEvent(ControllerEvent.Disconnected(lease.player))
        }
    }
}
