package com.aifieldcam.mobile.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aifieldcam.mobile.databinding.ActivityFaceCaptureBinding
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
    }

    private lateinit var binding: ActivityFaceCaptureBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var capturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
        binding.btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        binding.btnCapture.setOnClickListener { capture() }
        startCamera()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, imageCapture)
            } catch (_: Exception) {
                binding.tvHint.text = "相机启动失败"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        if (capturing) return
        val capture = imageCapture ?: return
        capturing = true
        binding.btnCapture.isEnabled = false
        val outFile = File(cacheDir, "face_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(outFile).build()
        capture.takePicture(
            options,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    runOnUiThread {
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(EXTRA_IMAGE_PATH, outFile.absolutePath),
                        )
                        finish()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        capturing = false
                        binding.btnCapture.isEnabled = true
                        binding.tvHint.text = "拍摄失败，请重试"
                    }
                }
            },
        )
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
