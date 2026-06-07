package dev.ringbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import no.nordicsemi.android.ble.BleManager
import java.util.UUID

/**
 * Thin BleManager subclass that owns all Android GATT machinery for the R01L ring.
 *
 * Responsibilities:
 *  - Discover and cache the ring's characteristics
 *  - Subscribe C3 → C1 → JL_NOTIFY → HR in the order the ring expects
 *  - Automatically use INDICATE vs NOTIFY based on characteristic properties
 *  - Serialise all descriptor writes and outgoing command writes via the Nordic queue
 *  - Deliver raw bytes to [onC1C3] and [onHr] callbacks for the protocol layer
 *
 * The protocol logic (handshake, history, keepalive) lives in [RingService].
 * This class only handles the BLE transport layer.
 */
@SuppressLint("MissingPermission")
class RingBleManager(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "RingBleManager"
    }

    /** Called on a BLE callback thread with raw bytes from C1 or C3. */
    var onC1C3: ((ByteArray) -> Unit)? = null

    /** Called on a BLE callback thread with raw bytes from the HR characteristic. */
    var onHr: ((ByteArray) -> Unit)? = null

    private var c1Char:       BluetoothGattCharacteristic? = null
    private var c3Char:       BluetoothGattCharacteristic? = null
    private var jlWriteChar:  BluetoothGattCharacteristic? = null
    private var jlNotifyChar: BluetoothGattCharacteristic? = null
    private var hrChar:       BluetoothGattCharacteristic? = null

    // ── BleManager contract ───────────────────────────────────────────────────

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        fun find(uuid: String): BluetoothGattCharacteristic? =
            gatt.services?.flatMap { it.characteristics }
                         ?.find { it.uuid == UUID.fromString(uuid) }

        c1Char       = find(RingProtocol.YCBT_C1)
        c3Char       = find(RingProtocol.YCBT_C3)
        jlWriteChar  = find(RingProtocol.JL_WRITE)
        jlNotifyChar = find(RingProtocol.JL_NOTIFY)
        hrChar       = find(RingProtocol.HR_CHAR)

        Log.d(TAG, "Characteristics found — " +
            "C1=${c1Char != null} C3=${c3Char != null} " +
            "JL_WRITE=${jlWriteChar != null} JL_NOTIFY=${jlNotifyChar != null} " +
            "HR=${hrChar != null}")

        return c1Char != null && c3Char != null
    }

    override fun initialize() {
        Log.d(TAG, "initialize: enabling subscriptions C3 → C1 → JL → HR")

        setNotificationCallback(c3Char).with { _, data ->
            data.value?.let { onC1C3?.invoke(it) }
        }
        enableIndications(c3Char).enqueue()

        setNotificationCallback(c1Char).with { _, data ->
            data.value?.let { onC1C3?.invoke(it) }
        }
        enableIndications(c1Char).enqueue()

        jlNotifyChar?.let { jl ->
            setNotificationCallback(jl).with { _, data ->
                Log.v(TAG, "JL_NOTIFY: ${data.value?.toHex()}")
            }
            enableNotifications(jl).enqueue()
        }

        hrChar?.let { hr ->
            setNotificationCallback(hr).with { _, data ->
                data.value?.let { onHr?.invoke(it) }
            }
            enableNotifications(hr).enqueue()
        }

        // Request low-power connection parameters after subscriptions are set up.
        // Default (HIGH priority) = 7.5–15 ms interval → ring radio wakes ~100×/sec.
        // LOW_POWER = 100–125 ms interval → ring radio wakes ~8×/sec.
        // The ring streams data at ~1 Hz so 100 ms interval has zero practical downside.
        requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER).enqueue()
        Log.d(TAG, "Connection priority set to LOW_POWER")
    }

    override fun onServicesInvalidated() {
        c1Char       = null
        c3Char       = null
        jlWriteChar  = null
        jlNotifyChar = null
        hrChar       = null
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enqueue a WRITE_NO_RESPONSE to C1.
     * Safe to call from any thread; Nordic queues and dispatches on its own thread.
     */
    fun send(packet: ByteArray) {
        val char = c1Char ?: run {
            Log.w(TAG, "send() — C1 not ready, dropping ${packet.toHex()}")
            return
        }
        writeCharacteristic(char, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .enqueue()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
