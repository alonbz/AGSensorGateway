package com.agsense.ksensorgateway

/**
 * Parses the "KSensor" advertisement frame — REWRITTEN to match KKM's own
 * open-source Android SDK (github.com/kkmhogen/android_kbeaconlib2,
 * KBAdvPackage/KBAdvPacketSensor.java + KBAdvPacketHandler.java), not the
 * PDF spec's ambiguous byte diagram. Verified against real hardware output
 * (KBPro_788797) shown in the reference scanner app.
 *
 * KEY FINDING: the KSensor advertisement is NOT carried in Eddystone
 * service data (UUID 0xFEAA) on this hardware — it's carried as
 * **Manufacturer Specific Data** with KKM's company ID **0x0A53**.
 * (The Eddystone-service-data path with FrameType 0x21 also exists per
 * the PDF spec/older firmware, so we support both — see [KKM_MANUFACTURER_ID]
 * and [parseFromServiceData]).
 *
 * Byte layout for the plain (non-encrypted) V1 frame, once you have the
 * raw bytes (manufacturer data with the 2-byte company ID already
 * stripped by Android, OR Eddystone service data with the 2-byte UUID
 * already stripped):
 *   [0]      FrameType     0x21 = KSensor V1 (plain), 0x24 = V2 (plain),
 *                          0x06/0x07 = encrypted (not supported here)
 *   [1..2]   SensorMask    UInt16 BIG-ENDIAN (high byte first)
 *   [3..]    fields present only if their mask bit is set, in this
 *             fixed order — ALL BIG-ENDIAN:
 *       bit0 (0x001) voltage/battery   UInt16  raw ADC-ish value (e.g. 3604)
 *       bit1 (0x002) temperature       Int16   signed, value/256.0, °C
 *       bit2 (0x004) humidity          Int16   signed, value/256.0, %
 *       bit3 (0x008) acc X/Y/Z         3x Int16
 *       bit4 (0x010) alarm/cutoff      UInt8
 *       bit5 (0x020) PIR               UInt8
 *       bit6 (0x040) lux               UInt16
 *       bit7 (0x080) VOC               5 bytes (elapsed*10, voc U16, nox U16)
 *       bit9 (0x200) CO2               3 bytes (elapsed*10, co2 U16)
 *       bit10(0x400) new record count  1 byte type + UInt16 count
 *
 * Separately, battery **percentage** (0-100) — as opposed to the raw
 * voltage/ADC value above — comes from its own Eddystone-style service
 * data UUID 00002080-0000-1000-8000-00805f9b34fb ([EXT_DATA_UUID]),
 * first byte = percent.
 */
object KSensorParser {

    /** KKM's BLE manufacturer company ID (little-endian 0x53 0x0A over the air). */
    const val KKM_MANUFACTURER_ID = 0x0A53

    const val FRAME_TYPE_KSENSOR_V1 = 0x21
    const val FRAME_TYPE_KSENSOR_V2 = 0x24
    const val FRAME_TYPE_KSENSOR_V1_ENC = 0x06
    const val FRAME_TYPE_KSENSOR_V2_ENC = 0x07

    private const val MASK_VOLTAGE = 0x1
    private const val MASK_TEMP = 0x2
    private const val MASK_HUME = 0x4
    private const val MASK_ACC = 0x8
    private const val MASK_ALARM = 0x10
    private const val MASK_PIR = 0x20
    private const val MASK_LUX = 0x40
    private const val MASK_VOC = 0x80
    private const val MASK_CO2 = 0x200
    private const val MASK_RECORD_NUM = 0x400

    data class Parsed(
        val frameType: Int,
        val batteryRaw: Int?,      // raw voltage/ADC-style value, e.g. 3604 (NOT a percentage)
        val temperatureC: Float?,
        val humidityPct: Float?,
        val accX: Int?,
        val accY: Int?,
        val accZ: Int?,
        val alarm: Int?,
        val pir: Int?,
        val luxValue: Int?,
        val newRecordCount: Int?
    )

    /** Big-endian, signed, fixed-point /256.0, rounded to 2 decimals — matches KBUtility.signedBytes2Float(). */
    private fun signed16BeOver256(hi: Byte, lo: Byte): Float {
        var combined = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
        if (combined >= 0x8000) combined -= 0x10000
        val raw = combined / 256.0f
        return Math.round(raw * 100f) / 100f
    }

    private fun unsigned16Be(hi: Byte, lo: Byte): Int =
        ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

    /**
     * Parses plain (non-encrypted) V1 (0x21) or V2 (0x24) frame data.
     * @param data the raw bytes with the FrameType byte still at index 0
     *   (i.e. straight from getManufacturerSpecificData()/getServiceData(),
     *   before any stripping).
     */
    fun parse(data: ByteArray): Parsed? {
        if (data.isEmpty()) return null
        val frameType = data[0].toInt() and 0xFF

        return when (frameType) {
            FRAME_TYPE_KSENSOR_V1 -> parseV1(frameType, data)
            FRAME_TYPE_KSENSOR_V2 -> parseV2(frameType, data)
            FRAME_TYPE_KSENSOR_V1_ENC, FRAME_TYPE_KSENSOR_V2_ENC ->
                null // encrypted advertisement — needs the device password + AES, not supported yet
            else -> null
        }
    }

