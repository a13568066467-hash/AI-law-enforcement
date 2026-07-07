package com.aifieldcam.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.aifieldcam.app.data.AppConfig
import com.aifieldcam.app.data.AuthConfig
import com.aifieldcam.app.data.DeviceCmd
import com.aifieldcam.app.demo.DemoScenarios
import com.aifieldcam.app.platform.BatteryPolicy
import com.aifieldcam.app.platform.DeviceProfile
import com.aifieldcam.app.platform.DeviceStatusIndicator
import com.aifieldcam.app.platform.NativeAudioRecorder
import com.aifieldcam.app.platform.NativeRecorder
import com.aifieldcam.app.platform.MediaInteractionPolicy
import com.aifieldcam.app.platform.RecordingPipelineWatchdog
import com.aifieldcam.app.platform.SessionPolicy
import com.aifieldcam.app.platform.Ze69Hardware
import com.aifieldcam.app.service.RecordingForegroundService
import com.aifieldcam.app.util.TtsSpeaker
import com.aifieldcam.app.util.CameraPermissionHelper
import com.aifieldcam.app.util.GallerySaver
import com.aifieldcam.app.util.MediaStorageLocator
import com.aifieldcam.app.util.PhoneCameraHelper
import com.aifieldcam.app.util.VideoMetadata
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
        var important: Boolean = false,
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
    private var aiListening = false
    private var aiChatInFlight = false
    private var whiteLightOn = false
    private var recordingInterruptHandling = false

    private val albumItems = CopyOnWriteArrayList<AlbumItem>()
    private val videoItems = CopyOnWriteArrayList<VideoItem>()
    private val statusListeners = CopyOnWriteArrayList<StatusListener>()

    init {
        restoreAuth()
        NativeRecorder.onPipelineInterrupted = { reason ->
            mainHandler.post { handleRecordingPipelineInterrupted(reason) }
        }
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

    fun isAiBusy(): Boolean = aiListening || aiChatInFlight

    /** PTT 按下 / 云端 CMD_START_AI_LISTEN（交互设计：蓝灯） */
    fun setAiListening(active: Boolean) {
        if (active && isRecording()) return
        aiListening = active
        syncZe69Indicators()
        notifyStatus()
    }

    fun isNativeRecorderMode(): Boolean = DeviceProfile.isDsjZecn6a1

    fun isRecording(): Boolean = NativeRecorder.isRecording()

    fun isAudioRecording(): Boolean = NativeAudioRecorder.isRecording()

    fun isRecorderBusy(): Boolean = NativeRecorder.isBusy()

    fun isStillCapturing(): Boolean = NativeRecorder.isCapturing()

    fun getRecorderSummary(): String = when {
        NativeRecorder.isPreparing() -> "正在启动本机录像 · ${DeviceProfile.MODEL_NAME}"
        isRecording() -> "本机录像中 · ${DeviceProfile.MODEL_NAME}"
        isAudioRecording() -> "本机录音中 · ${DeviceProfile.MODEL_NAME}"
        whiteLightOn -> "白光灯已开 · ${DeviceProfile.MODEL_NAME}"
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
                    if (!DeviceProfile.isDsjZecn6a1) {
                        val local = DemoScenarios.run(scenarioId, deviceId)
                        if (local.scenarioId.isNotEmpty()) {
                            applyDeviceCmds(local.bleCmds)
                            onDone(local, "")
                            return@post
                        }
                    }
                    onDone(null, err.ifBlank { "网络异常，请检查 4G 与后端连接" })
                    return@post
                }
                applyDeviceCmds(result.bleCmds)
                onDone(result, "")
            }
        }
    }

    private var pendingExpertCapture: ((ByteArray) -> Unit)? = null

    fun runExpertConsult(
        question: String? = null,
        captureFirst: Boolean = false,
        onDone: (DemoScenarios.SceneResult?, String) -> Unit,
    ) {
        if (!isLoggedIn()) {
            onDone(null, "请先完成巡查员人脸认证")
            return
        }
        if (BatteryPolicy.shouldBlockNewWork()) {
            onDone(null, BatteryPolicy.blockReason())
            return
        }
        if (isRecording()) {
            onDone(null, "录像中请先停止录像")
            return
        }
        val q = question?.trim().orEmpty().ifEmpty {
            "现场安全员请求技术专家远程指导，请结合可见信息给出风险研判与可执行处置步骤。"
        }
        if (captureFirst && DeviceProfile.isDsjZecn6a1) {
            pendingExpertCapture = { jpeg -> postExpertConsult(q, jpeg, onDone) }
            if (!triggerNativeCapture()) {
                pendingExpertCapture = null
                onDone(null, lastErrorLocal.ifBlank { "拍照失败" })
            }
            return
        }
        postExpertConsult(q, null, onDone)
    }

    private fun postExpertConsult(
        question: String,
        jpeg: ByteArray?,
        onDone: (DemoScenarios.SceneResult?, String) -> Unit,
    ) {
        val devId = officerDeviceId.ifEmpty {
            com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        }
        val imageBase64 = jpeg?.let { Base64.getEncoder().encodeToString(it) }.orEmpty()
        aiChatInFlight = true
        syncZe69Indicators()
        ApiClient.postExpertSession(
            workerToken,
            sessionId,
            devId,
            question,
            imageBase64,
        ) { ok, result, err ->
            mainHandler.post {
                aiChatInFlight = false
                syncZe69Indicators()
                if (!ok || result == null) {
                    if (err == ApiClient.ERR_AUTH_EXPIRED) {
                        reloginAndRetry(
                            onSuccess = { postExpertConsult(question, jpeg, onDone) },
                            onFail = { failMsg -> onDone(null, failMsg) },
                        )
                    } else {
                        onDone(null, err)
                    }
                    return@post
                }
                if (result.reply.isNotBlank()) {
                    TtsSpeaker.speak(result.reply)
                }
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
        if (BatteryPolicy.shouldBlockNewWork()) {
            onDone("", BatteryPolicy.blockReason(), null)
            return
        }
        if (isRecording() && SessionPolicy.recordingBlocksChat(text)) {
            onDone("", "录像中请先停止录像", null)
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
        aiChatInFlight = true
        syncZe69Indicators()
        ApiClient.postChat(workerToken, sessionId, devId, text, deviceState) { ok, body, err ->
            mainHandler.post {
                aiChatInFlight = false
                syncZe69Indicators()
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
                if (body.reply.isNotBlank()) {
                    TtsSpeaker.speak(body.reply)
                }
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

    /** 侧键/屏幕统一入口：失败时 Toast 提示 */
    fun startRecordWithFeedback(): Boolean {
        val ok = startRecord()
        if (!ok) showToast(lastErrorLocal.ifBlank { "无法开始录像" })
        return ok
    }

    fun stopRecordWithFeedback(): Boolean {
        val ok = stopRecord()
        if (!ok) showToast(lastErrorLocal.ifBlank { "无法停止录像" })
        return ok
    }

    fun triggerCaptureWithFeedback(): Boolean {
        val ok = triggerCapture()
        if (!ok) showToast(lastErrorLocal.ifBlank { "无法拍照" })
        return ok
    }

    fun startAudioRecordWithFeedback(): Boolean {
        val ok = startAudioRecord()
        if (!ok) showToast(lastErrorLocal.ifBlank { "无法开始录音" })
        return ok
    }

    fun stopAudioRecordWithFeedback(): Boolean {
        val ok = stopAudioRecord()
        if (!ok) showToast(lastErrorLocal.ifBlank { "无法停止录音" })
        return ok
    }

    /** SOS 短按：重点标记当前录像/最近文件 */
    fun markImportantWithFeedback() {
        val marked = markImportant()
        showToast(if (marked) "已标记为重点文件" else "暂无可标记的录像")
    }

    /** PTT 短按：白光灯开关（说明书） */
    fun toggleWhiteLight() {
        whiteLightOn = !whiteLightOn
        Ze69Hardware.setWhiteLight(whiteLightOn)
        notifyStatus()
        showToast(if (whiteLightOn) "白光灯已开" else "白光灯已关")
    }

    fun startAudioRecord(): Boolean {
        lastErrorLocal = ""
        val block = MediaInteractionPolicy.canStartAudio(mediaInteractionState())
        if (block != null) return applyBlock(block)
        if (CameraPermissionHelper.missing(
                appContext,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
            ).isNotEmpty()
        ) {
            lastErrorLocal = "需要麦克风权限"
            return false
        }
        NativeAudioRecorder.startRecording(
            appContext,
            onStarted = {
                mainHandler.post {
                    DeviceStatusIndicator.setAudioRecording(true)
                    notifyStatus()
                    showToast("本机录音已开始")
                }
            },
            onError = { err ->
                mainHandler.post {
                    lastErrorLocal = err
                    showToast(err)
                    DeviceStatusIndicator.setAudioRecording(false)
                    notifyStatus()
                }
            },
        )
        return true
    }

    fun stopAudioRecord(): Boolean {
        lastErrorLocal = ""
        val block = MediaInteractionPolicy.canStopAudio(mediaInteractionState())
        if (block != null) return applyBlock(block)
        NativeAudioRecorder.stopRecording { file, err ->
            mainHandler.post {
                DeviceStatusIndicator.setAudioRecording(false)
                if (file != null) {
                    showToast("录音已保存")
                } else if (err.isNotBlank()) {
                    lastErrorLocal = err
                    showToast(err)
                }
                notifyStatus()
            }
        }
        return true
    }

    private fun markImportant(): Boolean {
        if (activeRecordId.isNotEmpty()) {
            videoItems.find { it.id == activeRecordId }?.let {
                it.important = true
                it.note = "★ ${it.note}"
                notifyStatus()
                return true
            }
        }
        val latest = videoItems.firstOrNull { it.file != null } ?: return false
        latest.important = true
        if (!latest.note.startsWith("★")) {
            latest.note = "★ ${latest.note}"
        }
        notifyStatus()
        return true
    }

    /** App 回到前台时纠正卡死的录像状态 */
    fun reconcileRecorderOnResume() {
        if (!DeviceProfile.isDsjZecn6a1) return
        if (NativeRecorder.reconcileStaleState()) {
            notifyStatus()
            showToast("已解除相机占用状态")
        }
    }

    private fun mediaInteractionState(): MediaInteractionPolicy.State =
        MediaInteractionPolicy.State(
            videoRecording = isRecording(),
            videoPreparing = NativeRecorder.isPreparing(),
            stillCapturing = NativeRecorder.isCapturing(),
            audioRecording = isAudioRecording(),
            aiBusy = isAiBusy(),
            lowBatteryBlock = BatteryPolicy.shouldBlockNewWork(),
            hasCameraPermission = CameraPermissionHelper.hasCamera(appContext),
            hasRecordPermissions = hasNativeCameraPermissions(),
            isDsjDevice = DeviceProfile.isDsjZecn6a1,
        )

    private fun applyBlock(block: MediaInteractionPolicy.Block): Boolean {
        lastErrorLocal = when (block) {
            is MediaInteractionPolicy.Block.LowBattery -> BatteryPolicy.blockReason()
            else -> block.message
        }
        return false
    }

    private fun startNativeRecord(): Boolean {
        val block = MediaInteractionPolicy.canStartVideo(mediaInteractionState())
        if (block != null) {
            if (block is MediaInteractionPolicy.Block.LowBattery ||
                block is MediaInteractionPolicy.Block.AiBusy
            ) {
                showToast(block.message)
            }
            return applyBlock(block)
        }
        val videoDir = PhoneCameraHelper.videoDir(appContext)
        val freeMb = MediaStorageLocator.freeMb(videoDir)
        if (freeMb <= 0) {
            lastErrorLocal = "存储空间已满，无法开始录像"
            showToast(lastErrorLocal)
            return false
        }
        if (freeMb in 1..1024) {
            showToast("存储空间低（剩余 ${freeMb}MB），将自动保存后停止")
        }
        notifyStatus()
        RecordingForegroundService.ensureRunning(appContext, forRecording = true)
        NativeRecorder.startRecording(
            appContext,
            onStarted = {
                mainHandler.post { onNativeRecordStarted() }
            },
            onError = { err ->
                mainHandler.post {
                    RecordingForegroundService.releaseIfIdle(appContext)
                    lastErrorLocal = err
                    showToast(err)
                    notifyStatus()
                }
            },
        )
        return true
    }

    private fun stopNativeRecord(): Boolean {
        val block = MediaInteractionPolicy.canStopVideo(mediaInteractionState())
        if (block != null) return applyBlock(block)
        NativeRecorder.stopRecording { file, err ->
            mainHandler.post {
                if (file != null) {
                    onNativeRecordStopped(file)
                } else if (err.isBlank()) {
                    abortNativeRecordingSession(message = "已取消启动录像", toast = true)
                } else {
                    abortNativeRecordingSession(message = err.ifBlank { "停止录像失败" }, toast = true)
                }
            }
        }
        return true
    }

    private fun triggerNativeCapture(): Boolean {
        val block = MediaInteractionPolicy.canCapture(mediaInteractionState())
        if (block != null) {
            if (block is MediaInteractionPolicy.Block.LowBattery) {
                showToast(block.message)
            }
            return applyBlock(block)
        }
        RecordingForegroundService.ensureRunning(appContext, forRecording = false)
        NativeRecorder.captureStill(
            appContext,
            onCaptured = { file ->
                mainHandler.post {
                    RecordingForegroundService.releaseIfIdle(appContext)
                    Ze69Hardware.pulseCaptureFlash()
                    val jpeg = file.readBytes()
                    onPhonePhotoCaptured(jpeg, file)
                }
            },
            onError = { err ->
                mainHandler.post {
                    RecordingForegroundService.releaseIfIdle(appContext)
                    lastErrorLocal = err
                    showToast(err)
                }
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
        aiListening = false
        activeRecordId = "native-${System.currentTimeMillis()}"
        nativeRecordStartedAt = System.currentTimeMillis()
        DeviceStatusIndicator.setVideoRecording(true)
        RecordingPipelineWatchdog.start(PhoneCameraHelper.videoDir(appContext))
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

    private fun onNativeRecordStopped(file: File, toastMessage: String? = null) {
        RecordingPipelineWatchdog.stop()
        RecordingForegroundService.releaseIfIdle(appContext)
        DeviceStatusIndicator.setVideoRecording(false)
        val startedAt = nativeRecordStartedAt
        val stoppedAt = System.currentTimeMillis()
        val wallMs = (stoppedAt - startedAt).coerceAtLeast(0)
        val mediaMs = VideoMetadata.durationMs(file)
        val durationMs = when {
            mediaMs > 0 && wallMs > 0 && mediaMs < wallMs - 3_000 -> mediaMs
            mediaMs > 0 -> mediaMs
            else -> wallMs
        }
        val sizeKb = file.length() / 1024
        videoItems.find { it.id == activeRecordId }?.let { item ->
            item.stoppedAt = stoppedAt
            item.durationMs = durationMs
            item.file = file
            item.note = buildString {
                append("本机录像 ${DeviceProfile.VIDEO_WIDTH}p")
                append(" · ${durationMs / 1000}s")
                if (mediaMs > 0 && mediaMs < wallMs - 3_000) {
                    append("（媒体时长）")
                }
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
                    note = "本机录像 ${DeviceProfile.VIDEO_WIDTH}p · ${durationMs / 1000}s · ${sizeKb}KB",
                    file = file,
                ),
            )
        }
        activeRecordId = ""
        nativeRecordStartedAt = 0L
        val galleryOk = GallerySaver.saveVideoToGallery(appContext, file)
        notifyStatus()
        val base = toastMessage ?: "本机录像已保存"
        showToast(if (galleryOk) base else "$base（系统相册写入失败，文件在应用内）")
    }

    /** 停录失败 / 取消启动：释放 FGS、清 UI 状态、移除「录像中」占位项 */
    private fun abortNativeRecordingSession(message: String, toast: Boolean) {
        RecordingPipelineWatchdog.stop()
        RecordingForegroundService.releaseIfIdle(appContext)
        DeviceStatusIndicator.setVideoRecording(false)
        if (activeRecordId.isNotEmpty()) {
            videoItems.removeAll { it.id == activeRecordId }
        }
        activeRecordId = ""
        nativeRecordStartedAt = 0L
        lastErrorLocal = message
        notifyStatus()
        if (toast) showToast(message)
    }

    private fun handleRecordingPipelineInterrupted(reason: String) {
        if (!NativeRecorder.isBusy() || recordingInterruptHandling) return
        recordingInterruptHandling = true
        RecordingPipelineWatchdog.stop()
        NativeRecorder.stopRecording { file, err ->
            mainHandler.post {
                recordingInterruptHandling = false
                if (file != null) {
                    onNativeRecordStopped(file, toastMessage = reason)
                    // 录像达到单文件上限后自动重启下一段（FAT32 4GB 限制）
                    if (reason.contains("分段保存")) {
                        mainHandler.postDelayed({
                            if (!NativeRecorder.isBusy()) {
                                startNativeRecord()
                            }
                        }, 1200L)
                    }
                } else {
                    abortNativeRecordingSession(
                        message = reason.ifBlank { "录像已中断" },
                        toast = true,
                    )
                }
            }
        }
    }

    fun getLastActionError(): String = lastErrorLocal

    fun onPhonePhotoCaptured(jpeg: ByteArray, savedFile: File) {
        mainHandler.post {
            val galleryOk = GallerySaver.saveImageToGallery(appContext, savedFile)
            val expertCb = pendingExpertCapture
            if (expertCb != null) {
                pendingExpertCapture = null
                val item = AlbumItem(
                    id = "img-${System.currentTimeMillis()}",
                    file = savedFile,
                    size = jpeg.size,
                    explanation = "专家咨询现场图",
                    createdAt = System.currentTimeMillis(),
                )
                albumItems.add(0, item)
                notifyStatus()
                expertCb(jpeg)
                showToast(galleryToast(galleryOk, DeviceProfile.isDsjZecn6a1))
                return@post
            }
            onImageCaptured(jpeg, savedFile)
            showToast(galleryToast(galleryOk, DeviceProfile.isDsjZecn6a1))
        }
    }

    private fun galleryToast(galleryOk: Boolean, nativeDevice: Boolean): String {
        val base = if (nativeDevice) "照片已保存" else "照片已保存到手机相册"
        return if (galleryOk) base else "$base（系统相册写入失败，可在应用相册查看）"
    }

    fun getDeviceSummary(): String = DeviceProfile.summaryLine()

    fun onPhoneVideoStarted() {
        // 不自动开红外
    }

    fun onPhoneVideoCaptured(file: File, startedAt: Long) {
        mainHandler.post {
            DeviceStatusIndicator.setVideoRecording(false)
            val galleryOk = GallerySaver.saveVideoToGallery(appContext, file)
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
            showToast(
                if (galleryOk) "录像已保存到手机相册" else "录像已保存（系统相册写入失败，可在应用内查看）",
            )
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
                TtsSpeaker.speak(body.explanation)
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
                        TtsSpeaker.speak(body.explanation)
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
        DeviceStatusIndicator.setVideoRecording(isRecording())
        DeviceStatusIndicator.setAudioRecording(isAudioRecording())
        Ze69Hardware.setAiListeningIndicator(aiListening || aiChatInFlight)
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
                DeviceCmd.CMD_START_AI_LISTEN -> setAiListening(true)
                DeviceCmd.CMD_STOP_AI_LISTEN -> setAiListening(false)
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
        if (msg.isBlank()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post { Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show() }
        }
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
