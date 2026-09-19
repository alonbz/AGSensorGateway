package com.agsense.ksensorgateway

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Full-screen camera preview that decodes any barcode/QR code it sees and
 * returns the raw scanned text to whoever started this activity, via
 * [EXTRA_RESULT] on RESULT_OK. Used by MainActivity to read a MAC address
 * off a sensor's printed label instead of picking it out of a full,
 * unfiltered BLE scan of everything nearby.
 *
 * Decoding is entirely on-device (ML Kit's Barcode Scanning API) — no
 * network call, nothing leaves the phone. Accepts ANY barcode format
 * (QR, Code128, Code39, EAN, etc.) since we don't control what format the
 * sensor manufacturer printed; MainActivity is the one that validates
 * whether the decoded text actually looks like a MAC address.
 *
 * Finishes itself (with RESULT_OK) the moment ONE barcode is successfully
 * decoded — this is a single-shot picker, not a continuous scanner.
 */
class BarcodeScanActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_barcode_scan)
        title = "סרוק בר-קוד"

        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
    }

    private fun startCamera() {
        val previewView: PreviewView = findViewById(R.id.cameraPreview)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val scanner = BarcodeScanning.getClient()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processFrame(imageProxy, scanner)
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "לא ניתן לפתוח את המצלמה: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || handled) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes -> onBarcodesDetected(barcodes) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun onBarcodesDetected(barcodes: List<Barcode>) {
        if (handled) return
        val value = barcodes.firstOrNull()?.rawValue ?: return
        handled = true
        runOnUiThread {
            setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_RESULT, value))
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        const val EXTRA_RESULT = "barcode_result"
    }
}
