package com.aifieldcam.app.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aifieldcam.app.databinding.ActivityFaceVerifyBinding
import com.aifieldcam.app.util.FaceFingerprint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceVerifyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceVerifyBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var capturing = false

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.2f)
            .build()
        FaceDetection.getClient(options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceVerifyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        binding.btnCapture.setOnClickListener { captureFace() }
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
            } catch (e: Exception) {
                binding.tvHint.text = "相机启动失败"
                showHint(binding.tvHint.text.toString())
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureFace() {
        if (capturing) return
        val capture = imageCapture ?: return
        capturing = true
        binding.btnCapture.isEnabled = false
        binding.tvHint.visibility = View.GONE
        val photoFile = File(cacheDir, "face_verify_${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        capture.takePicture(
            output,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val jpeg = photoFile.readBytes()
                    validateFace(jpeg)
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        capturing = false
                        binding.btnCapture.isEnabled = true
                        showHint("拍照失败")
                    }
                }
            },
        )
    }

    private fun validateFace(jpeg: ByteArray) {
        val mirrored = FaceFingerprint.mirrorJpeg(jpeg)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(mirrored, 0, mirrored.size)
            ?: run {
                runOnUiThread {
                    capturing = false
                    binding.btnCapture.isEnabled = true
                    showHint("图像解析失败")
                }
                return
            }
        val image = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                runOnUiThread {
                    capturing = false
                    binding.btnCapture.isEnabled = true
                    when {
                        faces.isEmpty() -> showHint("未检测到人脸")
                        faces.size > 1 -> showHint("检测到多张人脸")
                        else -> {
                            val face = faces.first()
                            val box = face.boundingBox
                            val area = box.width().toFloat() * box.height()
                            val imageArea = bitmap.width.toFloat() * bitmap.height.toFloat()
                            if (area / imageArea < 0.08f) {
                                showHint("请靠近一些")
                            } else {
                                val cropped = FaceFingerprint.cropToFace(mirrored, box)
                                deliverResult(cropped)
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                runOnUiThread {
                    capturing = false
                    binding.btnCapture.isEnabled = true
                    showHint("检测失败")
                }
            }
    }

    private fun showHint(msg: String) {
        binding.tvHint.text = msg
        binding.tvHint.visibility = View.VISIBLE
    }

    private fun deliverResult(jpeg: ByteArray) {
        val outFile = File(cacheDir, "face_verify_result.jpg")
        outFile.writeBytes(jpeg)
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_FACE_PATH, outFile.absolutePath),
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceDetector.close()
    }

    companion object {
        const val EXTRA_FACE_PATH = "face_path"
        /** @deprecated 大图 Intent 传递会超限，请用 [EXTRA_FACE_PATH] */
        const val EXTRA_FACE_JPEG = "face_jpeg"
    }
}
