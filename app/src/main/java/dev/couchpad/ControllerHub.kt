package dev.couchpad

import android.content.Context
import android.os.Build
import dev.couchpad.domain.ControllerEvent
import dev.couchpad.domain.ControllerProtocol
import dev.couchpad.domain.PlayerSlots
import dev.couchpad.domain.RomLibrary
import dev.couchpad.network.TvHotspot
import dev.couchpad.server.ControllerServer
import dev.couchpad.server.LocalAddress
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * App-wide controller state. Lives for the whole process so phones stay
 * connected (same token, same slots) across the lobby and game screens.
 * The screen in the foreground registers itself as [listener].
 */
class ControllerHub(context: Context) {
    interface Listener {
        /** Called on a network thread. */
        fun onControllerEvent(event: ControllerEvent)
        fun onGameSelected()
        fun onOverlayChanged() = Unit
        fun onReturnToLobby() = Unit
        fun onResetGame() = Unit
        fun onPauseChanged(paused: Boolean) = Unit
        /** Called on the main thread when the TV's own Wi-Fi network changes state. */
        fun onDirectNetworkChanged(state: TvHotspot.State) = Unit
        /** Called on a network thread when who asked for / who is on the faster connection changes. */
        fun onFastJoinChanged() = Unit
    }

    /** The TV's own Wi-Fi for controllers; lives as long as the app so its password stays stable. */
    val directNetwork = TvHotspot(context)

