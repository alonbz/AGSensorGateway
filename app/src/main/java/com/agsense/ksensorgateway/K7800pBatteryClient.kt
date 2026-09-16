package com.agsense.ksensorgateway

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID

/**
 * One-shot BLE GATT read of the STANDARD Bluetooth SIG Battery Level
 * characteristic (0x2A19, under the standard Battery Service 0x180F).
 *
 * Confirmed present on the K7800P by a live [GenericGattExplorer] dump:
 *   service 0000180f-0000-1000-8000-00805f9b34fb
 *     char 00002a19-0000-1000-8000-00805f9b34fb  props=READ+NOTIFY
 *
 * Unlike temperature/humidity/door/light — all reverse-engineered from
 * the passive advertisement in [K7800pParser] — battery needs no
 * guessing: 0x2A19 is a standardized characteristic (single unsigned
 * byte, 0-100 = percent) defined by the Bluetooth SIG, not Jimi-specific.
 * It's just not present in the passive advertisement, so it can only be
 * read over an active GATT connection — hence this separate client.
 *
 * Deliberately a one-shot connect → read → disconnect, NOT a kept-open
 * connection: the app already runs a continuous passive BLE scan for
 * everything else, and holding one GATT connection open per K7800P on
 * top of that competes for Android's limited concurrent-connection
 * slots for no real benefit (battery changes slowly; polling every few
 * minutes is plenty).
 */
class K7800pBatteryClient(private val context: Context) {

    interface Listener {
        fun onBatteryPercent(mac: String, percent: Int)
        fun onFailed(mac: String, reason: String) {}
    }

    @SuppressLint("MissingPermission")
    fun readBatteryOnce(macAddress: String, listener: Listener) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            listener.onFailed(macAddress, "BluetoothAdapter הוא null")
            return
        }
        val device = adapter.getRemoteDevice(macAddress)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> g.close()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onFailed(macAddress, "גילוי services נכשל (status=$status)")
                    g.disconnect()
                    return
                }
                val ch = g.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID)
                if (ch == null) {
                    listener.onFailed(macAddress, "לא נמצא Battery Level characteristic (0x2A19)")
                    g.disconnect()
                    return
                }
                if (!g.readCharacteristic(ch)) {
                    listener.onFailed(macAddress, "readCharacteristic נכשל להתחיל")
                    g.disconnect()
                }
            }

            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                if (ch.uuid == BATTERY_LEVEL_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // Standard Battery Level format: a single unsigned
                        // byte, 0-100 = percent.
                        val percent = ch.value?.getOrNull(0)?.toInt()?.and(0xFF)
                        if (percent != null) {
                            listener.onBatteryPercent(macAddress, percent)
                        } else {
                            listener.onFailed(macAddress, "ערך סוללה ריק")
                        }
                    } else {
                        listener.onFailed(macAddress, "קריאת סוללה נכשלה (status=$status)")
                    }
                }
                g.disconnect()
            }
        }

        val gatt = device.connectGatt(context, false, callback)
        if (gatt == null) {
            listener.onFailed(macAddress, "connectGatt החזיר null")
        }
    }

    companion object {
        private val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    }
}
