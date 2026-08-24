package com.agsense.ksensorgateway

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Connects directly to a KBeacon (K6 / KBPro) by its known MAC address —
 * no chooser dialog needed, since native Android BLE APIs expose the real
 * MAC (unlike Web Bluetooth in a browser). Performs the vendor's two-way
 * MD5 authentication handshake, then configures a "report temperature and
 * humidity periodically" trigger (trType 0xC) and streams live readings.
 */
class GattClient(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onLog(msg: String)
        fun onStatus(msg: String)
        fun onReading(temperatureC: Float, humidityPct: Float)
    }

    companion object {
        private val SERVICE_UUID = UUID.fromString("0000fea0-0000-1000-8000-00805f9b34fb")
        private val CHAR_WRITE = UUID.fromString("0000fea1-0000-1000-8000-00805f9b34fb")
        private val CHAR_ACK = UUID.fromString("0000fea2-0000-1000-8000-00805f9b34fb")
        private val CHAR_INDICATE = UUID.fromString("0000fea3-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SOURCE_KEY = byteArrayOf(0xA9.toByte(), 0xB1.toByte())
        private const val CHUNK_SIZE = 17
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private var bleAddr = ByteArray(0)
    private var password = ByteArray(0)
    private var shortMode = false

    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false
    private var pendingResponseHandler: ((ByteArray) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun connect(mac: String, pwd: String) {
        // Always tear down any previous connection first — pressing connect
        // again while a prior attempt is still in flight used to leave two
        // overlapping GATT sessions running at once, which cross-contaminated
        // responses and produced bogus auth failures.
        disconnect()
        pendingResponseHandler = null
        opQueue.clear()
        opInFlight = false
        shortMode = false

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter.getRemoteDevice(mac)
        val macBytes = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
        bleAddr = macBytes.reversedArray()
        password = pwd.padEnd(16, '0').take(16).toByteArray(Charsets.US_ASCII)

        listener.onStatus("מתחבר...")
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { String.format("%02X", it) }

    // ---------------- GATT operation queue (for descriptor writes only) ----------------

    private fun enqueue(op: () -> Unit) { opQueue.addLast(op) }
    private fun runNext() {
        if (opInFlight) return
        val op = opQueue.removeFirstOrNull() ?: return
        opInFlight = true
        op()
    }

    @SuppressLint("MissingPermission")
    private fun enqueueEnableNotify(characteristic: BluetoothGattCharacteristic, indicate: Boolean) {
        enqueue {
            gatt?.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD_UUID)
            @Suppress("DEPRECATION")
            cccd.value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                         else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt?.writeDescriptor(cccd)
        }
    }

    // ---------------- Sending raw protocol frames ----------------

    @SuppressLint("MissingPermission")
    private fun sendRaw(bytes: ByteArray) {
        val c = writeChar ?: return
        listener.onLog("-> " + hex(bytes))
        @Suppress("DEPRECATION")
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        c.value = bytes
        @Suppress("DEPRECATION")
        gatt?.writeCharacteristic(c)
    }

    // ---------------- GATT callbacks ----------------

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onStatus("מגלה שירותים...")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> listener.onStatus("נותק")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                listener.onStatus("לא נמצא שירות 0xFEA0 על המכשיר")
                return
            }
            writeChar = service.getCharacteristic(CHAR_WRITE)
            val ackChar = service.getCharacteristic(CHAR_ACK)
            val indicateChar = service.getCharacteristic(CHAR_INDICATE)

            enqueueEnableNotify(ackChar, indicate = false)
            enqueueEnableNotify(indicateChar, indicate = true)
            enqueue { startAuth() }
            runNext()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            opInFlight = false
            runNext()
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            listener.onLog("<- " + hex(value))
            when (characteristic.uuid) {
                CHAR_ACK -> {
                    // A bare trigger-data frame (first byte exactly 0x02, not
                    // nibble-packed like our protocol Acks are) can arrive
                    // here too on some firmware — handle it either way.
                    if (pendingResponseHandler == null && value.isNotEmpty() && (value[0].toInt() and 0xFF) == 0x02) {
                        handleIndicateFrame(value)
                    } else {
                        val handler = pendingResponseHandler
                        pendingResponseHandler = null
                        handler?.invoke(value)
                    }
                }
                CHAR_INDICATE -> handleIndicateFrame(value)
            }
        }
    }

    // ---------------- Authentication (section 6.2 of the KBPro spec) ----------------

    private fun startAuth() {
        opInFlight = false // release the setup queue; auth uses its own response handler
        val appRandom = ByteArray(4).also { SecureRandom().nextBytes(it) }
        pendingResponseHandler = { resp -> onAuthStep1(resp) }
        sendRaw(byteArrayOf(0x13, 0x01) + appRandom)
    }

    private fun onAuthStep1(resp: ByteArray) {
        val dataType = (resp[0].toInt() shr 4) and 0xF
        if (dataType != 0x1) { listener.onLog("תגובת אימות לא צפויה"); return }
        val authAlg = resp[1].toInt() and 0xFF
        shortMode = authAlg == 0x11
        val deviceRandom = resp.copyOfRange(2, 6)

        val md5 = MessageDigest.getInstance("MD5")
        val input = bleAddr + SOURCE_KEY + deviceRandom + password
        var auth2 = md5.digest(input)
        if (shortMode) auth2 = foldTo8(auth2)
        val authAlg2 = if (shortMode) 0x12 else 0x02

        pendingResponseHandler = { resp2 -> onAuthStep2(resp2) }
        sendRaw(byteArrayOf(0x13, authAlg2.toByte()) + auth2)
    }

    private fun onAuthStep2(resp: ByteArray) {
        val dataType = (resp[0].toInt() shr 4) and 0xF
        if (dataType != 0x1) { listener.onLog("תגובת אימות לא צפויה (שלב 2)"); return }
        val authRslt = resp[1].toInt() and 0xFF
        if (authRslt == 0x02) {
            listener.onLog("אימות הצליח")
            sendCfgTrigger()
        } else {
            listener.onStatus("אימות נכשל (קוד 0x${authRslt.toString(16)})")
        }
    }

    private fun foldTo8(md5_16: ByteArray): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (md5_16[i].toInt() xor md5_16[8 + i].toInt()).toByte()
        return out
    }

    // ---------------- Config: enable realtime temp/humidity trigger ----------------

    private fun sendCfgTrigger() {
        listener.onStatus("מגדיר דיווח בזמן אמת...")
        // trAct=16 (report only) was ACCEPTED (cause 0x0) but produced no
        // data — trying trAct=17 (advertisement + report), matching the
        // vendor's own worked example exactly, in case the firmware only
        // arms the periodic-check loop when the advertisement bit is set.
        val json = """{"msg":"cfg","stype":64,"trType":8,"trAct":17,"htMsk":16,"slot":0,"trATm":10}"""
        sendJsonChunks(json.toByteArray(Charsets.US_ASCII))
    }

    private fun sendJsonChunks(bytes: ByteArray) {
        var offset = 0

        fun sendNext() {
            val end = minOf(offset + CHUNK_SIZE, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            val isFirst = offset == 0
            val isLast = end >= bytes.size
            val pduTag = if (isFirst && isLast) 3 else if (isFirst) 0 else if (isLast) 2 else 1
            val header = ((0x2 shl 4) or pduTag).toByte()
            val idx = offset
            val frame = byteArrayOf(header, ((idx shr 8) and 0xFF).toByte(), (idx and 0xFF).toByte()) + chunk

            pendingResponseHandler = { ack ->
                offset = end
                val ackCause = if (ack.size >= 7) ((ack[5].toInt() and 0xFF) shl 8) or (ack[6].toInt() and 0xFF) else -1
                if (offset >= bytes.size) {
                    val causeHex = "0x" + ackCause.toString(16).uppercase()
                    when (ackCause) {
                        0x0, 0x5, 0x6 -> {
                            listener.onLog("cfg הושלם בהצלחה ($causeHex)")
                            listener.onStatus("מחובר — trigger הוגדר ($causeHex) — ממתין לנתונים")
                        }
                        else -> {
                            listener.onLog("cfg נדחה על ידי החיישן ($causeHex)")
                            listener.onStatus("החיישן דחה את הגדרת ה-trigger — קוד תשובה: $causeHex")
                        }
                    }
                } else {
                    sendNext()
                }
            }
            sendRaw(frame)
        }
        sendNext()
    }

    // ---------------- Incoming trigger data (section 9.2) ----------------

    private fun handleIndicateFrame(bytes: ByteArray) {
        if (bytes.isEmpty() || (bytes[0].toInt() and 0xFF) != 0x2) return
        if (bytes.size < 9) return

        fun s16(hi: Int, lo: Int): Int {
            val u = (hi shl 8) or lo
            return if (u >= 0x8000) u - 0x10000 else u
        }

        val a = s16(bytes[5].toInt() and 0xFF, bytes[6].toInt() and 0xFF) / 256f
        val b = s16(bytes[7].toInt() and 0xFF, bytes[8].toInt() and 0xFF) / 256f
        // Section 4.2 (same doc as the trigger config we're now using) lists
        // Humidity before Temperature — opposite of section 9.2. Since we
        // switched to the section-4/legacy trigger schema, trust that order.
        listener.onReading(temperatureC = b, humidityPct = a)
    }
}
