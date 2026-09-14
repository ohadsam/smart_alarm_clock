package com.smartring.app.util

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowPowerManager

/**
 * The lock that covers the window between the alarm broadcast and the firing service
 * taking its own. Its failure modes are both invisible until they matter: never
 * acquiring it means the device can sleep through the hand-off and the alarm rings
 * late or not at all, and never releasing it means a permanently held CPU lock.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmHandoffWakeLockTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    // The shadow records the latest wake lock in a *static* field, and this object
    // holds its own across tests, so both have to be reset or one test's lock shows up
    // in the next one's assertions.
    @Before fun setUp() = ShadowPowerManager.clearWakeLocks()

    @After fun tearDown() {
        AlarmHandoffWakeLock.release()
        ShadowPowerManager.clearWakeLocks()
    }

    /** The most recently created WakeLock in this process — the one the object under
     *  test just took, since nothing else in a unit test takes any. Static on the
     *  shadow class itself, not a property of a shadowOf(powerManager) instance. */
    private fun latestLock(): PowerManager.WakeLock = ShadowPowerManager.getLatestWakeLock()

    @Test
    fun `acquire takes a lock and release lets it go`() {
        AlarmHandoffWakeLock.acquire(ctx)
        val lock = latestLock()
        assertTrue("the hand-off lock must actually be held", lock.isHeld)

        AlarmHandoffWakeLock.release()
        assertFalse("release must let the CPU go again", lock.isHeld)
    }

    @Test
    fun `releasing without acquiring is a no-op, not a crash`() {
        // The receiver releases on its failure path and the service releases on start;
        // either can run without the other having acquired.
        AlarmHandoffWakeLock.release()
        AlarmHandoffWakeLock.release()
    }

    @Test
    fun `a second acquire does not replace a lock that is already held`() {
        // Two alarms in the same minute, or a snooze racing the previous teardown.
        // Reference counting is off, so a replaced-but-still-held lock would be
        // unreachable by the single release that follows and stay held forever.
        AlarmHandoffWakeLock.acquire(ctx)
        val first = latestLock()
        AlarmHandoffWakeLock.acquire(ctx)
        assertEquals("the same lock must still be the one held", first, latestLock())

        AlarmHandoffWakeLock.release()
        assertFalse(first.isHeld)
    }

    @Test
    fun `the lock can be taken again after being released`() {
        AlarmHandoffWakeLock.acquire(ctx)
        AlarmHandoffWakeLock.release()
        AlarmHandoffWakeLock.acquire(ctx)
        assertTrue(latestLock().isHeld)
    }
}
