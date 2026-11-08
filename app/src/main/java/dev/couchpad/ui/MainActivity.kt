package dev.couchpad.ui

import android.content.Context
import android.graphics.Color
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
import dev.couchpad.ControllerHub
import dev.couchpad.CouchpadApp
import dev.couchpad.domain.ControllerButton
import dev.couchpad.domain.ControllerEvent
import dev.couchpad.emulator.RefreshRateContext
import dev.couchpad.emulator.SnesKeyMap
import dev.couchpad.server.LocalAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Game screen. Recreated whenever a phone picks another game; the hub keeps phones connected. */
class MainActivity : ComponentActivity(), ControllerHub.Listener {
    private val panels = mutableMapOf<Int, PlayerPanel>()
    private var retroView: GLRetroView? = null
    private var pacingContext: RefreshRateContext? = null
    private lateinit var hub: ControllerHub
    private lateinit var root: FrameLayout
    private lateinit var statusLabel: TextView
    private lateinit var overlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        setContentView(root)

        val game = hub.library.resolve(hub.selectedGame) ?: hub.library.list().firstOrNull()?.let(hub.library::resolve)
        if (game != null) {
            statusLabel.text = game.nameWithoutExtension
            startGame(game)
        } else {
            statusLabel.text = "No games yet.\nScan the QR and upload one from your phone."
        }

        hub.connectedPlayers().forEach { panels[it]?.setConnected(true) }
        applyOverlayVisibility()
        hub.listener = this
    }

    override fun onDestroy() {
        if (hub.listener === this) hub.listener = null
        super.onDestroy()
        pacingContext?.release()
    }

    /** The TV remote's OK / Menu / Info button shows or hides the QR panel. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in OVERLAY_TOGGLE_KEYS && event.repeatCount == 0) {
            hub.toggleOverlay()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onControllerEvent(event: ControllerEvent) {
        runOnUiThread { handle(event) }
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
            if (slow) recreate()
        }
    }

    private fun createRetroView(game: File, context: Context): GLRetroView {
        val data = GLRetroViewData(context).apply {
            coreFilePath = CORE_LIBRARY
            gameFilePath = game.absolutePath
            systemDirectory = filesDir.absolutePath
            savesDirectory = filesDir.absolutePath
            preferLowLatencyAudio = true
        }
        return GLRetroView(context, data).also { view ->
            retroView = view
            lifecycle.addObserver(view)
            lifecycleScope.launch {
                view.getGLRetroErrors().collect { code -> statusLabel.text = "Emulator error $code" }
            }
            logFrameRate(view)
        }
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
                Log.i(TAG, "drawn fps=${frames.getAndSet(0) * 1000.0 / FPS_WINDOW_MS} overlay=${overlay.visibility == View.VISIBLE}")
            }
        }
    }

    private fun handle(event: ControllerEvent) {
        val port = event.player - 1
        when (event) {
            is ControllerEvent.Connected -> panels[event.player]?.setConnected(true)
            is ControllerEvent.Disconnected -> {
                panels[event.player]?.setConnected(false)
                ControllerButton.entries.forEach { sendKey(it, pressed = false, port = port) }
            }
            is ControllerEvent.Button -> {
                panels[event.player]?.setPressed(event.change.button, event.change.pressed)
                sendKey(event.change.button, event.change.pressed, port)
            }
        }
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
        val OVERLAY_TOGGLE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO,
        )
    }
}
