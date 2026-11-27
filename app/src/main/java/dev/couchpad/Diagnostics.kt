package dev.couchpad

import android.os.Build

/**
 * Live numbers for devices without adb, served at `GET /diag`.
 * Written from the game screen, read by the HTTP server.
 */
object Diagnostics {
    @Volatile var displayRefreshHz: Float? = null
    @Volatile var displayModes: String? = null
    @Volatile var drawnFps: Double? = null
    /** Set over the LAN to tune render resolution; null means the automatic choice. */
    @Volatile var renderHeightOverride: Int? = null
    @Volatile var renderSize: String = "native"
    @Volatile var overlayVisible: Boolean? = null
    /** Bundled libretro cores selectable over the LAN for comparison; null means the default. */
    val cores = setOf("snes9x", "snes9x2010", "snes9x2005_plus")
    @Volatile var coreOverride: String? = null
    @Volatile var core: String? = null
    @Volatile var pacing: String = "unknown"
    @Volatile var game: String? = null

    fun report(): String = buildString {
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} board=${Build.BOARD} hardware=${Build.HARDWARE}")
        appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("cpus=${Runtime.getRuntime().availableProcessors()} maxHeapMb=${Runtime.getRuntime().maxMemory() / 1_048_576}")
        appendLine("game=$game core=$core override=$coreOverride")
        appendLine("displayRefreshHz=$displayRefreshHz")
        appendLine("displayModes=$displayModes")
        appendLine("drawnFps=$drawnFps overlayVisible=$overlayVisible")
        appendLine("renderSize=$renderSize override=$renderHeightOverride")
        appendLine("pacing=$pacing")
        appendLine("--- last crash ---")
        append(CrashLog.read() ?: "none")
    }
}
