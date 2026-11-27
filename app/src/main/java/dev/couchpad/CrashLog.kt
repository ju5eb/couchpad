package dev.couchpad

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Persists the last uncaught JVM exception so it can be read over the LAN
 * (`GET /crash`) on devices where adb is unavailable. Native crashes are not captured.
 */
object CrashLog {
    @Volatile
    private var file: File? = null

    fun install(context: Context) {
        val target = File(context.filesDir, "last-crash.txt").also { file = it }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { target.writeText(format(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(): String? = file?.takeIf { it.exists() }?.readText()

    private fun format(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        return buildString {
            appendLine("time=${System.currentTimeMillis()} thread=${thread.name}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.joinToString()}")
            append(trace)
        }
    }
}
