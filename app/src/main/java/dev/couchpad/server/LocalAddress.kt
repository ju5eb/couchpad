package dev.couchpad.server

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalAddress {
    /** First private IPv4 address of an active interface, e.g. 192.168.1.20. */
    fun find(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress
}
