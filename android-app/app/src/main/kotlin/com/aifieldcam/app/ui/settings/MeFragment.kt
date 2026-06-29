package com.aifieldcam.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.aifieldcam.app.R
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentMeBinding
import com.aifieldcam.app.databinding.ItemMeMenuRowBinding

class MeFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentMeBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMenuRow(binding.rowPersonnel, getString(R.string.me_personnel)) {
            navigateToChild(PersonnelInfoFragment())
        }
        setupMenuRow(binding.rowSettings, getString(R.string.me_settings)) {
            navigateToChild(AppSettingsFragment())
        }
        setupMenuRow(binding.rowOffboard, getString(R.string.me_offboard)) {
            confirmOffboard()
        }

        binding.btnLogout.setOnClickListener {
            session.logoutWorker()
            Toast.makeText(requireContext(), "已退出", Toast.LENGTH_SHORT).show()
            refreshLogoutState()
        }

        childFragmentManager.addOnBackStackChangedListener {
            if (_binding == null) return@addOnBackStackChangedListener
            val showingChild = childFragmentManager.backStackEntryCount > 0
            binding.meHub.visibility = if (showingChild) View.GONE else View.VISIBLE
            binding.meChildContainer.visibility = if (showingChild) View.VISIBLE else View.GONE
        }

        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                onChildBack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        childFragmentManager.addOnBackStackChangedListener {
            backCallback.isEnabled = childFragmentManager.backStackEntryCount > 0
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

    fun onChildBack() {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
        }
    }

    fun navigateToChild(fragment: Fragment) {
        binding.meHub.visibility = View.GONE
        binding.meChildContainer.visibility = View.VISIBLE
        childFragmentManager.beginTransaction()
            .replace(R.id.me_child_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun setupMenuRow(rowBinding: ItemMeMenuRowBinding, title: String, onClick: () -> Unit) {
        rowBinding.tvTitle.text = title
        rowBinding.root.setOnClickListener { onClick() }
    }

    private fun refreshLogoutState() {
        val loggedIn = session.isLoggedIn()
        binding.btnLogout.isEnabled = loggedIn
        binding.btnLogout.alpha = if (loggedIn) 1f else 0.4f
        binding.rowOffboard.root.isEnabled = loggedIn || session.getSavedOfficerProfile() != null
        binding.rowOffboard.root.alpha =
            if (binding.rowOffboard.root.isEnabled) 1f else 0.4f
    }

    private fun confirmOffboard() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.me_offboard))
            .setMessage("确认注销本机绑定巡查员？云端将标记为离职，本机可绑定新人员。")
            .setPositiveButton("注销") { _, _ -> runOffboard() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runOffboard() {
        binding.rowOffboard.root.isEnabled = false
        session.offboardOfficer { ok, msg ->
            if (_binding == null || !isAdded) return@offboardOfficer
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            refreshLogoutState()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
