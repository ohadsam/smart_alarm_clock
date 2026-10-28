package com.smartring.app.sync.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A phone seen nearby, or already known to this one. [name] is what its owner called its Bluetooth. */
data class NearbyDevice(val name: String?, val address: String)

/**
 * Finds the phone to pair with.
 *
 * Two sources, merged: phones already known to this one (instant, no radio work) and a live
 * discovery scan (a few seconds). The scan is the only thing in this feature that needs the
 * location permission on older Android, which is why it is requested only when the person opens
 * the search screen and never at startup.
 *
 * The host phone is shown by *Bluetooth name* — "Pixel 7", "Galaxy S23" — because that is the only
 * thing both people can see. Which of several nearby phones is the right one is the person's call;
 * what stops them pairing with the wrong one is that the code will not verify, and that both
 * screens then show a verification number to compare.
 */
@Singleton
class BluetoothScanner @Inject constructor(@ApplicationContext private val context: Context) {

    /** Phones this one already knows about. Empty if Bluetooth is off or the permission is missing. */
    @SuppressLint("MissingPermission")   // guarded by canConnect(); SecurityException is caught regardless
    fun knownDevices(): List<NearbyDevice> {
        if (!BluetoothSupport.canConnect(context)) return emptyList()
        return runCatching {
            BluetoothSupport.adapter(context)?.bondedDevices.orEmpty().map { NearbyDevice(it.name, it.address) }
        }.getOrDefault(emptyList())
    }

    /**
     * A live scan: emits each phone as it is found, and ends when discovery finishes or the
     * collector goes away. Cancelling the collection cancels the scan — a scan left running drains
     * the battery and, on some phones, makes every other Bluetooth connection stutter.
     */
    @SuppressLint("MissingPermission")   // callers check scanPermissions() first; failures are caught and end the flow
    fun scan(): Flow<NearbyDevice> = callbackFlow {
        val adapter = BluetoothSupport.adapter(context)
        if (adapter == null || !BluetoothSupport.hasAll(context, BluetoothSupport.scanPermissions())) {
            close()
            return@callbackFlow
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (device != null) {
                            val name = runCatching { device.name }.getOrNull()
                                ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                            trySend(NearbyDevice(name, device.address))
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // NOT_EXPORTED: only the system's own broadcasts are meant to reach this.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        if (!started) close()
        awaitClose {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