    /** Starts the TV's Wi-Fi once (Android 13+); safe to call again. */
    fun startDirectNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        directNetwork.start { state ->
            server.broadcast(networkMessage())
            listener?.onDirectNetworkChanged(state)
        }
    }

    /** The TV's own Wi-Fi details while it is up. */
    fun directNetworkInfo(): TvHotspot.Info? = (directNetwork.state as? TvHotspot.State.On)?.info

    /** Players who asked the TV to show how to join its own Wi-Fi and are not on it yet. */
    fun fastJoinRequests(): Set<Int> = synchronized(lock) { fastJoinRequests.toSet() }

    /** Players whose phone is connected through the TV's own Wi-Fi. */
    fun directPlayers(): Set<Int> = synchronized(lock) { directPlayers.toSet() }

    private fun networkMessage(): String {
        val info = directNetworkInfo()
        val home = LocalAddress.find(exclude = setOfNotNull(info?.address))
        return ControllerProtocol.networkInfo(info?.address, home, info?.ssid, info?.password)
    }

    private val prefs = context.getSharedPreferences("couchpad", Context.MODE_PRIVATE)

    /** Persisted so phones keep working when Android kills and restarts the app. */
    val token: String = prefs.getString(KEY_TOKEN, null)
        ?: newToken().also { prefs.edit().putString(KEY_TOKEN, it).apply() }
    val library = RomLibrary(File(context.getExternalFilesDir(null), "roms").apply { mkdirs() })

    @Volatile
    var listener: Listener? = null

    /** Whether the QR / player panel is shown over the game. Kept here so it survives game switches. */
    @Volatile
    var overlayVisible: Boolean = true
        private set

    /** True while the game screen is showing; phones get `s:game`, otherwise `s:lobby`. */
    @Volatile
    var inGame: Boolean = false
        private set

    /**
     * Whether this device needs wall-clock frame pacing because it cannot draw 60
     * frames per second. Null until measured once; persisted afterwards.
     */
    var wallClockPacing: Boolean?
        get() = if (prefs.contains(KEY_PACING)) prefs.getBoolean(KEY_PACING, false) else null
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_PACING) else putBoolean(KEY_PACING, value) }.apply()

    /** Persisted so the lobby can highlight the last game played. */
    var selectedGame: String?
        get() = prefs.getString(KEY_GAME, null)
        private set(value) = prefs.edit().putString(KEY_GAME, value).apply()

    private val lock = Any()
    private val connectedPlayers = mutableSetOf<Int>()
    private val playersInMenu = mutableSetOf<Int>()
    private val latencies = mutableMapOf<Int, Int>()
    private val fastJoinRequests = mutableSetOf<Int>()
    private val fastJoinRequestedAt = mutableMapOf<Int, Long>()
    private val directPlayers = mutableSetOf<Int>()
    private val expiry = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fast-join-expiry").apply { isDaemon = true } }

    private val server = ControllerServer(
        port = PORT,
        token = token,
        controllerPage = context.assets.open("controller.html").bufferedReader().use { it.readText() },
        slots = PlayerSlots(capacity = MAX_PLAYERS),
        library = library,
        currentGame = { selectedGame },
        onEvent = ::dispatch,
        onGameSelected = ::selectGame,
        onToggleOverlay = ::toggleOverlay,
        onReturnToLobby = { listener?.onReturnToLobby() },
        onResetGame = { listener?.onResetGame() },
        currentScreen = ::screenMessage,
        currentNetwork = ::networkMessage,
    )

    fun start() = server.start()

    fun connectedPlayers(): Set<Int> = synchronized(lock) { connectedPlayers.toSet() }

    /** Average round trip last reported by each player's phone, in ms. */
    fun latencies(): Map<Int, Int> = synchronized(lock) { latencies.toMap() }

    fun isPaused(): Boolean = synchronized(lock) { playersInMenu.isNotEmpty() }

    fun toggleOverlay() {
        overlayVisible = !overlayVisible
        listener?.onOverlayChanged()
    }

    /** Called by the screens so phones know whether to show the lobby or the pause menu. */
    fun setInGame(value: Boolean) {
        inGame = value
        if (!value) clearPauses()
        server.broadcast(screenMessage())
    }

    fun selectGame(name: String) {
        selectedGame = name
        listener?.onGameSelected()
    }

    private fun screenMessage() = if (inGame) ControllerProtocol.SCREEN_GAME else ControllerProtocol.SCREEN_LOBBY

    private fun dispatch(event: ControllerEvent) {
        var pauseChanged: Boolean? = null
        var fastJoinChanged = false
        synchronized(lock) {
            val wasPaused = playersInMenu.isNotEmpty()
            val before = fastJoinRequests.toSet() to directPlayers.toSet()
            when (event) {
                is ControllerEvent.Connected -> {
                    connectedPlayers += event.player
                    // Arriving through the TV's own Wi-Fi fulfils a pending "faster connection" request.
                    if (ControllerProtocol.sameSubnet(event.remoteHost, directNetworkInfo()?.address)) {
                        directPlayers += event.player
                        fastJoinRequests -= event.player
                    } else {
                        directPlayers -= event.player
                    }
                }
                // A pending request survives the disconnect: the phone is probably switching networks.
                is ControllerEvent.Disconnected -> {
                    connectedPlayers -= event.player
                    playersInMenu -= event.player
                    latencies -= event.player
                    directPlayers -= event.player
                }
                is ControllerEvent.Menu -> if (event.open && inGame) playersInMenu += event.player else playersInMenu -= event.player
                is ControllerEvent.Latency -> latencies[event.player] = event.report.averageMs
                is ControllerEvent.FastJoin -> if (event.show) {
                    fastJoinRequests += event.player
                    fastJoinRequestedAt[event.player] = System.currentTimeMillis()
                    expiry.schedule(::expireFastJoins, FAST_JOIN_TTL_MS + 500, TimeUnit.MILLISECONDS)
                } else {
                    fastJoinRequests -= event.player
                }
                is ControllerEvent.Button -> Unit
            }
            val paused = playersInMenu.isNotEmpty()
            if (paused != wasPaused) pauseChanged = paused
            fastJoinChanged = before != (fastJoinRequests.toSet() to directPlayers.toSet())
        }
        listener?.onControllerEvent(event)
        pauseChanged?.let { listener?.onPauseChanged(it) }
        if (fastJoinChanged) listener?.onFastJoinChanged()
    }

    /**
     * A request survives a disconnect (the phone is switching networks) but not
     * forever: if the phone never shows up on the TV's Wi-Fi, the panel goes away.
     */
    private fun expireFastJoins() {
        val now = System.currentTimeMillis()
        val changed = synchronized(lock) {
            fastJoinRequests.removeAll { now - (fastJoinRequestedAt[it] ?: 0L) >= FAST_JOIN_TTL_MS }
        }
        if (changed) listener?.onFastJoinChanged()
    }

    /** Leaving the game drops every pending pause. */
    private fun clearPauses() {
        val wasPaused = synchronized(lock) {
            playersInMenu.isNotEmpty().also { playersInMenu.clear() }
        }
        if (wasPaused) listener?.onPauseChanged(false)
    }

    private fun newToken(): String {
        val random = SecureRandom()
        return (1..8).map { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] }.joinToString("")
    }

    companion object {
        const val PORT = 8080
        const val MAX_PLAYERS = 2
        private const val FAST_JOIN_TTL_MS = 2 * 60 * 1000L
        private const val KEY_GAME = "selected_game"
        private const val KEY_TOKEN = "token"
        // v2: re-measure once now that game mode lets the TV draw 60 fps.
        private const val KEY_PACING = "wall_clock_pacing_v2"
        private const val TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}
