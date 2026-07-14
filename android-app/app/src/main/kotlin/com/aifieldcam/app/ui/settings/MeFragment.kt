package com.aifieldcam.app.ui.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.aifieldcam.app.R
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentMeBinding
import com.aifieldcam.app.databinding.ItemMeMenuRowBinding
import com.aifieldcam.app.platform.DeviceIdentity
import com.aifieldcam.app.ui.VisibleTabFragment
import com.aifieldcam.app.util.FaceAvatarStore
import com.aifieldcam.app.util.QrCodeUtil
import java.util.concurrent.Executors

class MeFragment : VisibleTabFragment() {

    private var _binding: FragmentMeBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }
    private val pollHandler = Handler(Looper.getMainLooper())
    private val qrExecutor = Executors.newSingleThreadExecutor()
    private var pendingBindToken: String = ""
    private var polling = false

    override fun sessionManager(): SessionManager = session

    override fun onTabVisible() {
        refreshProfile()
    }

    override fun onTabHidden() {
        stopPolling()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (_binding == null || !isAdded || !polling || pendingBindToken.isEmpty()) return
            session.pollBindStatus(pendingBindToken) { result ->
                if (_binding == null || !isAdded) return@pollBindStatus
                when (result.status) {
                    "bound" -> {
                        stopPolling()
                        refreshProfile()
                    }
                    "expired" -> {
                        binding.tvQrHint.text = getString(R.string.me_qr_expired)
                        refreshBindToken()
                    }
                    "rejected" -> {
                        if (result.message.isNotBlank()) {
                            binding.tvQrHint.text = result.message
                        }
                        pollHandler.postDelayed(this, POLL_INTERVAL_MS)
                    }
                    else -> pollHandler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
    }

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

        binding.btnEditProfile.setOnClickListener {
            navigateToChild(PersonnelInfoFragment.newInstance())
        }
        binding.btnRefreshQr.setOnClickListener { refreshBindToken() }
        setupMenuRow(binding.rowSettings, getString(R.string.me_settings), R.drawable.ic_menu_settings) {
            navigateToChild(AppSettingsFragment())
        }

        childFragmentManager.addOnBackStackChangedListener {
            if (_binding == null) return@addOnBackStackChangedListener
            val showingChild = childFragmentManager.backStackEntryCount > 0
            binding.meHub.visibility = if (showingChild) View.GONE else View.VISIBLE
            binding.meChildContainer.visibility = if (showingChild) View.VISIBLE else View.GONE
            if (!showingChild) refreshProfile()
        }

        val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                onChildBack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        childFragmentManager.addOnBackStackChangedListener {
            backCallback.isEnabled = childFragmentManager.backStackEntryCount > 0
        }

        refreshProfile()
    }

    override fun onSessionChanged() {
        if (_binding == null || !isAdded) return
        refreshProfile()
    }

    fun onChildBack() {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
        }
    }

    fun finishRegistrationFlow() {
        popToMeHub()
        refreshProfile()
    }

    fun popToMeHub() {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        if (_binding == null) return
        binding.meHub.visibility = View.VISIBLE
        binding.meChildContainer.visibility = View.GONE
        refreshProfile()
    }

    fun navigateToChild(fragment: Fragment) {
        binding.meHub.visibility = View.GONE
        binding.meChildContainer.visibility = View.VISIBLE
        childFragmentManager.beginTransaction()
            .replace(R.id.me_child_container, fragment)
            .addToBackStack(null)
            .commit()
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

    private fun refreshProfile() {
        if (_binding == null) return
        val bound = session.isDeviceBound()
        binding.panelBound.visibility = if (bound) View.VISIBLE else View.GONE
        binding.panelQrUnbound.visibility = if (bound) View.GONE else View.VISIBLE
        binding.tvDeviceId.text = DeviceIdentity.recorderId(requireContext())

        if (bound) {
            stopPolling()
            pendingBindToken = ""
            val profile = session.getSavedOfficerProfile()
            val name = profile?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.me_profile_guest)
            binding.tvProfileName.text = name
            binding.tvAvatarLetter.text = name.firstOrNull()?.toString().orEmpty()
            FaceAvatarStore.bindTo(binding.ivFaceAvatar, binding.tvAvatarLetter)
            binding.tvProfilePhone.text = profile?.phone.takeUnless { it.isNullOrBlank() }
                ?: getString(R.string.me_profile_no_phone)
            binding.tvProfileBind.text = getString(R.string.me_bind_active)
        } else {
            if (pendingBindToken.isEmpty()) refreshBindToken() else startPolling()
        }
    }

    private fun refreshBindToken() {
        if (_binding == null || !isAdded) return
        binding.tvQrHint.text = getString(R.string.me_qr_loading)
        session.requestBindToken { ok, data, err ->
            if (_binding == null || !isAdded) return@requestBindToken
            if (!ok || data == null || data.token.isEmpty()) {
                val hint = when {
                    err.contains("公司") -> getString(R.string.me_qr_no_company)
                    else -> err.ifBlank { getString(R.string.me_qr_failed) }
                }
                binding.tvQrHint.text = hint
                return@requestBindToken
            }
            pendingBindToken = data.token
            val content = if (data.qrUrl.startsWith("http")) {
                data.qrUrl
            } else {
                "${com.aifieldcam.app.data.ApiConfig.getBaseUrl()}${data.qrUrl}"
            }
            qrExecutor.execute {
                val bmp = QrCodeUtil.encode(content)
                pollHandler.post {
                    if (_binding == null || !isAdded) return@post
                    if (bmp != null) {
                        binding.ivQrCode.setImageBitmap(bmp)
                    }
                    binding.tvQrHint.text = getString(R.string.me_qr_hint)
                    startPolling()
                }
            }
        }
    }

    private fun startPolling() {
        if (pendingBindToken.isEmpty()) return
        polling = true
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        polling = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    override fun onDestroyView() {
        stopPolling()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
