package dev.couchpad.network

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import dev.couchpad.domain.WifiQr
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The TV's own Wi-Fi network for controllers (Android local-only hotspot).
 * Phones talk to the TV directly instead of through the home router, while the
 * TV stays on the home Wi-Fi (needs STA + AP concurrency, true on the test TV).
 *
 * Android picks the SSID and a fresh random password each time it starts.
 */
class TvHotspot(context: Context) {
    data class Info(val ssid: String, val password: String?, val security: WifiQr.Security, val address: String) {
        val joinQr: String get() = WifiQr.encode(ssid, password, security)
    }

    sealed interface State {
        data object Off : State
        data object Starting : State
        data class On(val info: Info) : State
        data class Failed(val reason: String) : State
    }

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    var state: State = State.Off
        private set

    /** Starts the network once; [onChange] is called on the main thread with every new state. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun start(onChange: (State) -> Unit) {
        if (state is State.Starting || state is State.On) return
        val before = ipv4Addresses()
        update(State.Starting, onChange)
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    awaitAddress(before, res.softApConfiguration, onChange, attemptsLeft = ADDRESS_ATTEMPTS)
                }

                override fun onStopped() {
                    reservation = null
                    update(State.Off, onChange)
                }

                override fun onFailed(reason: Int) = update(State.Failed(failureText(reason)), onChange)
            }, handler)
        } catch (e: SecurityException) {
            update(State.Failed("permission missing"), onChange)
        } catch (e: IllegalStateException) {
            update(State.Failed("already running in another app"), onChange)
        }
    }

    fun stop() {
        reservation?.close()
        reservation = null
        state = State.Off
    }

    /** The hotspot interface gets its IPv4 address a moment after onStarted. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun awaitAddress(before: Set<String>, config: SoftApConfiguration, onChange: (State) -> Unit, attemptsLeft: Int) {
        val address = (ipv4Addresses() - before).firstOrNull()
        when {
            address != null -> update(State.On(Info(config.ssid.orEmpty(), config.passphrase, security(config), address)), onChange)
            attemptsLeft > 0 -> handler.postDelayed({ awaitAddress(before, config, onChange, attemptsLeft - 1) }, ADDRESS_POLL_MS)
            else -> {
                stop()
                update(State.Failed("no address on the TV network"), onChange)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun security(config: SoftApConfiguration) = when (config.securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> WifiQr.Security.OPEN
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> WifiQr.Security.SAE
        else -> WifiQr.Security.WPA   // WPA2 and WPA2/WPA3 transition
    }

    private fun update(next: State, onChange: (State) -> Unit) {
        state = next
        onChange(next)
    }

    private fun ipv4Addresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    private fun failureText(reason: Int) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no free Wi-Fi channel"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Wi-Fi mode not compatible"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "hotspots are disabled on this TV"
        else -> "could not start (code $reason)"
    }

    private companion object {
        const val ADDRESS_ATTEMPTS = 15
        const val ADDRESS_POLL_MS = 300L
    }
}
