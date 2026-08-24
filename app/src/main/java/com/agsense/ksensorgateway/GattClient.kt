package com.agsense.ksensorgateway

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Connects directly to a KBeacon (K6 / KBPro) by its known MAC address —
 * no chooser dialog needed, since native Android BLE APIs expose the real
 * MAC (unlike Web Bluetooth in a browser). Performs the vendor's two-way
 * MD5 authentication handshake, then polls the sensor's current
 * temperature/humidity reading directly via "getPara type:8" every few
 * seconds.
 *
 * Note on trigger-based push: two independent attempts to configure a
 * "trType":12 ("report temperature and humidity periodically") trObj
 * trigger were both rejected by this specific device (firmware
 * K6p_NRF52XX V6.74) with AckCause 0x103 ("illegal content field") —
 * once with trAct:17 (advertise+report, requiring slot/trATm) and once
 * with trAct:16 (report-to-app only, no slot needed at all). Both were
 * byte-for-byte valid per the KBPro spec's own worked example, and
 * getPara's trCap (528257) confirms bit11 ("temperature realtime
 * trigger") IS advertised as supported. The repeated identical failure
 * strongly suggests trCap reflects generic hardware capability rather
 * than what this firmware build's cfg-write path actually implements —
 * i.e. a firmware-side gap, not a malformed request. Direct polling
 * sidesteps that gap entirely, since getPara is confirmed to work
 * reliably (it's how we read trCap/model/etc. in the first place).
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
        private const val POLL_INTERVAL_MS = 5000L
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private var bleAddr = ByteArray(0)
    private var password = ByteArray(0)
    private var shortMode = false

    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false
    private var pendingResponseHandler: ((ByteArray) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollingActive = false

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
        pollingActive = false

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
        pollingActive = false
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
                BluetoothProfile.STATE_DISCONNECTED -> {
                    pollingActive = false
                    listener.onStatus("נותק")
                }
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
            sendGetPara(1) { commonJson ->
                listener.onLog("getPara <- $commonJson")
                sendEnableSensor {
                    sendGetPara(8) { sensorJson ->
                        listener.onLog("sensor getPara <- $sensorJson")
                        listener.onStatus("קורא חיישן כל ${POLL_INTERVAL_MS / 1000} שניות...")
                        pollingActive = true
                        pollSensorOnce()
                    }
                }
            }
        } else {
            listener.onStatus("אימות נכשל (קוד 0x${authRslt.toString(16)})")
        }
    }

    private fun foldTo8(md5_16: ByteArray): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (md5_16[i].toInt() xor md5_16[8 + i].toInt()).toByte()
        return out
    }

    // ---------------- getPara: read device / sensor parameters ----------------

    private fun sendGetPara(type: Int, onComplete: (String) -> Unit) {
        val buffer = StringBuilder()
        val json = """{"msg":"getPara","type":$type}"""
        sendJsonChunks(json.toByteArray(Charsets.US_ASCII)) { ack ->
            // Per section 6.3.2, the first response frame is the Ack itself
            // (7-byte head) with any JSON body that fits appended after it.
            // If the body didn't fully fit, cause is 0x5 ("received, now
            // execute") and PduTag (low nibble of byte 0) is FrameStart(0),
            // meaning more JSON arrives afterwards as separate DataType-0x3
            // report frames that we must ack ourselves until FrameEnd.
            val pduTag = ack[0].toInt() and 0xF
            if (ack.size > 7) {
                buffer.append(String(ack.copyOfRange(7, ack.size), Charsets.US_ASCII))
            }
            if (pduTag == 2 || pduTag == 3) {
                onComplete(buffer.toString())
            } else {
                pendingResponseHandler = { frame -> handleGetParaContinuation(frame, buffer, onComplete) }
            }
        }
    }

    private fun handleGetParaContinuation(frame: ByteArray, buffer: StringBuilder, onComplete: (String) -> Unit) {
        if (frame.size < 3) return
        val pduTag = frame[0].toInt() and 0xF
        val dataIndex = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        val payload = frame.copyOfRange(3, frame.size)
        buffer.append(String(payload, Charsets.US_ASCII))

        val isFinal = pduTag == 2 || pduTag == 3
        val nextIndex = dataIndex + payload.size
        val ackCause = if (isFinal) 0x0 else 0x4
        val ackHeader = ((frame[0].toInt() and 0xF0) or 0x3).toByte()
        val ackFrame = byteArrayOf(
            ackHeader,
            ((nextIndex shr 8) and 0xFF).toByte(), (nextIndex and 0xFF).toByte(),
            0x00, 0x00,
            ((ackCause shr 8) and 0xFF).toByte(), (ackCause and 0xFF).toByte()
        )
        sendRaw(ackFrame)

        if (isFinal) {
            pendingResponseHandler = null
            onComplete(buffer.toString())
        } else {
            pendingResponseHandler = { f -> handleGetParaContinuation(f, buffer, onComplete) }
        }
    }

    // ---------------- Enable the humidity sensor task (section 3 of the K6 supplement) ----------------

    private fun sendEnableSensor(onDone: () -> Unit) {
        listener.onStatus("מפעיל את חיישן הלחות/טמפרטורה...")
        val json = """{"msg":"cfg","stype":1,"type":1,"sensor":2}"""
        sendJsonChunks(json.toByteArray(Charsets.US_ASCII)) { ack ->
            val cause = if (ack.size >= 7) ((ack[5].toInt() and 0xFF) shl 8) or (ack[6].toInt() and 0xFF) else -1
            listener.onLog("enable sensor cause=0x${cause.toString(16)}")
            onDone()
        }
    }

    // ---------------- Periodic direct polling of the live sensor reading ----------------

    private fun pollSensorOnce() {
        if (!pollingActive) return
        sendGetPara(8) { sensorJson ->
            listener.onLog("sensor getPara <- $sensorJson")
            if (pollingActive) {
                mainHandler.postDelayed({ pollSensorOnce() }, POLL_INTERVAL_MS)
            }
        }
    }

    private fun sendJsonChunks(bytes: ByteArray, onDone: (ByteArray) -> Unit) {
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
                if (offset >= bytes.size) {
                    onDone(ack)
                } else {
                    sendNext()
                }
            }
            sendRaw(frame)
        }
        sendNext()
    }

    // ---------------- Incoming trigger data (kept in case a future trigger config works) ----------------

    private fun handleIndicateFrame(bytes: ByteArray) {
        if (bytes.isEmpty() || (bytes[0].toInt() and 0xFF) != 0x2) return
        if (bytes.size < 9) return

        fun s16(hi: Int, lo: Int): Int {
            val u = (hi shl 8) or lo
            return if (u >= 0x8000) u - 0x10000 else u
        }

        val a = s16(bytes[5].toInt() and 0xFF, bytes[6].toInt() and 0xFF) / 256f
        val b = s16(bytes[7].toInt() and 0xFF, bytes[8].toInt() and 0xFF) / 256f
        listener.onReading(temperatureC = b, humidityPct = a)
    }
}
