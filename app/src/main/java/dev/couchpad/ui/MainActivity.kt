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
import dev.couchpad.domain.ControllerButton
import dev.couchpad.domain.ControllerEvent
import dev.couchpad.domain.PlayerSlots
import dev.couchpad.emulator.SnesKeyMap
import dev.couchpad.server.ControllerServer
import dev.couchpad.server.LocalAddress
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

class MainActivity : ComponentActivity() {
    private val panels = mutableMapOf<Int, PlayerPanel>()
    private var server: ControllerServer? = null
    private var retroView: GLRetroView? = null
    private lateinit var statusLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val token = newToken()
        val url = LocalAddress.find()?.let { "http://$it:$PORT/?t=$token" }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        statusLabel = label("", 18f)
        val rom = findRom()
        if (rom != null) {
            root.addView(createRetroView(rom), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        } else {
            statusLabel.text = "No ROM found in\n${romsDir().absolutePath}"
        }
        root.addView(buildOverlay(url), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ))
        setContentView(root)

        server = ControllerServer(
            port = PORT,
            token = token,
            controllerPage = assets.open("controller.html").bufferedReader().use { it.readText() },
            slots = PlayerSlots(capacity = MAX_PLAYERS),
            onEvent = { event -> runOnUiThread { handle(event) } },
        ).also { it.start() }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun createRetroView(rom: File): GLRetroView {
        val data = GLRetroViewData(this).apply {
            coreFilePath = CORE_LIBRARY
            gameFilePath = rom.absolutePath
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
            statusLabel.text = rom.name
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

    /** Created by the app itself so it owns the folder; adb can then push ROMs into it. */
    private fun romsDir(): File = File(getExternalFilesDir(null), "roms").apply { mkdirs() }

    private fun findRom(): File? = romsDir().listFiles()
        ?.filter { it.extension.lowercase() in ROM_EXTENSIONS }
        ?.minByOrNull { it.name }

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
        (1..MAX_PLAYERS).forEach { player ->
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

    private fun newToken(): String {
        val random = SecureRandom()
        return (1..8).map { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] }.joinToString("")
    }

    private companion object {
        const val PORT = 8080
        const val MAX_PLAYERS = 2
        const val CORE_LIBRARY = "libsnes9x_libretro_android.so"
        const val TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
        val ROM_EXTENSIONS = setOf("sfc", "smc")
    }
}
