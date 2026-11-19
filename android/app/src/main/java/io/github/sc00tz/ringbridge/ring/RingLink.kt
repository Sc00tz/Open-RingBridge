package io.github.sc00tz.ringbridge.ring

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.util.UUID

class RingError(message: String) : Exception(message)

/**
 * One Bluetooth connection to the ring. Same commands and order as bench/ringlink.py.
 *
 * Safety: this only reads history, sets the clock, runs measurements and workouts. It never sends the
 * history acknowledgement (0x0580), delete, reset or firmware commands.
 */
@SuppressLint("MissingPermission")  // callers check BLUETOOTH_CONNECT first
class RingLink(private val context: Context, private val address: String) {
    companion object {
        val C1: UUID = UUID.fromString("be940001-7333-be46-b7ae-689e71722bd5")
        val C3: UUID = UUID.fromString("be940003-7333-be46-b7ae-689e71722bd5")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Only one connection at a time: the ring itself seems to allow just one. */
        val busy = Mutex()

        /** The CCCD value for a characteristic: notifications if it offers them, otherwise indications. */
        fun cccdValue(properties: Int): ByteArray = when {
            properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
    }

    private var gatt: BluetoothGatt? = null
    private var write: BluetoothGattCharacteristic? = null
    private val assembler = Codec.Assembler()
    private val frames = Channel<ByteArray>(Channel.UNLIMITED)
    private var pending: CompletableDeferred<Int>? = null
    private val op = Mutex()
    @Volatile var connected = false
        private set
    private var ready = CompletableDeferred<Boolean>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            connected = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS
            if (connected) g.discoverServices() else {
                ready.complete(false)
                pending?.complete(-1)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { ready.complete(status == BluetoothGatt.GATT_SUCCESS) }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { pending?.complete(status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { pending?.complete(status) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { pending?.complete(status) }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = feed(value)

        @Deprecated("Android 12")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) feed(c.value ?: return)
        }
    }

    private fun feed(data: ByteArray) {
        for (raw in assembler.feed(data)) frames.trySend(raw)
    }

    /** Run one GATT operation and wait for its callback; Android allows only one at a time. */
    private suspend fun gattOp(start: () -> Boolean): Int = op.withLock {
        val done = CompletableDeferred<Int>()
        pending = done
        if (!start()) throw RingError("Bluetooth refused the request.")
        try { withTimeout(8_000) { done.await() } } catch (e: TimeoutCancellationException) { throw RingError("The ring stopped answering.") }
    }

    suspend fun connect() {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: throw RingError("This phone has no Bluetooth.")
        if (!adapter.isEnabled) throw RingError("Bluetooth is off.")
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        ready = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(25_000) { ready.await() } ?: false
        if (!ok) { close(); throw RingError("Couldn't connect to the ring. It needs to be nearby, charged, and not connected to another app.") }
        val g = gatt!!
        runCatching { gattOp { g.requestMtu(247) } }
        val chars = g.services.flatMap { it.characteristics }
        write = chars.firstOrNull { it.uuid == C1 } ?: throw RingError("This doesn't look like a supported ring.")
        for (uuid in listOf(C3, C1)) chars.firstOrNull { it.uuid == uuid }?.let { notify(g, it) }
    }

    /**
     * Subscribe to a characteristic. The ring's C1 and C3 only support indications, and the ring drops
     * the connection if the notification flag is written instead (seen on a Pixel 9 Pro), so use
     * whichever one the characteristic offers, like Bleak does on the Mac.
     */
    private suspend fun notify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return
        val value = cccdValue(c.properties)
        gattOp {
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
            else @Suppress("DEPRECATION") run { d.value = value; g.writeDescriptor(d) }
        }
    }

    fun close() {
        connected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    suspend fun send(code: Int, payload: ByteArray = ByteArray(0)) {
        val g = gatt ?: throw RingError("Not connected.")
        val c = write ?: throw RingError("Not connected.")
        val data = Codec.frame(code, payload)
        val status = gattOp {
            if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            else @Suppress("DEPRECATION") run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = data; g.writeCharacteristic(c) }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw RingError("The ring didn't accept a command (status $status).")
    }

    /** The next complete frame, or null after `timeoutMs`. */
    suspend fun recv(timeoutMs: Long): Codec.Frame? = withTimeoutOrNull(timeoutMs) { Codec.parse(frames.receive()) }

    suspend fun ask(code: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = 4_000): ByteArray? {
        send(code, payload)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val f = recv(deadline - System.currentTimeMillis()) ?: return null
            if (f.code == code) return f.payload
        }
        return null
    }

    /** Sets the ring's clock, then reads device info. */
    suspend fun status(): Codec.DeviceInfo? {
        ask(0x0100, Codec.timePayload(LocalDateTime.now()))
        return ask(0x0200, "GC".toByteArray())?.let(Codec::parseDeviceInfo)
    }

    suspend fun setLive(enable: Boolean) {
        for (t in 1..5) { send(0x0309, byteArrayOf(if (enable) 1 else 0, t.toByte(), 2)); delay(100) }
    }

    data class Stream(val data: ByteArray, val crc: Int?, val verified: Boolean)

    /** Read one history stream. Never acknowledges. `crc` is the checksum from the ring's end packet. */
    suspend fun readHistory(name: String): Stream {
        val (code, chunkKey) = Codec.HISTORY.getValue(name)
        val chunkCode = (code and 0xFF00) or chunkKey
        send(code)
        val chunks = java.io.ByteArrayOutputStream()
        while (true) {
            val f = recv(10_000) ?: throw RingError("The ring stopped responding while sending $name history.")
            when {
                f.code == code && f.payload.size <= 9 -> return Stream(ByteArray(0), null, true)
                f.code == chunkCode -> chunks.write(f.payload)
                f.code == 0x0580 && f.payload.size >= 6 -> {
                    val data = chunks.toByteArray()
                    val want = (f.payload[4].toInt() and 0xFF) or ((f.payload[5].toInt() and 0xFF) shl 8)
                    return Stream(data, want, Codec.crc16(data) == want)
                }
            }
        }
    }
}
