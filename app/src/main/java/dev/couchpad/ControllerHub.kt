package dev.couchpad

import android.content.Context
import android.os.Build
import dev.couchpad.domain.ControllerEvent
import dev.couchpad.domain.ControllerProtocol
import dev.couchpad.domain.PlayerSlots
import dev.couchpad.domain.RomLibrary
import dev.couchpad.network.TvHotspot
import dev.couchpad.server.ControllerServer
import java.io.File
import java.security.SecureRandom

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
    }

    /** The TV's own Wi-Fi for controllers; lives as long as the app so its password stays stable. */
    val directNetwork = TvHotspot(context)

    /** Starts the TV's Wi-Fi once (Android 13+); safe to call again. */
    fun startDirectNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        directNetwork.start { state -> listener?.onDirectNetworkChanged(state) }
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
        synchronized(lock) {
            val wasPaused = playersInMenu.isNotEmpty()
            when (event) {
                is ControllerEvent.Connected -> connectedPlayers += event.player
                is ControllerEvent.Disconnected -> {
                    connectedPlayers -= event.player
                    playersInMenu -= event.player
                    latencies -= event.player
                }
                is ControllerEvent.Menu -> if (event.open && inGame) playersInMenu += event.player else playersInMenu -= event.player
                is ControllerEvent.Latency -> latencies[event.player] = event.report.averageMs
                is ControllerEvent.Button -> Unit
            }
            val paused = playersInMenu.isNotEmpty()
            if (paused != wasPaused) pauseChanged = paused
        }
        listener?.onControllerEvent(event)
        pauseChanged?.let { listener?.onPauseChanged(it) }
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
        private const val KEY_GAME = "selected_game"
        private const val KEY_TOKEN = "token"
        // v2: re-measure once now that game mode lets the TV draw 60 fps.
        private const val KEY_PACING = "wall_clock_pacing_v2"
        private const val TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}
