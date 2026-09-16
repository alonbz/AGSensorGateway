package com.agsense.ksensorgateway

/**
 * Parses the K7800P (Jimi IoT) environmental-sensor manufacturer-specific
 * data. No public byte-level spec for the raw advertisement exists (unlike
 * KKM's KSensor) — temperature/humidity were reverse-engineered from raw
 * hex dumps (manufacturer company ID 0x7E7D), by comparing units and
 * repeated samples where values visibly ticked between samples.
 *
 * The door/light status is NOT in byte [15] — that byte (0x80, constant)
 * matches Jimi's documented VL103M "valid" bit positions, which is why it
 * looked like both sensors were permanently unpaired. The actual live
 * door/light state is in byte [16], which we'd previously assumed was a
 * fixed per-device marker. A controlled test (door closed/open, sensor
 * moved dark/light, all 4 combinations covered) confirmed byte [16] toggles
 * in lockstep with real-world state, using a different 2-bit-field layout
 * than the VL103M valid/status bits — each field takes only 1 or 2 (never
 * 0 or 3):
 *   confirmed samples: 0x05=dark+closed, 0x06=light+closed,
 *                       0x0A=light+open,  0x09=dark+open
 *
 * Payload layout (company ID 0x7E7D already stripped by Android):
 *   [0..5]   the device's own BLE MAC address, repeated
 *   [6..9]   constant across every sample seen so far (0C 02 64 03) —
 *            purpose unknown, possibly a sub-type/version marker
 *   [10..11] temperature: Int16, LITTLE-endian, unit 0.1°C
 *   [12]     constant (0x04) — likely a tag marking the next field
 *   [13..14] humidity: UInt16, LITTLE-endian, unit 0.1%RH
 *   [15]     constant (0x80) across all samples/devices seen — appears to
 *            be Jimi's documented "valid" bits, permanently 0 on this
 *            hardware; not used for door/light on this device
 *   [16]     door/light status byte (confirmed by live toggle test):
 *              bits [3:2] door: 1=closed, 2=open
 *              bits [1:0] light: 1=dark, 2=light detected
 *              (values 0 and 3 never observed — treated as "unknown")
 *
 * Battery level was NOT found in this advertisement frame — Jimi's cloud
 * docs describe a separate "power"/"powerRate" field that likely comes
 * from the gateway/tracker polling the sensor over GATT, not from this
 * passive advertisement.
 */
object K7800pParser {

    const val JIMI_MANUFACTURER_ID = 0x7E7D

    data class Parsed(
        val temperatureC: Float?,
        val humidityPct: Float?,
        val doorSensorValid: Boolean,
        val doorOpen: Boolean?,      // null when doorSensorValid is false
        val lightSensorValid: Boolean,
        val lightDetected: Boolean?  // null when lightSensorValid is false
    )

    fun parse(data: ByteArray): Parsed? {
        if (data.size < 17) return null

        fun int16Le(lo: Byte, hi: Byte): Int {
            var v = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
            if (v >= 0x8000) v -= 0x10000
            return v
        }

        val temp = int16Le(data[10], data[11]) / 10.0f
        val hum = int16Le(data[13], data[14]) / 10.0f

        val doorLightByte = data[16].toInt() and 0xFF
        val doorField = (doorLightByte shr 2) and 0x03
        val lightField = doorLightByte and 0x03

        val doorOpen = when (doorField) {
            1 -> false
            2 -> true
            else -> null // 0/3 never observed; unknown state
        }
        val lightDetected = when (lightField) {
            1 -> false
            2 -> true
            else -> null // 0/3 never observed; unknown state
        }

        return Parsed(
            temperatureC = temp,
            humidityPct = hum,
            doorSensorValid = doorOpen != null,
            doorOpen = doorOpen,
            lightSensorValid = lightDetected != null,
            lightDetected = lightDetected
        )
    }
}
