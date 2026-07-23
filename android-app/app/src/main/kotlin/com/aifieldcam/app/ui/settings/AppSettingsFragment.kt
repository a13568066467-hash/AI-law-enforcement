package com.aifieldcam.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.aifieldcam.app.R
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentAppSettingsBinding
import com.aifieldcam.app.databinding.ItemMeMenuRowBinding
import com.aifieldcam.app.platform.SystemDesktopLauncher
import com.aifieldcam.app.platform.UnboundDesktopEscapeCounter

/** 设置：人员信息 / 解绑 / 关于我们 */
class AppSettingsFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentAppSettingsBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }
    private val desktopEscape = UnboundDesktopEscapeCounter()

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
        if (session.isDeviceBound()) {
            desktopEscape.reset()
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.settings_unbind_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_unbind) { _, _ ->
                    session.releaseBind { ok, msg ->
                        if (!isAdded || _binding == null) return@releaseBind
                        if (ok) {
                            (parentFragment as? MeFragment)?.popToMeHub()
                            return@releaseBind
                        }
                        // Toast 在专机上已禁用；失败必须弹窗，否则像「点了没反应」
                        AlertDialog.Builder(requireContext())
                            .setMessage(msg.ifBlank { getString(R.string.settings_unbind_failed) })
                            .setPositiveButton(android.R.string.ok, null)
                            .setNeutralButton(R.string.settings_unbind_clear_local) { _, _ ->
                                session.clearBindLocal()
                                (parentFragment as? MeFragment)?.popToMeHub()
                            }
                            .show()
                    }
                }
                .show()
            return
        }
        // 未绑定：专机锁定维保出口 — 连续点 7 次「解绑」进系统桌面（无浮层，避免干扰维保手势）
        if (desktopEscape.onTap(unbound = true)) {
            SystemDesktopLauncher.open(requireContext().applicationContext)
        }
    }

    private fun refreshUnbindState() {
        val bound = session.isDeviceBound()
        if (bound) desktopEscape.reset()
        binding.rowUnbind.root.alpha = if (bound) 1f else 0.45f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
