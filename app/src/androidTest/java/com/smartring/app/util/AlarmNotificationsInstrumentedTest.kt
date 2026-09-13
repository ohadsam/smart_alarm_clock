package com.smartring.app.util

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The alarm notification channel, checked through the real NotificationManager.
 *
 * A channel's settings are fixed at creation time and can only be inspected by asking
 * the system for it back, so this is not something the JVM test suite can meaningfully
 * verify — and the specific bug it guards (the channel's own default notification
 * sound and vibration playing *on top of* the alarm's chosen ringtone and configured
 * vibration pattern) is only audible on a device.
 */
@RunWith(AndroidJUnit4::class)
class AlarmNotificationsInstrumentedTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        AlarmNotifications.ensureChannel(context)
    }

    @Test
    fun theAlarmChannelExistsAndIsHighImportance() {
        val channel = manager.getNotificationChannel(AlarmNotifications.CHANNEL_ID)
        assertNotNull("the alarm channel must exist before anything notifies on it", channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
    }

    @Test
    fun theAlarmChannelIsSilentSoItDoesNotPlayOverTheAlarmItself() {
        val channel = manager.getNotificationChannel(AlarmNotifications.CHANNEL_ID)!!
        assertNull(
            "the channel must not add its own notification sound — AlarmFiringService " +
                "already plays the alarm's chosen ringtone",
            channel.sound,
        )
        assertFalse(
            "the channel must not add its own buzz — the service drives the alarm's " +
                "own configured vibration pattern",
            channel.shouldVibrate(),
        )
    }

    @Test
    fun theSupersededChannelIsGone() {
        assertNull(
            "the pre-v1.5.0 channel is replaced, not left behind as a dead entry in " +
                "the system's per-channel notification settings",
            manager.getNotificationChannel("smartring_alarm_channel"),
        )
    }
}
