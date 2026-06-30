package com.aifieldcam.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.aifieldcam.app.data.AppConfig
import com.aifieldcam.app.data.AuthConfig
import com.aifieldcam.app.data.DeviceCmd
import com.aifieldcam.app.demo.DemoScenarios
import com.aifieldcam.app.platform.DeviceProfile
import com.aifieldcam.app.platform.NativeRecorder
import com.aifieldcam.app.platform.NightVisionController
import com.aifieldcam.app.platform.Ze69Hardware
import com.aifieldcam.app.util.CameraPermissionHelper
import com.aifieldcam.app.util.GallerySaver
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 页面统一入口（Session / 本机执法仪 / 云端 API）
 */
class SessionManager private constructor(context: Context) {

    data class AlbumItem(
        val id: String,
        val file: File,
        val size: Int,
        var explanation: String,
        val createdAt: Long,
    )

    data class VideoItem(
        val id: String,
        val startedAt: Long,
        var stoppedAt: Long,
        var durationMs: Long,
        var note: String,
        var file: File? = null,
    )

    interface StatusListener {
        fun onSessionChanged()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var workerToken = ""
    private var sessionId = ""
    private var officerName = ""
    private var officerPhone = ""
    private var officerDepartment = ""
    private var officerEmployeeId = ""
    private var officerDeviceId = ""
    private var activeRecordId = ""
    private var nativeRecordStartedAt = 0L

    private val albumItems = CopyOnWriteArrayList<AlbumItem>()
    private val videoItems = CopyOnWriteArrayList<VideoItem>()
    private val statusListeners = CopyOnWriteArrayList<StatusListener>()

    init {
        restoreAuth()
    }

    fun addStatusListener(listener: StatusListener) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: StatusListener) {
        statusListeners.remove(listener)
    }

    fun isLoggedIn(): Boolean = workerToken.isNotEmpty()

    fun getAlbumItems(): List<AlbumItem> = albumItems.toList()

    fun getVideoItems(): List<VideoItem> = videoItems.toList()

    fun getBleSummary(): String = getRecorderSummary()

    fun isNativeRecorderMode(): Boolean = DeviceProfile.isDsjZecn6a1

    fun isRecording(): Boolean = NativeRecorder.isRecording()

    fun getRecorderSummary(): String = when {
        isRecording() -> "本机录像中 · ${DeviceProfile.MODEL_NAME}"
        DeviceProfile.isDsjZecn6a1 -> "本机就绪 · ${DeviceProfile.summaryLine()}"
        else -> "开发模式 · 请使用执法仪本机"
    }

    fun getLoginSummary(): String {
        if (!isLoggedIn()) return "未认证"
        val who = if (officerName.isNotBlank()) officerName else "巡查员"
        val phoneTail = if (officerPhone.length >= 4) officerPhone.takeLast(4) else officerPhone
        return "已认证：$who · 尾号$phoneTail"
    }

    fun getOfficerDetailSummary(): String {
        if (!isLoggedIn()) return ""
        return buildString {
            append("工号 $officerEmployeeId · $officerDepartment\n")
            append("专属执法仪：${officerDeviceId.ifEmpty { com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext) }}")
        }
    }

