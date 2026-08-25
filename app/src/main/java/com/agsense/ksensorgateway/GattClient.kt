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
 * MD5 authentication handshake, enables the humidity/temperature sensor
 * task, then polls the sensor's logged history (section 8.1 "Read sensor
 * record" byte-message protocol) every few seconds to get live readings.
 *
 * Why history-read instead of getPara or a trigger:
 * - Two independent attempts at a "trType":12 trObj trigger (push
 *   notifications) were both rejected by this device's firmware
 *   (K6p_NRF52XX V6.74) with AckCause 0x103, despite getPara's trCap
 *   bitmask claiming the capability is supported — a firmware-side gap,
 *   not a malformed request.
 * - getPara (any type, including type:8) only ever returns *configuration*
 *   (srObj shows msItvl/lgItvl/thresholds), never a live measurement —
 *   confirmed by testing. The srObj response showed the sensor is already
 *   actively measuring every ~3s (msItvl:3) and logging with near-zero
 *   thresholds (tsThd:0, hsThd:0), so records should already be
 *   accumulating in the device's flash log.
 * - Section 8.1's binary "read sensor record" protocol (DataType 0x0/0x5,
 *   distinct from the JSON DataType 0x2/0x3 used elsewhere) reads that log
 *   directly, sidestepping both the broken trigger path and getPara's
 *   config-only responses.
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
        private const val SENSOR_TYPE_HUMIDITY = 0x02
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

    /** Stops the periodic history poll without closing the BLE connection. */
    fun stopPolling() {
        pollingActive = false
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
            enableFullAdvertisement(0) { _, causeHex ->
                listener.onLog("שידור מלא (temp+humidity+acc) הוגדר: $causeHex")
                sendEnableSensor {
                    sendReadHistoryInfo { total, unread ->
                        listener.onLog("records: total=$total unread=$unread")
                        listener.onStatus("קורא היסטוריית חיישן כל ${POLL_INTERVAL_MS / 1000} שניות...")
                        pollingActive = true
                        pollHistoryOnce()
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

    /**
     * One-time setup (section 7.3.1.5): tells the device to include full
     * temperature+humidity ("ht") and X/Y/Z accelerometer ("axis") readings
     * in its KSensor advertisement packet — the broadcast frame the app's
     * scanner (not this GATT connection) already parses for battery/RSSI.
     * Call this once per device; the setting persists on the beacon after
     * this GATT session ends, so no ongoing connection is needed afterwards.
     */
    fun enableFullAdvertisement(slot: Int = 0, onDone: (success: Boolean, causeHex: String) -> Unit) {
        val json = """{"msg":"cfg","advObj":[{"slot":$slot,"type":1,"ht":1,"axis":1}]}"""
        sendJsonChunks(json.toByteArray(Charsets.US_ASCII)) { ack ->
            val cause = if (ack.size >= 7) ((ack[5].toInt() and 0xFF) shl 8) or (ack[6].toInt() and 0xFF) else -1
            val causeHex = "0x" + cause.toString(16).uppercase()
            listener.onLog("advObj (ht+axis) cause=$causeHex")
            onDone(cause == 0x0 || cause == 0x5 || cause == 0x6, causeHex)
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

    // ---------------- Section 8.1: read sensor record (binary byte-message protocol) ----------------

    private fun sendByteRequest(payload: ByteArray, onComplete: (ByteArray) -> Unit) {
        var offset = 0

        fun sendNext() {
            val end = minOf(offset + CHUNK_SIZE, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            val isFirst = offset == 0
            val isLast = end >= payload.size
            val pduTag = if (isFirst && isLast) 3 else if (isFirst) 0 else if (isLast) 2 else 1
            val header = ((0x0 shl 4) or pduTag).toByte() // DataType 0x0: byte message frame
            val idx = offset
            val frame = byteArrayOf(header, ((idx shr 8) and 0xFF).toByte(), (idx and 0xFF).toByte()) + chunk

            pendingResponseHandler = { ack ->
                offset = end
                if (offset >= payload.size) {
                    val buffer = java.io.ByteArrayOutputStream()
                    val pduTagResp = ack[0].toInt() and 0xF
                    if (ack.size > 7) buffer.write(ack, 7, ack.size - 7)
                    if (pduTagResp == 2 || pduTagResp == 3) {
                        onComplete(buffer.toByteArray())
                    } else {
                        pendingResponseHandler = { frame2 -> handleByteContinuation(frame2, buffer, onComplete) }
                    }
                } else {
                    sendNext()
                }
            }
            sendRaw(frame)
        }
        sendNext()
    }

    private fun handleByteContinuation(frame: ByteArray, buffer: java.io.ByteArrayOutputStream, onComplete: (ByteArray) -> Unit) {
        if (frame.size < 3) return
        val pduTag = frame[0].toInt() and 0xF
        val dataIndex = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        val payloadLen = frame.size - 3
        buffer.write(frame, 3, payloadLen)

        val isFinal = pduTag == 2 || pduTag == 3
        val nextIndex = dataIndex + payloadLen
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
            onComplete(buffer.toByteArray())
        } else {
            pendingResponseHandler = { f -> handleByteContinuation(f, buffer, onComplete) }
        }
    }

    private fun u32be(b: ByteArray, off: Int): Long =
        (((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
         ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF))

    private fun s16fixed(b: ByteArray, off: Int): Float {
        val u = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
        val signed = if (u >= 0x8000) u - 0x10000 else u
        return signed / 256f
    }

    /** Section 8.1.1: total/unread record counts for the humidity&temperature sensor. */
    private fun sendReadHistoryInfo(onComplete: (total: Long, unread: Long) -> Unit) {
        val req = byteArrayOf(0x01, SENSOR_TYPE_HUMIDITY.toByte())
        sendByteRequest(req) { resp ->
            if (resp.size < 10) {
                listener.onLog("read history info: תגובה קצרה מדי (${resp.size} bytes)")
                onComplete(0, 0)
                return@sendByteRequest
            }
            val total = u32be(resp, 2)
            val unread = u32be(resp, 6)
            onComplete(total, unread)
        }
    }

    /**
     * Section 8.1.2: read up to [maxRecords] unread temperature&humidity
     * records (8 bytes each: UTC time + temperature + humidity, fixed-point 8.8).
     */
    private fun sendReadHistoryData(maxRecords: Int, onComplete: (List<Triple<Long, Float, Float>>) -> Unit) {
        val req = byteArrayOf(
            0x02, SENSOR_TYPE_HUMIDITY.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // read from last-read position
            ((maxRecords shr 8) and 0xFF).toByte(), (maxRecords and 0xFF).toByte(),
            0x02 // read new records
        )
        sendByteRequest(req) { resp ->
            if (resp.size < 7) {
                onComplete(emptyList())
                return@sendByteRequest
            }
            // Per section 8.1.2 table: msgType(1)+msgLength(2)+sensorType(1)+nextRecordsID(4) = 8 header bytes.
            val header = 8
            val records = mutableListOf<Triple<Long, Float, Float>>()
            var off = header
            while (off + 8 <= resp.size) {
                val utc = u32be(resp, off)
                val temperature = s16fixed(resp, off + 4)
                val humidity = s16fixed(resp, off + 6)
                records.add(Triple(utc, temperature, humidity))
                off += 8
            }
            onComplete(records)
        }
    }

    // ---------------- Periodic direct polling of the sensor's logged history ----------------

    private fun pollHistoryOnce() {
        if (!pollingActive) return
        sendReadHistoryData(maxRecords = 10) { records ->
            if (records.isEmpty()) {
                listener.onLog("history: אין רשומות חדשות")
            } else {
                for ((utc, temperature, humidity) in records) {
                    listener.onLog("history <- utc=$utc temp=$temperature hum=$humidity")
                    listener.onReading(temperatureC = temperature, humidityPct = humidity)
                }
            }
            if (pollingActive) {
                mainHandler.postDelayed({ pollHistoryOnce() }, POLL_INTERVAL_MS)
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

        // Per KBPro spec section 5.1.1 (confirmed against the vendor's own
        // "KBPro Message List Specification" doc): byte[1..4]=UTC time,
        // byte[5..6]=Temperature, byte[7..8]=Humidity — both signed 8.8
        // fixed-point, big-endian. (Previously swapped here.)
        val temperature = s16(bytes[5].toInt() and 0xFF, bytes[6].toInt() and 0xFF) / 256f
        val humidity = s16(bytes[7].toInt() and 0xFF, bytes[8].toInt() and 0xFF) / 256f
        listener.onReading(temperatureC = temperature, humidityPct = humidity)
    }
}
