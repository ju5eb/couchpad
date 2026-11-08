package dev.couchpad.emulator

import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Context whose default display reports [refreshRate].
 *
 * LibretroDroid picks its frame pacing from `Display.getRefreshRate()`: when it is
 * within 5 Hz of the game (~60) it runs exactly one emulated frame per drawn frame,
 * so a TV that can only draw ~42 frames per second plays the game at 0.7x. Reporting
 * a rate outside that window makes it pace emulation by wall clock instead, running
 * up to two emulated frames per draw to keep real-time speed.
 *
 * The fake display is a private, surfaceless virtual display; only
 * `getDefaultDisplay()` is redirected, every other WindowManager call goes to the
 * real one. Call [release] when the game view is destroyed.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class RefreshRateContext(base: Context, refreshRate: Float) : ContextWrapper(base) {
    private val virtualDisplay: VirtualDisplay = checkNotNull(
        base.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            VirtualDisplayConfig.Builder("couchpad-frame-pacing", 16, 16, 160)
                .setRequestedRefreshRate(refreshRate)
                .build(),
            null,
            null,
        ),
    ) { "Could not create the frame pacing display" }

    private val windowManager: WindowManager = run {
        val real = base.getSystemService(WindowManager::class.java)
        Proxy.newProxyInstance(WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java)) { _, method, args ->
            if (method.name == "getDefaultDisplay" && args.isNullOrEmpty()) {
                virtualDisplay.display
            } else {
                try {
                    method.invoke(real, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } as WindowManager
    }

    /** What the emulator will see; may differ slightly from the requested value. */
    val reportedRefreshRate: Float get() = virtualDisplay.display.refreshRate

    override fun getSystemService(name: String): Any? =
        if (name == WINDOW_SERVICE) windowManager else super.getSystemService(name)

    fun release() = virtualDisplay.release()
}
