package dev.couchpad.ui

import android.graphics.Color
import android.os.Bundle
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
import com.swordfish.libretrodroid.GLRetroViewData
import dev.couchpad.ControllerHub
import dev.couchpad.CouchpadApp
import dev.couchpad.domain.ControllerButton
import dev.couchpad.domain.ControllerEvent
import dev.couchpad.emulator.SnesKeyMap
import dev.couchpad.server.LocalAddress
import kotlinx.coroutines.launch
import java.io.File

/** Game screen. Recreated whenever a phone picks another game; the hub keeps phones connected. */
class MainActivity : ComponentActivity(), ControllerHub.Listener {
    private val panels = mutableMapOf<Int, PlayerPanel>()
    private var retroView: GLRetroView? = null
    private lateinit var hub: ControllerHub
    private lateinit var statusLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hub = (application as CouchpadApp).hub

        val url = LocalAddress.find()?.let { "http://$it:${ControllerHub.PORT}/?t=${hub.token}" }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        statusLabel = label("", 16f)

        val game = hub.library.resolve(hub.selectedGame) ?: hub.library.list().firstOrNull()?.let(hub.library::resolve)
        if (game != null) {
            root.addView(createRetroView(game), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        } else {
            statusLabel.text = "No games yet.\nScan the QR and upload one from your phone."
        }
        root.addView(buildOverlay(url), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ))
        setContentView(root)

        hub.connectedPlayers().forEach { panels[it]?.setConnected(true) }
        hub.listener = this
    }

    override fun onDestroy() {
        if (hub.listener === this) hub.listener = null
        super.onDestroy()
    }

    override fun onControllerEvent(event: ControllerEvent) {
        runOnUiThread { handle(event) }
    }

    override fun onGameSelected() {
        runOnUiThread { recreate() }
    }

    private fun createRetroView(game: File): GLRetroView {
        val data = GLRetroViewData(this).apply {
            coreFilePath = CORE_LIBRARY
            gameFilePath = game.absolutePath
            systemDirectory = filesDir.absolutePath
            savesDirectory = filesDir.absolutePath
            preferLowLatencyAudio = true
        }
        return GLRetroView(this, data).also { view ->
            lifecycle.addObserver(view)
            retroView = view
            lifecycleScope.launch {
                view.getGLRetroErrors().collect { code -> statusLabel.text = "Emulator error $code" }
            }
            statusLabel.text = game.nameWithoutExtension
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
    }
}
