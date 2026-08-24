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

        // Standard Eddystone service UUID used by the KSensor advertisement.
        private val EDDYSTONE_SERVICE_UUID: UUID = UUID.fromString("0000FEAA-0000-1000-8000-00805F9B34FB")

        private const val REQUEST_PERMISSIONS_CODE = 100
    }

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var adapter: SensorAdapter

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var gattClient: GattClient? = null

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
        val textLog: TextView = findViewById(R.id.textLog)
        val scrollLog = textLog.parent as? android.widget.ScrollView

        gattClient = GattClient(this, object : GattClient.Listener {
            override fun onLog(msg: String) {
                Log.d(TAG, "GATT: $msg")
                runOnUiThread {
                    textLog.append(msg + "\n")
                    scrollLog?.post { scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
                }
            }
            override fun onStatus(msg: String) {
                runOnUiThread { textGattStatus.text = msg }
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
            gattClient?.connect(mac, editPassword.text.toString())
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
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
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

    private fun startScanning() {
        val scanner = bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, "בלוטות' לא זמין/כבוי במכשיר", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasAllPermissions()) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Filtering by the Eddystone service UUID cuts battery use; we still
        // double-check the frame type (0x21) after parsing.
        val filters = listOf(
            android.bluetooth.le.ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(EDDYSTONE_SERVICE_UUID))
                .build()
        )

        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            statusText.text = "סורק..."
            toggleButton.text = "עצור סריקה"
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

    private fun handleScanResult(result: ScanResult) {
        val serviceData = result.scanRecord
            ?.getServiceData(ParcelUuid(EDDYSTONE_SERVICE_UUID)) ?: return

        Log.d(TAG, "${result.device.address} raw service data: ${KSensorParser.toHex(serviceData)}")

        val parsed = KSensorParser.parse(serviceData) ?: return // not a KSensor frame, ignore

        val reading = SensorReading(
            mac = result.device.address,
            name = try {
                if (ActivityCompat.checkSelfPermission(
                        this, Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                ) result.device.name else null
            } catch (e: SecurityException) {
                null
            },
            rssi = result.rssi,
            batteryMv = parsed.voltageMv,
            temperatureC = parsed.temperatureC,
            humidityPct = parsed.humidityPct,
            lastUpdateMillis = System.currentTimeMillis()
        )

        runOnUiThread {
            adapter.upsert(reading)
        }
    }
}
