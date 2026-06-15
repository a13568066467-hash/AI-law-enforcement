package com.aifieldcam.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.aifieldcam.app.ble.BleConfig
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentSettingsBinding

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
        binding.tvApiUrl.text = BleConfig.API_BASE_URL
        refreshUi()

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

    private fun refreshUi() {
        binding.tvLoginStatus.text = session.getLoginSummary()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
