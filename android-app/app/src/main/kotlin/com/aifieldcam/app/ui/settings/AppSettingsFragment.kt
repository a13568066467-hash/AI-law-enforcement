package com.aifieldcam.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.aifieldcam.app.data.ApiConfig
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.data.VerificationStateStore
import com.aifieldcam.app.databinding.FragmentAppSettingsBinding

class AppSettingsFragment : Fragment() {

    private var _binding: FragmentAppSettingsBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.header.tvTitle.text = getString(com.aifieldcam.app.R.string.me_settings)
        binding.header.btnBack.setOnClickListener {
            (parentFragment as? MeFragment)?.onChildBack()
        }

        binding.etApiUrl.setText(ApiConfig.getBaseUrl())
        binding.btnSaveApi.setOnClickListener { saveApiUrl() }

        binding.rowAbout.tvTitle.text = getString(com.aifieldcam.app.R.string.settings_about)
        binding.rowAbout.root.setOnClickListener { openAbout() }
        binding.rowPrivacy.tvTitle.text = getString(com.aifieldcam.app.R.string.settings_privacy)
        binding.rowPrivacy.root.setOnClickListener { openPrivacy() }
    }

    private fun openAbout() {
        (parentFragment as? MeFragment)?.navigateToChild(
            TextContentFragment.newInstance(
                getString(com.aifieldcam.app.R.string.settings_about),
                getString(com.aifieldcam.app.R.string.about_us_content),
            ),
        )
    }

    private fun openPrivacy() {
        (parentFragment as? MeFragment)?.navigateToChild(
            TextContentFragment.newInstance(
                getString(com.aifieldcam.app.R.string.settings_privacy),
                getString(com.aifieldcam.app.R.string.privacy_policy_content),
            ),
        )
    }

    override fun onStart() {
        super.onStart()
        binding.etApiUrl.setText(ApiConfig.getBaseUrl())
        pingBackend()
    }

    private fun pingBackend() {
        session.pingBackend { ok, msg ->
            if (_binding == null || !isAdded) return@pingBackend
            binding.tvHealth.text = msg
            binding.tvHealth.setTextColor(
                resources.getColor(
                    if (ok) com.aifieldcam.app.R.color.primary else com.aifieldcam.app.R.color.on_surface_variant,
                    null,
                ),
            )
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
