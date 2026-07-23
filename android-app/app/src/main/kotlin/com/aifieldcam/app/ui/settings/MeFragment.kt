package com.aifieldcam.app.ui.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.aifieldcam.app.R
import com.aifieldcam.app.data.ApiConfig
import com.aifieldcam.app.data.BackendDiscovery
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentMeBinding
import com.aifieldcam.app.databinding.ItemMeMenuRowBinding
import com.aifieldcam.app.ui.VisibleTabFragment
import com.aifieldcam.app.ui.common.ThemisTopBar
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
    private var discoveringBackend = false
    private var consecutivePollFailures = 0

    override fun sessionManager(): SessionManager = session

    override fun onTabVisible() {
        refreshTopBar()
        refreshProfileContent()
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
                        consecutivePollFailures = 0
                        stopPolling()
                        refreshProfile()
                    }
                    "expired" -> {
                        consecutivePollFailures = 0
                        showQrStatus(getString(R.string.me_qr_expired))
                        refreshBindToken()
                    }
                    "rejected" -> {
                        if (result.message.isNotBlank()) {
                            showQrStatus(result.message)
                            if (isNetworkError(result.message)) {
                                consecutivePollFailures++
                                if (consecutivePollFailures >= 3 && !discoveringBackend) {
                                    showQrStatus(getString(R.string.me_qr_rediscovering))
                                    discoveringBackend = true
                                    BackendDiscovery.ensureReachable { reachable, _ ->
                                        discoveringBackend = false
                                        if (_binding == null || !isAdded) return@ensureReachable
                                        if (reachable) {
                                            consecutivePollFailures = 0
                                            doRequestBindToken()
                                        } else {
                                            showQrStatus(getString(R.string.me_qr_no_backend))
                                        }
                                    }
                                    return@pollBindStatus
                                }
                            }
                        } else {
                            consecutivePollFailures = 0
                        }
                        pollHandler.postDelayed(this, POLL_INTERVAL_MS)
                    }
                    else -> {
                        consecutivePollFailures = 0
                        pollHandler.postDelayed(this, POLL_INTERVAL_MS)
                    }
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
        binding.ivQrCode.setOnClickListener { refreshBindToken() }
        // 未绑定页无设置入口：长按二维码进入设置（维保解绑出口仍可用）
        binding.ivQrCode.setOnLongClickListener {
            navigateToChild(AppSettingsFragment())
            true
        }
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

        refreshTopBar()
        refreshProfileContent()
    }

    override fun onSessionChanged() {
        if (_binding == null || !isAdded) return
        refreshTopBar()
        refreshProfileContent()
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

    private fun refreshTopBar() {
        val currentBinding = _binding ?: return
        if (!isAdded) return
        ThemisTopBar.bind(
            session,
            currentBinding.themisTopBar.statusDot,
            currentBinding.themisTopBar.tvStatus,
            currentBinding.themisTopBar.tvBattery,
            requireContext(),
        )
    }

    private fun refreshProfile() {
        refreshTopBar()
        refreshProfileContent()
    }

    private fun refreshProfileContent() {
        if (_binding == null) return
        val bound = session.isDeviceBound()
        binding.panelBound.visibility = if (bound) View.VISIBLE else View.GONE
        binding.panelSettings.visibility = if (bound) View.VISIBLE else View.GONE
        binding.panelQrUnbound.visibility = if (bound) View.GONE else View.VISIBLE

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

    private fun showQrStatus(message: String?) {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) {
            binding.tvQrHint.visibility = View.GONE
            binding.tvQrHint.text = ""
        } else {
            binding.tvQrHint.text = text
            binding.tvQrHint.visibility = View.VISIBLE
        }
    }

    private fun refreshBindToken() {
        if (_binding == null || !isAdded) return
        showQrStatus(getString(R.string.me_qr_loading))
        if (discoveringBackend) return
        discoveringBackend = true
        BackendDiscovery.ensureReachable { reachable, _ ->
            discoveringBackend = false
            if (_binding == null || !isAdded) return@ensureReachable
            if (!reachable) {
                showQrStatus(getString(R.string.me_qr_no_backend))
                return@ensureReachable
            }
            doRequestBindToken()
        }
    }

    private fun doRequestBindToken() {
        if (_binding == null || !isAdded) return
        session.requestBindToken { ok, data, err ->
            if (_binding == null || !isAdded) return@requestBindToken
            if (!ok || data == null || data.token.isEmpty()) {
                val hint = when {
                    err.contains("公司") -> getString(R.string.me_qr_no_company)
                    err.contains("Method Not Allowed") || err.contains("405") ->
                        getString(R.string.me_qr_bad_backend, ApiConfig.getBaseUrl())
                    else -> err.ifBlank { getString(R.string.me_qr_failed) }
                }
                showQrStatus(hint)
                return@requestBindToken
            }
            pendingBindToken = data.token
            val content = if (data.qrUrl.startsWith("http")) {
                data.qrUrl
            } else {
                "${ApiConfig.getBaseUrl()}${data.qrUrl}"
            }
            qrExecutor.execute {
                val bmp = QrCodeUtil.encode(content)
                pollHandler.post {
                    if (_binding == null || !isAdded) return@post
                    if (bmp != null) {
                        binding.ivQrCode.setImageBitmap(bmp)
                        showQrStatus(null)
                        startPolling()
                    } else {
                        showQrStatus(getString(R.string.me_qr_failed))
                    }
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
        consecutivePollFailures = 0
        pollHandler.removeCallbacks(pollRunnable)
    }

    /** 判断是否为网络相关错误，需要触发后端重新发现 */
    private fun isNetworkError(msg: String): Boolean {
        if (msg.isBlank()) return false
        val m = msg.lowercase()
        return m.contains("网络错误") ||
            m.contains("unable to resolve") ||
            m.contains("failed to connect") ||
            m.contains("connection refused") ||
            m.contains("timeout") ||
            m.contains("method not allowed") ||
            m.contains("请确认手机与电脑同一 wifi")
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
