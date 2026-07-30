package com.aifieldcam.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aifieldcam.mobile.R
import com.aifieldcam.mobile.databinding.ActivityScanBinding
import com.aifieldcam.mobile.util.BindQrParser
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/** 全屏相机扫码，无提示文案 / 遮罩组件。 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private val handled = AtomicBoolean(false)
    private val mlkitScanner by lazy { BarcodeScanning.getClient() }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, R.string.scan_camera_denied, Toast.LENGTH_SHORT).show()
            finish()
        }
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
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                Log.e(TAG, "bind camera failed", e)
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
        mlkitScanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (handled.get()) return@addOnSuccessListener
                for (code in barcodes) {
                    val raw = code.rawValue?.trim().orEmpty()
                    if (raw.isEmpty()) continue
                    if (!handled.compareAndSet(false, true)) return@addOnSuccessListener
                    handleRaw(raw)
                    break
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun handleRaw(raw: String) {
        val payload = BindQrParser.parse(raw)
        if (payload == null) {
            handled.set(false)
            return
        }
        startActivity(
            Intent(this, BindConfirmActivity::class.java)
                .putExtra(BindConfirmActivity.EXTRA_DEVICE_ID, payload.deviceId)
                .putExtra(BindConfirmActivity.EXTRA_BIND_TOKEN, payload.token),
        )
        finish()
    }

    override fun onDestroy() {
        runCatching { mlkitScanner.close() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScanActivity"
    }
}
