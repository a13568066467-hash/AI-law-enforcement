package com.aifieldcam.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.aifieldcam.app.data.ApiClient
import com.aifieldcam.app.data.OfficerProfile
import com.aifieldcam.app.data.OfficerProfileStore
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.data.VerificationStateStore
import com.aifieldcam.app.databinding.FragmentPersonnelInfoBinding
import com.aifieldcam.app.platform.DeviceIdentity
import com.aifieldcam.app.ui.auth.FaceVerifyActivity
import com.aifieldcam.app.util.CameraPermissionHelper
import java.io.File

class PersonnelInfoFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentPersonnelInfoBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }
    private var pendingProfile: OfficerProfile? = null
    private var pendingVerifyToken: String = ""

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchFaceVerify() else {
            Toast.makeText(requireContext(), "需要相机权限进行人脸验证", Toast.LENGTH_LONG).show()
        }
    }

    private val faceVerifyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val profile = pendingProfile
        val token = pendingVerifyToken
        pendingProfile = null
        pendingVerifyToken = ""

        if (result.resultCode != android.app.Activity.RESULT_OK) {
            return@registerForActivityResult
        }
        if (profile == null || token.isEmpty()) {
            toast("验证信息已丢失，请返回步骤2后重新人脸验证")
            refreshUi()
            return@registerForActivityResult
        }
        val jpeg = readFaceJpeg(result.data)
        if (jpeg == null) {
            toast("人脸图片读取失败，请重试")
            refreshUi()
            return@registerForActivityResult
        }

        binding.btnStep3Face.isEnabled = false
        binding.btnStep3Face.text = "正在登录…"
        session.loginPatrolOfficer(profile, token, jpeg) { _, _ ->
            if (_binding == null || !isAdded) return@loginPatrolOfficer
            refreshUi()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPersonnelInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restorePendingFaceAuth(savedInstanceState)
        binding.header.tvTitle.text = getString(com.aifieldcam.app.R.string.me_personnel)
        binding.header.btnBack.setOnClickListener {
            (parentFragment as? MeFragment)?.onChildBack()
        }

        loadProfileFields()
        refreshUi()

        binding.btnStep1.setOnClickListener { runStep1() }
        binding.btnSendSms.setOnClickListener { runSendSms() }
        binding.btnStep2.setOnClickListener { runStep2() }
        binding.btnStep3Face.setOnClickListener { runStep3() }
    }

    override fun onStart() {
        super.onStart()
        session.addStatusListener(this)
        refreshUi()
    }

    override fun onStop() {
        session.removeStatusListener(this)
        saveProfileDraft()
        super.onStop()
    }

    override fun onSessionChanged() {
        if (_binding == null || !isAdded) return
        refreshUi()
    }

    private fun runStep1() {
        saveProfileDraft()
        val profile = readProfileFromForm()
        if (profile.name.isBlank() || profile.employeeId.isBlank() || profile.department.isBlank()) {
            Toast.makeText(requireContext(), "请填写姓名、工号、部门", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnStep1.isEnabled = false
        ApiClient.verifyStep1Profile(profile) { ok, msg, sessionId ->
            if (_binding == null || !isAdded) return@verifyStep1Profile
            binding.btnStep1.isEnabled = true
            if (ok && sessionId.isNotEmpty()) {
                VerificationStateStore.markStep1(sessionId)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), msg.ifEmpty { "步骤1失败" }, Toast.LENGTH_LONG).show()
            }
            refreshUi()
        }
    }

    private fun runSendSms() {
        val state = VerificationStateStore.load()
        if (!state.step1Ok || state.sessionId.isEmpty()) {
            Toast.makeText(requireContext(), "请先完成步骤1", Toast.LENGTH_SHORT).show()
            return
        }
        val phone = binding.etPhone.text?.toString().orEmpty().trim()
        if (phone.length != 11) {
            Toast.makeText(requireContext(), "请填写11位手机号", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnSendSms.isEnabled = false
        ApiClient.sendSmsCode(state.sessionId, phone) { ok, msg, devCode ->
            if (_binding == null || !isAdded) return@sendSmsCode
            binding.btnSendSms.isEnabled = true
            if (ok) {
                if (devCode.isNotEmpty()) {
                    VerificationStateStore.saveDevCode(devCode)
                    binding.etSmsCode.setText(devCode)
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runStep2() {
        val state = VerificationStateStore.load()
        if (!state.step1Ok) {
            Toast.makeText(requireContext(), "请先完成步骤1", Toast.LENGTH_SHORT).show()
            return
        }
        val phone = binding.etPhone.text?.toString().orEmpty().trim()
        val code = binding.etSmsCode.text?.toString().orEmpty().trim()
        if (phone.length != 11) {
            Toast.makeText(requireContext(), "请填写11位手机号", Toast.LENGTH_SHORT).show()
            return
        }
        if (code.length < 4) {
            Toast.makeText(requireContext(), "请输入验证码", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnStep2.isEnabled = false
        ApiClient.verifySmsCode(state.sessionId, phone, code) { ok, msg, verifyToken ->
            if (_binding == null || !isAdded) return@verifySmsCode
            binding.btnStep2.isEnabled = true
            if (ok && verifyToken.isNotEmpty()) {
                VerificationStateStore.markStep2(verifyToken)
                saveProfileDraft()
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), msg.ifEmpty { "步骤2失败" }, Toast.LENGTH_LONG).show()
            }
            refreshUi()
        }
    }

    private fun runStep3() {
        val state = VerificationStateStore.load()
        if (!state.step2Ok || state.verifyToken.isEmpty()) {
            Toast.makeText(requireContext(), "请先完成步骤1和步骤2", Toast.LENGTH_LONG).show()
            return
        }
        val profile = readProfileFromForm()
        if (profile.phone.length != 11) {
            Toast.makeText(requireContext(), "请确认手机号已填写", Toast.LENGTH_SHORT).show()
            return
        }
        if (profile.name.isBlank() || profile.employeeId.isBlank() || profile.department.isBlank()) {
            Toast.makeText(requireContext(), "人员信息不完整", Toast.LENGTH_SHORT).show()
            return
        }
        pendingProfile = profile
        pendingVerifyToken = state.verifyToken
        if (CameraPermissionHelper.hasCamera(requireContext())) {
            launchFaceVerify()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingProfile?.let { profile ->
            outState.putString(KEY_PENDING_PHONE, profile.phone)
            outState.putString(KEY_PENDING_NAME, profile.name)
            outState.putString(KEY_PENDING_EMPLOYEE_ID, profile.employeeId)
            outState.putString(KEY_PENDING_DEPARTMENT, profile.department)
            outState.putString(KEY_PENDING_DEVICE_ID, profile.deviceId)
        }
        if (pendingVerifyToken.isNotEmpty()) {
            outState.putString(KEY_PENDING_VERIFY_TOKEN, pendingVerifyToken)
        }
    }

    private fun restorePendingFaceAuth(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        val phone = savedInstanceState.getString(KEY_PENDING_PHONE).orEmpty()
        if (phone.isEmpty()) return
        pendingProfile = OfficerProfile(
            phone = phone,
            name = savedInstanceState.getString(KEY_PENDING_NAME).orEmpty(),
            employeeId = savedInstanceState.getString(KEY_PENDING_EMPLOYEE_ID).orEmpty(),
            department = savedInstanceState.getString(KEY_PENDING_DEPARTMENT).orEmpty(),
            deviceId = savedInstanceState.getString(KEY_PENDING_DEVICE_ID)
                ?: DeviceIdentity.recorderId(requireContext()),
        )
        pendingVerifyToken = savedInstanceState.getString(KEY_PENDING_VERIFY_TOKEN).orEmpty()
    }

    private fun readFaceJpeg(data: Intent?): ByteArray? {
        if (data == null) return null
        val path = data.getStringExtra(FaceVerifyActivity.EXTRA_FACE_PATH)
        if (!path.isNullOrEmpty()) {
            val file = File(path)
            if (file.exists() && file.length() > 0) {
                return file.readBytes()
            }
        }
        @Suppress("DEPRECATION")
        return data.getByteArrayExtra(FaceVerifyActivity.EXTRA_FACE_JPEG)
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    private fun readProfileFromForm(): OfficerProfile {
        return OfficerProfile(
            phone = binding.etPhone.text?.toString().orEmpty().trim(),
            name = binding.etName.text?.toString().orEmpty().trim(),
            employeeId = binding.etEmployeeId.text?.toString().orEmpty().trim(),
            department = binding.etDepartment.text?.toString().orEmpty().trim(),
            deviceId = DeviceIdentity.recorderId(requireContext()),
        )
    }

    private fun loadProfileFields() {
        val profile = session.getSavedOfficerProfile()
        binding.etName.setText(profile?.name.orEmpty())
        binding.etEmployeeId.setText(profile?.employeeId.orEmpty())
        binding.etDepartment.setText(profile?.department.orEmpty())
        binding.etPhone.setText(profile?.phone.orEmpty())
        val devCode = VerificationStateStore.load().devCode
        if (devCode.isNotEmpty()) {
            binding.etSmsCode.setText(devCode)
        }
    }

    private fun saveProfileDraft() {
        if (_binding == null) return
        val phone = binding.etPhone.text?.toString().orEmpty().trim()
        if (phone.length == 11) {
            OfficerProfileStore.saveDraft(readProfileFromForm())
        }
    }

    private fun refreshUi() {
        val state = VerificationStateStore.load()
        binding.tvLoginStatus.text = session.getLoginSummary()
        binding.tvOfficerDetail.text = session.getOfficerDetailSummary().ifBlank { "未绑定" }
        binding.tvVerifyProgress.text = VerificationStateStore.stepSummary()

        val loggedIn = session.isLoggedIn()
        binding.btnStep1.isEnabled = !loggedIn && !state.step1Ok
        binding.btnSendSms.isEnabled = !loggedIn && state.step1Ok && !state.step2Ok
        binding.btnStep2.isEnabled = !loggedIn && state.step1Ok && !state.step2Ok
        binding.btnStep3Face.isEnabled = !loggedIn && state.step2Ok

        binding.etName.isEnabled = !loggedIn && !state.step1Ok
        binding.etEmployeeId.isEnabled = !loggedIn && !state.step1Ok
        binding.etDepartment.isEnabled = !loggedIn && !state.step1Ok
        binding.etPhone.isEnabled = !loggedIn && state.step1Ok && !state.step2Ok
        binding.etSmsCode.isEnabled = !loggedIn && state.step1Ok && !state.step2Ok

        binding.btnStep1.text = if (state.step1Ok) "步骤1已完成 ✓" else "验证人员信息"
        binding.btnStep2.text = if (state.step2Ok) "步骤2已完成 ✓" else "验证手机号"
        binding.btnStep3Face.text = if (loggedIn) "已登录" else "人脸验证并登录"
    }

    private fun launchFaceVerify() {
        faceVerifyLauncher.launch(Intent(requireContext(), FaceVerifyActivity::class.java))
    }

    companion object {
        private const val KEY_PENDING_PHONE = "pending_phone"
        private const val KEY_PENDING_NAME = "pending_name"
        private const val KEY_PENDING_EMPLOYEE_ID = "pending_employee_id"
        private const val KEY_PENDING_DEPARTMENT = "pending_department"
        private const val KEY_PENDING_DEVICE_ID = "pending_device_id"
        private const val KEY_PENDING_VERIFY_TOKEN = "pending_verify_token"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