    private fun parseV1(frameType: Int, data: ByteArray): Parsed? {
        if (data.size < 3) return null
        val mask = unsigned16Be(data[1], data[2])
        var idx = 3

        fun need(n: Int): Boolean = idx + n <= data.size

        var battery: Int? = null
        var temp: Float? = null
        var humidity: Float? = null
        var accX: Int? = null; var accY: Int? = null; var accZ: Int? = null
        var alarm: Int? = null
        var pir: Int? = null
        var lux: Int? = null
        var recordCount: Int? = null

        if (mask and MASK_VOLTAGE != 0) {
            if (!need(2)) return null
            battery = unsigned16Be(data[idx], data[idx + 1]); idx += 2
        }
        if (mask and MASK_TEMP != 0) {
            if (!need(2)) return null
            temp = signed16BeOver256(data[idx], data[idx + 1]); idx += 2
        }
        if (mask and MASK_HUME != 0) {
            if (!need(2)) return null
            val humHigh = data[idx]
            if (humHigh >= 0) {
                humidity = signed16BeOver256(data[idx], data[idx + 1])
            } else {
                humidity = null // negative high byte here means an alternate CPU-temperature encoding; skipped
            }
            idx += 2
        }
        if (mask and MASK_ACC != 0) {
            if (!need(6)) return null
            fun s16(): Int { val v = unsigned16Be(data[idx], data[idx + 1]); idx += 2; return if (v >= 0x8000) v - 0x10000 else v }
            accX = s16(); accY = s16(); accZ = s16()
        }
        if (mask and MASK_ALARM != 0) {
            if (!need(1)) return null
            alarm = data[idx].toInt(); idx += 1
        }
        if (mask and MASK_PIR != 0) {
            if (!need(1)) return null
            pir = data[idx].toInt(); idx += 1
        }
        if (mask and MASK_LUX != 0) {
            if (!need(2)) return null
            lux = unsigned16Be(data[idx], data[idx + 1]); idx += 2
        }
        if (mask and MASK_VOC != 0) {
            if (!need(5)) return null
            idx += 5 // VOC/NOx — not surfaced in the UI yet
        }
        if (mask and MASK_CO2 != 0) {
            if (!need(3)) return null
            idx += 3 // CO2 — not surfaced in the UI yet
        }
        if (mask and MASK_RECORD_NUM != 0) {
            if (!need(3)) return null
            recordCount = unsigned16Be(data[idx + 1], data[idx + 2]); idx += 3
        }

        return Parsed(frameType, battery, temp, humidity, accX, accY, accZ, alarm, pir, lux, recordCount)
    }

    private fun parseV2(frameType: Int, data: ByteArray): Parsed? {
        // V2 frame: [0]=type [1..2]=battery(U16) [3]=chip temp(1 byte, unused here)
        // then a TLV list: [len][type][...(len-1) bytes...] repeated.
        if (data.size < 4) return null
        val battery = unsigned16Be(data[1], data[2])
        var idx = 4 // skip type + battery(2) + chip temp(1)

        var temp: Float? = null
        var humidity: Float? = null
        var accX: Int? = null; var accY: Int? = null; var accZ: Int? = null
        var alarm: Int? = null
        var pir: Int? = null
        var lux: Int? = null
        var recordCount: Int? = null

        while (idx + 3 <= data.size) {
            var len = (data[idx].toInt() and 0xFF); val type = data[idx + 1].toInt() and 0xFF
            idx += 2
            len -= 1
            if (idx + len > data.size || len < 0) break

            when {
                type == 0x2 && len >= 2 -> {
                    val raw = unsigned16Be(data[idx], data[idx + 1])
                    val signed = if (raw >= 0x8000) raw - 0x10000 else raw
                    temp = signed / 10.0f
                }
                type == 0x3 && len >= 3 -> {
                    val raw = unsigned16Be(data[idx], data[idx + 1])
                    val signed = if (raw >= 0x8000) raw - 0x10000 else raw
                    temp = signed / 10.0f
                    humidity = (data[idx + 2].toInt() and 0xFF).toFloat()
                }
                type == 0x4 && len >= 6 -> {
                    fun s16(off: Int): Int { val v = unsigned16Be(data[idx + off], data[idx + off + 1]); return if (v >= 0x8000) v - 0x10000 else v }
                    accX = s16(0); accY = s16(2); accZ = s16(4)
                }
                type == 0x5 && len >= 1 -> alarm = data[idx].toInt()
                type == 0x6 && len >= 1 -> pir = data[idx].toInt()
                type == 0x7 && len >= 2 -> lux = unsigned16Be(data[idx], data[idx + 1])
                type == 0xA && len >= 3 -> recordCount = unsigned16Be(data[idx + 1], data[idx + 2])
            }
            idx += len
        }

        return Parsed(frameType, battery, temp, humidity, accX, accY, accZ, alarm, pir, lux, recordCount)
    }

    /** Battery percentage (0-100), parsed separately from the 00002080-... service data UUID. First byte = percent. */
    fun parseBatteryPercent(extServiceData: ByteArray): Int? {
        if (extServiceData.isEmpty()) return null
        val pct = extServiceData[0].toInt() and 0xFF
        return if (pct > 100) 100 else pct
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(separator = " ") { String.format("%02X", it) }
}
