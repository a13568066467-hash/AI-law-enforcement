package com.aifieldcam.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.aifieldcam.mobile.data.ApiClient
import com.aifieldcam.mobile.data.SessionManager
import com.aifieldcam.mobile.databinding.ActivityBindConfirmBinding

class BindConfirmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_BIND_TOKEN = "bind_token"
    }

    private lateinit var binding: ActivityBindConfirmBinding
    private lateinit var deviceId: String
    private lateinit var bindToken: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        bindToken = intent.getStringExtra(EXTRA_BIND_TOKEN).orEmpty()
        if (deviceId.isEmpty() || bindToken.isEmpty()) {
            finish()
            return
        }
        binding = ActivityBindConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvDeviceId.text = deviceId
        binding.tvMessage.text = "确认后将在此执法仪上建立当次执勤占用。"
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnConfirm.setOnClickListener { confirmBind() }
    }

    private fun confirmBind() {
        val mobileToken = SessionManager.getToken()
        if (mobileToken.isEmpty()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        binding.btnConfirm.isEnabled = false
        binding.tvMessage.text = "绑定中…"
        ApiClient.confirmBind(deviceId, bindToken, mobileToken) { result ->
            runOnUiThread {
                binding.btnConfirm.isEnabled = true
                if (result.ok) {
                    binding.tvMessage.text = result.message.ifEmpty { "绑定成功" }
                    binding.btnConfirm.visibility = View.GONE
                    binding.btnCancel.text = "完成"
                    binding.btnCancel.setOnClickListener { finish() }
                } else {
                    binding.tvMessage.text = result.message
                }
            }
        }
    }
}
