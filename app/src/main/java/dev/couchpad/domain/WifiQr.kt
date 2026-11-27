package dev.couchpad.domain

/**
 * Wi-Fi join payload understood by Android and iOS camera apps:
 * `WIFI:T:WPA;S:<ssid>;P:<password>;;`. Special characters in the SSID and
 * password (`\ ; , : "`) are backslash-escaped as the format requires.
 */
object WifiQr {
    enum class Security(val code: String) { WPA("WPA"), SAE("SAE"), OPEN("nopass") }

    private val SPECIAL = Regex("""([\\;,:"])""")

    fun encode(ssid: String, password: String?, security: Security): String {
        val builder = StringBuilder("WIFI:T:").append(security.code).append(";S:").append(escape(ssid)).append(';')
        if (security != Security.OPEN && !password.isNullOrEmpty()) builder.append("P:").append(escape(password)).append(';')
        return builder.append(';').toString()
    }

    private fun escape(value: String) = value.replace(SPECIAL, """\\$1""")
}
