package com.aifieldcam.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.aifieldcam.app.data.ApiConfig
import com.aifieldcam.app.data.BackendDiscovery
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.data.VerificationStateStore
import com.aifieldcam.app.databinding.FragmentBasicConfigBinding

class BasicConfigFragment : Fragment() {

    private var _binding: FragmentBasicConfigBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentBasicConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.header.tvTitle.text = getString(com.aifieldcam.app.R.string.settings_basic)
        binding.header.btnBack.setOnClickListener {
            (parentFragment as? MeFragment)?.onChildBack()
        }
        binding.etApiUrl.setText(ApiConfig.getBaseUrl())
        binding.btnSaveApi.setOnClickListener { saveApiUrl() }
        binding.btnDiscoverApi.setOnClickListener { runAutoDiscover() }
    }

    override fun onStart() {
        super.onStart()
        binding.etApiUrl.setText(ApiConfig.getBaseUrl())
        ensureBackendThenPing()
    }

    private fun ensureBackendThenPing() {
        BackendDiscovery.ensureReachable { ok, msg ->
            if (_binding == null || !isAdded) return@ensureReachable
            if (ok) {
                binding.etApiUrl.setText(ApiConfig.getBaseUrl())
            }
            pingBackend(msg.takeIf { !ok })
        }
    }

    private fun pingBackend(offlineHint: String? = null) {
        session.pingBackend { ok, msg ->
            if (_binding == null || !isAdded) return@pingBackend
            val display = if (ok) msg else offlineHint ?: msg
            binding.tvHealth.text = display
            binding.tvHealth.setTextColor(
                resources.getColor(
                    if (ok) com.aifieldcam.app.R.color.primary else com.aifieldcam.app.R.color.on_surface_variant,
                    null,
                ),
            )
        }
    }

    private fun runAutoDiscover() {
        binding.btnDiscoverApi.isEnabled = false
        binding.tvHealth.text = getString(com.aifieldcam.app.R.string.settings_discovering)
        BackendDiscovery.discoverInBackground { found ->
            if (_binding == null || !isAdded) return@discoverInBackground
            binding.btnDiscoverApi.isEnabled = true
            if (found != null) {
                binding.etApiUrl.setText(found)
                Toast.makeText(requireContext(), "已发现后端：$found", Toast.LENGTH_LONG).show()
                pingBackend()
            } else {
                Toast.makeText(requireContext(), "未发现后端，请确认电脑已启动且同一 WiFi", Toast.LENGTH_LONG).show()
                pingBackend("未发现后端")
            }
        }
    }

    private fun saveApiUrl() {
        val raw = binding.etApiUrl.text?.toString().orEmpty()
        if (raw.isBlank()) {
            Toast.makeText(requireContext(), "请输入后端地址", Toast.LENGTH_SHORT).show()
            return
        }
        val (saved, changed) = ApiConfig.setBaseUrlResult(raw)
        binding.etApiUrl.setText(saved)
        if (changed) {
            session.onApiBaseUrlChanged { _, msg ->
                if (_binding != null && isAdded) {
                    VerificationStateStore.clear()
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    pingBackend()
                }
            }
        } else {
            pingBackend()
            Toast.makeText(requireContext(), "地址已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
