package com.aifieldcam.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.aifieldcam.app.R
import com.aifieldcam.app.data.ApiClient
import com.aifieldcam.app.data.AuthConfig
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentMeBinding
import com.aifieldcam.app.databinding.ItemMeMenuRowBinding
import com.aifieldcam.app.platform.DeviceIdentity
import com.aifieldcam.app.ui.auth.FaceVerifyActivity
import com.aifieldcam.app.util.CameraPermissionHelper
import com.aifieldcam.app.util.FaceAvatarStore
import java.io.File

class MeFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentMeBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }
    /** null=未查询或网络不可达；true/false=云端绑定状态 */
    private var cloudDeviceBound: Boolean? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchFaceLogin() else {
            Toast.makeText(requireContext(), "需要相机权限进行人脸登录", Toast.LENGTH_LONG).show()
        }
    }

    private val faceVerifyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val jpeg = readFaceJpeg(result.data) ?: run {
            Toast.makeText(requireContext(), "人脸图片读取失败，请重试", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        binding.btnFaceLogin.isEnabled = false
        binding.btnFaceLogin.text = getString(R.string.me_face_login_loading)
        session.loginPatrolByFace(jpeg) { _, _ ->
            if (_binding == null || !isAdded) return@loginPatrolByFace
            binding.btnFaceLogin.text = getString(R.string.me_face_login)
            refreshProfile()
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
        binding.btnFaceLogin.setOnClickListener { startFaceLogin() }
        setupMenuRow(binding.rowSettings, getString(R.string.me_settings), R.drawable.ic_menu_settings) {
            navigateToChild(AppSettingsFragment())
        }

        childFragmentManager.addOnBackStackChangedListener {
            if (_binding == null) return@addOnBackStackChangedListener
            val showingChild = childFragmentManager.backStackEntryCount > 0
            binding.meHub.visibility = if (showingChild) View.GONE else View.VISIBLE
            binding.meChildContainer.visibility = if (showingChild) View.VISIBLE else View.GONE
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

    override fun onStart() {
        super.onStart()
        session.addStatusListener(this)
        refreshProfile()
        queryCloudBindStatus()
    }

    override fun onStop() {
        session.removeStatusListener(this)
        super.onStop()
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

    /** 注册/注销成功后回到「我的」主页并刷新资料 */
    fun finishRegistrationFlow() {
        popToMeHub()
        cloudDeviceBound = true
        refreshProfile()
        queryCloudBindStatus()
    }

    fun popToMeHub() {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        if (_binding == null) return
        binding.meHub.visibility = View.VISIBLE
        binding.meChildContainer.visibility = View.GONE
    }

    fun navigateToChild(fragment: Fragment) {
        binding.meHub.visibility = View.GONE
        binding.meChildContainer.visibility = View.VISIBLE
        childFragmentManager.beginTransaction()
            .replace(R.id.me_child_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun startFaceLogin() {
        if (!session.isPatrolRegisteredOnDevice()) {
            Toast.makeText(requireContext(), "请先完成首次人员注册", Toast.LENGTH_SHORT).show()
            navigateToChild(PersonnelInfoFragment.newInstance())
            return
        }
        if (CameraPermissionHelper.hasCamera(requireContext())) {
            launchFaceLogin()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun launchFaceLogin() {
        faceVerifyLauncher.launch(Intent(requireContext(), FaceVerifyActivity::class.java))
    }

    private fun readFaceJpeg(data: Intent?): ByteArray? {
        if (data == null) return null
        val path = data.getStringExtra(FaceVerifyActivity.EXTRA_FACE_PATH)
        if (!path.isNullOrEmpty()) {
            val file = File(path)
            if (file.exists() && file.length() > 0) return file.readBytes()
        }
        @Suppress("DEPRECATION")
        return data.getByteArrayExtra(FaceVerifyActivity.EXTRA_FACE_JPEG)
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
        val profile = session.getSavedOfficerProfile()
        val loggedIn = session.isLoggedIn()
        val registered = session.isPatrolRegisteredOnDevice()
        val auth = AuthConfig.load()
        val name = when {
            profile?.name?.isNotBlank() == true -> profile.name
            loggedIn && auth.officerName.isNotBlank() -> auth.officerName
            else -> getString(R.string.me_profile_guest)
        }
        binding.tvProfileName.text = name
        binding.tvAvatarLetter.text = name.firstOrNull()?.toString().orEmpty()
        FaceAvatarStore.bindTo(binding.ivFaceAvatar, binding.tvAvatarLetter)

        val phone = when {
            profile?.phone?.length == 11 -> profile.phone
            loggedIn && auth.officerPhone.length == 11 -> auth.officerPhone
            else -> "—"
        }
        binding.tvProfilePhone.text = phone
        binding.tvProfileBind.text = when {
            loggedIn -> getString(R.string.me_bind_status_bound)
            registered && cloudDeviceBound == true -> getString(R.string.me_registered_face_hint)
            registered && cloudDeviceBound == false -> getString(R.string.me_local_only_hint)
            registered -> getString(R.string.me_cloud_checking)
            else -> getString(R.string.me_bind_status_unbound)
        }

        val showFaceLogin = registered && !loggedIn
        binding.btnFaceLogin.visibility = if (showFaceLogin) View.VISIBLE else View.GONE
        binding.btnFaceLogin.isEnabled = showFaceLogin
        binding.btnFaceLogin.text = getString(R.string.me_face_login)
    }

    private fun queryCloudBindStatus() {
        val profile = session.getSavedOfficerProfile() ?: run {
            cloudDeviceBound = null
            return
        }
        if (!session.isPatrolRegisteredOnDevice()) {
            cloudDeviceBound = null
            return
        }
        ApiClient.fetchPatrolDeviceBound(
            DeviceIdentity.recorderId(requireContext()),
            profile.phone,
        ) { bound, _ ->
            if (_binding == null || !isAdded) return@fetchPatrolDeviceBound
            cloudDeviceBound = bound
            refreshProfile()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
