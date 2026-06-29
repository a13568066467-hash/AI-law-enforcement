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
        if (result.resultCode != android.app.Activity.RESULT_OK || profile == null || token.isEmpty()) {
            return@registerForActivityResult
        }
        val jpeg = result.data?.getByteArrayExtra(FaceVerifyActivity.EXTRA_FACE_JPEG) ?: return@registerForActivityResult
        session.loginPatrolOfficer(profile, token, jpeg) { ok, msg ->
            if (_binding == null || !isAdded) return@loginPatrolOfficer
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
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

    private fun launchFaceVerify() {
        faceVerifyLauncher.launch(Intent(requireContext(), FaceVerifyActivity::class.java))
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
