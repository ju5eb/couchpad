package dev.couchpad.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.couchpad.domain.ControllerEvent
import dev.couchpad.domain.PlayerSlots
import dev.couchpad.server.ControllerServer
import dev.couchpad.server.LocalAddress
import java.security.SecureRandom

class MainActivity : Activity() {
    private val panels = mutableMapOf<Int, PlayerPanel>()
    private var server: ControllerServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val token = newToken()
        val address = LocalAddress.find()
        val url = address?.let { "http://$it:$PORT/?t=$token" }

        setContentView(buildLayout(url))

        server = ControllerServer(
            port = PORT,
            token = token,
            controllerPage = assets.open("controller.html").bufferedReader().use { it.readText() },
            slots = PlayerSlots(capacity = MAX_PLAYERS),
            onEvent = { event -> runOnUiThread { render(event) } },
        ).also { it.start() }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun render(event: ControllerEvent) {
        val panel = panels[event.player] ?: return
        when (event) {
            is ControllerEvent.Connected -> panel.setConnected(true)
            is ControllerEvent.Disconnected -> panel.setConnected(false)
            is ControllerEvent.Button -> panel.setPressed(event.change.button, event.change.pressed)
        }
    }

    private fun buildLayout(url: String?): LinearLayout {
        val join = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            if (url != null) {
                addView(ImageView(context).apply { setImageBitmap(QrCode.render(url, 420)) })
                addView(label("Scan to join", 24f))
                addView(label(url, 14f))
            } else {
                addView(label("No network. Connect the TV to Wi-Fi.", 22f))
            }
        }
        val players = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            (1..MAX_PLAYERS).forEach { player ->
                addView(PlayerPanel(context, player).also { panels[player] = it })
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#14141C"))
            addView(join, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(players, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.4f))
        }
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(0, 16, 0, 0)
    }

    private fun newToken(): String {
        val random = SecureRandom()
        return (1..8).map { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] }.joinToString("")
    }

    private companion object {
        const val PORT = 8080
        const val MAX_PLAYERS = 2
        const val TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}
