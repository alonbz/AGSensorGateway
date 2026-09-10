package com.agsense.ksensorgateway

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * Generic BLE GATT explorer — connects to ANY device, discovers every
 * service/characteristic, subscribes to notify/indicate on all of them,
 * and logs every value change. No vendor-specific auth handshake (unlike
 * [GattClient], which speaks KKM's proprietary protocol and won't work
 * against other hardware).
 *
 * Built to reverse-engineer the K7800P's door-open/light-detected EVENTS
 * (per Jimi IoT's TCP protocol doc, these are one-shot alarm packets —
 * 0x33/0x34 — not a continuously-maintained flag in the passive BLE
 * advertisement, which is why they never showed up there). The idea: sit
 * connected and subscribed to everything, then physically trigger the
 * door/light change, and see which characteristic fires a notification.
 */
class GenericGattExplorer(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onLog(msg: String)
    }

    private var gatt: BluetoothGatt? = null

    @SuppressLint("MissingPermission")
    fun connect(macAddress: String) {
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null) {
                listener.onLog("[explorer] !!! BluetoothAdapter הוא null")
                return
            }
            val device = adapter.getRemoteDevice(macAddress)
            listener.onLog("[explorer] מתחבר ל-$macAddress ...")
            gatt = device.connectGatt(context, false, callback)
            if (gatt == null) {
                listener.onLog("[explorer] !!! connectGatt החזיר null")
            }
        } catch (e: Exception) {
            listener.onLog("[explorer] !!! EXCEPTION בתוך connect(): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    /**
     * Writes raw bytes to a characteristic found by matching [uuidFragment]
     * against the end of each discovered characteristic's UUID (so typing
     * just "36f5" is enough — no need for the full 128-bit UUID). Tries
     * WRITE_TYPE_DEFAULT (with response) first per characteristic
     * capability; falls back to no-response if that's all it supports.
     */
    @SuppressLint("MissingPermission")
    fun writeCharacteristic(uuidFragment: String, hexBytes: String) {
        val g = gatt
        if (g == null) {
            listener.onLog("[explorer] !!! אין חיבור GATT פעיל - אי אפשר לכתוב")
            return
        }
        val bytes = try {
            hexBytes.replace(Regex("[^0-9A-Fa-f]"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            listener.onLog("[explorer] !!! hex לא תקין: ${e.message}")
            return
        }
        if (bytes.isEmpty()) {
            listener.onLog("[explorer] !!! אין בייטים לכתוב")
            return
        }

        var target: BluetoothGattCharacteristic? = null
        for (service in g.services) {
            for (ch in service.characteristics) {
                if (ch.uuid.toString().endsWith(uuidFragment.lowercase())) {
                    target = ch
                    break
                }
            }
            if (target != null) break
        }
        if (target == null) {
            listener.onLog("[explorer] !!! לא נמצא characteristic שמסתיים ב-'$uuidFragment'")
            return
        }

        val writeType = if ((target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        listener.onLog("[explorer] כותב ${KSensorParser.toHex(bytes)} ל-${target.uuid} (writeType=$writeType)...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = g.writeCharacteristic(target, bytes, writeType)
                listener.onLog("[explorer] writeCharacteristic (API33+) result=$result")
            } else {
                @Suppress("DEPRECATION")
                target.writeType = writeType
                @Suppress("DEPRECATION")
                target.value = bytes
                @Suppress("DEPRECATION")
                val ok = g.writeCharacteristic(target)
                listener.onLog("[explorer] writeCharacteristic (legacy) ok=$ok")
            }
        } catch (e: Exception) {
            listener.onLog("[explorer] !!! EXCEPTION בכתיבה: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onLog("[explorer] מחובר. מגלה services...")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    listener.onLog("[explorer] מנותק (status=$status)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("[explorer] גילוי services נכשל, status=$status")
                return
            }
            listener.onLog("[explorer] === נמצאו ${g.services.size} services ===")
            var subscribedCount = 0
            for (service in g.services) {
                listener.onLog("[explorer] service ${service.uuid}")
                for (ch in service.characteristics) {
                    val props = describeProperties(ch.properties)
                    listener.onLog("[explorer]   char ${ch.uuid}  props=$props")

                    val canNotify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                        (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    if (canNotify) {
                        val ok = g.setCharacteristicNotification(ch, true)
                        val cccd = ch.getDescriptor(CCCD_UUID)
                        if (cccd != null) {
                            val value = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            else
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            cccd.value = value
                            g.writeDescriptor(cccd)
                            subscribedCount++
                            listener.onLog("[explorer]     -> נרשם ל-notify/indicate (setNotification=$ok)")
                        } else {
                            listener.onLog("[explorer]     -> יש NOTIFY אבל אין CCCD descriptor (ok=$ok)")
                        }
                    }

                    // Also opportunistically read anything readable, to see
                    // the current resting value (door/light might already
                    // reflect a state even without a live notify).
                    if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        g.readCharacteristic(ch)
                    }
                }
            }
            listener.onLog("[explorer] === סה\"כ נרשמנו ל-$subscribedCount characteristics. עכשיו תפעיל דלת/אור. ===")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            listener.onLog("[explorer] *** NOTIFY *** ${ch.uuid} = ${KSensorParser.toHex(ch.value ?: byteArrayOf())}")
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("[explorer] read ${ch.uuid} = ${KSensorParser.toHex(ch.value ?: byteArrayOf())}")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val statusText = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAILED (status=$status)"
            listener.onLog("[explorer] write-result ${ch.uuid}: $statusText")
        }
    }

    private fun describeProperties(props: Int): String {
        val parts = mutableListOf<String>()
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts.add("READ")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts.add("WRITE")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts.add("WRITE_NR")
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts.add("NOTIFY")
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts.add("INDICATE")
        return if (parts.isEmpty()) "(none)" else parts.joinToString("+")
    }

    companion object {
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
