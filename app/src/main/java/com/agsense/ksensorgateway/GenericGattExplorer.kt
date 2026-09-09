package com.agsense.ksensorgateway

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
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
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice(macAddress)
        listener.onLog("[explorer] מתחבר ל-$macAddress ...")
        gatt = device.connectGatt(context, false, callback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
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
