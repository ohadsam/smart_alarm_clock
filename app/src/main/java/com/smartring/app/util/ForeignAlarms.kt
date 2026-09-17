package com.smartring.app.util

import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * The next alarm clock the OS knows about, and whether this app is the one that set it.
 *
 * A null `showIntent` counts as ours: the only registration this app makes without a
 * readable creator would be its own, and treating an unattributable alarm as somebody
 * else's would produce a warning naming no app at all.
 */
data class RegisteredAlarm(
    val packageName: String,
    val appLabel: String,
    val triggerAtMillis: Long,
    val isOurs: Boolean,
)

/** An alarm clock some *other* app has registered with the OS. */
data class ForeignAlarm(
    val packageName: String,
    val appLabel: String,
    val triggerAtMillis: Long,
)

/**
 * Finding out that another app — the stock Clock, usually — has an alarm set.
 *
 * ## What this cannot do, and why
 *
 * It cannot switch that alarm off. Android has no API for it and never has: an alarm is
 * a PendingIntent owned by the UID that created it, and nothing outside that UID can
 * cancel, pause or mute it. That is a deliberate boundary rather than a gap — an app
 * able to silence a stranger's wake-up alarm would be a weapon, and every alarm clock on
 * the Play Store lives behind the same wall. So this file detects and reports; the user
 * switches the other alarm off themselves, in the app that owns it.
 *
 * ## What it can do
 *
 * [AlarmManager.getNextAlarmClock] is device-wide, not per-app: it returns the next
 * `setAlarmClock` registration for the whole user, whoever made it. The registration
 * carries a `showIntent`, and a PendingIntent knows its creator's package — which is
 * what turns "an alarm exists" into "Clock has an alarm at 06:30".
 *
 * ## The limitation, stated plainly
 *
 * It returns only the *next* alarm clock. When this app's own alarm is sooner, that is
 * the one the OS reports and any later foreign alarm is invisible to us. So this warns
 * about a foreign alarm that would ring **before** ours, and stays quiet about one
 * scheduled after it. That happens to be the case worth warning about — an alarm going
 * off ahead of the Shabbat alarm is the one that wakes you — but it is coverage, not
 * completeness, and the UI must not imply otherwise.
 */
object ForeignAlarms {

    /**
     * The next alarm clock registered on this device, whoever owns it — including this
     * app.
     *
     * Exposed as well as [next] because it answers a second question the user asked and
     * the app could not previously address: whether the system's own next-alarm
     * indicator (the little clock in the status bar) is showing *their* alarm. That
     * indicator comes from `setAlarmClock`, so "nothing in the status bar" means either
     * nothing is armed or the exact-alarm permission forced the inexact fallback — and
     * both are worth being able to see rather than guess at.
     */
    fun nextRegistered(context: Context): RegisteredAlarm? {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return null
        // Reading this never throws and needs no permission, but the whole call is
        // wrapped anyway: it runs on every resume of the alarm list, and an OEM
        // AlarmManager misbehaving is not worth taking the screen down for.
        val info = runCatching { manager.nextAlarmClock }.getOrNull() ?: return null
        val owner = runCatching { info.showIntent?.creatorPackage }.getOrNull()
        val ours = owner == null || owner == context.packageName
        return RegisteredAlarm(
            packageName = owner ?: context.packageName,
            appLabel = if (ours) "" else appLabelFor(context, owner!!),
            triggerAtMillis = info.triggerTime,
            isOurs = ours,
        )
    }

    /** The next alarm clock on this device that belongs to another app, if any. */
    fun next(context: Context): ForeignAlarm? {
        val registered = nextRegistered(context) ?: return null
        if (registered.isOurs || !isForeignOwner(registered.packageName, context.packageName)) return null
        return ForeignAlarm(registered.packageName, registered.appLabel, registered.triggerAtMillis)
    }

    /**
     * Whether an alarm-clock registration belongs to someone else.
     *
     * A null owner means the registration carried no showIntent, or the package could
     * not be read — treated as "not foreign", because warning about an alarm this app
     * cannot even name would be noise the user has no way to act on.
     */
    fun isForeignOwner(ownerPackage: String?, ourPackage: String): Boolean =
        ownerPackage != null && ownerPackage.isNotBlank() && ownerPackage != ourPackage

    /** The other app's user-visible name, falling back to its package id. */
    fun appLabelFor(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrElse { packageName }
}

/**
 * The warning text, or null when there is nothing to say.
 *
 * Pure so the wording — which has to be honest about the app's limits without being
 * alarming — is covered by tests rather than only by reading the screen.
 */
fun foreignAlarmWarning(alarm: ForeignAlarm?, nowMillis: Long = System.currentTimeMillis()): String? {
    if (alarm == null) return null
    // A registration already in the past is stale: the OS clears it moments later, and
    // announcing an alarm that has already rung would be worse than silence.
    if (alarm.triggerAtMillis <= nowMillis) return null
    return "מוגדר שעמור באפליקציה \"${alarm.appLabel}\" ל-${formatDayAndTime(alarm.triggerAtMillis, nowMillis)}, " +
        "והוא יצלצל לפני השעמור הקרוב של SmartRing. " +
        "אנדרואיד לא מאפשר לאפליקציה אחת לכבות שעמור של אפליקציה אחרת, ולכן צריך לכבות אותו שם."
}
