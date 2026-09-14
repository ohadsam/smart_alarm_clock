package com.smartring.app.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The set of broadcasts that re-arm every alarm is declared twice — once in
 * [BootReceiver.RESCHEDULE_ACTIONS] and once in the manifest's intent-filter — and
 * both have to agree. An action present in only one of them fails completely
 * silently: the receiver is never invoked for it (missing from the manifest), or it
 * is invoked and returns immediately (missing from the set). That is exactly how the
 * exact-alarm case went unnoticed for months, and the consequence is the worst kind:
 * every alarm stays unarmed while the list still shows them all enabled.
 */
@RunWith(RobolectricTestRunner::class)
class BootReceiverActionsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** The actions the installed manifest actually routes to BootReceiver. */
    private fun manifestActions(): Set<String> =
        BootReceiver.RESCHEDULE_ACTIONS.filter { action ->
            // The framework method, which Robolectric resolves against the parsed
            // manifest's own <receiver> filters — so this really is asking "would the
            // system deliver this broadcast to BootReceiver on a device?"
            ctx.packageManager
                .queryBroadcastReceivers(Intent(action).setPackage(ctx.packageName), 0)
                .any { it.activityInfo?.name == BootReceiver::class.java.name }
        }.toSet()

    @Test
    fun `every action the receiver acts on is routed to it by the manifest`() {
        assertEquals(
            "an action in RESCHEDULE_ACTIONS but not in the manifest's intent-filter " +
                "means the receiver is simply never invoked for it",
            BootReceiver.RESCHEDULE_ACTIONS, manifestActions(),
        )
    }

    @Test
    fun `the four OS events that invalidate armed alarms are all covered`() {
        // Boot and app-replacement wipe AlarmManager outright; a clock or timezone
        // change leaves the alarms armed but at timestamps computed from the old local
        // time — after a few timezones, a 07:00 alarm fires at the wrong 07:00.
        listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        ).forEach {
            assertTrue("$it must trigger a reschedule", it in BootReceiver.RESCHEDULE_ACTIONS)
        }
    }

    @Test
    fun `granting the exact-alarm permission triggers a reschedule`() {
        // The system cancels every exact alarm when SCHEDULE_EXACT_ALARM is revoked and
        // does not restore them when it is granted back. The app's own reliability
        // prompt sends users to that very screen, so without this the recommended fix
        // left them with nothing armed.
        assertTrue(
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" in
                BootReceiver.RESCHEDULE_ACTIONS,
        )
    }

    @Test
    fun `an unrelated broadcast is not treated as a reschedule trigger`() {
        // The receiver is exported=false and only manifest-filtered, but it also guards
        // on the action; a guard that accepted everything would re-arm every alarm on
        // any stray broadcast.
        listOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT, Intent.ACTION_POWER_CONNECTED)
            .forEach { assertTrue("$it must not trigger a reschedule", it !in BootReceiver.RESCHEDULE_ACTIONS) }
    }
}
