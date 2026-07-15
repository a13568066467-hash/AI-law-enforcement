package com.aifieldcam.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aifieldcam.mobile.databinding.ActivityScanBinding
import com.aifieldcam.mobile.util.BindQrParser
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private val handled = AtomicBoolean(false)
    private val scanner = BarcodeScanning.getClient()

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                scanFrame(proxy)
            }
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (_: Exception) {
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scanFrame(proxy: ImageProxy) {
        if (handled.get()) {
            proxy.close()
            return
        }
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (handled.get()) return@addOnSuccessListener
                for (code in barcodes) {
                    val raw = code.rawValue?.trim().orEmpty()
                    val payload = BindQrParser.parse(raw) ?: continue
                    if (handled.compareAndSet(false, true)) {
                        openConfirm(payload.deviceId, payload.token)
                    }
                    break
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun openConfirm(deviceId: String, token: String) {
        startActivity(
            Intent(this, BindConfirmActivity::class.java)
                .putExtra(BindConfirmActivity.EXTRA_DEVICE_ID, deviceId)
                .putExtra(BindConfirmActivity.EXTRA_BIND_TOKEN, token),
        )
        finish()
    }

    override fun onDestroy() {
        scanner.close()
        super.onDestroy()
    }
}
