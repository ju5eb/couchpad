package dev.couchpad.server

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalAddress {
    /**
     * First private IPv4 address of an active interface, e.g. 192.168.1.20.
     * [exclude] skips addresses such as the TV's own hotspot, so this returns
     * the home-network address even while that hotspot is up.
     */
    fun find(exclude: Set<String> = emptySet()): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
        .firstOrNull { it !in exclude }
}
