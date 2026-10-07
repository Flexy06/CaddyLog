package de.flexy.caddylog.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import de.flexy.caddylog.core.ElmLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * ELM327 over Bluetooth Low Energy (Vgate iCar Pro BLE 4.0 and similar).
 * The adapter exposes a UART-like service: one characteristic with NOTIFY (adapter → phone) and one with WRITE.
 * Known layouts are tried first, otherwise any custom service offering notify + write is used.
 */
@SuppressLint("MissingPermission")
class BleElmLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onDisconnected: () -> Unit,
) : ElmLink {

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var mtu = 23

    private val rx = StringBuilder()
    private val lock = Any()
    private var promptWaiter: CompletableDeferred<String>? = null
    private var writeAck: CompletableDeferred<Boolean>? = null
    private var ready = CompletableDeferred<Unit>()
    private val mutex = Mutex()

    @Volatile var isConnected = false; private set
    var description = ""; private set

    companion object {
        private const val TAG = "BleElmLink"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private fun short(x: String) = UUID.fromString("0000$x-0000-1000-8000-00805f9b34fb")

        /** (service, notify char, write char) – first match wins */
        val KNOWN = listOf(
            // Vgate iCar Pro BLE / vLinker / "IOS-Vlink"
            Triple(UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
                UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
                UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")),
            Triple(short("fff0"), short("fff1"), short("fff2")),
            Triple(short("ffe0"), short("ffe1"), short("ffe1")),
            Triple(short("18f0"), short("2af0"), short("2af1")),
        )
        private val IGNORED_SERVICES = setOf(short("1800"), short("1801"), short("180a"), short("180f"))
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = isConnected
                isConnected = false
                ready.completeExceptionally(IOException("Verbindung fehlgeschlagen (Status $status)"))
                promptWaiter?.completeExceptionally(IOException("Adapter getrennt"))
                writeAck?.complete(false)
                g.close()
                if (wasConnected) onDisconnected()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!pickCharacteristics(g)) {
                ready.completeExceptionally(IOException("Kein ELM327-UART-Dienst gefunden. Dienste: " +
                        g.services.joinToString { it.uuid.toString() }))
                return
            }
            val n = notifyChar!!
            g.setCharacteristicNotification(n, true)
            val d = n.getDescriptor(CCCD)
            if (d == null) { isConnected = true; ready.complete(Unit); return }
            val value = if (n.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, value)
            } else {
                @Suppress("DEPRECATION")
                d.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            isConnected = true
            ready.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onData(c.value ?: return)
        }
    }

    private fun onData(bytes: ByteArray) {
        val s = String(bytes, Charsets.ISO_8859_1)
        synchronized(lock) {
            rx.append(s)
            if (rx.contains('>')) {
                val text = rx.substring(0, rx.lastIndexOf(">"))
                rx.clear()
                promptWaiter?.complete(text)
            }
        }
    }

    private fun pickCharacteristics(g: BluetoothGatt): Boolean {
        for ((s, n, w) in KNOWN) {
            val svc = g.getService(s) ?: continue
            val nc = svc.getCharacteristic(n); val wc = svc.getCharacteristic(w)
            if (nc != null && wc != null) {
                notifyChar = nc; writeChar = wc
                description = "Dienst $s"
                return true
            }
        }
        for (svc in g.services) {
            if (svc.uuid in IGNORED_SERVICES) continue
            val nc = svc.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            }
            val wc = svc.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            if (nc != null && wc != null) {
                notifyChar = nc; writeChar = wc
                description = "Dienst ${svc.uuid} (automatisch erkannt)"
                return true
            }
        }
        return false
    }

    suspend fun connect(timeoutMs: Long = 20000) {
        ready = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(timeoutMs) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            close(); throw IOException("Zeitüberschreitung beim Verbinden")
        } catch (e: Exception) {
            close(); throw e
        }
    }

    private suspend fun writeRaw(bytes: ByteArray) {
        val g = gatt ?: throw IOException("nicht verbunden")
        val c = writeChar ?: throw IOException("nicht verbunden")
        val withResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val chunk = (mtu - 3).coerceAtLeast(20)
        var off = 0
        var busyRetries = 0
        while (off < bytes.size) {
            val part = bytes.copyOfRange(off, minOf(bytes.size, off + chunk))
            val ack = CompletableDeferred<Boolean>()
            writeAck = ack
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, part, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = part
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (!started) {
                // GATT busy – retry a few times
                if (++busyRetries > 20) throw IOException("BLE-Schreiben fehlgeschlagen")
                delay(30)
                continue
            }
            withTimeout(3000) { ack.await() }
            off += part.size
        }
    }

    override suspend fun exchange(command: String, timeoutMs: Long): String = mutex.withLock {
        if (!isConnected) throw IOException("Adapter nicht verbunden")
        val waiter = CompletableDeferred<String>()
        synchronized(lock) { rx.clear(); promptWaiter = waiter }
        writeRaw((command + "\r").toByteArray(Charsets.ISO_8859_1))
        try {
            withTimeout(timeoutMs) { waiter.await() }
        } catch (e: TimeoutCancellationException) {
            // ELM still busy? Any character aborts the running command; give it time to print '>'
            runCatching { writeRaw("\r".toByteArray()) }
            delay(300)
            synchronized(lock) { rx.clear(); promptWaiter = null }
            throw IOException("Zeitüberschreitung bei '$command'")
        } finally {
            synchronized(lock) { if (promptWaiter === waiter) promptWaiter = null }
        }
    }

    fun close() {
        isConnected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }
}
