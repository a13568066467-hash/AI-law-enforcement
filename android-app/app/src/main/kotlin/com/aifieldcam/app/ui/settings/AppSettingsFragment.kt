package com.aifieldcam.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.aifieldcam.app.R
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentAppSettingsBinding
import com.aifieldcam.app.databinding.ItemMeMenuRowBinding

/** 设置页菜单（基础配置 / 安全 / 关于 / 隐私 / 注册 / 退出） */
class AppSettingsFragment : Fragment(), SessionManager.StatusListener {

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
        binding.header.tvTitle.text = getString(R.string.me_settings)
        binding.header.btnBack.setOnClickListener {
            (parentFragment as? MeFragment)?.onChildBack()
        }

        setupMenuRow(binding.rowBasic, getString(R.string.settings_basic), R.drawable.ic_menu_basic) {
            (parentFragment as? MeFragment)?.navigateToChild(BasicConfigFragment())
        }
        setupMenuRow(binding.rowSecurity, getString(R.string.settings_security), R.drawable.ic_menu_security) {
            (parentFragment as? MeFragment)?.navigateToChild(SecuritySettingsFragment())
        }
        setupMenuRow(binding.rowAbout, getString(R.string.settings_about), R.drawable.ic_menu_about) {
            openAbout()
        }
        setupMenuRow(binding.rowPrivacy, getString(R.string.settings_privacy), R.drawable.ic_menu_privacy) {
            openPrivacy()
        }
        setupMenuRow(binding.rowRegister, getString(R.string.me_register_account), R.drawable.ic_menu_register) {
            (parentFragment as? MeFragment)?.navigateToChild(PersonnelInfoFragment.newInstance())
        }

        binding.btnLogout.setOnClickListener {
            session.logoutWorker()
            Toast.makeText(requireContext(), "已退出", Toast.LENGTH_SHORT).show()
            refreshLogoutState()
            (parentFragment as? MeFragment)?.popToMeHub()
        }

        refreshLogoutState()
    }

    override fun onStart() {
        super.onStart()
        session.addStatusListener(this)
        refreshLogoutState()
    }

    override fun onStop() {
        session.removeStatusListener(this)
        super.onStop()
    }

    override fun onSessionChanged() {
        if (_binding == null || !isAdded) return
        refreshLogoutState()
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

    private fun openAbout() {
        (parentFragment as? MeFragment)?.navigateToChild(
            TextContentFragment.newInstance(
                getString(R.string.settings_about),
                getString(R.string.about_us_content),
            ),
        )
    }

    private fun openPrivacy() {
        (parentFragment as? MeFragment)?.navigateToChild(
            TextContentFragment.newInstance(
                getString(R.string.settings_privacy),
                getString(R.string.privacy_policy_content),
            ),
        )
    }

    private fun refreshLogoutState() {
        val loggedIn = session.isLoggedIn()
        binding.btnLogout.isEnabled = loggedIn
        binding.btnLogout.alpha = if (loggedIn) 1f else 0.4f
        binding.rowRegister.tvTitle.text = if (loggedIn) {
            getString(R.string.me_personnel)
        } else {
            getString(R.string.me_register_account)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
