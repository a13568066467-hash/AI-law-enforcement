package com.aifieldcam.mobile.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.aifieldcam.mobile.data.SessionManager
import com.aifieldcam.mobile.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val user = SessionManager.getUser()
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvUserName.text = user.name
        binding.tvUserMeta.text = "工号 ${user.employeeId} · ${user.phone}"
        binding.btnScan.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        binding.btnLogout.setOnClickListener {
            SessionManager.clear()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
