package com.smartring.app.sync.platform

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.smartring.app.sync.DeviceRole
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides, from the pairings and the settings, which of the sync machinery should be running — and
 * runs only that. A phone with no pairings pays nothing: no service, no worker, no socket.
 *
 *  - **Listening** (a foreground service holding the Bluetooth server) is needed by a phone that
 *    something may send to: any enabled pairing in which this phone is not the parent.
 *  - **Sending** (the coordinator, and the periodic worker that backs it up) is needed by any
 *    enabled pairing in which this phone is not the child.
 */
@Singleton
class SyncController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pairedDevices: PairedDeviceRepository,
    private val identity: DeviceIdentityRepository,
    private val coordinator: SyncCoordinator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private data class Plan(val listen: Boolean, val send: Boolean)

    /** Called once from the Application. Idempotent. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        coordinator.start()
        scope.launch {
            while (true) {
                runCatching {
                    combine(pairedDevices.devices, identity.listenInBackground) { devices, listen ->
                        val active = devices.filter { it.isEnabled }
                        Plan(
                            listen = listen && active.any { it.myRole != DeviceRole.PARENT },
                            send = active.any { it.myRole != DeviceRole.CHILD },
                        )
                    }.distinctUntilChanged().collect { apply(it) }
                }
                // The flow only ends on a failure; start over rather than leave the machinery
                // stuck in whatever state it was last put in.
                delay(10_000)
            }
        }
    }

    /** Re-evaluates the service — for when a permission was just granted or Bluetooth switched on. */
    fun refreshListener() {
        scope.launch {
            val devices = pairedDevices.all().filter { it.isEnabled }
            val listen = identity.listenInBackgroundNow()
            apply(Plan(listen && devices.any { it.myRole != DeviceRole.PARENT }, devices.any { it.myRole != DeviceRole.CHILD }))
        }
    }

    private fun apply(plan: Plan) {
        val work = WorkManager.getInstance(context)
        if (plan.send) {
            work.enqueueUniquePeriodicWork(
                SyncWorker.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(SyncWorker.INTERVAL_MINUTES, TimeUnit.MINUTES).build(),
            )
        } else {
            work.cancelUniqueWork(SyncWorker.WORK_NAME)
        }
        if (plan.listen && BluetoothSupport.canConnect(context)) {
            // Starting a foreground service from the background is refused on recent Android; the
            // next time the app is open the plan is applied again, so a refusal is not an error.
            runCatching { SyncListenerService.start(context) }
        } else {
            runCatching { SyncListenerService.stop(context) }
        }
    }
}
