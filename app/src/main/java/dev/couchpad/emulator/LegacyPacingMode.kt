package dev.couchpad.emulator

import android.view.Display
import kotlin.math.abs

/**
 * Wall-clock pacing for devices below Android 14, where [RefreshRateContext] is unavailable.
 *
 * LibretroDroid paces by vsync when the display reports within 5 Hz of the game's ~60 fps.
 * Switching the real output to a mode outside that window (ideally 50 Hz, which every HDMI
 * TV accepts) makes it pace by wall clock, so a device that draws only 30 fps still runs
 * the game at full speed by emulating two frames per draw.
 */
object LegacyPacingMode {
    private const val GAME_FPS = 60f
    private const val VSYNC_WINDOW_HZ = 5f
    private const val PREFERRED_HZ = 50f

    /** A mode with the current resolution whose rate makes LibretroDroid use wall-clock pacing. */
    fun pick(display: Display): Display.Mode? {
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { abs(it.refreshRate - GAME_FPS) >= VSYNC_WINDOW_HZ }
            .minByOrNull { abs(it.refreshRate - PREFERRED_HZ) }
    }

    fun describe(display: Display): String =
        display.supportedModes.joinToString { "#${it.modeId} ${it.physicalWidth}x${it.physicalHeight}@${"%.2f".format(it.refreshRate)}" } +
            " (current #${display.mode.modeId})"
}