    /** 人员信息页展示用（含公司/职位/身份证脱敏） */
    fun getProfileDisplaySummary(): String {
        val profile = OfficerProfileStore.load()
        val name = profile?.name?.takeIf { it.isNotBlank() } ?: officerName
        val phone = profile?.phone?.takeIf { it.length == 11 } ?: officerPhone
        val employeeId = profile?.employeeId?.takeIf { it.isNotBlank() } ?: officerEmployeeId
        if (name.isBlank() && phone.isBlank() && employeeId.isBlank()) return ""
        return buildString {
            if (name.isNotBlank()) appendLine("姓名：$name")
            if (employeeId.isNotBlank()) appendLine("工号：$employeeId")
            if (phone.isNotBlank()) appendLine("手机：$phone")
            profile?.idCard?.takeIf { it.isNotEmpty() }?.let {
                appendLine("身份证：${maskIdCard(it)}")
            }
            profile?.company?.takeIf { it.isNotEmpty() }?.let { appendLine("公司：$it") }
            val dept = profile?.department?.takeIf { it.isNotBlank() } ?: officerDepartment
            if (dept.isNotBlank()) appendLine("部门：$dept")
            profile?.position?.takeIf { it.isNotEmpty() }?.let { appendLine("职位：$it") }
            val dev = profile?.deviceId?.takeIf { it.isNotBlank() }
                ?: officerDeviceId.ifEmpty { com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext) }
            append("执法仪：$dev")
        }
    }

    private fun maskIdCard(idCard: String): String {
        if (idCard.length < 8) return idCard
        return idCard.take(4) + "**********" + idCard.takeLast(4)
    }

    fun getSavedOfficerProfile(): OfficerProfile? = OfficerProfileStore.load()

    fun isPatrolRegisteredOnDevice(): Boolean = OfficerProfileStore.isRegisteredLocally()

    fun loginWorker(phone: String, password: String, onDone: (Boolean, String) -> Unit) {
        ApiClient.login(phone, password) { ok, token, err ->
            mainHandler.post {
                if (ok) {
                    applyLogin(token)
                    onDone(true, loginSuccessMessage(err))
                } else {
                    onDone(false, err)
                }
            }
        }
    }

    fun loginPatrolOfficer(
        profile: OfficerProfile,
        verifyToken: String,
        faceJpeg: ByteArray,
        onDone: (Boolean, String) -> Unit,
    ) {
        if (!profile.isCompleteForRegister()) {
            onDone(false, "请填写完整个人信息与11位手机号")
            return
        }
        if (verifyToken.isEmpty()) {
            onDone(false, "请先完成步骤1和步骤2验证")
            return
        }
        if (verifyToken.startsWith("offline-")) {
            onDone(false, "请连接后端完成人员注册")
            return
        }
        val deviceId = com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        if (profile.deviceId != deviceId) {
            onDone(false, "设备 ID 异常")
            return
        }
        OfficerProfileStore.saveDraft(profile)
        ApiClient.patrolAuthenticate(verifyToken, deviceId, profile, faceJpeg) { ok, result, err ->
            mainHandler.post {
                applyPatrolAuthResult(ok, result, err, faceJpeg, profile, onDone)
            }
        }
    }

    /** 已注册设备：后续登录仅扫脸，必须联网与云端库比对 */
    fun loginPatrolByFace(faceJpeg: ByteArray, onDone: (Boolean, String) -> Unit) {
        if (!isPatrolRegisteredOnDevice()) {
            onDone(false, "本机未完成注册，请先完成人员信息绑定")
            return
        }
        val deviceId = com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        ApiClient.patrolFaceOnlyLogin(deviceId, faceJpeg) { ok, result, err ->
            mainHandler.post {
                val profile = OfficerProfileStore.load()
                applyPatrolAuthResult(ok, result, err, faceJpeg, profile, onDone)
            }
        }
    }

    private fun applyPatrolAuthResult(
        ok: Boolean,
        result: ApiClient.PatrolAuthResult?,
        err: String,
        faceJpeg: ByteArray,
        profile: OfficerProfile?,
        onDone: (Boolean, String) -> Unit,
    ) {
        if (!ok || result == null || result.token.isEmpty() || ApiClient.isMockPatrolToken(result.token)) {
            val failMsg = when {
                result != null && ApiClient.isMockPatrolToken(result.token) ->
                    "需要联网完成认证，请检查后端连接"
                err.isNotEmpty() -> err
                else -> "认证失败"
            }
            showToast(failMsg)
            onDone(false, failMsg)
            return
        }
        val fingerprint = com.aifieldcam.app.util.FaceFingerprint.fromJpeg(faceJpeg)
        val draft = profile ?: OfficerProfileStore.load()
        val toSave = OfficerProfile(
            phone = result.phone,
            name = result.name,
            employeeId = result.employeeId,
            department = result.department,
            deviceId = result.deviceId,
            idCard = draft?.idCard.orEmpty(),
            company = draft?.company.orEmpty(),
            position = draft?.position.orEmpty(),
        )
        OfficerProfileStore.saveRegistered(toSave, fingerprint)
        com.aifieldcam.app.util.FaceAvatarStore.saveFromJpeg(faceJpeg)
        officerName = result.name
        officerPhone = result.phone
        officerEmployeeId = result.employeeId
        officerDepartment = result.department
        officerDeviceId = result.deviceId
        applyLogin(result.token)
        VerificationStateStore.markLoginComplete()
        val successMsg = patrolLoginSuccessMessage(result.message)
        showToast(successMsg)
        onDone(true, successMsg)
    }

    /** 修改后端地址后清除登录态；需重新人脸验证 */
    fun onApiBaseUrlChanged(onDone: ((Boolean, String) -> Unit)? = null) {
        clearAuthState()
        mainHandler.post {
            notifyStatus()
            onDone?.invoke(false, "地址已保存，请重新进行人脸验证登录")
        }
    }

    fun logoutWorker() {
        clearSessionOnly()
    }

    fun offboardOfficer(onDone: (Boolean, String) -> Unit) {
        val deviceId = com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        val token = workerToken
        val clearLocal = {
            clearAuthState()
            OfficerProfileStore.clear()
            com.aifieldcam.app.util.FaceAvatarStore.delete()
        }
        if (!isPatrolRegisteredOnDevice() && token.isEmpty()) {
            onDone(false, "本机无绑定人员")
            return
        }
        ApiClient.offboardPatrolOfficer(token, deviceId) { ok, msg ->
            mainHandler.post {
                if (ok) {
                    clearLocal()
                    onDone(true, msg.ifEmpty { "已注销" })
                    notifyStatus()
                    return@post
                }
                if (msg.contains("未绑定") && isPatrolRegisteredOnDevice()) {
                    tryClearOrphanLocalProfile(deviceId, msg, clearLocal, onDone)
                    return@post
                }
                onDone(false, msg.ifEmpty { "注销失败，请检查后端连接" })
                notifyStatus()
            }
        }
    }

    /** 云端确认本设备无绑定时，清除仅存在于本机的孤儿档案（仍需联网校验） */
    private fun tryClearOrphanLocalProfile(
        deviceId: String,
        originalMsg: String,
        clearLocal: () -> Unit,
        onDone: (Boolean, String) -> Unit,
    ) {
        val phone = OfficerProfileStore.load()?.phone.orEmpty()
        if (phone.length != 11) {
            onDone(false, originalMsg)
            notifyStatus()
            return
        }
        ApiClient.fetchPatrolDeviceBound(deviceId, phone) { bound, _ ->
            mainHandler.post {
                if (bound == false) {
                    clearLocal()
                    onDone(true, "云端无绑定记录，本机档案已清除")
                } else {
                    onDone(false, originalMsg)
                }
                notifyStatus()
            }
        }
    }

    fun pingBackend(onDone: (Boolean, String) -> Unit) {
        ApiClient.pingHealth { ok, msg ->
            mainHandler.post { onDone(ok, msg) }
        }
    }

    fun runDemoScenario(scenarioId: String, onDone: (DemoScenarios.SceneResult?, String) -> Unit) {
        if (!isLoggedIn()) {
            onDone(null, "请先完成巡查员人脸认证")
            return
        }
        val deviceId = officerDeviceId.ifEmpty {
            com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        }
        ApiClient.runDemoScenario(workerToken, scenarioId, deviceId) { ok, result, err ->
            mainHandler.post {
                if (!ok || result == null) {
                    val local = DemoScenarios.run(scenarioId, deviceId)
                    if (local.scenarioId.isNotEmpty()) {
                        applyDeviceCmds(local.bleCmds)
                        onDone(local, "")
                    } else {
                        onDone(null, err)
                    }
                    return@post
                }
                applyDeviceCmds(result.bleCmds)
                onDone(result, "")
            }
        }
    }

    fun sendChatText(
        text: String,
        onDone: (reply: String, err: String, demo: DemoScenarios.SceneResult?) -> Unit,
    ) {
        if (!isLoggedIn()) {
            onDone("", "请先完成巡查员人脸认证", null)
            return
        }
        val deviceState = DeviceCmd.currentFsmState(isRecording())
        if (deviceState == DeviceCmd.FSM_RECORD &&
            (text.contains("识别") || text.contains("拍照"))
        ) {
            onDone("", "录像中请先停止录像", null)
            return
        }
        val devId = officerDeviceId.ifEmpty {
            com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        }
        ApiClient.postChat(workerToken, sessionId, devId, text, deviceState) { ok, body, err ->
            mainHandler.post {
                if (!ok || body == null) {
                    if (err == ApiClient.ERR_AUTH_EXPIRED) {
                        reloginAndRetry(
                            onSuccess = {
                                sendChatText(text, onDone)
                            },
                            onFail = { failMsg ->
                                onDone("", failMsg, null)
                            },
                        )
                    } else {
                        onDone("", err, null)
                    }
                    return@post
                }
                applyDeviceCmds(body.bleCmds)
                onDone(body.reply, "", body.demo)
            }
        }
    }

    fun startRecord(): Boolean {
        lastErrorLocal = ""
        if (!DeviceProfile.isDsjZecn6a1) {
            lastErrorLocal = "请在执法仪本机使用"
            return false
        }
        return startNativeRecord()
    }

    fun stopRecord(): Boolean {
        lastErrorLocal = ""
        if (!DeviceProfile.isDsjZecn6a1) {
            lastErrorLocal = "请在执法仪本机使用"
            return false
        }
        return stopNativeRecord()
    }

    fun triggerCapture(): Boolean {
        lastErrorLocal = ""
        if (!DeviceProfile.isDsjZecn6a1) {
            lastErrorLocal = "请在执法仪本机使用"
            return false
        }
        return triggerNativeCapture()
    }

    private fun startNativeRecord(): Boolean {
        if (NativeRecorder.isRecording()) {
            lastErrorLocal = "已在录像中"
            return false
        }
        if (!hasNativeCameraPermissions()) {
            lastErrorLocal = "需要相机与麦克风权限"
            return false
        }
        NativeRecorder.startRecording(
            appContext,
            onStarted = {
                onNativeRecordStarted()
            },
            onError = { err ->
                lastErrorLocal = err
                showToast(err)
                notifyStatus()
            },
        )
        return true
    }

    private fun stopNativeRecord(): Boolean {
        if (!NativeRecorder.isRecording()) {
            lastErrorLocal = "当前未在录像"
            return false
        }
        NativeRecorder.stopRecording { file, err ->
            if (file != null) {
                onNativeRecordStopped(file)
            } else {
                lastErrorLocal = err
                showToast(err.ifBlank { "停止录像失败" })
                notifyStatus()
            }
        }
        return true
    }

    private fun triggerNativeCapture(): Boolean {
        if (NativeRecorder.isRecording()) {
            lastErrorLocal = "录像中无法拍照"
            return false
        }
        if (!CameraPermissionHelper.hasCamera(appContext)) {
            lastErrorLocal = "需要相机权限"
            return false
        }
        NativeRecorder.captureStill(
            appContext,
            onCaptured = { file ->
                val jpeg = file.readBytes()
                onPhonePhotoCaptured(jpeg, file)
            },
            onError = { err ->
                lastErrorLocal = err
                showToast(err)
            },
        )
        return true
    }

    private fun hasNativeCameraPermissions(): Boolean {
        return CameraPermissionHelper.missing(
            appContext,
            CameraPermissionHelper.requiredPermissions(),
        ).isEmpty()
    }

    private fun onNativeRecordStarted() {
        activeRecordId = "native-${System.currentTimeMillis()}"
        nativeRecordStartedAt = System.currentTimeMillis()
        Ze69Hardware.setRecordingIndicator(true)
        NightVisionController.onRecordingStarted()
        videoItems.add(
            0,
            VideoItem(
                id = activeRecordId,
                startedAt = nativeRecordStartedAt,
                stoppedAt = 0,
                durationMs = 0,
                note = "本机录像中 · ${DeviceProfile.VIDEO_WIDTH}p",
            ),
        )
        notifyStatus()
        showToast("本机录像已开始")
    }

    private fun onNativeRecordStopped(file: File) {
        Ze69Hardware.setRecordingIndicator(false)
        NightVisionController.onRecordingStopped()
        val startedAt = nativeRecordStartedAt
        val stoppedAt = System.currentTimeMillis()
        val durationMs = (stoppedAt - startedAt).coerceAtLeast(0)
        val sizeKb = file.length() / 1024
        videoItems.find { it.id == activeRecordId }?.let { item ->
            item.stoppedAt = stoppedAt
            item.durationMs = durationMs
            item.file = file
            item.note = buildString {
                append("本机录像 ${DeviceProfile.VIDEO_WIDTH}p")
                append(" · ${durationMs / 1000}s")
                append(" · ${sizeKb}KB")
            }
        } ?: run {
            videoItems.add(
                0,
                VideoItem(
                    id = "native-${System.currentTimeMillis()}",
                    startedAt = startedAt,
                    stoppedAt = stoppedAt,
                    durationMs = durationMs,
                    note = "本机录像 ${DeviceProfile.VIDEO_WIDTH}p · ${sizeKb}KB",
                    file = file,
                ),
            )
        }
        activeRecordId = ""
        nativeRecordStartedAt = 0L
        GallerySaver.saveVideoToGallery(appContext, file)
        notifyStatus()
        showToast("本机录像已保存")
    }

    fun getLastActionError(): String = lastErrorLocal

    fun onPhonePhotoCaptured(jpeg: ByteArray, savedFile: File) {
        mainHandler.post {
            GallerySaver.saveImageToGallery(appContext, savedFile)
            onImageCaptured(jpeg, savedFile)
            showToast(if (DeviceProfile.isDsjZecn6a1) "照片已保存" else "照片已保存到手机相册")
        }
    }

    fun getDeviceSummary(): String = DeviceProfile.summaryLine()

    fun onPhoneVideoStarted() {
        NightVisionController.onRecordingStarted()
    }

    fun onPhoneVideoCaptured(file: File, startedAt: Long) {
        mainHandler.post {
            NightVisionController.onRecordingStopped()
            GallerySaver.saveVideoToGallery(appContext, file)
            val stoppedAt = System.currentTimeMillis()
            val sizeKb = if (file.exists()) file.length() / 1024 else 0L
            videoItems.add(
                0,
                VideoItem(
                    id = "phone-${System.currentTimeMillis()}",
                    startedAt = startedAt,
                    stoppedAt = stoppedAt,
                    durationMs = (stoppedAt - startedAt).coerceAtLeast(0),
                    note = buildString {
                        if (DeviceProfile.isDsjZecn6a1) {
                            append("本机录像 ${DeviceProfile.VIDEO_WIDTH}p")
                        } else {
                            append("手机录像")
                        }
                        append(" · ${sizeKb}KB · 已入系统相册")
                    },
                    file = file,
                ),
            )
            notifyStatus()
            showToast("录像已保存到手机相册")
        }
    }

    fun analyzeUploadedImage(jpeg: ByteArray, onDone: (explanation: String, err: String) -> Unit) {
        if (!isLoggedIn()) {
            onDone("", "请先完成巡查员人脸认证")
            return
        }
        val base64 = Base64.getEncoder().encodeToString(jpeg)
        ApiClient.postVision(workerToken, sessionId, base64) { ok, body, err ->
            mainHandler.post {
                if (!ok || body == null) {
                    if (err == ApiClient.ERR_AUTH_EXPIRED) {
                        reloginAndRetry(
                            onSuccess = { analyzeUploadedImage(jpeg, onDone) },
                            onFail = { failMsg -> onDone("", failMsg) },
                        )
                    } else {
                        onDone("", err)
                    }
                    return@post
                }
                onDone(body.explanation, "")
            }
        }
    }

    private fun onImageCaptured(jpeg: ByteArray, savedFile: File) {
        val item = AlbumItem(
            id = "img-${System.currentTimeMillis()}",
            file = savedFile,
            size = jpeg.size,
            explanation = if (savedFile.name.contains("_native")) {
                "本机拍摄"
            } else if (savedFile.name.contains("_phone")) {
                "手机拍摄"
            } else {
                ""
            },
            createdAt = System.currentTimeMillis(),
        )
        albumItems.add(0, item)
        notifyStatus()

        if (!isLoggedIn()) {
            item.explanation = "未登录，仅本地预览"
            notifyStatus()
            return
        }

        val base64 = Base64.getEncoder().encodeToString(jpeg)
        ApiClient.postVision(workerToken, sessionId, base64) { ok, body, err ->
            mainHandler.post {
                item.explanation = when {
                    ok && body != null -> {
                        showToast("识图完成，照片已同步到手机相册")
                        body.explanation
                    }
                    else -> "识图失败: $err"
                }
                notifyStatus()
            }
        }
    }

    private fun syncZe69Indicators() {
        if (!DeviceProfile.isDsjZecn6a1 && !Ze69Hardware.isZe69Platform) return
        if (isRecording()) {
            Ze69Hardware.setRecordingIndicator(true)
        } else {
            Ze69Hardware.setRecordingIndicator(false)
            Ze69Hardware.setAiListeningIndicator(false)
        }
    }

    private fun applyDeviceCmds(cmds: List<Int>) {
        cmds.forEach { cmd ->
            if (isRecording() &&
                (cmd == DeviceCmd.CMD_START_AI_LISTEN || cmd == DeviceCmd.CMD_CAPTURE)
            ) {
                return@forEach
            }
            when (cmd) {
                DeviceCmd.CMD_START_RECORD -> startRecord()
                DeviceCmd.CMD_STOP_RECORD -> stopRecord()
                DeviceCmd.CMD_CAPTURE -> triggerCapture()
                DeviceCmd.CMD_START_AI_LISTEN ->
                    showToast("本机模式暂不支持 AI 聆听键")
                DeviceCmd.CMD_STOP_AI_LISTEN -> Unit
            }
        }
    }

    private var lastErrorLocal = ""

    private fun restoreAuth() {
        val saved = AuthConfig.load()
        if (saved.token.isEmpty()) return
        if (saved.baseUrl != ApiConfig.getBaseUrl()) {
            AuthConfig.clear()
            return
        }
        workerToken = saved.token
        sessionId = saved.sessionId.ifEmpty { "sess-${System.currentTimeMillis()}" }
        officerName = saved.officerName
        officerPhone = saved.officerPhone
        OfficerProfileStore.load()?.let { profile ->
            if (officerEmployeeId.isEmpty()) officerEmployeeId = profile.employeeId
            if (officerDepartment.isEmpty()) officerDepartment = profile.department
            if (officerDeviceId.isEmpty()) officerDeviceId = profile.deviceId
        }
        VerificationStateStore.markLoginComplete()
    }

    private fun persistAuth() {
        if (workerToken.isEmpty()) {
            AuthConfig.clear()
        } else {
            AuthConfig.save(
                workerToken,
                sessionId,
                ApiConfig.getBaseUrl(),
                officerName,
                officerPhone,
            )
        }
    }

    private fun clearSessionOnly() {
        workerToken = ""
        sessionId = ""
        officerName = ""
        officerPhone = ""
        officerEmployeeId = ""
        officerDepartment = ""
        officerDeviceId = ""
        AuthConfig.clear()
        notifyStatus()
    }

    private fun clearAuthState() {
        clearSessionOnly()
        VerificationStateStore.clear()
    }

    private fun applyLogin(token: String) {
        workerToken = token
        if (sessionId.isEmpty()) {
            sessionId = "sess-${System.currentTimeMillis()}"
        }
        persistAuth()
        notifyStatus()
    }

    private fun loginSuccessMessage(err: String): String {
        val mode = when {
            err == "offline-mock" -> "（离线 mock，后端连上后请点「保存并登录」）"
            workerToken.startsWith("mock-token") -> "（mock）"
            else -> "（云端 AI）"
        }
        return "登录成功$mode"
    }

    private fun patrolLoginSuccessMessage(detail: String): String {
        val suffix = detail.ifEmpty { "人脸验证通过" }
        return if (suffix.contains("登录")) suffix else "登录成功：$suffix"
    }

    private fun reloginAndRetry(onSuccess: () -> Unit, onFail: (String) -> Unit) {
        if (officerPhone.isNotBlank()) {
            clearAuthState()
            onFail("登录已过期，请到设置页重新进行人脸验证")
            return
        }
        ApiClient.login(AppConfig.DEMO_PHONE, AppConfig.DEMO_PASSWORD) { ok, token, _ ->
            mainHandler.post {
                if (ok) {
                    applyLogin(token)
                    onSuccess()
                } else {
                    clearAuthState()
                    onFail("登录已过期，请检查后端地址后点「保存并登录」")
                }
            }
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun notifyStatus() {
        mainHandler.post {
            statusListeners.forEach { it.onSessionChanged() }
        }
    }

    companion object {
        @Volatile
        private var instance: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(context).also { instance = it }
            }
        }
    }
}
