package com.aifieldcam.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.aifieldcam.app.ble.BleConfig
import com.aifieldcam.app.data.ApiConfig
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentSettingsBinding
import com.aifieldcam.app.platform.DeviceProfile

class SettingsFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.etApiUrl.setText(ApiConfig.getBaseUrl())
        refreshUi()

        binding.btnSaveApi.setOnClickListener { saveApiUrlAndPing() }
        binding.btnLogin.setOnClickListener {
            session.loginWorker(BleConfig.DEMO_PHONE, BleConfig.DEMO_PASSWORD) { ok, msg ->
                if (!isAdded || _binding == null) return@loginWorker
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                refreshUi()
            }
        }
        binding.btnLogout.setOnClickListener {
            session.logoutWorker()
            Toast.makeText(requireContext(), "已退出", Toast.LENGTH_SHORT).show()
            refreshUi()
        }
        binding.btnPing.setOnClickListener {
            binding.tvHealth.text = "检测中…"
            session.pingBackend { _, msg ->
                if (_binding == null || !isAdded) return@pingBackend
                binding.tvHealth.text = msg
            }
        }
    }

    override fun onStart() {
        super.onStart()
        session.addStatusListener(this)
        refreshUi()
        binding.etApiUrl.setText(ApiConfig.getBaseUrl())
        session.pingBackend { _, msg ->
            if (_binding != null && isAdded) binding.tvHealth.text = msg
        }
    }

    override fun onStop() {
        session.removeStatusListener(this)
        super.onStop()
    }

    override fun onSessionChanged() {
        if (_binding == null || !isAdded) return
        refreshUi()
    }

    private fun saveApiUrlAndPing() {
        val raw = binding.etApiUrl.text?.toString().orEmpty()
        if (raw.isBlank()) {
            Toast.makeText(requireContext(), "请输入后端地址", Toast.LENGTH_SHORT).show()
            return
        }
        val saved = ApiConfig.setBaseUrl(raw)
        binding.etApiUrl.setText(saved)
        binding.tvHealth.text = "检测中…"
        session.pingBackend { ok, msg ->
            if (_binding == null || !isAdded) return@pingBackend
            binding.tvHealth.text = msg
            val tip = if (ok) {
                "地址已保存，后端在线。若此前离线登录，请重新登录"
            } else {
                "地址已保存，但当前无法连接：$msg"
            }
            Toast.makeText(requireContext(), tip, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshUi() {
        binding.tvLoginStatus.text = session.getLoginSummary()
        binding.tvPlatform.text = DeviceProfile.settingsDetail()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
