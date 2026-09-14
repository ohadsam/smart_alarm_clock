package com.smartring.app.util

import android.content.Context
import android.os.PowerManager

/**
 * Covers the gap between the alarm broadcast arriving and AlarmFiringService taking
 * its own wake lock.
 *
 * The platform holds a wake lock for an RTC_WAKEUP alarm only for the duration of
 * `onReceive`. Once that returns, the device is free to go straight back to sleep —
 * and `startForegroundService()` is asynchronous, so on a phone that has been idle
 * all night the service's `onStartCommand` (where its own PARTIAL_WAKE_LOCK is
 * taken) can be delayed or, at worst, not reached until something else wakes the
 * CPU. The symptom is the one an alarm clock can least afford: the alarm rings
 * late, or not at all, precisely on the nights the phone was left untouched.
 *
 * So the receiver takes a short-lived lock of its own before starting the service,
 * and the service releases it as soon as its own is held. Static because the two
 * live in different objects with no handle on each other, and reference-counting is
 * off so the release is idempotent — a second alarm arriving before the first
 * handed off must not leave a lock held forever.
 *
 * [TIMEOUT_MILLIS] is the real safety net: if the service never starts at all (the
 * "restricted" battery state refusing the foreground start, the process being
 * killed between the two), the lock still cannot outlive its usefulness.
 */
object AlarmHandoffWakeLock {
    private const val TAG = "SmartRing:handoff"
    private const val TIMEOUT_MILLIS = 60_000L

    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        if (lock?.isHeld == true) return
        lock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            ?.apply {
                setReferenceCounted(false)
                runCatching { acquire(TIMEOUT_MILLIS) }
            }
    }

    @Synchronized
    fun release() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }
}
