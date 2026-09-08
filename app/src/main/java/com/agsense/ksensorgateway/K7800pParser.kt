package com.agsense.ksensorgateway

/**
 * Parses the K7800P (Jimi IoT) environmental-sensor manufacturer-specific
 * data. No public byte-level spec exists for this device (unlike KKM's
 * KSensor) — this was reverse-engineered from raw hex dumps captured live
 * (manufacturer company ID 0x7E7D), by comparing two different K7800P
 * units and comparing repeated samples of the same unit where temperature/
 * humidity had visibly ticked by 0.1 between samples.
 *
 * Payload layout (company ID 0x7E7D already stripped by Android):
 *   [0..5]   the device's own BLE MAC address, repeated
 *   [6..9]   constant across every sample seen so far (0C 02 64 03) —
 *            purpose unknown, possibly a sub-type/version marker
 *   [10..11] temperature: Int16, LITTLE-endian, unit 0.1°C
 *   [12]     constant (0x04) — likely a tag marking the next field
 *   [13..14] humidity: UInt16, LITTLE-endian, unit 0.1%RH
 *   [15]     flags byte — likely door/light sensor status bits (not yet
 *            decoded; stayed constant across every sample since the unit
 *            didn't move/change light conditions during capture)
 *   [16]     constant per-device (varies BETWEEN devices, not between
 *            samples of the same device) — purpose unknown, possibly a
 *            model/revision marker
 *
 * Battery level was NOT found in this frame — Jimi's cloud docs describe
 * a separate "power"/"powerRate" field that likely comes from the
 * gateway/tracker polling the sensor over GATT, not from this passive
 * advertisement.
 */
object K7800pParser {

    const val JIMI_MANUFACTURER_ID = 0x7E7D

    data class Parsed(
        val temperatureC: Float?,
        val humidityPct: Float?
    )

    fun parse(data: ByteArray): Parsed? {
        if (data.size < 15) return null

        fun int16Le(lo: Byte, hi: Byte): Int {
            var v = ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
            if (v >= 0x8000) v -= 0x10000
            return v
        }

        val temp = int16Le(data[10], data[11]) / 10.0f
        val hum = int16Le(data[13], data[14]) / 10.0f

        return Parsed(temperatureC = temp, humidityPct = hum)
    }
}
