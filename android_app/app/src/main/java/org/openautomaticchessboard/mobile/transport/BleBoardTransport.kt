package org.openautomaticchessboard.mobile.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.openautomaticchessboard.mobile.protocol.LineBuffer
import org.openautomaticchessboard.mobile.protocol.Protocol
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * HC-08 BLE GATT transport. All Android callbacks are reduced to line/status events;
 * reconnect, notification setup, 20-byte framing, and write serialization stay here.
 */
@SuppressLint("MissingPermission")
class BleBoardTransport(
    private val context: Context,
    private val address: String,
    private val deviceName: String,
    private val listener: BoardTransport.Listener,
) : BoardTransport {
    override val label: String get() = deviceName.ifBlank { address }
    override val isConnected: Boolean get() = connected

    private val main = Handler(Looper.getMainLooper())
    private val lineBuffer = LineBuffer()
    private val writes = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var characteristic: BluetoothGattCharacteristic? = null
    @Volatile private var connected = false
    @Volatile private var closing = false
    private var reconnectDelayMs = 1_000L
    @Volatile private var writeActive = false
    private val writeLock = Any()
    private var nextWriteToken = 0L
    private var activeWriteToken: Long? = null
    private val callbackTokens = ConcurrentLinkedQueue<Long>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("BLE connected; discovering board service…", false)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                characteristic = null
                resetWriteState()
                writes.clear()
                g.close()
                if (!closing) scheduleReconnect("BLE interrupted (status $status)")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("Board service discovery failed ($status)", false)
                g.disconnect()
                return
            }
            val service: BluetoothGattService? = g.getService(UUID.fromString(Protocol.SERVICE_UUID))
            val serial = service?.getCharacteristic(UUID.fromString(Protocol.CHARACTERISTIC_UUID))
            if (serial == null) {
                listener.onStatus("HC-08 FFE1 characteristic was not found", false)
                g.disconnect()
                return
            }
            characteristic = serial
            g.setCharacteristicNotification(serial, true)
            val cccd = serial.getDescriptor(CLIENT_CONFIG_UUID)
            if (cccd != null) {
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
                if (!accepted) {
                    listener.onStatus("Android rejected notification setup", false)
                    g.disconnect()
                }
            } else {
                markReady()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CLIENT_CONFIG_UUID && status == BluetoothGatt.GATT_SUCCESS) markReady()
            else if (descriptor.uuid == CLIENT_CONFIG_UUID) {
                listener.onStatus("Could not enable HC-08 notifications ($status)", false)
                g.disconnect()
            }
        }

        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            consume(c.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            consume(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val token = callbackTokens.poll() ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                rejectQueuedWrite(token, "BLE write failed ($status)")
            } else if (finishWrite(token)) drainWrites()
        }
    }

    override fun start() {
        closing = false
        connect()
    }

    private fun connect() {
        if (closing) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            listener.onStatus("Bluetooth Low Energy is unavailable on this device", false)
            return
        }
        listener.onStatus("Connecting to $label…", false)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            scheduleReconnect("Bluetooth is off")
            return
        }
        try {
            val device = adapter.getRemoteDevice(address)
            gatt = device.connectGatt(
                context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE,
            )
        } catch (error: Exception) {
            scheduleReconnect("BLE connection failed: ${error.message}")
        }
    }

    private fun markReady() {
        connected = true
        reconnectDelayMs = 1_000L
        listener.onStatus("BLE connected: $label", true)
    }

    private fun scheduleReconnect(reason: String) {
        connected = false
        listener.onStatus("$reason; retrying in ${reconnectDelayMs / 1000}s", false)
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15_000L)
        main.postDelayed({ if (!closing) connect() }, delay)
    }

    override fun send(line: String) {
        check(connected) { "BLE is not ready" }
        val payload = if (line.trim() == "!") "!".toByteArray(Charsets.US_ASCII)
        else (line.trim() + "\n").toByteArray(Charsets.US_ASCII)
        payload.asList().chunked(20).forEach { part -> writes.add(part.toByteArray()) }
        drainWrites()
    }

    private fun drainWrites() {
        val pendingWrite = beginWrite() ?: return
        val c = characteristic
        if (c == null) {
            rejectQueuedWrite(pendingWrite.token, "BLE characteristic is unavailable")
            return
        }
        val g = gatt
        if (g == null) {
            rejectQueuedWrite(pendingWrite.token, "BLE connection is unavailable")
            return
        }
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        trackCallbackToken(pendingWrite.token)
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, pendingWrite.value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = pendingWrite.value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!accepted) {
            callbackTokens.remove(pendingWrite.token)
            rejectQueuedWrite(pendingWrite.token, "Android rejected BLE write")
            return
        }
        // WRITE_NO_RESPONSE callbacks vary by vendor. This fallback cannot overlap
        // at 9600 baud and keeps multi-packet developer commands portable.
        main.postDelayed({
            if (finishWrite(pendingWrite.token)) {
                drainWrites()
            }
        }, 35)
    }

    private fun beginWrite(): PendingWrite? = synchronized(writeLock) {
        if (writeActive) return@synchronized null
        val value = writes.poll() ?: return@synchronized null
        val token = ++nextWriteToken
        writeActive = true
        activeWriteToken = token
        PendingWrite(token, value)
    }

    private fun finishWrite(token: Long): Boolean = synchronized(writeLock) {
        if (!writeActive || activeWriteToken != token) return@synchronized false
        writeActive = false
        activeWriteToken = null
        true
    }

    private fun resetWriteState() = synchronized(writeLock) {
        writeActive = false
        activeWriteToken = null
        callbackTokens.clear()
    }

    private fun trackCallbackToken(token: Long) {
        callbackTokens.add(token)
        while (callbackTokens.size > MAX_CALLBACK_TOKENS) callbackTokens.poll()
    }

    private fun rejectQueuedWrite(token: Long, reason: String) {
        if (!finishWrite(token)) return
        writes.clear()
        listener.onStatus(reason, connected)
    }

    private fun consume(value: ByteArray) = lineBuffer.feed(value).forEach(listener::onLine)

    override fun close() {
        closing = true
        connected = false
        main.removeCallbacksAndMessages(null)
        characteristic = null
        writes.clear()
        resetWriteState()
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
        listener.onStatus("Disconnected", false)
    }

    companion object {
        private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_CALLBACK_TOKENS = 256

        @SuppressLint("MissingPermission")
        fun scan(context: Context, durationMs: Long = 7_000L, result: (Result<List<BleDevice>>) -> Unit): () -> Unit {
            val handler = Handler(Looper.getMainLooper())
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                result(Result.failure(IllegalStateException("Bluetooth Low Energy is unavailable on this device")))
                return {}
            }
            val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
            val scanner = adapter?.bluetoothLeScanner
            if (adapter == null || !adapter.isEnabled || scanner == null) {
                result(Result.failure(IllegalStateException("Turn Bluetooth on first")))
                return {}
            }
            val found = linkedMapOf<String, BleDevice>()
            var finished = false
            lateinit var callback: ScanCallback
            fun finish(error: Throwable? = null) {
                if (finished) return
                finished = true
                try { scanner.stopScan(callback) } catch (_: Exception) {}
                handler.removeCallbacksAndMessages(callback)
                if (error != null) result(Result.failure(error))
                else result(Result.success(found.values.sortedByDescending { it.rssi }))
            }
            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, scanResult: ScanResult) {
                    val device = scanResult.device
                    val name = scanResult.scanRecord?.deviceName ?: device.name ?: "Unnamed BLE device"
                    found[device.address] = BleDevice(name, device.address, scanResult.rssi)
                }
                override fun onScanFailed(errorCode: Int) = finish(IllegalStateException("BLE scan failed ($errorCode)"))
            }
            scanner.startScan(callback)
            handler.postAtTime({ finish() }, callback, android.os.SystemClock.uptimeMillis() + durationMs)
            return { finish() }
        }
    }

    private data class PendingWrite(val token: Long, val value: ByteArray)
}
