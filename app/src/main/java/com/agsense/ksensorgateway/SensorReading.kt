package com.agsense.ksensorgateway

/**
 * One row in the on-screen list: the latest known state for a single
 * KSensor beacon (KBeacon K6 / KBPro), identified by its MAC address.
 */
data class SensorReading(
    val mac: String,
    var name: String? = null,
    var rssi: Int = 0,
    var batteryMv: Int? = null,
    var batteryPercent: Int? = null,
    var temperatureC: Float? = null,
    var humidityPct: Float? = null,
    var accXmg: Int? = null,
    var accYmg: Int? = null,
    var accZmg: Int? = null,
    var doorOpen: Boolean? = null,      // K7800P only; null = not reported/no door sensor
    var lightDetected: Boolean? = null, // K7800P only; null = not reported/no light sensor
    var alarmCode: Int? = null,         // KSensor "alarm/cutoff" byte (MASK_ALARM); null = not present on this advertisement
    var lastUpdateMillis: Long = System.currentTimeMillis()
)
