package dev.couchpad

import android.content.Context
import dev.couchpad.domain.ControllerEvent
import dev.couchpad.domain.PlayerSlots
import dev.couchpad.domain.RomLibrary
import dev.couchpad.server.ControllerServer
import java.io.File
import java.security.SecureRandom

/**
 * App-wide controller state. Lives for the whole process so phones stay
 * connected (same token, same slots) while the game screen is recreated.
 */
class ControllerHub(context: Context) {
    interface Listener {
        fun onControllerEvent(event: ControllerEvent)
        fun onGameSelected()
        fun onOverlayChanged()
    }

    /** Whether the QR / player panel is shown on the TV. Kept here so it survives game switches. */
    @Volatile
    var overlayVisible: Boolean = true
        private set

    /**
     * Whether this device needs wall-clock frame pacing because it cannot draw 60
     * frames per second. Null until measured once; persisted afterwards.
     */
    var wallClockPacing: Boolean?
        get() = if (prefs.contains(KEY_PACING)) prefs.getBoolean(KEY_PACING, false) else null
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_PACING) else putBoolean(KEY_PACING, value) }.apply()

    fun toggleOverlay() {
        overlayVisible = !overlayVisible
        listener?.onOverlayChanged()
    }

    private val prefs = context.getSharedPreferences("couchpad", Context.MODE_PRIVATE)

    /** Persisted so phones keep working when Android kills and restarts the app. */
    val token: String = prefs.getString(KEY_TOKEN, null)
        ?: newToken().also { prefs.edit().putString(KEY_TOKEN, it).apply() }
    val library = RomLibrary(File(context.getExternalFilesDir(null), "roms").apply { mkdirs() })

    @Volatile
    var listener: Listener? = null

    private val connectedPlayers = mutableSetOf<Int>()
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
    )

    /** Persisted so the last game reopens after a restart. */
    var selectedGame: String?
        get() = prefs.getString(KEY_GAME, null)
        private set(value) = prefs.edit().putString(KEY_GAME, value).apply()

    fun start() = server.start()

    fun connectedPlayers(): Set<Int> = synchronized(connectedPlayers) { connectedPlayers.toSet() }

    private fun dispatch(event: ControllerEvent) {
        synchronized(connectedPlayers) {
            when (event) {
                is ControllerEvent.Connected -> connectedPlayers += event.player
                is ControllerEvent.Disconnected -> connectedPlayers -= event.player
                is ControllerEvent.Button, is ControllerEvent.Latency -> Unit
            }
        }
        listener?.onControllerEvent(event)
    }

    private fun selectGame(name: String) {
        selectedGame = name
        listener?.onGameSelected()
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
