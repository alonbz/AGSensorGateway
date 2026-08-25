package com.agsense.ksensorgateway

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import java.util.Locale
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KSensorGateway"

        // Eddystone service UUID — kept as a fallback path per the KBPro
        // spec PDF (older firmware may use it), but the real hardware we
        // tested against (KBPro_788797) uses manufacturer-specific data
        // instead — see KKM_MANUFACTURER_ID below.
        private val EDDYSTONE_SERVICE_UUID: UUID = UUID.fromString("0000FEAA-0000-1000-8000-00805F9B34FB")

        // KKM's BLE manufacturer company ID — confirmed against KKM's own
        // open-source Android SDK (github.com/kkmhogen/android_kbeaconlib2,
        // KBUtility.KKM_MANUFACTURE_ID). This is where the real KSensor
        // advertisement payload actually lives on this hardware.
        private const val KKM_MANUFACTURER_ID = KSensorParser.KKM_MANUFACTURER_ID

        // Separate service data UUID carrying battery *percentage*
        // (0-100), distinct from the raw voltage/ADC value in the main
        // KSensor payload.
        private val EXT_DATA_UUID: UUID = UUID.fromString("00002080-0000-1000-8000-00805F9B34FB")

        private const val REQUEST_PERMISSIONS_CODE = 100
    }

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var adapter: SensorAdapter

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var gattClient: GattClient? = null
    private lateinit var textLog: TextView
    private var scrollLog: android.widget.ScrollView? = null

    private val readings = mutableListOf<SensorReading>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed, error code: $errorCode")
            val explanation = when (errorCode) {
                1 -> "כבר יש סריקה פעילה (SCAN_FAILED_ALREADY_STARTED)"
                2 -> "רישום האפליקציה נכשל (SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)"
                3 -> "שגיאה פנימית (SCAN_FAILED_INTERNAL_ERROR)"
                4 -> "התכונה לא נתמכת (SCAN_FAILED_FEATURE_UNSUPPORTED)"
                5 -> "אין משאבי חומרה זמינים (SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES)"
                6 -> "יותר מדי סריקות בזמן קצר — אנדרואיד חוסם זמנית (SCAN_FAILED_SCANNING_TOO_FREQUENTLY). חכה כמה דקות בלי ללחוץ על סריקה ונסה שוב."
                else -> "קוד לא מוכר"
            }
            appendLog("!!! onScanFailed קוד $errorCode: $explanation")
            runOnUiThread {
                statusText.text = "שגיאת סריקה (קוד $errorCode)"
            }
            isScanning = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.textStatus)
        toggleButton = findViewById(R.id.buttonToggleScan)

        val recyclerView: RecyclerView = findViewById(R.id.recyclerSensors)
        adapter = SensorAdapter(readings)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        toggleButton.setOnClickListener {
            if (isScanning) stopScanning() else ensurePermissionsThenScan()
        }

        val editMac: EditText = findViewById(R.id.editMac)
        val editPassword: EditText = findViewById(R.id.editPassword)
        val buttonGattConnect: Button = findViewById(R.id.buttonGattConnect)
        val textGattStatus: TextView = findViewById(R.id.textGattStatus)
        val textGattReading: TextView = findViewById(R.id.textGattReading)
        textLog = findViewById(R.id.textLog)
        scrollLog = textLog.parent as? android.widget.ScrollView

        gattClient = GattClient(this, object : GattClient.Listener {
            override fun onLog(msg: String) {
                Log.d(TAG, "GATT: $msg")
                runOnUiThread {
                    textLog.append(msg + "\n")
                    scrollLog?.post { scrollLog?.fullScroll(android.view.View.FOCUS_DOWN) }
                }
            }
            override fun onStatus(msg: String) {
                runOnUiThread {
                    textGattStatus.text = msg
                    // Re-enable the button once the attempt reaches a terminal
                    // state (success, failure, or disconnect) so the user can
                    // retry — but keep it disabled while mid-flight to avoid
                    // starting a second overlapping connection.
                    val stillInProgress = msg.contains("מתחבר") || msg.contains("מגלה שירותים") || msg.contains("מאמת") || msg.contains("מגדיר")
                    buttonGattConnect.isEnabled = !stillInProgress
                }
            }
            override fun onReading(temperatureC: Float, humidityPct: Float) {
                runOnUiThread {
                    textGattReading.text = String.format(
                        Locale.getDefault(), "🌡 %.1f°C   💧 %.1f%%", temperatureC, humidityPct
                    )
                }
            }
        })

        buttonGattConnect.setOnClickListener {
            val mac = editMac.text.toString().trim().uppercase()
            if (!mac.matches(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$"))) {
                Toast.makeText(this, "פורמט MAC לא תקין, לדוגמה BC:57:29:26:BD:EF", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!hasAllPermissions()) {
                ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_PERMISSIONS_CODE)
                return@setOnClickListener
            }
            buttonGattConnect.isEnabled = false
            textLog.text = ""
            gattClient?.connect(mac, editPassword.text.toString())
        }

        // No new layout views are wired up yet, so — until the layout XML
        // gets a dedicated "stop polling" / "copy log" button — these two
        // actions live on long-presses of views that already exist:
        // long-press the connect button to stop the periodic history poll
        // (without disconnecting), and long-press the log itself to copy
        // its full text to the clipboard for sharing.
        buttonGattConnect.setOnLongClickListener {
            gattClient?.stopPolling()
            textGattStatus.text = "הקריאה נעצרה (עדיין מחובר) — אפשר להעתיק את הלוג"
            Toast.makeText(this, "הקריאה החוזרת נעצרה", Toast.LENGTH_SHORT).show()
            true
        }

        textLog.setOnLongClickListener {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("KSensor log", textLog.text))
            Toast.makeText(this, "הלוג הועתק ללוח", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isScanning) stopScanningInternal()
        gattClient?.disconnect()
    }

    // ---------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun ensurePermissionsThenScan() {
        if (hasAllPermissions()) {
            startScanning()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_PERMISSIONS_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScanning()
            } else {
                Toast.makeText(this, "צריך לאשר הרשאות Bluetooth כדי לסרוק חיישנים", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------

    private fun appendLog(msg: String) {
        Log.d(TAG, msg)
        runOnUiThread {
            textLog.append(msg + "\n")
            scrollLog?.post { scrollLog?.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun startScanning() {
        val scanner = bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, "בלוטות' לא זמין/כבוי במכשיר", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasAllPermissions()) return

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)

        // Explicitly force Legacy (BLE 4.0) scanning + 1M PHY — matching
        // nRF Connect's behavior, which DOES see this sensor reliably
        // while our previous default (non-legacy, "PHY_LE_ALL_SUPPORTED")
        // settings never delivered a single onScanResult callback for it.
        // The sensor itself is confirmed configured to Legacy advertising
        // mode, so this should be the exact match. This looks like a
        // chipset/driver-level quirk on this phone where the default
        // (non-legacy) scan mode silently drops certain legacy
        // advertisers rather than something in our parsing code — every
        // other diagnostic (heartbeat log before any parsing, try/catch
        // around parsing, no onScanFailed, no throttling) has been ruled
        // out already.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settingsBuilder.setLegacy(true)
            settingsBuilder.setPhy(android.bluetooth.BluetoothDevice.PHY_LE_1M)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Hardware-level match/batching parameters — some OEM
            // Bluetooth chipsets silently drop specific advertisers under
            // the default (MATCH_MODE_AGGRESSIVE) matching before results
            // ever reach app code. Forcing STICKY + MAX_ADVERTISEMENT asks
            // the controller to hold onto and report every match rather
            // than aggressively pruning.
            settingsBuilder.setMatchMode(ScanSettings.MATCH_MODE_STICKY)
            settingsBuilder.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        }

        val settings = settingsBuilder.build()

        // DIAGNOSTIC MODE: scanning with NO filters (was: filtered by the
        // Eddystone service UUID 0xFEAA). The KSensorParser's Eddystone
        // assumption was never confirmed against real hardware, and real
        // KBPro devices appear to advertise using a different format
        // (likely manufacturer-specific data, not an Eddystone service
        // frame) — which is why the filtered scan matched nothing from
        // the start. Once handleScanResult's diagnostic logging below
        // shows us the real advertisement layout, put a proper filter
        // back here (by name prefix "KBPro"/"KSensor" or the correct
        // UUID) to restore the battery savings.
        val filters = emptyList<android.bluetooth.le.ScanFilter>()

        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            statusText.text = "סורק..."
            toggleButton.text = "עצור סריקה"
            appendLog("--- סריקה התחילה (ללא פילטר, מצב דיאגנוסטיקה) ---")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to start scan", e)
        }
    }

    private fun stopScanning() {
        stopScanningInternal()
        statusText.text = "נעצר"
        toggleButton.text = "התחל סריקה"
    }

    private fun stopScanningInternal() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to stop scan", e)
        }
        isScanning = false
    }

    // ---------------------------------------------------------------
    // Parsing incoming advertisements
    // ---------------------------------------------------------------

    private val seenAddresses = mutableSetOf<String>()

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord

        val deviceName = try {
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            ) result.device.name else null
        } catch (e: SecurityException) {
            null
        }

        // Unconditional, once-per-address heartbeat, logged BEFORE any
        // parsing is attempted — so it's independent of any exception in
        // the parsing logic below. Confirms the scan callback is
        // receiving *something* for this device at all.
        if (seenAddresses.add(result.device.address)) {
            appendLog("[ראה מכשיר] ${result.device.address}  name=$deviceName")
        }

        // Wrapped in try-catch: an uncaught exception thrown while parsing
        // a specific advertisement would otherwise be silently swallowed
        // by Android's scan callback dispatcher, leaving NO trace in our
        // log for that device — which is exactly the symptom we were
        // chasing (KSensor devices never appearing, not even as a
        // failed-parse fallback line). If this ever fires, we'll finally
        // see why.
        try {
            // Try the real path first: KKM manufacturer-specific data.
            val kkmData = record?.getManufacturerSpecificData(KKM_MANUFACTURER_ID)
            // Fallback: Eddystone-carried KSensor frame (FrameType 0x21), per the spec PDF.
            val eddystoneData = record?.getServiceData(ParcelUuid(EDDYSTONE_SERVICE_UUID))

            val rawFrame = kkmData ?: eddystoneData
            val parsed = rawFrame?.let { KSensorParser.parse(it) }

            val batteryPercent = record?.getServiceData(ParcelUuid(EXT_DATA_UUID))
                ?.let { KSensorParser.parseBatteryPercent(it) }

            if (parsed != null) {
                appendLog(
                    "[KSensor] ${result.device.address}  temp=${parsed.temperatureC}  " +
                        "hum=${parsed.humidityPct}  batt=${parsed.batteryRaw}  " +
                        "batt%=$batteryPercent  acc=(${parsed.accX},${parsed.accY},${parsed.accZ})"
                )

                val reading = SensorReading(
                    mac = result.device.address,
                    name = deviceName,
                    rssi = result.rssi,
                    batteryMv = parsed.batteryRaw,
                    batteryPercent = batteryPercent,
                    temperatureC = parsed.temperatureC,
                    humidityPct = parsed.humidityPct,
                    accXmg = parsed.accX,
                    accYmg = parsed.accY,
                    accZmg = parsed.accZ,
                    lastUpdateMillis = System.currentTimeMillis()
                )
                runOnUiThread { adapter.upsert(reading) }
            }
        } catch (e: Exception) {
            appendLog("!!! EXCEPTION for ${result.device.address}: ${e.javaClass.simpleName}: ${e.message}")
            appendLog("!!! stack: " + (e.stackTrace.firstOrNull()?.toString() ?: "(none)"))
        }
    }
}
