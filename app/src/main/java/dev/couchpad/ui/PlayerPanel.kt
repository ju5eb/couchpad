package dev.couchpad.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.couchpad.domain.ControllerButton

/** Shows one player's connection state and which buttons are held. */
class PlayerPanel(context: Context, private val player: Int) : LinearLayout(context) {
    private val title = TextView(context).apply {
        textSize = 14f
        setTextColor(Color.WHITE)
    }
    private val buttons = ControllerButton.entries.associateWith { button ->
        TextView(context).apply {
            text = button.label()
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 4)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(0, 12, 0, 0)
        addView(title)
        addView(GridLayout(context).apply {
            columnCount = 6
            buttons.values.forEach { addView(it, chipParams()) }
        })
        setConnected(false)
    }

    fun setConnected(connected: Boolean) {
        title.text = if (connected) "Player $player · connected" else "Player $player · waiting"
        title.alpha = if (connected) 1f else 0.5f
        if (!connected) buttons.keys.forEach { setPressed(it, false) }
    }

    fun setPressed(button: ControllerButton, pressed: Boolean) {
        buttons.getValue(button).apply {
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(if (pressed) Color.parseColor("#6B4FD8") else Color.parseColor("#2A2A35"))
            }
            setTextColor(if (pressed) Color.WHITE else Color.parseColor("#9A9AB0"))
        }
    }

    private fun chipParams() = GridLayout.LayoutParams().apply { setMargins(3, 3, 3, 3) }

    private fun ControllerButton.label() = when (this) {
        ControllerButton.UP -> "↑"
        ControllerButton.DOWN -> "↓"
        ControllerButton.LEFT -> "←"
        ControllerButton.RIGHT -> "→"
        ControllerButton.SHOULDER_L -> "L"
        ControllerButton.SHOULDER_R -> "R"
        ControllerButton.START -> "Start"
        ControllerButton.SELECT -> "Select"
        else -> name
    }
}
