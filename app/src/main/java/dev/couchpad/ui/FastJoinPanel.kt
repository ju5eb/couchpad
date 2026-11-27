package dev.couchpad.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.couchpad.network.TvHotspot

/**
 * Full-screen panel shown when a phone taps "⚡ Faster connection": the QR that
 * joins the TV's own Wi-Fi plus the steps. The phone switches over by itself
 * once it can reach the TV there, which hides the panel.
 */
class FastJoinPanel(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val Int.dp get() = (this * density).toInt()

    private val title = text("", 24f, Color.WHITE, bold = true)
    private val qr = ImageView(context)
    private val network = text("", 15f, Color.WHITE, bold = true)
    private val password = text("", 15f, Color.WHITE)
    private var shownQr: String? = null

    init {
        setBackgroundColor(Color.argb(170, 0, 0, 0))
        visibility = GONE
        val steps = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(step("1", "Open your phone's camera and scan this code to join the TV's Wi-Fi."))
            addView(step("2", "If the phone says the network has no internet, choose \"Keep connection\" or \"Use anyway\"."))
            addView(step("3", "Go back to the controller and leave it open: it switches by itself."))
            addView(network.apply { setPadding(0, 18.dp, 0, 0) })
            addView(password)
            addView(text("Cancel it from the phone if you change your mind.", 12f, Color.rgb(140, 140, 166)).apply { setPadding(0, 14.dp, 0, 0) })
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28.dp, 28.dp, 32.dp, 28.dp)
            background = GradientDrawable().apply { cornerRadius = 24.dp.toFloat(); setColor(Color.rgb(23, 23, 31)) }
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { cornerRadius = 16.dp.toFloat(); setColor(Color.WHITE) }
                setPadding(10.dp, 10.dp, 10.dp, 10.dp)
                addView(qr, LayoutParams(200.dp, 200.dp))
            })
            addView(steps, LinearLayout.LayoutParams(400.dp, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 28.dp })
        }
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** Shows the panel for [players] (empty or no network hides it). */
    fun update(info: TvHotspot.Info?, players: Set<Int>) {
        if (info == null || players.isEmpty()) {
            visibility = GONE
            return
        }
        title.text = "⚡ Faster connection for " + players.sorted().joinToString(" & ") { "Player $it" }
        if (shownQr != info.joinQr) {
            qr.setImageBitmap(QrCode.render(info.joinQr, 200.dp))
            shownQr = info.joinQr
        }
        network.text = "Wi-Fi  ${info.ssid}"
        password.text = info.password?.let { "Password  $it" }.orEmpty()
        visibility = VISIBLE
    }

    private fun step(number: String, body: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 12.dp, 0, 0)
        addView(text(number, 15f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(124, 92, 255)) }
        }, LinearLayout.LayoutParams(26.dp, 26.dp).apply { rightMargin = 12.dp })
        addView(text(body, 15f, Color.rgb(220, 220, 232)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
}
