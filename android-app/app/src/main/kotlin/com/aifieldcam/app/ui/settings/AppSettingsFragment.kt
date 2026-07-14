package com.aifieldcam.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.aifieldcam.app.R
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentAppSettingsBinding
import com.aifieldcam.app.databinding.ItemMeMenuRowBinding

/** 设置：人员信息 / 解绑 / 关于我们 */
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
        binding.header.tvTitle.setTextColor(resources.getColor(R.color.home_text_primary, null))
        binding.header.btnBack.setOnClickListener {
            (parentFragment as? MeFragment)?.onChildBack()
        }

        setupMenuRow(binding.rowPersonnel, getString(R.string.me_personnel), R.drawable.ic_menu_register) {
            (parentFragment as? MeFragment)?.navigateToChild(PersonnelInfoFragment.newInstance())
        }
        setupMenuRow(binding.rowUnbind, getString(R.string.settings_unbind), R.drawable.ic_menu_security) {
            unbind()
        }
        setupMenuRow(binding.rowAbout, getString(R.string.settings_about), R.drawable.ic_menu_about) {
            openAbout()
        }
        refreshUnbindState()
    }

    override fun onStart() {
        super.onStart()
        session.addStatusListener(this)
        refreshUnbindState()
    }

    override fun onStop() {
        session.removeStatusListener(this)
        super.onStop()
    }

    override fun onSessionChanged() {
        if (_binding == null || !isAdded) return
        refreshUnbindState()
    }

    private fun setupMenuRow(
        rowBinding: ItemMeMenuRowBinding,
        title: String,
        iconRes: Int,
        onClick: () -> Unit,
    ) {
        rowBinding.tvTitle.text = title
        rowBinding.tvTitle.setTextColor(resources.getColor(R.color.home_text_primary, null))
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

    private fun unbind() {
        if (!session.isDeviceBound()) return
        session.releaseBind { ok, _ ->
            if (ok) (parentFragment as? MeFragment)?.popToMeHub()
        }
    }

    private fun refreshUnbindState() {
        val bound = session.isDeviceBound()
        binding.rowUnbind.root.alpha = if (bound) 1f else 0.45f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
