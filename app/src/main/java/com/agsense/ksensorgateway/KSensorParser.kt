package com.agsense.ksensorgateway

/**
 * Parses the "KSensor" advertisement frame defined in the KBeacon /
 * KBPro API spec (Eddystone-extended format, service UUID 0xFEAA,
 * FrameType 0x21).
 *
 * IMPORTANT: Android's ScanRecord.getServiceData(uuid) already strips the
 * AD length byte, the AD type byte (0x16) and the 2-byte service UUID.
 * So the array you pass in here should start at "FrameType" (0x21).
 *
 * Byte layout (per spec), all multi-byte fields BIG-ENDIAN:
 *   [0]      FrameType        (0x21 = KSensor)
 *   [1]      Version          (0x1)
 *   [2]      SensorMask       bit0=voltage,bit1=temp,bit2=humidity,
 *                             bit3=acc,bit4=cutoff,bit5=PIR
 *   [3..]    optional fields, present ONLY if their mask bit is set,
 *             in this fixed order: voltage(2) temp(2) humidity(2)
 *             accX(2) accY(2) accZ(2) cutoff(1) pir(1)
 *
 * NOTE: the spec PDF's offset table shows a 1-byte gap between the
 * SensorMask and the Voltage field (offset 15 -> 16). We could not
 * verify this against real hardware. This parser assumes NO gap
 * (fields packed back-to-back), matching the payload diagram in the
 * same document. If your decoded temperature/humidity values look
 * wrong, check the raw hex logged by this class against what the
 * "nRF Connect" app shows for the same beacon (the vendor's own doc
 * recommends that app for verification) and flip ASSUME_GAP_BYTE below.
 */
object KSensorParser {

    private const val ASSUME_GAP_BYTE = false // see note above

    const val FRAME_TYPE_KSENSOR = 0x21

    data class Parsed(
        val frameType: Int,
        val version: Int,
        val voltageMv: Int?,
        val temperatureC: Float?,
        val humidityPct: Float?,
        val accXmg: Int?,
        val accYmg: Int?,
        val accZmg: Int?,
        val cutoff: Int?,
        val pir: Int?
    )

    fun parse(serviceData: ByteArray): Parsed? {
        if (serviceData.size < 3) return null
        val frameType = serviceData[0].toInt() and 0xFF
        if (frameType != FRAME_TYPE_KSENSOR) return null

        val version = serviceData[1].toInt() and 0xFF
        val mask = serviceData[2].toInt() and 0xFF

        var idx = 3
        if (ASSUME_GAP_BYTE) idx = 4

        var voltage: Int? = null
        var temp: Float? = null
        var humidity: Float? = null
        var accX: Int? = null
        var accY: Int? = null
        var accZ: Int? = null
        var cutoff: Int? = null
        var pir: Int? = null

        fun readU16BE(): Int? {
            if (idx + 1 >= serviceData.size) return null
            val v = ((serviceData[idx].toInt() and 0xFF) shl 8) or (serviceData[idx + 1].toInt() and 0xFF)
            idx += 2
            return v
        }

        fun readS16BE(): Int? {
            val u = readU16BE() ?: return null
            return if (u >= 0x8000) u - 0x10000 else u
        }

        fun readU8(): Int? {
            if (idx >= serviceData.size) return null
            val v = serviceData[idx].toInt() and 0xFF
            idx += 1
            return v
        }

        if (mask and 0x01 != 0) voltage = readU16BE()
        if (mask and 0x02 != 0) {
            val raw = readS16BE()
            if (raw != null) temp = raw / 256f // Fixed point 8.8
        }
        if (mask and 0x04 != 0) {
            val raw = readS16BE()
            if (raw != null) humidity = raw / 256f // Fixed point 8.8
        }
        if (mask and 0x08 != 0) {
            accX = readS16BE()
            accY = readS16BE()
            accZ = readS16BE()
        }
        if (mask and 0x10 != 0) cutoff = readU8()
        if (mask and 0x20 != 0) pir = readU8()

        return Parsed(frameType, version, voltage, temp, humidity, accX, accY, accZ, cutoff, pir)
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(separator = " ") { String.format("%02X", it) }
}
