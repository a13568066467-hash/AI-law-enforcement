package com.aifieldcam.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.aifieldcam.app.R
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentSecuritySettingsBinding
import com.aifieldcam.app.databinding.ItemMeMenuRowBinding

class SecuritySettingsFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentSecuritySettingsBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSecuritySettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.header.tvTitle.text = getString(R.string.settings_security)
        binding.header.btnBack.setOnClickListener {
            (parentFragment as? MeFragment)?.onChildBack()
        }
        setupMenuRow(binding.rowOffboard, getString(R.string.me_offboard), R.drawable.ic_menu_security) {
            runOffboard()
        }
        refreshOffboardState()
    }

    override fun onStart() {
        super.onStart()
        session.addStatusListener(this)
        refreshOffboardState()
    }

    override fun onStop() {
        session.removeStatusListener(this)
        super.onStop()
    }

    override fun onSessionChanged() {
        if (_binding == null || !isAdded) return
        refreshOffboardState()
    }

    private fun setupMenuRow(
        rowBinding: ItemMeMenuRowBinding,
        title: String,
        iconRes: Int,
        onClick: () -> Unit,
    ) {
        rowBinding.tvTitle.text = title
        rowBinding.ivIcon.setImageResource(iconRes)
        rowBinding.ivIcon.visibility = View.VISIBLE
        rowBinding.root.setOnClickListener { onClick() }
    }

    private fun refreshOffboardState() {
        if (_binding == null) return
        val enabled = session.isLoggedIn() || session.getSavedOfficerProfile() != null
        binding.rowOffboard.root.isEnabled = enabled
        binding.rowOffboard.root.alpha = if (enabled) 1f else 0.4f
    }

    private fun runOffboard() {
        binding.rowOffboard.root.isEnabled = false
        session.offboardOfficer { ok, _ ->
            if (_binding == null || !isAdded) return@offboardOfficer
            if (ok) {
                (parentFragment as? MeFragment)?.finishRegistrationFlow()
            } else {
                refreshOffboardState()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
