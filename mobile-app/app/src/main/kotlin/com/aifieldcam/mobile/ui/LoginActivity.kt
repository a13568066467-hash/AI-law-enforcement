package com.aifieldcam.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aifieldcam.mobile.data.ApiClient
import com.aifieldcam.mobile.data.ApiConfig
import com.aifieldcam.mobile.data.SessionManager
import com.aifieldcam.mobile.databinding.ActivityLoginBinding
import com.aifieldcam.mobile.util.ImageBase64

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var pendingPhone = ""

    private val faceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val path = result.data?.getStringExtra(FaceCaptureActivity.EXTRA_IMAGE_PATH).orEmpty()
        val b64 = ImageBase64.fromFile(path) ?: run {
            showStatus("人脸照片无效")
            return@registerForActivityResult
        }
        showStatus("登录中…")
        binding.btnFaceLogin.isEnabled = false
        ApiClient.mobileLogin(pendingPhone, b64) { auth ->
            runOnUiThread {
                binding.btnFaceLogin.isEnabled = true
                if (!auth.ok) {
                    showStatus(auth.message)
                    return@runOnUiThread
                }
                SessionManager.saveSession(
                    token = auth.token,
                    phone = auth.phone,
                    name = auth.name,
                    employeeId = auth.employeeId,
                    department = auth.department,
                )
                goHome()
            }
        }
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchFaceCapture() else showStatus("需要相机权限进行人脸登录")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SessionManager.isLoggedIn()) {
            goHome()
            return
        }
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.etApiUrl.setText(ApiConfig.getBaseUrl())
        binding.btnSaveApi.setOnClickListener {
            val saved = ApiConfig.setBaseUrl(binding.etApiUrl.text?.toString().orEmpty())
            binding.etApiUrl.setText(saved)
            showStatus("已保存：$saved")
        }
        binding.btnFaceLogin.setOnClickListener { startFaceLogin() }
        binding.btnRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun startFaceLogin() {
        val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
        if (phone.length != 11) {
            showStatus("请输入 11 位手机号")
            return
        }
        pendingPhone = phone
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchFaceCapture()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchFaceCapture() {
        faceLauncher.launch(Intent(this, FaceCaptureActivity::class.java))
    }

    private fun showStatus(msg: String) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = msg
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
