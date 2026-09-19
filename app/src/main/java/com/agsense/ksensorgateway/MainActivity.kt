package com.agsense.ksensorgateway

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import java.util.Locale
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
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
        private const val REQUEST_PERMISSIONS_CODE_EXPLORER = 101
        private const val REQUEST_PERMISSIONS_CODE_CAMERA = 102

        // How often to open a fresh GATT connection to re-read a K7800P's
        // battery (see maybeRefreshK7800pBattery) — battery drains slowly,
        // so there's no need to connect anywhere near as often as the
        // ~1x/sec passive advertisement updates temp/humidity/door/light.
        private const val BATTERY_REFRESH_INTERVAL_MILLIS = 5 * 60 * 1000L // 5 minutes

        // Shown via the "גרסה" options-menu item — previously a fixed
        // TextView on the main screen (removed to make room for the
        // sensor list / search box); bump this on every build.
        private const val APP_VERSION = "1.0.44"
    }

    private lateinit var statusText: TextView
    private lateinit var adapter: SensorAdapter
    private lateinit var debugSection: View
    private var scanMenuItem: MenuItem? = null
    private var debugMenuItem: MenuItem? = null

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var gattClient: GattClient? = null
    private var gattExplorer: GenericGattExplorer? = null
    private var explorerPendingMac: String? = null
    private lateinit var textLog: TextView
    private var scrollLog: android.widget.ScrollView? = null

    private lateinit var filterEditText: EditText

    // Set only while the current scan is BLE-hardware-filtered to one MAC
    // (see startScanning(targetMac)) — null means the normal, unfiltered
    // "see everyone nearby" scan. Used so clearing the search box also
    // knows to restart an unfiltered scan, instead of silently staying
    // stuck seeing only the one focused sensor.
    private var focusedScanMac: String? = null

    // Carries a barcode-scanned MAC across the Bluetooth-permission
    // request flow (onRequestPermissionsResult doesn't get a "which MAC"
    // parameter of its own) — set by handleScannedMac() right before
    // requesting permission, consumed and cleared once granted.
    private var pendingFocusedScanMac: String? = null

    private val barcodeScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val raw = result.data?.getStringExtra(BarcodeScanActivity.EXTRA_RESULT)
            if (raw != null) handleScannedMac(raw)
        }
    }

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
        supportActionBar?.title = "AGS-Gateway"

        statusText = findViewById(R.id.textStatus)
        debugSection = findViewById(R.id.debugSection)

        val recyclerView: RecyclerView = findViewById(R.id.recyclerSensors)
        filterEditText = findViewById(R.id.editFilterMac)
        val clearFilterButton: Button = findViewById(R.id.buttonClearFilter)

        adapter = SensorAdapter(onItemClick = { reading ->
            // Tapping a row in the list fills its MAC into the filter
            // field — "pick from the list" and "type a MAC" both funnel
            // into the same filter, so only that sensor stays visible.
            filterEditText.setText(reading.mac)
            filterEditText.setSelection(filterEditText.text.length)
        })
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        filterEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.setFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        clearFilterButton.setOnClickListener {
            filterEditText.setText("")
            // If we're mid a MAC-focused scan (from a barcode scan), typing
            // an empty filter alone wouldn't bring other sensors back —
            // the BLE-level ScanFilter is still restricting the radio to
            // just that one MAC. Restart unfiltered so "נקה" really means
            // "show everyone" again.
            if (focusedScanMac != null) {
                focusedScanMac = null
                if (isScanning) {
                    stopScanningInternal()
                    startScanning(null)
                }
            }
        }

        val buttonScanBarcode: Button = findViewById(R.id.buttonScanBarcode)
        buttonScanBarcode.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                barcodeScanLauncher.launch(Intent(this, BarcodeScanActivity::class.java))
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_PERMISSIONS_CODE_CAMERA)
            }
        }

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        val editMac: EditText = findViewById(R.id.editMac)
        val editPassword: EditText = findViewById(R.id.editPassword)
        val buttonGattConnect: Button = findViewById(R.id.buttonGattConnect)
        val textGattStatus: TextView = findViewById(R.id.textGattStatus)
        val textGattReading: TextView = findViewById(R.id.textGattReading)
        textLog = findViewById(R.id.textLog)
        scrollLog = findViewById(R.id.debugSection)

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
            val rawMac = editMac.text.toString().trim().uppercase()
            val hexOnly = rawMac.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }  // strips colons, spaces, and invisible RTL bidi marks alike
            if (!hexOnly.matches(Regex("^[0-9A-F]{12}$"))) {
                Toast.makeText(this, "פורמט MAC לא תקין: '$hexOnly' (${hexOnly.length} תווים, צריך 12)", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val mac = hexOnly.chunked(2).joinToString(":")
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

        val buttonExploreGatt: Button = findViewById(R.id.buttonExploreGatt)
        gattExplorer = GenericGattExplorer(this, object : GenericGattExplorer.Listener {
            override fun onLog(msg: String) {
                appendLog(msg)
            }
        })
        buttonExploreGatt.setOnClickListener {
            val rawMac = editMac.text.toString().trim().uppercase()
            val hexOnly = rawMac.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }  // strips colons, spaces, and invisible RTL bidi marks alike
            appendLog("[explorer] קלט גולמי: '$rawMac' (${rawMac.length} תווים) -> אחרי סינון: '$hexOnly' (${hexOnly.length} תווים)")
            appendLog("[explorer] קודי התווים בקלט הגולמי: " + rawMac.map { it.code }.joinToString(","))
            if (!hexOnly.matches(Regex("^[0-9A-F]{12}$"))) {
                Toast.makeText(this, "פורמט MAC לא תקין: '$hexOnly' (${hexOnly.length} תווים, צריך 12)", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val mac = hexOnly.chunked(2).joinToString(":")
            explorerPendingMac = mac
            val neededPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_CONNECT
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (ContextCompat.checkSelfPermission(this, neededPermission) != PackageManager.PERMISSION_GRANTED) {
                appendLog("[explorer] מבקש הרשאה $neededPermission במפורש (התגלתה חסרה על סמך SecurityException אמיתי) ועוצר כאן עד לתשובה...")
                ActivityCompat.requestPermissions(this, arrayOf(neededPermission), REQUEST_PERMISSIONS_CODE_EXPLORER)
                return@setOnClickListener
            }
            appendLog("[explorer] MAC מנורמל: $mac. מתחיל חיבור...")
            try {
                gattExplorer?.disconnect()
                gattExplorer?.connect(mac)
            } catch (e: Exception) {
                appendLog("[explorer] !!! EXCEPTION בעת חיבור: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        buttonExploreGatt.setOnLongClickListener {
            gattExplorer?.disconnect()
            Toast.makeText(this, "נותק מחקירת GATT", Toast.LENGTH_SHORT).show()
            true
        }

        val editWriteTargetUuid: EditText = findViewById(R.id.editWriteTargetUuid)
        val editWriteHexBytes: EditText = findViewById(R.id.editWriteHexBytes)
        val buttonWriteGatt: Button = findViewById(R.id.buttonWriteGatt)
        buttonWriteGatt.setOnClickListener {
            var uuidFragment = editWriteTargetUuid.text.toString().trim()
            var hex = editWriteHexBytes.text.toString().trim()
            // Tolerate both values typed together (space-separated) into
            // the UUID field, since the write fields look nearly identical
            // to the MAC/password fields and this mix-up keeps happening.
            if (hex.isEmpty() && uuidFragment.contains(Regex("\\s"))) {
                val parts = uuidFragment.split(Regex("\\s+"), limit = 2)
                uuidFragment = parts[0]
                hex = parts.getOrElse(1) { "" }
                appendLog("[explorer] (פיצלתי את הקלט המשולב: UUID='$uuidFragment' bytes='$hex')")
            }
            if (uuidFragment.isEmpty() || hex.isEmpty()) {
                Toast.makeText(this, "צריך למלא גם UUID וגם בייטים", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            gattExplorer?.writeCharacteristic(uuidFragment, hex)
        }

        textLog.setOnLongClickListener {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("KSensor log", textLog.text))
            Toast.makeText(this, "הלוג הועתק ללוח", Toast.LENGTH_SHORT).show()
            true
        }
    }

    // ---------------------------------------------------------------
    // Options menu — scan toggle, technical-details toggle, version.
    // Replaces what used to be two on-screen buttons plus a fixed
    // "גרסה: x.y.z" TextView on the main screen.
    // ---------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        scanMenuItem = menu.findItem(R.id.action_scan)
        debugMenuItem = menu.findItem(R.id.action_debug)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        scanMenuItem?.title = if (isScanning) "עצור סריקה" else "סריקה"
        debugMenuItem?.title = if (debugSection.visibility == View.VISIBLE) "הסתר פרטים טכניים" else "הצג פרטים טכניים"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_scan -> {
                if (isScanning) stopScanning() else ensurePermissionsThenScan()
                invalidateOptionsMenu()
                true
            }
            R.id.action_debug -> {
                debugSection.visibility = if (debugSection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                invalidateOptionsMenu()
                true
            }
            R.id.action_version -> {
                Toast.makeText(this, "AGS-Gateway — גרסה $APP_VERSION", Toast.LENGTH_LONG).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isScanning) stopScanningInternal()
        gattClient?.disconnect()
        gattExplorer?.disconnect()
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

    private fun ensurePermissionsThenScan(targetMac: String? = null) {
        if (hasAllPermissions()) {
            startScanning(targetMac)
        } else {
            pendingFocusedScanMac = targetMac
            ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_PERMISSIONS_CODE)
        }
    }

    /**
     * Validates a raw barcode/QR scan result as a MAC address, and if it
     * is one, focuses the list on it and starts (or restarts) a scan
     * filtered — at the BLE hardware level, not just on-screen — to just
     * that one address. This is the whole point of scanning the barcode
     * in the first place: skip the noisy full scan of everything nearby.
     */
    private fun handleScannedMac(raw: String) {
        val upper = raw.uppercase()

        // Real-world barcode payloads seen on these sensors aren't a bare
        // MAC — they're a structured string like "MAC:BC5729007D2B,SERIAL:2..."
        // with other comma-separated fields alongside it. Stripping every
        // non-hex character from the WHOLE string (the old approach) also
        // picks up stray hex-looking letters from those other fields (e.g.
        // the "A" and "C" in "MAC", the "E" and "A" in "SERIAL"), so it
        // never lands on exactly 12 characters. Look specifically for a
        // "MAC:" label and take the 12 hex characters right after it.
        val labeled = Regex("MAC:([0-9A-F]{12})").find(upper)?.groupValues?.get(1)

        // Fallback for barcodes that really are just the bare MAC with no
        // label/other fields at all.
        val hexOnly = labeled ?: upper.filter { it in '0'..'9' || it in 'A'..'F' }

        if (!hexOnly.matches(Regex("^[0-9A-F]{12}$"))) {
            Toast.makeText(this, "הבר-קוד שנסרק לא מכיל MAC תקין: '$raw'", Toast.LENGTH_LONG).show()
            return
        }
        val mac = hexOnly.chunked(2).joinToString(":")
        filterEditText.setText(mac)
        filterEditText.setSelection(filterEditText.text.length)
        Toast.makeText(this, "מתמקד בחיישן $mac", Toast.LENGTH_SHORT).show()
        if (isScanning) stopScanningInternal()
        ensurePermissionsThenScan(mac)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                val mac = pendingFocusedScanMac
                pendingFocusedScanMac = null
                startScanning(mac)
            } else {
                Toast.makeText(this, "צריך לאשר הרשאות Bluetooth כדי לסרוק חיישנים", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_PERMISSIONS_CODE_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                barcodeScanLauncher.launch(Intent(this, BarcodeScanActivity::class.java))
            } else {
                Toast.makeText(this, "צריך לאשר הרשאת מצלמה כדי לסרוק בר-קוד", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_PERMISSIONS_CODE_EXPLORER) {
            val mac = explorerPendingMac
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED } && mac != null) {
                appendLog("[explorer] ההרשאה אושרה. מתחבר אוטומטית ל-$mac ...")
                try {
                    gattExplorer?.disconnect()
                    gattExplorer?.connect(mac)
                } catch (e: Exception) {
                    appendLog("[explorer] !!! EXCEPTION בעת חיבור: ${e.javaClass.simpleName}: ${e.message}")
                }
            } else {
                appendLog("[explorer] !!! ההרשאה נדחתה - לא ניתן להתחבר")
                Toast.makeText(this, "צריך לאשר הרשאת Bluetooth כדי לחקור את ה-GATT", Toast.LENGTH_LONG).show()
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

    private fun startScanning(targetMac: String? = null) {
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

        // DIAGNOSTIC MODE: no filter by default (was: filtered by the
        // Eddystone service UUID 0xFEAA). The KSensorParser's Eddystone
        // assumption was never confirmed against real hardware, and real
        // KBPro devices appear to advertise using a different format
        // (likely manufacturer-specific data, not an Eddystone service
        // frame) — which is why the filtered scan matched nothing from
        // the start. Once handleScanResult's diagnostic logging below
        // shows us the real advertisement layout, put a proper filter
        // back here (by name prefix "KBPro"/"KSensor" or the correct
        // UUID) to restore the battery savings.
        //
        // EXCEPT when targetMac is set (barcode-scan flow): then we DO
        // filter, by exact device address, at the BLE hardware level —
        // the whole point of scanning a barcode is to skip the noisy
        // unfiltered scan and go straight to just that one sensor.
        val filters = if (targetMac != null) {
            listOf(ScanFilter.Builder().setDeviceAddress(targetMac).build())
        } else {
            emptyList()
        }
        focusedScanMac = targetMac

        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            statusText.text = if (targetMac != null) "סורק (ממוקד ל-$targetMac)..." else "סורק..."
            invalidateOptionsMenu()
            if (targetMac != null) {
                appendLog("--- סריקה ממוקדת התחילה (רק $targetMac, בעקבות סריקת בר-קוד) ---")
            } else {
                appendLog("--- סריקה התחילה (ללא פילטר, מצב דיאגנוסטיקה) ---")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to start scan", e)
        }
    }

    private fun stopScanning() {
        stopScanningInternal()
        statusText.text = "נעצר"
        invalidateOptionsMenu()
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
    private val lastK7800pDumpMillis = mutableMapOf<String, Long>()

    // Battery isn't in the passive advertisement (see K7800pBatteryClient's
    // docstring) — it's fetched separately over an occasional GATT
    // connection and cached here so it survives across the ~1x/sec
    // passive-advertisement updates (which otherwise have no battery
    // value to report and would overwrite it back to null).
    private val k7800pBatteryPercent = mutableMapOf<String, Int>()
    private val lastK7800pBatteryReadMillis = mutableMapOf<String, Long>()
    private val k7800pBatteryClient by lazy { K7800pBatteryClient(this) }

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

        // K7800P (Jimi IoT) reverse-engineering aid: no public byte-level
        // protocol doc exists for this sensor (unlike KKM's KSensor). Dump
        // every AD structure's raw hex once every ~10s per device so we
        // can correlate against a known-good reading (e.g. from Jimi's
        // own app or a physical thermometer) and figure out the byte
        // offsets ourselves. Throttled — otherwise this floods the log
        // every scan cycle (~1x/second) for no added benefit.
        // K7800P (Jimi IoT) — now parsed for real (see K7800pParser.kt for
        // the reverse-engineered byte layout). Still also dump the raw
        // hex occasionally, since the door/light flag byte and the
        // per-device constant byte aren't decoded yet.
        if (deviceName?.startsWith("K7800P", ignoreCase = true) == true) {
            val jimiData = record?.getManufacturerSpecificData(K7800pParser.JIMI_MANUFACTURER_ID)
            val parsedK7800p = jimiData?.let { K7800pParser.parse(it) }
            if (parsedK7800p != null) {
                val reading = SensorReading(
                    mac = result.device.address,
                    name = deviceName,
                    rssi = result.rssi,
                    batteryMv = null,
                    batteryPercent = k7800pBatteryPercent[result.device.address],
                    temperatureC = parsedK7800p.temperatureC,
                    humidityPct = parsedK7800p.humidityPct,
                    accXmg = null,
                    accYmg = null,
                    accZmg = null,
                    doorOpen = if (parsedK7800p.doorSensorValid) parsedK7800p.doorOpen else null,
                    lightDetected = if (parsedK7800p.lightSensorValid) parsedK7800p.lightDetected else null,
                    lastUpdateMillis = System.currentTimeMillis()
                )
                runOnUiThread { adapter.upsert(reading) }
                maybeRefreshK7800pBattery(result.device.address)
            }

            // Unconditional (bypasses the throttle below): a door/light
            // ALARM is a one-shot event per Jimi's own TCP protocol docs
            // (0x33/0x34 alarm packets), not a continuously-maintained
            // flag — so it's more likely to show up as a brief STRUCTURAL
            // difference in the advertisement (extra AD entry, different
            // length) than as a value change in the bytes we already
            // decode. This check fires immediately on any such anomaly,
            // regardless of the 10-second sampling throttle, so a
            // several-hundred-ms alarm burst isn't missed between samples.
            val manufacturerEntryCount = record?.manufacturerSpecificData?.size() ?: 0
            val serviceUuidCount = record?.serviceUuids?.size ?: 0
            val serviceDataCount = record?.serviceData?.size ?: 0
            val isAnomalous = jimiData == null || jimiData.size != 17 ||
                manufacturerEntryCount != 1 || serviceUuidCount != 1 || serviceDataCount != 0
            if (isAnomalous) {
                appendLog("[K7800P ANOMALY!] ${result.device.address}  jimiDataSize=${jimiData?.size}  manufacturerEntries=$manufacturerEntryCount  serviceUuids=$serviceUuidCount  serviceData=$serviceDataCount")
                record?.manufacturerSpecificData?.let { msd ->
                    for (i in 0 until msd.size()) {
                        appendLog("  [ANOMALY] manufacturerData[companyId=0x${msd.keyAt(i).toString(16)}] = ${KSensorParser.toHex(msd.valueAt(i))}")
                    }
                }
                record?.serviceData?.forEach { (uuid, data) ->
                    appendLog("  [ANOMALY] serviceData[$uuid] = ${KSensorParser.toHex(data)}")
                }
            }

            val now = System.currentTimeMillis()
            val last = lastK7800pDumpMillis[result.device.address] ?: 0L
            if (now - last > 10_000) {
                lastK7800pDumpMillis[result.device.address] = now
                appendLog("[K7800P dump] ${result.device.address}  name=$deviceName  rssi=${result.rssi}  parsed=$parsedK7800p")
                record?.manufacturerSpecificData?.let { msd ->
                    for (i in 0 until msd.size()) {
                        val companyId = msd.keyAt(i)
                        val data = msd.valueAt(i)
                        appendLog("  manufacturerData[companyId=0x${companyId.toString(16)}] = ${KSensorParser.toHex(data)}")
                    }
                }
                record?.serviceData?.forEach { (uuid, data) ->
                    appendLog("  serviceData[$uuid] = ${KSensorParser.toHex(data)}")
                }
                record?.serviceUuids?.let { uuids ->
                    if (uuids.isNotEmpty()) appendLog("  serviceUUIDs = ${uuids.joinToString { it.uuid.toString() }}")
                }
            }
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
                        "batt%=$batteryPercent  acc=(${parsed.accX},${parsed.accY},${parsed.accZ})" +
                        "  alarm=${parsed.alarm}"
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
                    alarmCode = parsed.alarm,
                    lastUpdateMillis = System.currentTimeMillis()
                )
                runOnUiThread { adapter.upsert(reading) }
            }
        } catch (e: Exception) {
            appendLog("!!! EXCEPTION for ${result.device.address}: ${e.javaClass.simpleName}: ${e.message}")
            appendLog("!!! stack: " + (e.stackTrace.firstOrNull()?.toString() ?: "(none)"))
        }
    }

    /**
     * Battery on the K7800P only comes over an active GATT connection
     * (see [K7800pBatteryClient]) — never in the passive advertisement
     * this method's caller is already parsing every ~1s. So instead of
     * connecting every time, only kick off a fresh GATT read once every
     * [BATTERY_REFRESH_INTERVAL_MILLIS] per device; the cached value in
     * [k7800pBatteryPercent] carries the last known reading in between.
     */
    private fun maybeRefreshK7800pBattery(macAddress: String) {
        val now = System.currentTimeMillis()
        val last = lastK7800pBatteryReadMillis[macAddress] ?: 0L
        if (now - last < BATTERY_REFRESH_INTERVAL_MILLIS) return
        lastK7800pBatteryReadMillis[macAddress] = now
        if (!hasAllPermissions()) return // silently skip; the passive scan already required these permissions to be running at all
        k7800pBatteryClient.readBatteryOnce(macAddress, object : K7800pBatteryClient.Listener {
            override fun onBatteryPercent(mac: String, percent: Int) {
                k7800pBatteryPercent[mac] = percent
                appendLog("[K7800P battery] $mac -> $percent%")
                // No need to force-refresh the adapter here: the next
                // passive-advertisement update for this MAC (≈1s away)
                // will read the now-updated k7800pBatteryPercent map.
            }
            override fun onFailed(mac: String, reason: String) {
                appendLog("[K7800P battery] !!! $mac נכשל: $reason")
            }
        })
    }
}
