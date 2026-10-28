package com.smartring.app.sync

/**
 * What one phone is to another, from the first phone's point of view.
 *
 * Fixed when two phones are paired — after the person holding *each* phone confirmed it on
 * their own screen — and stored with the pairing. It is never taken from anything a peer
 * sends later: a phone that claimed a different role than the one agreed would simply be
 * refused.
 */
enum class DeviceRole {
    /** May set, change and remove alarms on the other phone. */
    PARENT,
    /** Its alarms from this peer are set remotely and locked against its own user. */
    CHILD,
    /** Equals: either may send the other a copy of an alarm, which the receiver must accept. */
    PEER;

    /** What the *other* phone is, given this phone's role. */
    val counterpart: DeviceRole
        get() = when (this) {
            PARENT -> CHILD
            CHILD  -> PARENT
            PEER   -> PEER
        }

    companion object {
        fun parse(name: String?): DeviceRole? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The permission matrix, in one place.
 *
 * Every rule about who may do what to whose alarms is a function here, so it can be read
 * (and tested) as a table rather than reassembled from scattered `if`s. The engine asks
 * these before it sends *and* before it applies, so a peer cannot gain a capability by
 * sending a message the pairing never granted.
 */
object SyncPolicy {

    /** A pairing is only coherent when the two sides' recorded roles are counterparts. */
    fun isConsistent(myRole: DeviceRole, theirRole: DeviceRole): Boolean =
        theirRole == myRole.counterpart

    /** This phone may set, change and delete alarms on the peer. */
    fun mayManagePeer(myRole: DeviceRole, theirRole: DeviceRole): Boolean =
        isConsistent(myRole, theirRole) && myRole == DeviceRole.PARENT

    /** The peer may set, change and delete alarms on this phone, locked against its user. */
    fun mayBeManagedByPeer(myRole: DeviceRole, theirRole: DeviceRole): Boolean =
        isConsistent(myRole, theirRole) && myRole == DeviceRole.CHILD

    /** Either phone may offer the other a copy of one of its alarms. */
    fun mayExchangeCopies(myRole: DeviceRole, theirRole: DeviceRole): Boolean =
        isConsistent(myRole, theirRole) && myRole == DeviceRole.PEER
}

/** This phone, as it introduces itself to a peer. */
data class LocalIdentity(
    /** 32 lowercase hex characters, random, generated once and never changed. */
    val deviceId: String,
    val name: String,
)

/** What is recorded about a pairing, as the sync engine needs it. */
data class PeerLink(
    val deviceId: String,
    val name: String,
    val myRole: DeviceRole,
    val theirRole: DeviceRole,
)
