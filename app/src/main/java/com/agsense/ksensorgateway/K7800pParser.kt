package com.agsense.ksensorgateway

/**
 * Parses the K7800P (Jimi IoT) environmental-sensor manufacturer-specific
 * data. No public byte-level spec for the raw advertisement exists (unlike
 * KKM's KSensor) — temperature/humidity were reverse-engineered from raw
 * hex dumps (manufacturer company ID 0x7E7D), by comparing units and
 * repeated samples where values visibly ticked between samples.
 *
 * The door/light byte, however, IS documented — in Jimi's "VL103M
 * Communication Protocol" doc (module 0x00 0x7F, "Bluetooth peripheral
 * data"), which happens to describe the exact same bit layout we'd been
 * staring at as an unexplained constant. That doc's field order for the
 * TERMINAL's own TCP report is MAC(6) + batteryVoltage(2) + batteryLevel(1)
 * + temperature(2) + humidity(2) + doorLightStatus(1); our raw advertisement
 * differs in the constant filler bytes around temp/humidity, but the
 * door/light status byte's bit layout below matches directly and explains
 * why it never changed in any of our door/light toggle tests: both
 * "valid" bits were 0 the whole time (0x80 = 1000_0000 → bit0=0, bit2=0),
 * meaning this specific K7800P unit isn't reporting either sensor as
 * present/paired — not a parsing gap on our side.
 *
 * Payload layout (company ID 0x7E7D already stripped by Android):
 *   [0..5]   the device's own BLE MAC address, repeated
 *   [6..9]   constant across every sample seen so far (0C 02 64 03) —
 *            purpose unknown, possibly a sub-type/version marker
 *   [10..11] temperature: Int16, LITTLE-endian, unit 0.1°C
 *   [12]     constant (0x04) — likely a tag marking the next field
 *   [13..14] humidity: UInt16, LITTLE-endian, unit 0.1%RH
 *   [15]     door/light status byte (per VL103M doc, module 0x00 0x7F):
 *              bit0: door sensor valid (1=valid/0=invalid)
 *              bit1: door status (0=closed/1=open) — only meaningful if bit0=1
 *              bit2: light sensor valid (1=valid/0=invalid)
 *              bit3: light status (0=no light/1=light detected) — only meaningful if bit2=1
 *   [16]     constant per-device (varies BETWEEN devices, not between
 *            samples of the same device) — purpose still unknown, possibly
 *            a model/revision marker or hardware ID
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
        if (data.size < 16) return null

        fun int16Le(lo: Byte, hi: Byte): Int {
            var v = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
            if (v >= 0x8000) v -= 0x10000
            return v
        }

        val temp = int16Le(data[10], data[11]) / 10.0f
        val hum = int16Le(data[13], data[14]) / 10.0f

        val statusByte = data[15].toInt() and 0xFF
        val doorValid = (statusByte and 0x01) != 0
        val doorOpen = if (doorValid) (statusByte and 0x02) != 0 else null
        val lightValid = (statusByte and 0x04) != 0
        val lightDetected = if (lightValid) (statusByte and 0x08) != 0 else null

        return Parsed(
            temperatureC = temp,
            humidityPct = hum,
            doorSensorValid = doorValid,
            doorOpen = doorOpen,
            lightSensorValid = lightValid,
            lightDetected = lightDetected
        )
    }
}
