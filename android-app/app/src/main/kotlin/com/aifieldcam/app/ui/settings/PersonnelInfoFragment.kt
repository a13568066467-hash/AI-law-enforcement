package com.aifieldcam.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.aifieldcam.app.R
import com.aifieldcam.app.util.AlbumStore
import com.aifieldcam.app.data.ApiClient
import com.aifieldcam.app.data.AuthConfig
import com.aifieldcam.app.data.OfficerProfile
import com.aifieldcam.app.data.OfficerProfileStore
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.data.VerificationStateStore
import com.aifieldcam.app.databinding.FragmentPersonnelInfoBinding
import com.aifieldcam.app.databinding.ItemProfileInfoRowBinding
import com.aifieldcam.app.platform.DeviceIdentity
import com.aifieldcam.app.ui.auth.FaceVerifyActivity
import com.aifieldcam.app.util.CameraPermissionHelper
import com.aifieldcam.app.util.ImageUtils
import com.aifieldcam.app.util.ProfileAvatarStore
import java.io.File

class PersonnelInfoFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentPersonnelInfoBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }

    private var currentStep = 0
    private var employeeId = ""
    private var pendingProfile: OfficerProfile? = null
    private var pendingVerifyToken: String = ""
    private var waitingRecorderPhoto = false

    private val stepPanels by lazy {
        listOf(
            binding.stepName,
            binding.stepEmployeeId,
            binding.stepIdCard,
            binding.stepPhone,
            binding.stepCompany,
            binding.stepDepartment,
            binding.stepPosition,
            binding.stepFace,
        )
    }

    private val stepTitles = listOf(
        "填写姓名",
        "确认工号",
        "身份证",
        "手机验证",
        "公司",
        "所属部门",
        "职位",
        "人脸验证",
    )

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchFaceVerify() else {
            Toast.makeText(requireContext(), "需要相机权限进行人脸验证", Toast.LENGTH_LONG).show()
        }
    }

    private val takeAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        if (bitmap == null) return@registerForActivityResult
        val file = File(requireContext().cacheDir, "avatar_capture.jpg")
        file.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
        }
        applyAvatarJpeg(file.readBytes())
    }

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { loadAvatarFromUri(it) } }

    private val faceVerifyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val profile = pendingProfile
        val token = pendingVerifyToken
        pendingProfile = null
        pendingVerifyToken = ""

        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        if (profile == null || token.isEmpty()) {
            toast("验证信息已丢失，请返回上一步后重试")
            showStep(currentStep)
            return@registerForActivityResult
        }
        val jpeg = readFaceJpeg(result.data) ?: run {
            toast("人脸图片读取失败，请重试")
            return@registerForActivityResult
        }
        binding.btnStep3Face.isEnabled = false
        binding.btnStep3Face.text = "正在注册…"
        session.loginPatrolOfficer(profile, token, jpeg) { ok, _ ->
            if (_binding == null || !isAdded) return@loginPatrolOfficer
            if (ok) {
                (parentFragment as? MeFragment)?.finishRegistrationFlow()
            } else {
                binding.btnStep3Face.isEnabled = true
                binding.btnStep3Face.text = "开始人脸验证"
                updateFaceVerifyButton()
            }
        }
    }

    private val recorderListener = object : AlbumStore.Listener {
        override fun onImageSaved(file: File, jpeg: ByteArray) {
            if (!waitingRecorderPhoto) return
            waitingRecorderPhoto = false
            AlbumStore.removeListener(this)
            if (_binding == null || !isAdded) return
            applyAvatarJpeg(jpeg)
            Toast.makeText(requireContext(), "已使用执法仪照片", Toast.LENGTH_SHORT).show()
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
        binding.header.tvTitle.text = getString(R.string.me_personnel)
        binding.header.btnBack.setOnClickListener {
            (parentFragment as? MeFragment)?.onChildBack()
        }

        binding.avatarContainer.setOnClickListener { showAvatarPicker() }
        binding.btnRefreshEmployeeId.setOnClickListener { fetchEmployeeId() }
        binding.btnSendSms.setOnClickListener { runSendSms() }
        binding.btnStep3Face.setOnClickListener { runStep3() }
        binding.btnPrev.setOnClickListener { goPrev() }
        binding.btnNext.setOnClickListener { goNext() }

        loadProfileFields()
        refreshUi()
        if (employeeId.isEmpty()) fetchEmployeeId()
    }

    override fun onStart() {
        super.onStart()
        session.addStatusListener(this)
        refreshUi()
    }

    override fun onStop() {
        session.removeStatusListener(this)
        AlbumStore.removeListener(recorderListener)
        saveProfileDraft()
        super.onStop()
    }

    override fun onSessionChanged() {
        if (_binding == null || !isAdded) return
        refreshUi()
    }

    private fun showAvatarPicker() {
        val options = arrayOf("从相册选择", "拍摄照片", "本机拍一张")
        AlertDialog.Builder(requireContext())
            .setTitle("设置头像")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickAvatarLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                    1 -> takeAvatarLauncher.launch(null)
                    2 -> pickFromRecorder()
                }
            }
            .show()
    }

    private fun pickFromRecorder() {
        val latest = AlbumStore.latestImageFile(requireContext())
        if (latest != null && latest.length() > 0) {
            applyAvatarJpeg(latest.readBytes())
            Toast.makeText(requireContext(), "已使用最近照片", Toast.LENGTH_SHORT).show()
            return
        }
        if (!session.triggerCapture()) {
            Toast.makeText(
                requireContext(),
                session.getLastActionError().ifBlank { "本机拍照失败" },
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        waitingRecorderPhoto = true
        AlbumStore.addListener(recorderListener)
        Toast.makeText(requireContext(), "已触发执法仪拍照，请稍候…", Toast.LENGTH_SHORT).show()
    }

    private fun loadAvatarFromUri(uri: Uri) {
        val result = ImageUtils.readJpegBytes(requireContext(), uri)
        if (result.bytes != null) {
            applyAvatarJpeg(result.bytes)
        } else {
            Toast.makeText(requireContext(), result.error ?: "无法读取照片", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyAvatarJpeg(jpeg: ByteArray) {
        ProfileAvatarStore.saveFromJpeg(jpeg)
        updateAvatarViews()
        Toast.makeText(requireContext(), "头像已更新", Toast.LENGTH_SHORT).show()
    }

    private fun updateAvatarViews() {
        val name = binding.etName.text?.toString().orEmpty().trim()
        ProfileAvatarStore.bindEmployeePhoto(
            binding.ivProfileAvatar,
            binding.tvAvatarLetter,
            name,
        )
        if (binding.panelProfile.visibility == View.VISIBLE) {
            bindProfilePhoto(name)
        }
    }

    private fun bindProfilePhoto(displayName: String) {
        ProfileAvatarStore.bindEmployeePhoto(
            binding.ivProfilePhoto,
            binding.tvProfileHeroLetter,
            displayName,
        )
    }

    private fun fetchEmployeeId() {
        binding.btnRefreshEmployeeId.isEnabled = false
        ApiClient.fetchNewEmployeeId { ok, idOrErr ->
            if (_binding == null || !isAdded) return@fetchNewEmployeeId
            binding.btnRefreshEmployeeId.isEnabled = true
            if (ok && idOrErr.matches(EMPLOYEE_ID_PATTERN)) {
                employeeId = idOrErr
                binding.tvEmployeeId.text = employeeId
            } else {
                Toast.makeText(
                    requireContext(),
                    idOrErr.ifEmpty { "工号生成失败，请检查后端连接" },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun goPrev() {
        if (currentStep > 0) showStep(currentStep - 1)
    }

    private fun goNext() {
        when (currentStep) {
            0 -> if (!validateName()) return else showStep(1)
            1 -> if (employeeId.isBlank()) {
                toast("请等待工号生成"); return
            } else showStep(2)
            2 -> submitStep1AfterIdCard()
            3 -> submitPhoneVerification()
            4 -> if (binding.etCompany.text.isNullOrBlank()) {
                toast("请填写公司"); return
            } else showStep(5)
            5 -> if (binding.etDepartment.text.isNullOrBlank()) {
                toast("请填写所属部门"); return
            } else showStep(6)
            6 -> submitOrgAndGoFace()
        }
    }

    private fun validateName(): Boolean {
        val name = binding.etName.text?.toString().orEmpty().trim()
        if (name.length < 2) {
            toast("姓名至少 2 个字")
            return false
        }
        ProfileAvatarStore.bindEmployeePhoto(binding.ivProfileAvatar, binding.tvAvatarLetter, name)
        return true
    }

    private fun submitStep1AfterIdCard() {
        val idCard = binding.etIdCard.text?.toString().orEmpty().trim().uppercase()
        if (idCard.length != 18) {
            toast("请输入18位身份证号")
            return
        }
        if (!idCard.matches(ID_CARD_PATTERN)) {
            toast("身份证号格式不正确")
            return
        }
        val eid = employeeId.ifBlank { binding.tvEmployeeId.text?.toString().orEmpty().trim() }
        if (!eid.matches(EMPLOYEE_ID_PATTERN)) {
            toast("工号无效，请返回上一步重新生成")
            fetchEmployeeId()
            return
        }
        employeeId = eid
        val profile = readProfileFromForm()
        binding.btnNext.isEnabled = false
        ApiClient.verifyStep1Profile(profile) { ok, msg, sessionId ->
            if (_binding == null || !isAdded) return@verifyStep1Profile
            binding.btnNext.isEnabled = true
            if (ok && sessionId.isNotEmpty()) {
                VerificationStateStore.markStep1(sessionId)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                showStep(3)
            } else {
                Toast.makeText(requireContext(), msg.ifEmpty { "提交失败" }, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runSendSms() {
        val state = VerificationStateStore.load()
        if (!state.step1Ok) {
            toast("请先完成前面步骤")
            return
        }
        val phone = binding.etPhone.text?.toString().orEmpty().trim()
        if (phone.length != 11) {
            toast("请填写11位手机号")
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

    private fun submitPhoneVerification() {
        val state = VerificationStateStore.load()
        if (!state.step1Ok) {
            toast("请先完成前面步骤")
            return
        }
        val phone = binding.etPhone.text?.toString().orEmpty().trim()
        val code = binding.etSmsCode.text?.toString().orEmpty().trim()
        if (phone.length != 11) {
            toast("请填写11位手机号")
            return
        }
        if (code.length < 4) {
            toast("请输入验证码")
            return
        }
        binding.btnNext.isEnabled = false
        ApiClient.verifySmsCode(state.sessionId, phone, code) { ok, msg, verifyToken ->
            if (_binding == null || !isAdded) return@verifySmsCode
            binding.btnNext.isEnabled = true
            if (ok && verifyToken.isNotEmpty()) {
                VerificationStateStore.markStep2(verifyToken)
                saveProfileDraft()
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                showStep(4)
                updateFaceVerifyButton()
            } else {
                Toast.makeText(requireContext(), msg.ifEmpty { "验证失败" }, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun submitOrgAndGoFace() {
        val position = binding.etPosition.text?.toString().orEmpty().trim()
        if (position.isBlank()) {
            toast("请填写职位")
            return
        }
        val state = VerificationStateStore.load()
        if (!state.step2Ok) {
            toast("请先完成手机验证")
            return
        }
        val company = binding.etCompany.text?.toString().orEmpty().trim()
        val department = binding.etDepartment.text?.toString().orEmpty().trim()
        binding.btnNext.isEnabled = false
        ApiClient.completeProfileOrg(state.sessionId, company, department, position) { ok, msg ->
            if (_binding == null || !isAdded) return@completeProfileOrg
            binding.btnNext.isEnabled = true
            if (ok) {
                VerificationStateStore.markOrgComplete()
                saveProfileDraft()
                showStep(7)
            } else {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runStep3() {
        val state = VerificationStateStore.load()
        if (!state.step2Ok || state.verifyToken.isEmpty()) {
            toast("请先完成手机验证")
            return
        }
        if (!state.orgOk && currentStep != stepPanels.lastIndex) {
            toast("请先完成组织信息（公司/部门/职位）")
            return
        }
        val profile = readProfileFromForm()
        val missing = profileMissingHint(profile)
        if (missing != null) {
            toast(missing)
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

    private fun profileMissingHint(profile: OfficerProfile): String? {
        return when {
            profile.name.length < 2 -> "请填写姓名"
            !profile.employeeId.matches(EMPLOYEE_ID_PATTERN) -> "工号无效"
            profile.idCard.length != 18 -> "请填写身份证号"
            profile.phone.length != 11 -> "请填写手机号"
            profile.company.isBlank() -> "请填写公司"
            profile.department.isBlank() -> "请填写所属部门"
            profile.position.isBlank() -> "请填写职位"
            else -> null
        }
    }

    private fun updateFaceVerifyButton() {
        if (_binding == null) return
        val state = VerificationStateStore.load()
        val ready = state.step2Ok &&
            state.verifyToken.isNotEmpty() &&
            (state.orgOk || currentStep == stepPanels.lastIndex)
        binding.btnStep3Face.isEnabled = ready
        binding.btnStep3Face.alpha = if (ready) 1f else 0.5f
        binding.btnStep3Face.text = "开始人脸验证"
    }

    private fun showStep(step: Int) {
        currentStep = step.coerceIn(0, stepPanels.lastIndex)
        stepPanels.forEachIndexed { index, panel ->
            panel.visibility = if (index == currentStep) View.VISIBLE else View.GONE
            if (index == currentStep) {
                panel.bringToFront()
            }
        }
        binding.tvStepIndicator.text = "步骤 ${currentStep + 1} / ${stepPanels.size}"
        binding.tvStepTitle.text = stepTitles[currentStep]
        binding.progressRegistration.max = stepPanels.size
        binding.progressRegistration.setProgressCompat(currentStep + 1, true)
        binding.btnPrev.visibility = if (currentStep > 0) View.VISIBLE else View.GONE
        binding.btnNext.visibility = if (currentStep < 7) View.VISIBLE else View.GONE
        updateFaceVerifyButton()
        binding.btnNext.text = when (currentStep) {
            2 -> "提交并继续"
            3 -> "验证手机号"
            6 -> "保存并继续"
            else -> "下一步"
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_STEP, currentStep)
        outState.putString(KEY_EMPLOYEE_ID, employeeId)
        pendingProfile?.let { profile ->
            outState.putString(KEY_PENDING_PHONE, profile.phone)
            outState.putString(KEY_PENDING_NAME, profile.name)
            outState.putString(KEY_PENDING_EMPLOYEE_ID, profile.employeeId)
            outState.putString(KEY_PENDING_DEPARTMENT, profile.department)
            outState.putString(KEY_PENDING_DEVICE_ID, profile.deviceId)
            outState.putString(KEY_PENDING_ID_CARD, profile.idCard)
            outState.putString(KEY_PENDING_COMPANY, profile.company)
            outState.putString(KEY_PENDING_POSITION, profile.position)
        }
        if (pendingVerifyToken.isNotEmpty()) {
            outState.putString(KEY_PENDING_VERIFY_TOKEN, pendingVerifyToken)
        }
    }

    private fun restorePendingFaceAuth(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        currentStep = savedInstanceState.getInt(KEY_CURRENT_STEP, 0)
        employeeId = savedInstanceState.getString(KEY_EMPLOYEE_ID).orEmpty()
        val phone = savedInstanceState.getString(KEY_PENDING_PHONE).orEmpty()
        if (phone.isEmpty()) return
        pendingProfile = OfficerProfile(
            phone = phone,
            name = savedInstanceState.getString(KEY_PENDING_NAME).orEmpty(),
            employeeId = savedInstanceState.getString(KEY_PENDING_EMPLOYEE_ID).orEmpty(),
            department = savedInstanceState.getString(KEY_PENDING_DEPARTMENT).orEmpty(),
            deviceId = savedInstanceState.getString(KEY_PENDING_DEVICE_ID)
                ?: DeviceIdentity.recorderId(requireContext()),
            idCard = savedInstanceState.getString(KEY_PENDING_ID_CARD).orEmpty(),
            company = savedInstanceState.getString(KEY_PENDING_COMPANY).orEmpty(),
            position = savedInstanceState.getString(KEY_PENDING_POSITION).orEmpty(),
        )
        pendingVerifyToken = savedInstanceState.getString(KEY_PENDING_VERIFY_TOKEN).orEmpty()
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

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    private fun readProfileFromForm(): OfficerProfile {
        val gender = when (binding.rgGender.checkedRadioButtonId) {
            R.id.rb_gender_female -> "女"
            else -> "男"
        }
        return OfficerProfile(
            phone = binding.etPhone.text?.toString().orEmpty().trim(),
            name = binding.etName.text?.toString().orEmpty().trim(),
            gender = gender,
            employeeId = employeeId.ifBlank {
                binding.tvEmployeeId.text?.toString().orEmpty().trim()
            },
            department = binding.etDepartment.text?.toString().orEmpty().trim(),
            deviceId = DeviceIdentity.recorderId(requireContext()),
            idCard = binding.etIdCard.text?.toString().orEmpty().trim().uppercase(),
            company = binding.etCompany.text?.toString().orEmpty().trim(),
            position = binding.etPosition.text?.toString().orEmpty().trim(),
        )
    }

    private fun loadProfileFields() {
        val profile = session.getSavedOfficerProfile()
        binding.etName.setText(profile?.name.orEmpty())
        if (profile?.gender == "女") {
            binding.rgGender.check(R.id.rb_gender_female)
        } else {
            binding.rgGender.check(R.id.rb_gender_male)
        }
        employeeId = profile?.employeeId.orEmpty()
        binding.tvEmployeeId.text = employeeId
        binding.etIdCard.setText(profile?.idCard.orEmpty())
        binding.etCompany.setText(profile?.company.orEmpty())
        binding.etDepartment.setText(profile?.department.orEmpty())
        binding.etPosition.setText(profile?.position.orEmpty())
        binding.etPhone.setText(profile?.phone.orEmpty())
        val devCode = VerificationStateStore.load().devCode
        if (devCode.isNotEmpty()) binding.etSmsCode.setText(devCode)
        ProfileAvatarStore.bindEmployeePhoto(
            binding.ivProfileAvatar,
            binding.tvAvatarLetter,
            profile?.name.orEmpty(),
        )
    }

    private fun saveProfileDraft() {
        if (_binding == null) return
        OfficerProfileStore.saveDraft(readProfileFromForm())
    }

    private fun refreshUi() {
        val loggedIn = session.isLoggedIn()
        val registeredLocally = session.isPatrolRegisteredOnDevice()
        val showRegistration = !registeredLocally && !loggedIn
        val showProfile = registeredLocally || loggedIn

        binding.panelRegistration.visibility = if (showRegistration) View.VISIBLE else View.GONE
        binding.panelProfile.visibility = if (showProfile && !showRegistration) View.VISIBLE else View.GONE
        binding.tvRegisteredHint.visibility =
            if (registeredLocally && !loggedIn) View.VISIBLE else View.GONE

        if (showRegistration) {
            showStep(currentStep)
        } else if (showProfile) {
            bindProfileSummary()
        }
    }

    private fun bindProfileSummary() {
        val loggedIn = session.isLoggedIn()
        val registered = session.isPatrolRegisteredOnDevice()
        val profile = session.getSavedOfficerProfile()
        val auth = AuthConfig.load()

        val name = profile?.name?.takeIf { it.isNotBlank() }
            ?: auth.officerName.takeIf { loggedIn && it.isNotBlank() }
            ?: getString(R.string.me_profile_guest)
        val employeeId = profile?.employeeId?.takeIf { it.isNotBlank() }.orEmpty()
        val phone = profile?.phone?.takeIf { it.length == 11 }
            ?: auth.officerPhone.takeIf { loggedIn && it.length == 11 }
        val deviceId = profile?.deviceId?.takeIf { it.isNotBlank() }
            ?: DeviceIdentity.recorderId(requireContext())

        binding.tvProfileHeroName.text = name
        binding.tvProfileHeroEmployeeId.text = if (employeeId.isNotBlank()) {
            getString(R.string.profile_employee_id_format, employeeId)
        } else {
            getString(R.string.profile_employee_id_format, "—")
        }

        val (statusText, statusBg) = when {
            loggedIn -> getString(R.string.profile_status_verified) to R.drawable.bg_status_chip_success
            registered -> getString(R.string.profile_status_pending_face) to R.drawable.bg_status_chip_warn
            else -> getString(R.string.profile_status_empty) to R.drawable.bg_status_chip_warn
        }
        binding.tvProfileStatusChip.text = statusText
        binding.tvProfileStatusChip.setBackgroundResource(statusBg)
        binding.tvProfileHeroEmployeeId.setBackgroundResource(
            if (employeeId.isNotBlank()) R.drawable.bg_status_chip_success else R.drawable.bg_status_chip_warn,
        )

        bindProfilePhoto(name)
        bindProfileRow(binding.rowPhone, getString(R.string.profile_label_phone), phone)
        bindProfileRow(binding.rowGender, getString(R.string.profile_label_gender), profile?.gender)
        bindProfileRow(
            binding.rowIdCard,
            getString(R.string.profile_label_id_card),
            profile?.idCard?.let { maskIdCard(it) },
        )
        bindProfileRow(binding.rowCompany, getString(R.string.profile_label_company), profile?.company)
        bindProfileRow(
            binding.rowDepartment,
            getString(R.string.profile_label_department),
            profile?.department,
        )
        bindProfileRow(binding.rowPosition, getString(R.string.profile_label_position), profile?.position)
        bindProfileRow(binding.rowDevice, getString(R.string.profile_label_device), deviceId)
    }

    private fun bindProfileRow(row: ItemProfileInfoRowBinding, label: String, value: String?) {
        row.tvLabel.text = label
        if (value.isNullOrBlank()) {
            row.root.visibility = View.GONE
        } else {
            row.root.visibility = View.VISIBLE
            row.tvValue.text = value
        }
    }

    private fun maskIdCard(idCard: String): String {
        if (idCard.length < 8) return idCard
        return idCard.take(4) + "**********" + idCard.takeLast(4)
    }

    private fun launchFaceVerify() {
        faceVerifyLauncher.launch(Intent(requireContext(), FaceVerifyActivity::class.java))
    }

    companion object {
        fun newInstance(): PersonnelInfoFragment = PersonnelInfoFragment()

        private val EMPLOYEE_ID_PATTERN = Regex("^\\d{6}$")
        private val ID_CARD_PATTERN = Regex(
            "^[1-9]\\d{5}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]$",
        )

        private const val KEY_CURRENT_STEP = "current_step"
        private const val KEY_EMPLOYEE_ID = "draft_employee_id"
        private const val KEY_PENDING_PHONE = "pending_phone"
        private const val KEY_PENDING_NAME = "pending_name"
        private const val KEY_PENDING_EMPLOYEE_ID = "pending_employee_id"
        private const val KEY_PENDING_DEPARTMENT = "pending_department"
        private const val KEY_PENDING_DEVICE_ID = "pending_device_id"
        private const val KEY_PENDING_ID_CARD = "pending_id_card"
        private const val KEY_PENDING_COMPANY = "pending_company"
        private const val KEY_PENDING_POSITION = "pending_position"
        private const val KEY_PENDING_VERIFY_TOKEN = "pending_verify_token"
    }

    override fun onDestroyView() {
        AlbumStore.removeListener(recorderListener)
        super.onDestroyView()
        _binding = null
    }
}
