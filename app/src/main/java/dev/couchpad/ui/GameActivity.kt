package dev.couchpad.ui

import android.content.Context
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroView.GLRetroEvents
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import dev.couchpad.ControllerHub
import dev.couchpad.CouchpadApp
import dev.couchpad.domain.ControllerButton
import dev.couchpad.domain.ControllerEvent
import dev.couchpad.emulator.LegacyPacingMode
import dev.couchpad.emulator.RefreshRateContext
import dev.couchpad.emulator.SnesKeyMap
import dev.couchpad.server.LocalAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Game screen, opened from the lobby. Recreated when a phone picks another game;
 * finishing it (Back on the remote, or "Back to lobby" on a phone) returns to the
 * lobby. The hub keeps phones connected throughout.
 */
class GameActivity : ComponentActivity(), ControllerHub.Listener {
    private val panels = mutableMapOf<Int, PlayerPanel>()
    /** Read from network threads: input goes straight to the emulator, not via the main thread. */
    @Volatile
    private var retroView: GLRetroView? = null
    private var pacingContext: RefreshRateContext? = null
    private lateinit var hub: ControllerHub
    private lateinit var root: FrameLayout
    private lateinit var statusLabel: TextView
    private lateinit var overlay: View
    private lateinit var pauseOverlay: View
    private var emulationPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ask the TV for its game mode: skips picture processing that adds tens of ms of lag.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply { preferMinimalPostProcessing = true }
        }
        hub = (application as CouchpadApp).hub

        val url = LocalAddress.find()?.let { "http://$it:${ControllerHub.PORT}/?t=${hub.token}" }
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        statusLabel = label("", 16f)
        overlay = buildOverlay(url)
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ))
        pauseOverlay = buildPauseOverlay()
        root.addView(pauseOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        val game = hub.library.resolve(hub.selectedGame)
        if (game == null) {
            finish()   // nothing to play: back to the lobby
            return
        }
        statusLabel.text = game.nameWithoutExtension
        dev.couchpad.Diagnostics.game = game.name
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        dev.couchpad.Diagnostics.displayRefreshHz = display.refreshRate
        dev.couchpad.Diagnostics.displayModes = LegacyPacingMode.describe(display)
        val legacyMode = if (needsLegacyPacing()) LegacyPacingMode.pick(display) else null
        dev.couchpad.Diagnostics.pacing = "wallClockPacing=${hub.wallClockPacing} legacyMode=${legacyMode?.modeId}"
        if (legacyMode != null) startAfterModeSwitch(game, display, legacyMode) else startGame(game)
        hub.connectedPlayers().forEach { panels[it]?.setConnected(true) }
        applyOverlayVisibility()
    }

    /** Below Android 14 a slow device can only get wall-clock pacing through a display mode switch. */
    private fun needsLegacyPacing() =
        hub.wallClockPacing == true && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * Asks for [mode] on this window and starts the emulator once the display reports it
     * (LibretroDroid reads the refresh rate when it starts). Starts anyway after a timeout.
     */
    private fun startAfterModeSwitch(game: File, display: android.view.Display, mode: android.view.Display.Mode) {
        window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
        lifecycleScope.launch {
            var waited = 0L
            while (kotlin.math.abs(display.refreshRate - mode.refreshRate) > 1f && waited < MODE_SWITCH_TIMEOUT_MS) {
                delay(MODE_SWITCH_POLL_MS)
                waited += MODE_SWITCH_POLL_MS
            }
            dev.couchpad.Diagnostics.displayRefreshHz = display.refreshRate
            dev.couchpad.Diagnostics.pacing = "legacy mode switch to #${mode.modeId} @${mode.refreshRate}Hz, display now ${display.refreshRate}Hz after ${waited}ms"
            Log.i(TAG, dev.couchpad.Diagnostics.pacing)
            if (!isFinishing && !isDestroyed) startGame(game)
        }
    }

    /** Held while the game screen is visible: Wi-Fi power save causes 100 ms input spikes. */
    private val wifiLock by lazy {
        getSystemService(WifiManager::class.java)
            .createWifiLock(
                // LOW_LATENCY exists from Android 10; older devices reject it as an illegal lock type.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "couchpad:input",
            )
            .apply { setReferenceCounted(false) }
    }

    override fun onResume() {
        super.onResume()
        wifiLock.acquire()
        hub.listener = this
        hub.setInGame(true)
        // The emulator's lifecycle observer resumes it after this method; re-apply a pending pause then.
        emulationPaused = false
        root.post { applyPause(hub.isPaused()) }
    }

    override fun onPause() {
        wifiLock.release()
        if (hub.listener === this) hub.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) pacingContext?.release()
    }

    override fun onReturnToLobby() {
        runOnUiThread { finish() }
    }

    override fun onResetGame() {
        retroView?.reset()
    }

    override fun onPauseChanged(paused: Boolean) {
        runOnUiThread { applyPause(paused) }
    }

    /** Freezes emulation the same way LibretroDroid's own lifecycle pause does. */
    private fun applyPause(paused: Boolean) {
        pauseOverlay.visibility = if (paused) View.VISIBLE else View.GONE
        val view = retroView ?: return
        if (paused == emulationPaused) return
        emulationPaused = paused
        if (paused) {
            view.onPause()
            LibretroDroid.pause()
        } else {
            LibretroDroid.resume()
            view.onResume()
        }
    }

    private fun buildPauseOverlay(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        visibility = View.GONE
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(56, 36, 56, 36)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 36f
                setColor(Color.argb(235, 23, 23, 31))
            }
            addView(label("PAUSED", 30f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.2f })
            addView(label("A player has the menu open on their phone", 15f).apply { alpha = 0.7f })
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** The TV remote's OK / Menu / Info button shows or hides the QR panel. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in OVERLAY_TOGGLE_KEYS && event.repeatCount == 0) {
            hub.toggleOverlay()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Called on a network thread. Input reaches the emulator right here
     * (GLRetroView queues it for its GL thread); the on-screen panel updates later
     * on the main thread, off the critical path.
     */
    override fun onControllerEvent(event: ControllerEvent) {
        val port = event.player - 1
        when (event) {
            // While paused, drop presses so they do not fire all at once on resume; releases still go through.
            is ControllerEvent.Button -> if (!event.change.pressed || !hub.isPaused()) {
                sendKey(event.change.button, event.change.pressed, port)
            }
            is ControllerEvent.Menu -> return
            is ControllerEvent.Disconnected -> ControllerButton.entries.forEach { sendKey(it, pressed = false, port = port) }
            is ControllerEvent.Latency -> {
                Log.i(TAG, "latency player=${event.player} avg=${event.report.averageMs}ms max=${event.report.maxMs}ms")
                return
            }
            is ControllerEvent.Connected -> Log.i(TAG, "player ${event.player} connected, page version=${event.pageVersion ?: "unknown (old page)"}")
        }
        runOnUiThread { render(event) }
    }

    override fun onGameSelected() {
        runOnUiThread { recreate() }
    }

    override fun onOverlayChanged() {
        runOnUiThread { applyOverlayVisibility() }
    }

    /** Hidden while a game runs if requested; always shown when there is nothing to play. */
    private fun applyOverlayVisibility() {
        overlay.visibility = if (hub.overlayVisible || retroView == null) View.VISIBLE else View.GONE
    }

    private fun startGame(game: File) {
        val view = createRetroView(game, emulatorContext())
        root.addView(view, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        applyOverlayVisibility()
        if (hub.wallClockPacing == null) detectPacing(view)
    }

    /**
     * Vsync pacing (one emulated frame per drawn frame) is smoothest, but only works
     * if the device draws ~60 fps. Weak TVs get wall-clock pacing so the game keeps
     * real-time speed while some frames are skipped on screen.
     */
    private fun emulatorContext(): Context {
        if (hub.wallClockPacing != true || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return this
        return runCatching { RefreshRateContext(this, WALL_CLOCK_REPORTED_HZ) }
            .onSuccess {
                pacingContext = it
                Log.i(TAG, "wall-clock pacing: emulator sees ${it.reportedRefreshRate} Hz")
            }
            .onFailure { Log.w(TAG, "wall-clock pacing unavailable, using vsync pacing", it) }
            .getOrDefault(this)
    }

    /** First run only: measure drawn fps under vsync pacing and remember whether the device keeps up. */
    private fun detectPacing(view: GLRetroView) {
        val frames = AtomicInteger()
        lifecycleScope.launch {
            view.getGLRetroEvents().collect { if (it is GLRetroEvents.FrameRendered) frames.incrementAndGet() }
        }
        lifecycleScope.launch {
            delay(PACING_WARMUP_MS)
            frames.set(0)
            delay(PACING_SAMPLE_MS)
            val fps = frames.get() * 1000f / PACING_SAMPLE_MS
            val slow = fps < MIN_VSYNC_FPS
            Log.i(TAG, "pacing check: drawn fps=$fps -> ${if (slow) "wall-clock" else "vsync"}")
            hub.wallClockPacing = slow
            dev.couchpad.Diagnostics.pacing = "check fps=$fps -> ${if (slow) "wall-clock" else "vsync"} (wall-clock needs sdk 34)"
            if (slow) recreate()
        }
    }

    private fun createRetroView(game: File, context: Context): GLRetroView {
        val data = GLRetroViewData(context).apply {
            coreFilePath = dev.couchpad.Diagnostics.coreOverride?.let { "lib${it}_libretro_android.so" } ?: CORE_LIBRARY
            dev.couchpad.Diagnostics.core = coreFilePath
            gameFilePath = game.absolutePath
            systemDirectory = filesDir.absolutePath
            savesDirectory = filesDir.absolutePath
            preferLowLatencyAudio = true
        }
        return GLRetroView(context, data).also { view ->
            retroView = view
            applyRenderSize(view)
            lifecycle.addObserver(view)
            lifecycleScope.launch {
                view.getGLRetroErrors().collect { code -> statusLabel.text = "Emulator error $code" }
            }
            logFrameRate(view)
        }
    }

    /**
     * Weak GPUs (e.g. Mali-450 at 1080p) miss every other vsync and draw 30 fps. Rendering
     * the game surface smaller lets the hardware scaler do the upscale for free; SNES output
     * is 224 lines, so 540 loses nothing visible.
     */
    private fun applyRenderSize(view: GLRetroView) {
        val slowWithoutWallClock = needsLegacyPacing()
        val height = dev.couchpad.Diagnostics.renderHeightOverride
            ?: if (slowWithoutWallClock) LEGACY_RENDER_HEIGHT else 0
        if (height <= 0) {
            dev.couchpad.Diagnostics.renderSize = "native"
            return
        }
        val metrics = resources.displayMetrics
        val width = height * maxOf(metrics.widthPixels, metrics.heightPixels) / minOf(metrics.widthPixels, metrics.heightPixels)
        view.holder.setFixedSize(width, height)
        dev.couchpad.Diagnostics.renderSize = "${width}x$height"
    }

    /** Logs drawn frames per second (`adb logcat -s Couchpad`). */
    private fun logFrameRate(view: GLRetroView) {
        val frames = AtomicInteger()
        lifecycleScope.launch {
            view.getGLRetroEvents().collect { if (it is GLRetroEvents.FrameRendered) frames.incrementAndGet() }
        }
        lifecycleScope.launch {
            while (true) {
                delay(FPS_WINDOW_MS)
                val fps = frames.getAndSet(0) * 1000.0 / FPS_WINDOW_MS
                dev.couchpad.Diagnostics.drawnFps = fps
                dev.couchpad.Diagnostics.overlayVisible = overlay.visibility == View.VISIBLE
                Log.i(TAG, "drawn fps=$fps overlay=${overlay.visibility == View.VISIBLE}")
            }
        }
    }

    private fun render(event: ControllerEvent) {
        when (event) {
            is ControllerEvent.Connected -> {
                panels[event.player]?.setConnected(true)
                hideOverlayWhenEveryoneJoined()
            }
            is ControllerEvent.Disconnected -> panels[event.player]?.setConnected(false)
            is ControllerEvent.Button -> panels[event.player]?.setPressed(event.change.button, event.change.pressed)
            is ControllerEvent.Latency, is ControllerEvent.Menu -> Unit
        }
    }

    /** The translucent panel costs draw time; once all seats are taken, nobody needs the QR. */
    private fun hideOverlayWhenEveryoneJoined() {
        val full = hub.connectedPlayers().size >= ControllerHub.MAX_PLAYERS
        if (full && retroView != null && hub.overlayVisible) hub.toggleOverlay()
    }

    private fun sendKey(button: ControllerButton, pressed: Boolean, port: Int) {
        val action = if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        retroView?.sendKeyEvent(action, SnesKeyMap.keyCode(button), port)
    }

    private fun buildOverlay(url: String?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(16, 16, 16, 16)
        setBackgroundColor(Color.argb(190, 20, 20, 28))
        if (url != null) {
            addView(ImageView(context).apply { setImageBitmap(QrCode.render(url, 200)) })
            addView(label(url, 11f))
        } else {
            addView(label("No network. Connect the TV to Wi-Fi.", 14f))
        }
        addView(statusLabel)
        (1..ControllerHub.MAX_PLAYERS).forEach { player ->
            addView(PlayerPanel(context, player).also { panels[player] = it })
        }
        addView(label("Press OK on the remote to hide", 11f).apply { alpha = 0.6f })
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(0, 8, 0, 0)
    }

    private companion object {
        const val CORE_LIBRARY = "libsnes9x_libretro_android.so"
        const val TAG = "Couchpad"
        const val FPS_WINDOW_MS = 2_000L

        /** Anything 5+ Hz away from the game's ~60 fps makes LibretroDroid pace by wall clock. */
        const val WALL_CLOCK_REPORTED_HZ = 50f
        const val MIN_VSYNC_FPS = 55f
        const val PACING_WARMUP_MS = 2_000L
        const val PACING_SAMPLE_MS = 4_000L
        const val LEGACY_RENDER_HEIGHT = 540
        const val MODE_SWITCH_TIMEOUT_MS = 4_000L
        const val MODE_SWITCH_POLL_MS = 100L
        val OVERLAY_TOGGLE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
        )
    }
}
