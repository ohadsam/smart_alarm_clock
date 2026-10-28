package com.smartring.app.sync.platform

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.smartring.app.MainActivity
import com.smartring.app.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps this phone reachable by the phones that manage it.
 *
 * A child has to be listening for a parent's change to arrive, and Android does not let a
 * background app hold a Bluetooth server open — so this is a foreground service, with the
 * notification that implies. It runs only while it has a reason to: the person has not turned
 * listening off, and at least one enabled pairing makes this phone a child or a peer. A phone that
 * only ever sends (a parent's) never starts it.
 */
@AndroidEntryPoint
class SyncListenerService : Service() {

    @Inject lateinit var server: SyncServer

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InlinedApi")   // the type constant is inlined; the 3-argument form is handled by ServiceCompat below API 29
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Not allowed to run in the foreground right now (background start restrictions, or the
            // Bluetooth permission the service type requires was revoked). Not an error to surface:
            // the controller starts it again when the app is next in front.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!BluetoothSupport.canConnect(this) || !BluetoothSupport.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        server.acquire(OWNER)
        return START_STICKY
    }

    override fun onDestroy() {
        server.release(OWNER)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("מאזין לשעמורים ממכשיר מקושר")
            .setContentText("אפשר לכבות בהגדרות ← מכשירים מקושרים")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val OWNER = "listener"
        private const val CHANNEL_ID = "smartring_sync_listener"
        private const val NOTIFICATION_ID = 1010

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "מכשירים מקושרים", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "מציג שהמכשיר מקשיב לעדכוני שעמורים ממכשיר מקושר"
                    setShowBadge(false)
                }
            )
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncListenerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncListenerService::class.java))
        }
    }
}
