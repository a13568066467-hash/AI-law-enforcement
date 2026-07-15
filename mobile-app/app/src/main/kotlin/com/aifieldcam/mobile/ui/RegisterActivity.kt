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
import com.aifieldcam.mobile.data.SessionManager
import com.aifieldcam.mobile.databinding.ActivityRegisterBinding
import com.aifieldcam.mobile.util.ImageBase64

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    private val faceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val path = result.data?.getStringExtra(FaceCaptureActivity.EXTRA_IMAGE_PATH).orEmpty()
        submitRegister(path)
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            faceLauncher.launch(Intent(this, FaceCaptureActivity::class.java))
        } else {
            showStatus("需要相机权限采集人脸")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnSubmit.setOnClickListener { requestFaceCapture() }
    }

    private fun requestFaceCapture() {
        val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        val employeeId = binding.etEmployeeId.text?.toString()?.trim().orEmpty()
        val company = binding.etCompany.text?.toString()?.trim().orEmpty()
        val department = binding.etDepartment.text?.toString()?.trim().orEmpty()
        val position = binding.etPosition.text?.toString()?.trim().orEmpty()
        if (phone.length != 11 || name.length < 2 || employeeId.length != 6 ||
            company.isEmpty() || department.isEmpty() || position.isEmpty()
        ) {
            showStatus("请填写完整信息（工号 6 位数字）")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            faceLauncher.launch(Intent(this, FaceCaptureActivity::class.java))
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun submitRegister(facePath: String) {
        val b64 = ImageBase64.fromFile(facePath) ?: run {
            showStatus("人脸照片无效")
            return
        }
        binding.btnSubmit.isEnabled = false
        showStatus("注册中…")
        ApiClient.mobileRegister(
            phone = binding.etPhone.text?.toString()?.trim().orEmpty(),
            name = binding.etName.text?.toString()?.trim().orEmpty(),
            employeeId = binding.etEmployeeId.text?.toString()?.trim().orEmpty(),
            department = binding.etDepartment.text?.toString()?.trim().orEmpty(),
            company = binding.etCompany.text?.toString()?.trim().orEmpty(),
            position = binding.etPosition.text?.toString()?.trim().orEmpty(),
            idCard = binding.etIdCard.text?.toString()?.trim().orEmpty(),
            faceImageBase64 = b64,
        ) { auth ->
            runOnUiThread {
                binding.btnSubmit.isEnabled = true
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
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            }
        }
    }

    private fun showStatus(msg: String) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = msg
    }
}
