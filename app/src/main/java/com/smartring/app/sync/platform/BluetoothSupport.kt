package com.smartring.app.sync.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.smartring.app.sync.crypto.ChannelClosedException
import com.smartring.app.sync.crypto.Crypto
import com.smartring.app.sync.crypto.FrameChannel
import com.smartring.app.sync.protocol.Proto
import com.smartring.app.sync.protocol.ProtocolException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything about Bluetooth that depends on the Android version, in one place.
 *
 * ## Which permissions, and why only these
 *
 * From API 31 Bluetooth was split into three runtime permissions: CONNECT (talk to a device),
 * SCAN (find devices) and ADVERTISE (be found). Before that, the old BLUETOOTH/BLUETOOTH_ADMIN
 * pair was install-time only, but *finding* devices needed location — an awkward thing to ask for
 * in an alarm clock, so it is requested only for the search screen and only on the releases that
 * demand it. SCAN is declared `neverForLocation`: this app finds a phone to pair with, it does not
 * learn where anyone is.
 *
 * Nothing here ever needs to be bonded. The connection uses the *insecure* RFCOMM variant — which,
 * despite the name, is not unauthenticated: it merely skips Android's own pairing dialog and PIN,
 * because everything is authenticated and encrypted a layer up, by the pairing code and the keys
 * derived from it. Using the platform's bonding as well would add a second, unrelated PIN prompt
 * to every pairing and protect nothing the protocol does not already protect better.
 */
object BluetoothSupport {

    /** Identifies this app's service to the other phone. Random, fixed, and not a secret. */
    val SERVICE_UUID: UUID = UUID.fromString("0a4c2f7e-6b1d-4f5a-9c3e-8d2b7a1e5f60")

    const val SERVICE_NAME = "SmartRingSync"

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Needed to open or accept a connection. */
    fun connectPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT)
        else emptyList()

    /** Needed to search for a phone to pair with. */
    fun scanPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Needed to be found by a searching phone — the host side of pairing. */
    fun advertisePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }

    fun hasAll(context: Context, permissions: List<String>): Boolean =
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun canConnect(context: Context) = hasAll(context, connectPermissions())

    @SuppressLint("MissingPermission")   // never throws for want of a permission here; wrapped in runCatching regardless
    fun isEnabled(context: Context): Boolean = runCatching { adapter(context)?.isEnabled == true }.getOrDefault(false)

    /** The phone's Bluetooth name — what the person on the other phone will see in a list. */
    @SuppressLint("MissingPermission")   // guarded: callers check canConnect(); a missing grant throws SecurityException, caught here
    fun bluetoothName(context: Context): String? =
        if (!canConnect(context)) null else runCatching { adapter(context)?.name }.getOrNull()

    /**
     * An unconnected socket to [address]. The caller connects it — `connect()` blocks, and the
     * caller needs the socket in hand to be able to close it when giving up (see
     * [blockingCancellable]).
     *
     * Discovery is stopped first: a search running alongside a connection attempt is the classic
     * cause of an attempt that fails or takes ten seconds for no visible reason.
     */
    @SuppressLint("MissingPermission")   // guarded: callers check canConnect() first
    fun createSocket(context: Context, address: String): BluetoothSocket {
        val adapter = adapter(context) ?: throw IOException("no Bluetooth adapter")
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw IOException("not a Bluetooth address")
        runCatching { adapter.cancelDiscovery() }
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        return device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
    }
}

/**
 * Whole messages over a Bluetooth stream: a four-byte length, then that many bytes.
 *
 * The length is read from the *other phone* and is bounded before anything is allocated — a
 * hostile peer announcing a two-gigabyte frame gets a refusal, not an `OutOfMemoryError`.
 *
 * Writes are serialised; reads and writes run off the main thread and are made cancellable by
 * closing the socket (see [blockingCancellable]).
 */
class BluetoothFrameChannel(private val socket: BluetoothSocket) : FrameChannel {

    private val input = DataInputStream(BufferedInputStream(socket.inputStream))
    private val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
    private val writeLock = Mutex()
    private val closed = AtomicBoolean(false)

    /** The Bluetooth address of the phone on the other end. */
    @SuppressLint("MissingPermission")   // the socket exists, so the connect permission was held
    val remoteAddress: String? = runCatching { socket.remoteDevice?.address }.getOrNull()

    override suspend fun send(frame: ByteArray) {
        require(frame.size <= MAX_RAW_FRAME) { "frame too large" }
        writeLock.withLock {
            blockingCancellable(onCancel = { close() }) {
                try {
                    output.writeInt(frame.size)
                    output.write(frame)
                    output.flush()
                } catch (e: IOException) {
                    throw ChannelClosedException(e.message ?: "write failed")
                }
            }
        }
    }

    override suspend fun receive(): ByteArray = blockingCancellable(onCancel = { close() }) {
        try {
            val length = input.readInt()
            // Refused before allocating. A negative value is just as hostile as a huge one.
            if (length < 0 || length > MAX_RAW_FRAME) throw ProtocolException("frame length $length is not acceptable")
            ByteArray(length).also { input.readFully(it) }
        } catch (e: IOException) {
            throw ChannelClosedException(e.message ?: "read failed")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.flush() }
        runCatching { socket.close() }
    }

    companion object {
        /** Largest protocol frame plus the AEAD tag. Anything bigger is not from this protocol. */
        const val MAX_RAW_FRAME = Proto.MAX_FRAME_BYTES + Crypto.TAG_BYTES
    }
}
