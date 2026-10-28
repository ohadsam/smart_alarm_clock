package com.smartring.app.domain.model

/**
 * Where an alarm came from and who is allowed to change it — the one place the
 * multi-device feature touches the alarm model.
 *
 * Pure Kotlin on purpose (`docs/CLAUDE_CODE.md`: the domain layer has no `android.*`
 * imports), so every rule on this class is testable without a device.
 *
 * ## Three situations, and which fields carry them
 *
 * **A local alarm** — everything is null/false. This is every alarm that existed before
 * the feature, and every alarm the user makes for themselves.
 *
 * **An alarm a parent device set for this one** ([isRemoteManaged]). It rings here, and it
 * is *locked*: this device's user cannot edit, switch off or delete it — only the device
 * named in [originDeviceId] can, and it does so by sending a newer [revision].
 *
 * **An alarm this device set for a child** ([managedForDeviceId]). It is a record, not a
 * ring: it must never arm an alarm *here*. This device is the authority, so it stays
 * freely editable, and every change bumps [revision] so the child can tell a newer
 * version from a replay of an old one. [syncedRevision] is the highest revision the child
 * has acknowledged; `revision > syncedRevision` means a change is still waiting to be
 * delivered.
 *
 * A fourth, lighter case needs no fields of its own beyond [originDeviceId]: a *copy* a
 * peer device sent and the user accepted. It is an ordinary local alarm that merely
 * remembers who it came from, so the list can say so.
 */
data class AlarmSync(
    /**
     * Identity of this alarm *across* devices (32 hex chars). Local row ids are
     * per-device autoincrements and mean nothing on the other phone. Null until an alarm
     * is first shared.
     */
    val syncId: String?              = null,
    /** The device that authored this alarm, when it was not made here. */
    val originDeviceId: String?      = null,
    /**
     * That device's name as of the last sync. A snapshot on purpose: the list shows
     * "set by Mum" without joining another store, and it keeps reading sensibly after
     * the pairing has been removed.
     */
    val originDeviceName: String?    = null,
    /** Locked against this device's user. See the class comment. */
    val isRemoteManaged: Boolean     = false,
    /** Set when this device is the authority for an alarm that rings on another one. */
    val managedForDeviceId: String?  = null,
    val revision: Long               = 0L,
    val syncedRevision: Long         = 0L,
) {
    /** True for an alarm that arrived from another device, locked or not. */
    val cameFromAnotherDevice: Boolean get() = originDeviceId != null

    /** A change this device made for a child that the child has not confirmed yet. */
    val isAwaitingDelivery: Boolean
        get() = managedForDeviceId != null && revision > syncedRevision

    companion object {
        val LOCAL = AlarmSync()
    }
}
