package dev.couchpad.domain

/** A player slot held by one connection of one client. */
data class Lease(val player: Int, val clientId: String, val session: Long)

/**
 * Assigns player numbers (1..capacity) to connected phones.
 *
 * A client that reconnects with the same id (e.g. after reloading the page)
 * keeps its player number, and the stale connection can no longer release it.
 */
class PlayerSlots(private val capacity: Int) {
    private val leases = mutableMapOf<Int, Lease>()
    private var nextSession = 0L

    @Synchronized
    fun assign(clientId: String): Lease? {
        val player = leases.values.firstOrNull { it.clientId == clientId }?.player
            ?: (1..capacity).firstOrNull { it !in leases }
            ?: return null
        return Lease(player, clientId, nextSession++).also { leases[player] = it }
    }

    /** Returns true when the lease was still current and the slot is now free. */
    @Synchronized
    fun release(lease: Lease): Boolean {
        if (leases[lease.player] != lease) return false
        leases.remove(lease.player)
        return true
    }
}
