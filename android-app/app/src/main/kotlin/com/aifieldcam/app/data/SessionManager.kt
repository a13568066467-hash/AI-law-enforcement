package com.aifieldcam.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.aifieldcam.app.platform.MqttClient
import com.aifieldcam.app.platform.MqttHeartbeat
import com.aifieldcam.app.platform.MqttTopicRouter
import com.aifieldcam.app.platform.LoopRecordingStorage
import com.aifieldcam.app.platform.RecordingPipelineWatchdog
import com.aifieldcam.app.platform.RecordingSegmentPolicy
import com.aifieldcam.app.platform.RealtimeVoiceClient
import com.aifieldcam.app.platform.RealtimeVoiceEvent
import com.aifieldcam.app.platform.RealtimeVoicePhase
import com.aifieldcam.app.platform.RealtimeToolCallRegistry
import com.aifieldcam.app.platform.StorageRetentionWatchdog
import com.aifieldcam.app.platform.StreamingPipelineWatchdog
import com.aifieldcam.app.platform.SessionPolicy
import com.aifieldcam.app.platform.HttpPreviewRelay
import com.aifieldcam.app.platform.VideoStreamCoordinator
import com.aifieldcam.app.platform.VideoStreamManager
import com.aifieldcam.app.platform.WebRtcPeer
import com.aifieldcam.app.platform.SipUaClient
import com.aifieldcam.app.platform.Ze69Hardware
import com.aifieldcam.app.platform.commandcall.CommandCallAiGate
import com.aifieldcam.app.platform.commandcall.CommandCallController
import com.aifieldcam.app.platform.commandcall.CommandCallCredentials
import com.aifieldcam.app.platform.commandcall.CommandCallIntercom
import com.aifieldcam.app.platform.commandcall.CommandCallSignalParser
import com.aifieldcam.app.platform.PttSnapAskController
import com.aifieldcam.app.service.RecordingForegroundService
import com.aifieldcam.app.util.TtsSpeaker
import com.aifieldcam.app.util.CameraPermissionHelper
import com.aifieldcam.app.util.AlbumMediaSync
import com.aifieldcam.app.util.GallerySaver
import com.aifieldcam.app.util.MediaStorageLocator
import com.aifieldcam.app.util.PhoneCameraHelper
import com.aifieldcam.app.util.VideoMetadata
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal enum class AiListeningSource {
    SCREEN,
    REMOTE,
    PHYSICAL_PTT,
}

internal object AiListeningPolicy {
    fun allowsStart(source: AiListeningSource, recording: Boolean): Boolean =
        !recording || source == AiListeningSource.PHYSICAL_PTT
}

internal class AiListeningSources {
    private val activeSources = mutableSetOf<AiListeningSource>()

    @Synchronized
    fun update(source: AiListeningSource, active: Boolean, recording: Boolean): Boolean {
        if (active && !AiListeningPolicy.allowsStart(source, recording)) return false
        if (active) {
            activeSources.add(source)
        } else {
            activeSources.remove(source)
        }
        return true
    }

    @Synchronized
    fun isActive(): Boolean = activeSources.isNotEmpty()

    @Synchronized
    fun clearBlockedByRecording() {
        activeSources.removeAll { !AiListeningPolicy.allowsStart(it, recording = true) }
    }

    @Synchronized
    fun clearAll() {
        activeSources.clear()
    }
}

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
    private val aiListeningSources = AiListeningSources()
    private var aiChatInFlight = false
    private var realtimeVoicePhase = RealtimeVoicePhase.IDLE
    private val realtimeToolCalls = RealtimeToolCallRegistry()
    private val commandCallAiGate = CommandCallAiGate(
        interruptAi = { interruptAiAssistantForCommandCall() },
    )
    private var whiteLightOn = false
    private var recordingInterruptHandling = false
    /** 分段切换间隙：第一段已 stop、第二段尚未 start，保持录像红灯 */
    private var segmentRolloverActive = false
    /** 用户已停录，后台仍在 finalize MP4 / 写相册（避免 UI 长时间卡在「录像中」） */
    private var nativeVideoSaving = false
    private var webrtcPollScheduled = false
    private val webrtcPollRunnable = object : Runnable {
        override fun run() {
            pollWebRtcCommands()
            pollCommandCallCommands()
            if (webrtcPollScheduled) {
                // 指挥连线/画面监看依赖 HTTP 兜底（MQTT 默认关闭）；15s 过稀易在 RING_TIMEOUT 内漏信令
                mainHandler.postDelayed(this, 2_000L)
            }
        }
    }

    private val albumItems = CopyOnWriteArrayList<AlbumItem>()
    private val videoItems = CopyOnWriteArrayList<VideoItem>()
    private val statusListeners = CopyOnWriteArrayList<StatusListener>()
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SessionManager-IO").apply { isDaemon = true }
    }

    init {
        restoreAuth()
        NativeRecorder.onPipelineInterrupted = { reason ->
            mainHandler.post { handleRecordingPipelineInterrupted(reason) }
        }
        NativeRecorder.onSegmentRotated = { file ->
            mainHandler.post { onNativeRecordSeamlessSegmentRotated(file) }
        }
        SipUaClient.onIncomingCall = { _, _, _, _ ->
            mainHandler.post { onSipStreamActive() }
        }
        SipUaClient.onCallEnded = {
            mainHandler.post { stopVideoStream("sip-bye") }
        }
        SipUaClient.onRegistered = {
            mainHandler.post { onSipRegistered() }
        }
        StorageRetentionWatchdog.onFileDeleted = { file ->
            mainHandler.post {
                videoItems.removeAll { it.file?.absolutePath == file.absolutePath }
                notifyStatus()
            }
        }
        AlbumMediaSync.start(appContext) { file ->
            albumItems.removeAll { it.file.absolutePath == file.absolutePath }
            videoItems.removeAll { it.file?.absolutePath == file.absolutePath }
            notifyStatus()
        }
    }

    /**
     * 删除相册中的照片或视频：应用内主文件 + 系统相册同名副本 + 内存条目。
     * @return 主文件是否已不存在（删成功或不存在均视为 true）
     */
    fun deleteAlbumMedia(item: AlbumMediaItem): Boolean {
        val file = item.file
        val name = file.name
        AlbumMediaSync.beginLocalDelete(name)
        return try {
            if (item.isVideo) {
                GallerySaver.deleteVideoFromGallery(appContext, file)
            } else {
                GallerySaver.deleteImageFromGallery(appContext, file)
            }
            val removed = !file.exists() || file.delete()
            if (item.isVideo) {
                videoItems.removeAll { it.file?.absolutePath == file.absolutePath }
            } else {
                albumItems.removeAll { it.file.absolutePath == file.absolutePath }
            }
            notifyStatus()
            removed
        } finally {
            // 稍后再放开，避免 ContentObserver 抖动期间重复处理
            mainHandler.postDelayed({ AlbumMediaSync.endLocalDelete(name) }, 800L)
        }
    }

    /** 批量删除相册项：删主文件 + 系统相册副本，只通知一次 UI。 */
    fun deleteAlbumMediaBatch(items: List<AlbumMediaItem>): Int {
        if (items.isEmpty()) return 0
        val names = items.map { it.file.name }.filter { it.isNotBlank() }.distinct()
        names.forEach { AlbumMediaSync.beginLocalDelete(it) }
        var deleted = 0
        try {
            for (item in items) {
                val file = item.file
                if (item.isVideo) {
                    GallerySaver.deleteVideoFromGallery(appContext, file)
                } else {
                    GallerySaver.deleteImageFromGallery(appContext, file)
                }
                val removed = !file.exists() || file.delete()
                if (removed) deleted++
                if (item.isVideo) {
                    videoItems.removeAll { it.file?.absolutePath == file.absolutePath }
                } else {
                    albumItems.removeAll { it.file.absolutePath == file.absolutePath }
                }
            }
            notifyStatus()
        } finally {
            mainHandler.postDelayed({
                names.forEach { AlbumMediaSync.endLocalDelete(it) }
            }, 800L)
        }
        return deleted
    }

    fun addStatusListener(listener: StatusListener) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: StatusListener) {
        statusListeners.remove(listener)
    }

    fun isLoggedIn(): Boolean = isDeviceBound()

    fun getAlbumItems(): List<AlbumItem> = albumItems.toList()

    fun getVideoItems(): List<VideoItem> = videoItems.toList()

    fun getBleSummary(): String = getRecorderSummary()

    fun isAiBusy(): Boolean =
        aiListeningSources.isActive() ||
            aiChatInFlight ||
            realtimeVoicePhase !in setOf(RealtimeVoicePhase.IDLE, RealtimeVoicePhase.ERROR)

    fun isAiListening(): Boolean = aiListeningSources.isActive()

    fun isAiProcessing(): Boolean =
        aiChatInFlight || realtimeVoicePhase == RealtimeVoicePhase.THINKING

    fun isAiRealtimeSpeaking(): Boolean =
        realtimeVoicePhase == RealtimeVoicePhase.SPEAKING

    internal fun getRealtimeVoicePhase(): RealtimeVoicePhase = realtimeVoicePhase

    internal fun realtimeVoiceConfig(): RealtimeVoiceClient.Config? {
        if (workerToken.isBlank() || sessionId.isBlank()) return null
        val deviceId = officerDeviceId.ifEmpty {
            com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        }
        return RealtimeVoiceClient.Config(
            baseUrl = ApiConfig.getBaseUrl(),
            token = workerToken,
            sessionId = sessionId,
            deviceId = deviceId,
        )
    }

    internal fun setRealtimeVoicePhase(phase: RealtimeVoicePhase) {
        realtimeVoicePhase = phase
        aiListeningSources.update(
            AiListeningSource.PHYSICAL_PTT,
            phase == RealtimeVoicePhase.LISTENING,
            isRecording(),
        )
        syncZe69Indicators()
        notifyStatus()
    }

    internal fun executeRealtimeTool(
        call: RealtimeVoiceEvent.ToolCall,
        onResult: (org.json.JSONObject) -> Unit,
    ) {
        when (realtimeToolCalls.evaluate(call.callId, call.name)) {
            RealtimeToolCallRegistry.Decision.DUPLICATE -> {
                onResult(
                    org.json.JSONObject()
                        .put("ok", false)
                        .put("error", "duplicate_call"),
                )
                return
            }
            RealtimeToolCallRegistry.Decision.DENY -> {
                onResult(
                    org.json.JSONObject()
                        .put("ok", false)
                        .put("error", "tool_not_allowed"),
                )
                return
            }
            RealtimeToolCallRegistry.Decision.ALLOW -> Unit
        }
        when (call.name) {
            "start_recording" -> {
                val ok = startRecordWithFeedback()
                onResult(
                    org.json.JSONObject()
                        .put("ok", ok)
                        .put("message", if (ok) "已开始录像" else lastErrorLocal.ifBlank { "无法开始录像" }),
                )
            }
            "stop_recording" -> {
                val ok = stopRecordWithFeedback()
                onResult(
                    org.json.JSONObject()
                        .put("ok", ok)
                        .put("message", if (ok) "已停止录像" else lastErrorLocal.ifBlank { "无法停止录像" }),
                )
            }
            "capture_and_explain" -> {
                val question = call.arguments.optString("question", "请说明现场画面")
                grabSnapshot { jpeg ->
                    if (jpeg == null) {
                        onResult(
                            org.json.JSONObject()
                                .put("ok", false)
                                .put("error", "未能抓拍到画面"),
                        )
                        return@grabSnapshot
                    }
                    val imageBase64 = Base64.getEncoder().encodeToString(jpeg)
                    ApiClient.postExpertSession(
                        workerToken,
                        sessionId,
                        officerDeviceId,
                        question,
                        imageBase64,
                    ) { ok, result, err ->
                        mainHandler.post {
                            onResult(
                                org.json.JSONObject()
                                    .put("ok", ok && result != null)
                                    .put("explanation", result?.reply.orEmpty())
                                    .put("error", if (ok) "" else err),
                            )
                        }
                    }
                }
            }
            else -> onResult(
                org.json.JSONObject()
                    .put("ok", false)
                    .put("error", "不允许的工具"),
            )
        }
    }

    /** PTT 按下 / 云端 CMD_START_AI_LISTEN（交互设计：蓝灯） */
    fun setAiListening(active: Boolean) {
        updateAiListening(active, AiListeningSource.SCREEN)
    }

    /** 物理 PTT 与录像共麦时只同步 AI 状态，不放宽屏幕/云端入口的录像限制。 */
    fun setPhysicalPttListening(active: Boolean) {
        updateAiListening(active, AiListeningSource.PHYSICAL_PTT)
    }

    private fun updateAiListening(active: Boolean, source: AiListeningSource) {
        if (!aiListeningSources.update(source, active, isRecording())) return
        syncZe69Indicators()
        notifyStatus()
    }

    private fun setRemoteAiListening(active: Boolean) {
        updateAiListening(active, AiListeningSource.REMOTE)
    }

    fun isNativeRecorderMode(): Boolean = DeviceProfile.isDsjZecn6a1

    fun isRecording(): Boolean = NativeRecorder.isRecording()

    fun isAudioRecording(): Boolean = NativeAudioRecorder.isRecording()

    fun isRecorderBusy(): Boolean =
        NativeRecorder.isBusy() || nativeVideoSaving || segmentRolloverActive

    fun isVideoStreaming(): Boolean = VideoStreamManager.isStreaming()

    fun isVideoSaving(): Boolean = nativeVideoSaving

    /** 单测：分段切换间隙是否应视为「录像中」 */
    internal fun isSegmentRolloverActive(): Boolean = segmentRolloverActive

    fun isStillCapturing(): Boolean = NativeRecorder.isCapturing()

    fun getRecorderSummary(): String = when {
        nativeVideoSaving -> "正在保存录像 · ${DeviceProfile.MODEL_NAME}"
        NativeRecorder.isPreparing() -> "正在启动本机录像 · ${DeviceProfile.MODEL_NAME}"
        isRecording() || segmentRolloverActive -> "本机录像中 · ${DeviceProfile.MODEL_NAME}"
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
            append("当前执法仪：${officerDeviceId.ifEmpty { com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext) }}")
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

    fun isPatrolRegisteredOnDevice(): Boolean = isDeviceBound()

    /** 本机是否处于扫码绑定占用态（有会话 token + 本机展示缓存）。 */
    fun isDeviceBound(): Boolean =
        workerToken.isNotEmpty() && OfficerProfileStore.isBoundLocally()

    fun requestBindToken(onDone: (Boolean, ApiClient.DeviceBindTokenResult?, String) -> Unit) {
        val deviceId = com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        ApiClient.createDeviceBindToken(deviceId) { ok, data, err ->
            mainHandler.post { onDone(ok, data, err) }
        }
    }

    fun pollBindStatus(
        token: String,
        onDone: (ApiClient.DeviceBindStatusResult) -> Unit,
    ) {
        val deviceId = com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        ApiClient.fetchDeviceBindStatus(deviceId, token) { result ->
            mainHandler.post {
                if (result.status == "bound" && !result.sessionToken.isNullOrEmpty()) {
                    applyBindSuccess(result.sessionToken, result.officer)
                }
                onDone(result)
            }
        }
    }

    private fun applyBindSuccess(sessionToken: String, officer: org.json.JSONObject?) {
        val deviceId = com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        val profile = OfficerProfile(
            phone = officer?.optString("phone").orEmpty(),
            name = officer?.optString("name").orEmpty(),
            gender = officer?.optString("gender", "未知").orEmpty().ifBlank { "未知" },
            employeeId = officer?.optString("employee_id").orEmpty(),
            department = officer?.optString("department").orEmpty(),
            deviceId = deviceId,
            company = officer?.optString("company").orEmpty(),
            position = officer?.optString("position").orEmpty(),
            idCard = officer?.optString("id_card").orEmpty(),
        )
        OfficerProfileStore.saveBound(profile)
        officerName = profile.name
        officerPhone = profile.phone
        officerDepartment = profile.department
        officerEmployeeId = profile.employeeId
        officerDeviceId = deviceId
        applyLogin(sessionToken)
        BindBootMarker.markBound(appContext)
        notifyStatus()
    }

    fun releaseBind(onDone: (Boolean, String) -> Unit) {
        val deviceId = com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        ApiClient.releaseDeviceBind(deviceId) { ok, msg ->
            mainHandler.post {
                if (ok) {
                    clearBindLocal()
                }
                onDone(ok, msg.ifEmpty { if (ok) "已解绑" else "解绑失败" })
                notifyStatus()
            }
        }
    }

    fun onDeviceShutdown(onDone: ((Boolean, String) -> Unit)? = null) {
        val deviceId = com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        val hadBind = isDeviceBound() || BindBootMarker.wasBound(appContext)
        if (!hadBind) {
            onDone?.invoke(true, "无需清理")
            return
        }
        ApiClient.shutdownDeviceBind(deviceId) { ok, msg ->
            mainHandler.post {
                if (ok) {
                    clearBindLocal()
                }
                onDone?.invoke(ok, msg.ifEmpty { if (ok) "已清理" else "关机同步失败，请稍后重试" })
                notifyStatus()
            }
        }
    }

    /** 整机重启后仅通知云端结束占用（本机已在 [BindBootMarker.prepareBoot] 清过）。 */
    fun notifyCloudShutdown(onDone: ((Boolean, String) -> Unit)? = null) {
        val deviceId = com.aifieldcam.app.platform.DeviceIdentity.recorderId(appContext)
        ApiClient.shutdownDeviceBind(deviceId) { ok, msg ->
            mainHandler.post {
                onDone?.invoke(ok, msg.ifEmpty { if (ok) "关机占用已释放" else "关机同步失败" })
            }
        }
    }

    fun clearBindLocal() {
        clearAuthState()
        OfficerProfileStore.clear()
        BindBootMarker.clear(appContext)
        notifyStatus()
    }

    data class AlbumMediaItem(
        val id: String,
        val file: File,
        val isVideo: Boolean,
        val createdAt: Long,
        val explanation: String = "",
    )

    fun getAlbumMediaItems(): List<AlbumMediaItem> {
        val map = linkedMapOf<String, AlbumMediaItem>()
        for (item in albumItems) {
            if (!item.file.exists()) continue
            map[item.file.absolutePath] = AlbumMediaItem(
                id = item.id,
                file = item.file,
                isVideo = false,
                createdAt = item.createdAt,
                explanation = item.explanation,
            )
        }
        val albumDir = com.aifieldcam.app.util.AlbumStore.albumDir(appContext)
        albumDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg", ignoreCase = true) }
            ?.forEach { file ->
                val key = file.absolutePath
                if (!map.containsKey(key)) {
                    map[key] = AlbumMediaItem(
                        id = "photo-${file.name}",
                        file = file,
                        isVideo = false,
                        createdAt = file.lastModified(),
                    )
                }
            }
        for (item in videoItems) {
            val file = item.file ?: continue
            if (!file.exists()) continue
            map[file.absolutePath] = AlbumMediaItem(
                id = item.id,
                file = file,
                isVideo = true,
                createdAt = item.stoppedAt.takeIf { it > 0 } ?: item.startedAt,
            )
        }
        val videoDir = PhoneCameraHelper.videoDir(appContext)
        videoDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4", ignoreCase = true) }
            ?.forEach { file ->
                val key = file.absolutePath
                if (!map.containsKey(key)) {
                    map[key] = AlbumMediaItem(
                        id = "video-${file.name}",
                        file = file,
                        isVideo = true,
                        createdAt = file.lastModified(),
                    )
                }
            }
        return map.values.sortedByDescending { it.createdAt }
    }

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
        startWebRtcCommandPoll()
        val successMsg = patrolLoginSuccessMessage(result.message)
        showToast(successMsg)
        onDone(true, successMsg)
    }

    /** 修改后端地址后清除绑定态，需重新扫码。 */
    fun onApiBaseUrlChanged(onDone: ((Boolean, String) -> Unit)? = null) {
        clearBindLocal()
        mainHandler.post {
            notifyStatus()
            onDone?.invoke(false, MSG_NEED_BIND)
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
            onDone(null, MSG_NEED_BIND)
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

    /** 当前激活的场景 ID（PTT 语音切换场景后设置） */
    @Volatile
    var currentSceneId: String? = null
        private set

    fun runExpertConsult(
        question: String? = null,
        captureFirst: Boolean = false,
        onDone: (DemoScenarios.SceneResult?, String) -> Unit,
    ) {
        if (!isLoggedIn()) {
            onDone(null, MSG_NEED_BIND)
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
        notifyStatus()
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
                notifyStatus()
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

    /**
     * PTT 长按匹配到场景：切换场景 + 带入照片和问题。
     */
    fun switchSceneAndAsk(
        sceneId: String,
        snapshotJpeg: ByteArray?,
        question: String,
    ) {
        currentSceneId = sceneId
        val meta = DemoScenarios.all.find { it.id == sceneId }
        val title = meta?.title ?: sceneId
        TtsSpeaker.speak("已切换至${title}模式")

        runDemoScenario(sceneId) { sceneResult, err ->
            if (sceneResult != null) {
                notifyStatus()
            }
            // 同时将照片和问题发送给 AI 做深层分析
            if (snapshotJpeg != null) {
                postExpertConsult(question, snapshotJpeg) { _, _ -> }
            }
        }
    }

    /**
     * PTT 长按未匹配到场景：通用 AI 视觉咨询。
     */
    fun snapAskExpert(snapshotJpeg: ByteArray?, question: String) {
        if (snapshotJpeg == null) {
            TtsSpeaker.speak("未能抓拍到画面，请重试")
            return
        }
        postExpertConsult(question, snapshotJpeg) { result, err ->
            if (err.isNotBlank()) {
                TtsSpeaker.speak(err)
            }
        }
    }

    fun sendChatText(
        text: String,
        onDone: (reply: String, err: String, demo: DemoScenarios.SceneResult?) -> Unit,
    ) {
        if (!isLoggedIn()) {
            onDone("", MSG_NEED_BIND, null)
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
        notifyStatus()
        ApiClient.postChat(workerToken, sessionId, devId, text, deviceState) { ok, body, err ->
            mainHandler.post {
                aiChatInFlight = false
                syncZe69Indicators()
                notifyStatus()
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

    /** PTT 长按抓帧：录像中从流中取，非录像时临时开相机抓一帧 + FGS 保活 */
    fun grabSnapshot(onFrame: (ByteArray?) -> Unit) {
        if (NativeRecorder.isRecording()) {
            NativeRecorder.grabRecordingFrame(onFrame)
        } else {
            if (!DeviceProfile.isDsjZecn6a1) {
                mainHandler.post { onFrame(null) }
                return
            }
            RecordingForegroundService.ensureRunning(appContext, forRecording = false)
            NativeRecorder.grabSingleFrame(appContext) { jpeg ->
                RecordingForegroundService.releaseIfIdle(appContext)
                onFrame(jpeg)
            }
        }
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
    }

    // ── MQTT 信令通道 ──

    /** MQTT 连接成功后，启动心跳并订阅远程指令 */
    fun onMqttConnected() {
        MqttTopicRouter.onConnected(this)
        MqttHeartbeat.start { collectHeartbeatState() }
    }

    /** MQTT 断连 */
    fun onMqttDisconnected() {
        MqttHeartbeat.stop()
    }

    /** 处理云端广播通知 */
    fun handleBroadcast(title: String, body: String, level: String) {
        val text = listOfNotNull(title.takeIf { it.isNotBlank() }, body.takeIf { it.isNotBlank() })
            .joinToString("：")
        if (text.isNotBlank()) {
            TtsSpeaker.speak(text)
        }
        if (level == "urgent") {
            showToast("【紧急通知】$text")
        }
    }

    /** SOS 事件上报（F3 长按触发后调用） */
    fun publishSosEvent() {
        val json = org.json.JSONObject().apply {
            put("type", "sos")
            put("device_id", officerDeviceId)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        val topic = MqttTopicRouter.eventTopic("/thing/event/sos/post")
        MqttClient.publish(topic, json.toString(), qos = 2)
    }

    /** 录像/拍照保存后通知云端 */
    fun publishMediaEvent(fileName: String, fileSize: Long, mediaType: String) {
        val json = org.json.JSONObject().apply {
            put("file_name", fileName)
            put("file_size", fileSize)
            put("media_type", mediaType)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        val topic = MqttTopicRouter.eventTopic("/thing/event/media/post")
        MqttClient.publish(topic, json.toString(), qos = 1)
    }

    /** 录像开始/停止时上报状态变更 */
    fun publishRecordStateEvent(active: Boolean) {
        val json = org.json.JSONObject().apply {
            put("action", if (active) "started" else "stopped")
            put("device_id", officerDeviceId)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        val topic = MqttTopicRouter.eventTopic("/thing/event/record/post")
        MqttClient.publish(topic, json.toString(), qos = 1)
    }

    // ── V2 视频通信 ──

    /** WebRTC SDP answer 下行 → WebRtcPeer */
    fun onWebRtcSdpAnswer(sdp: String) {
        WebRtcPeer.onSdpAnswer(sdp)
    }

    /** WebRTC ICE candidate 下行 → WebRtcPeer */
    fun onWebRtcIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        WebRtcPeer.onRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
    }

    /** 云端发起 WebRTC 呼叫 → 设备创建 offer + 预览推流 */
    fun onWebRtcCallStart(caller: String, callId: String) {
        VideoStreamCoordinator.prepareCall(callId)
        TtsSpeaker.speak("指挥中心请求视频连线")
        WebRtcPeer.onSdpOfferReady = { sdp ->
            ApiClient.postWebRtcOffer(callId, sdp) { ok, err ->
                if (!ok) Log.w("SessionManager", "webrtc offer post failed: $err")
            }
            if (MqttClient.isConnected()) {
                val json = org.json.JSONObject().apply {
                    put("type", "offer")
                    put("sdp", sdp)
                    put("callId", callId)
                }
                MqttClient.publish(
                    MqttTopicRouter.eventTopic("/thing/event/webrtc/sdp/offer"),
                    json.toString(),
                    qos = 1,
                )
            }
        }
        WebRtcPeer.onIceCandidateReady = { candidate, sdpMid, idx ->
            ApiClient.postWebRtcIce(callId, candidate, sdpMid, idx) { _, _ -> }
            if (MqttClient.isConnected()) {
                val json = org.json.JSONObject().apply {
                    put("candidate", candidate)
                    put("sdpMid", sdpMid)
                    put("sdpMLineIndex", idx)
                }
                MqttClient.publish(
                    MqttTopicRouter.eventTopic("/thing/event/webrtc/ice/add"),
                    json.toString(),
                    qos = 1,
                )
            }
        }
        WebRtcPeer.onCallConnected = {
            startVideoStream(VideoStreamManager.Mode.WEBRTC, "cloud-call")
        }
        WebRtcPeer.onCallEnded = {
            stopVideoStream("cloud-call-end")
        }
        WebRtcPeer.createOfferAndCall(callId)
        // 简化信令：offer 发出后即开始预览推流（不等待 answer）
        mainHandler.postDelayed({
            if (WebRtcPeer.isActive()) {
                startVideoStream(VideoStreamManager.Mode.WEBRTC, "cloud-call-preview")
            }
        }, 500L)
    }

    /** 云端结束 WebRTC 呼叫 */
    fun onWebRtcCallEnd() {
        stopVideoStream("cloud-call-end")
    }

    /**
     * 画面监看开始：进房共摄推视频、红灯；不打断 AI、无 TTS。
     */
    fun onWatchStart(
        callId: String,
        caller: String,
        credentials: CommandCallCredentials,
    ) {
        Log.i("SessionManager", "watch start from $caller callId=$callId")
        // TRTC join 含阻塞等待，勿占主线程（否则易与 LiteAV 回调死锁/漏进房）
        ioExecutor.execute {
            val ok = CommandCallController.onWatchStart(callId, credentials)
            mainHandler.post {
                if (!ok) {
                    Log.w(
                        "SessionManager",
                        "watch join failed reason=${CommandCallController.lastFailureReason()}",
                    )
                } else {
                    ensurePipelineRecordingForCommandCall()
                    CommandCallController.ensureCoCaptureWhileInCall()
                    if (isRecording()) {
                        CommandCallController.ensureCoCaptureWhileInCall()
                    }
                }
                syncZe69Indicators()
                notifyStatus()
            }
        }
    }

    /**
     * 指挥连线开始：先打断 AI 全双工，再自动进房并尝试连线共摄旁路。
     * 无 TTS；不发送 answer/busy/hangup。
     */
    fun onCommandCallStart(
        callId: String,
        caller: String,
        credentials: CommandCallCredentials,
    ) {
        Log.i("SessionManager", "command_call start from $caller callId=$callId")
        commandCallAiGate.onCallStart()
        ioExecutor.execute {
            val ok = CommandCallController.onCallStart(callId, credentials)
            mainHandler.post {
                if (!ok) {
                    Log.w(
                        "SessionManager",
                        "command_call join failed reason=${CommandCallController.lastFailureReason()}",
                    )
                } else {
                    ensurePipelineRecordingForCommandCall()
                    CommandCallController.ensureCoCaptureWhileInCall()
                    if (isRecording()) {
                        CommandCallController.ensureCoCaptureWhileInCall()
                    }
                }
                syncZe69Indicators()
                notifyStatus()
            }
        }
    }

    /** 监看同房升级为指挥连线：打断 AI，无 TTS，不断流。 */
    fun onCommandCallUpgrade(
        callId: String,
        caller: String,
        credentials: CommandCallCredentials,
    ) {
        Log.i("SessionManager", "command_call upgrade from $caller callId=$callId")
        commandCallAiGate.onCallStart()
        val ok = CommandCallController.onCallUpgrade(callId, credentials)
        if (!ok) {
            Log.w("SessionManager", "command_call upgrade failed")
        } else {
            ensurePipelineRecordingForCommandCall()
            if (isRecording()) {
                CommandCallController.ensureCoCaptureWhileInCall()
            }
        }
        syncZe69Indicators()
        notifyStatus()
    }

    /** 指挥连线进房后确保本机在录，以便共摄推画面。 */
    private fun ensurePipelineRecordingForCommandCall() {
        if (NativeRecorder.isRecording() || NativeRecorder.isBusy()) return
        if (!startRecord()) {
            Log.w(
                "SessionManager",
                "command_call: auto record failed, room joined but no video until recording starts",
            )
        }
    }

    /** 指挥连线结束：停对讲/共摄、退房；不自动恢复 AI。 */
    fun onCommandCallEnd(callId: String = "") {
        Log.i("SessionManager", "command_call end callId=$callId")
        CommandCallController.onCallEnd(callId)
        commandCallAiGate.onCallEnd()
        // 不自动恢复被打断的 AI 会话；F6 长按能力随 isInCall=false 恢复
        syncZe69Indicators()
        notifyStatus()
    }

    /** 指挥来电：断开 Realtime、清听麦态；结束后由门闩决定不恢复。 */
    private fun interruptAiAssistantForCommandCall() {
        PttSnapAskController.interruptForCommandCall()
        aiChatInFlight = false
        realtimeVoicePhase = RealtimeVoicePhase.IDLE
        aiListeningSources.clearAll()
        syncZe69Indicators()
    }

    /** 启动视频推流（V2 管线 + JPEG/NAL 预览或 GB28181 RTP） */
    fun startVideoStream(mode: VideoStreamManager.Mode, reason: String) {
        when (mode) {
            VideoStreamManager.Mode.GB28181 -> {
                ensurePipelineRecordingForStream()
                SipUaClient.startStreaming()
                StreamingPipelineWatchdog.onStopStreaming = { stopVideoStream("stream-watchdog") }
                StreamingPipelineWatchdog.start()
                TtsSpeaker.speak("已连接到监控平台")
            }
            VideoStreamManager.Mode.WEBRTC -> {
                val callId = VideoStreamCoordinator.activeCallId()
                if (callId.isNotBlank() && !VideoStreamCoordinator.isPreviewStreaming()) {
                    VideoStreamCoordinator.beginCall(this, callId, reason)
                }
                TtsSpeaker.speak("视频连线已建立")
            }
            VideoStreamManager.Mode.BOTH -> {
                ensurePipelineRecordingForStream()
                SipUaClient.startStreaming()
                val callId = VideoStreamCoordinator.activeCallId()
                if (callId.isNotBlank() && !VideoStreamCoordinator.isPreviewStreaming()) {
                    VideoStreamCoordinator.beginCall(this, callId, reason)
                }
            }
            VideoStreamManager.Mode.NONE -> {}
        }
        notifyStatus()
    }

    /** GB28181 拉流前确保本机管线在录（单编码器双输出）。 */
    private fun ensurePipelineRecordingForStream() {
        NativeRecorder.useMediaEncoderPipeline = true
        if (!NativeRecorder.isRecording() && !NativeRecorder.isBusy()) {
            if (!startRecord()) {
                showStreamError(getLastActionError().ifBlank { "无法开录，监控推流失败" })
            }
        }
    }

    /** 停止视频推流 */
    fun stopVideoStream(reason: String) {
        val fromRemoteBye = reason == "sip-bye"
        SipUaClient.stopStreaming(sendBye = !fromRemoteBye)
        VideoStreamCoordinator.endCall(this, reason)
        VideoStreamManager.stopAll()
        notifyStatus()
    }

    fun onVideoStreamEnded(reason: String) {
        Log.i("SessionManager", "video stream ended: $reason")
    }

    fun showStreamError(message: String) {
        showToast(message)
        TtsSpeaker.speak(message)
    }

    private fun startWebRtcCommandPoll() {
        if (webrtcPollScheduled) return
        webrtcPollScheduled = true
        mainHandler.postDelayed(webrtcPollRunnable, 5_000L)
    }

    private fun stopWebRtcCommandPoll() {
        webrtcPollScheduled = false
        mainHandler.removeCallbacks(webrtcPollRunnable)
    }

    private fun pollWebRtcCommands() {
        val deviceId = officerDeviceId.ifBlank { return }
        ApiClient.pollWebRtcDevice(deviceId) { cmd, err ->
            if (cmd == null) {
                if (err.isNotBlank()) Log.d("SessionManager", "webrtc poll: $err")
                return@pollWebRtcDevice
            }
            val action = cmd.optString("action", "")
            if (action == "call_start") {
                val callId = cmd.optString("call_id", "")
                val caller = cmd.optString("caller", "指挥中心")
                if (callId.isNotBlank() && !WebRtcPeer.isActive()) {
                    mainHandler.post {
                        VideoStreamCoordinator.onHttpCallStart(this, callId, caller)
                    }
                }
            } else if (action == "call_end") {
                mainHandler.post { stopVideoStream("remote-hangup") }
            }
        }
    }

    private fun pollCommandCallCommands() {
        val deviceId = officerDeviceId.ifBlank { return }
        ApiClient.pollCommandCallDevice(deviceId) { cmd, err ->
            if (cmd == null) {
                if (err.isNotBlank()) Log.d("SessionManager", "command_call poll: $err")
                return@pollCommandCallDevice
            }
            val action = cmd.optString("action", "")
            when {
                action == "watch_start" || action == "call_start" || action == "call_upgrade" -> {
                    val start = CommandCallSignalParser.parseStart(cmd) ?: return@pollCommandCallDevice
                    mainHandler.post { dispatchCommandCallStartSignal(start) }
                }
                CommandCallSignalParser.isEndAction(action) -> {
                    val endId = CommandCallSignalParser.parseEndCallId(cmd)
                    mainHandler.post { onCommandCallEnd(endId) }
                }
            }
        }
    }

    private fun dispatchCommandCallStartSignal(start: CommandCallSignalParser.StartSignal) {
        when (start.kind) {
            CommandCallSignalParser.StartKind.WATCH -> {
                if (!CommandCallController.isInRoom()) {
                    onWatchStart(start.callId, start.caller, start.credentials)
                }
            }
            CommandCallSignalParser.StartKind.UPGRADE -> {
                onCommandCallUpgrade(start.callId, start.caller, start.credentials)
            }
            CommandCallSignalParser.StartKind.CALL -> {
                if (!CommandCallController.isInRoom()) {
                    onCommandCallStart(start.callId, start.caller, start.credentials)
                }
            }
        }
    }

    /** GB28181 SIP 注册成功 */
    fun onSipRegistered() {
        showToast("已连接到视频监控平台")
    }

    /** GB28181 SIP 注销 */
    fun onSipUnregistered() {
        // SIP 注销时清理状态
    }

    /** GB28181 平台开始拉流（收到 INVITE） */
    fun onSipStreamActive() {
        startVideoStream(VideoStreamManager.Mode.GB28181, "sip-invite")
    }

    private fun collectHeartbeatState(): MqttHeartbeat.SessionState {
        val storageFree = MediaStorageLocator.freeMb(
            PhoneCameraHelper.videoDir(appContext)
        )
        return MqttHeartbeat.SessionState(
            batteryPct = getBatteryPct(),
            isCharging = getBatteryCharging(),
            storageFreeMb = storageFree,
            isRecording = isRecording(),
            isAudioRecording = isAudioRecording(),
            gpsLat = 0.0,
            gpsLng = 0.0,
            signalStrength = 0,
        )
    }

    private fun getBatteryPct(): Int {
        return try {
            val intent = appContext.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0) (level * 100 / scale) else 0
        } catch (_: Exception) {
            0
        }
    }

    private fun getBatteryCharging(): Boolean {
        return try {
            val intent = appContext.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            false
        }
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
        // 按键当下黄灯闪，与 F2 同步
        DeviceStatusIndicator.setAudioRecording(true)
        return true
    }

    fun stopAudioRecord(): Boolean {
        lastErrorLocal = ""
        val block = MediaInteractionPolicy.canStopAudio(mediaInteractionState())
        if (block != null) return applyBlock(block)
        // 停录键当下灭灯
        DeviceStatusIndicator.setAudioRecording(false)
        NativeAudioRecorder.stopRecording { file, err ->
            mainHandler.post {
                DeviceStatusIndicator.setAudioRecording(false)
                if (file != null) {
                    showToast("录音已保存")
                    TtsSpeaker.speak("录音已保存")
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

    private fun startNativeRecord(segmentContinue: Boolean = false): Boolean {
        if (nativeVideoSaving) {
            lastErrorLocal = "正在保存录像，请稍候"
            return false
        }
        val block = MediaInteractionPolicy.canStartVideo(mediaInteractionState())
        if (block != null) {
            if (!segmentContinue &&
                (block is MediaInteractionPolicy.Block.LowBattery ||
                    block is MediaInteractionPolicy.Block.AiBusy)
            ) {
                showToast(block.message)
            }
            return applyBlock(block)
        }
        val videoDir = PhoneCameraHelper.videoDir(appContext)
        if (DeviceProfile.CONTINUOUS_LOOP_RECORDING && !segmentContinue) {
            val loopOk = LoopRecordingStorage.ensureSpaceForNextSegment(
                context = appContext,
                videoDir = videoDir,
                protectedPath = NativeRecorder.currentOutputFile()?.absolutePath,
            ) { deleted ->
                mainHandler.post {
                    videoItems.removeAll { it.file?.absolutePath == deleted.absolutePath }
                    notifyStatus()
                }
            }
            if (!loopOk) {
                lastErrorLocal = "存储空间不足，无法开始循环录像"
                showToast(lastErrorLocal)
                return false
            }
        }
        val freeMb = MediaStorageLocator.freeMb(videoDir)
        if (freeMb <= 0) {
            lastErrorLocal = "存储空间已满，无法开始录像"
            if (!segmentContinue) {
                showToast(lastErrorLocal)
                TtsSpeaker.speak("存储空间不足，无法录制，请及时上传清空内存")
            }
            return false
        }
        if (!segmentContinue && !DeviceProfile.CONTINUOUS_LOOP_RECORDING && freeMb in 1..1024) {
            val msg = "存储空间低（剩余 ${freeMb}MB），将自动保存后停止"
            showToast(msg)
            TtsSpeaker.speak("存储空间不足，即将自动停止，请及时上传清空内存")
        }
        notifyStatus()
        RecordingForegroundService.ensureRunning(appContext, forRecording = true)
        NativeRecorder.startRecording(
            appContext,
            onStarted = {
                mainHandler.post { onNativeRecordStarted(segmentContinue) }
            },
            onError = { err ->
                mainHandler.post {
                    segmentRolloverActive = false
                    syncZe69Indicators()
                    RecordingForegroundService.releaseIfIdle(appContext)
                    lastErrorLocal = err
                    showToast(err)
                    notifyStatus()
                }
            },
        )
        // 按键当下亮红灯（isPreparing=true），与 F5 同步，不等相机打开
        syncZe69Indicators()
        return true
    }

    private fun stopNativeRecord(): Boolean {
        if (isVideoStreaming()) {
            stopVideoStream("record-stop")
        }
        val block = MediaInteractionPolicy.canStopVideo(mediaInteractionState())
        if (block != null) return applyBlock(block)
        beginNativeVideoSaveUi()
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

    /** 停录请求后立即更新 UI/LED，重活（MP4 finalize、相册复制）在后台线程完成 */
    private fun beginNativeVideoSaveUi() {
        nativeVideoSaving = true
        if (isVideoStreaming()) {
            VideoStreamCoordinator.endCall(this, "record-saving")
            VideoStreamManager.stopAll()
        }
        RecordingPipelineWatchdog.stop()
        StorageRetentionWatchdog.stop()
        DeviceStatusIndicator.setVideoRecording(false)
        notifyStatus()
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
        // 按键当下红灯闪一次，与 F4 同步，不等 JPEG 落盘
        Ze69Hardware.pulseCaptureFlash()
        NativeRecorder.captureStill(
            appContext,
            onCaptured = { file ->
                mainHandler.post {
                    RecordingForegroundService.releaseIfIdle(appContext)
                    val jpeg = file.readBytes()
                    onPhonePhotoCaptured(jpeg, file)
                }
            },
            onError = { err ->
                mainHandler.post {
                    RecordingForegroundService.releaseIfIdle(appContext)
                    lastErrorLocal = err
                    showToast(err)
                    DeviceStatusIndicator.refresh()
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

    private fun onNativeRecordStarted(segmentContinue: Boolean = false) {
        aiListeningSources.clearBlockedByRecording()
        segmentRolloverActive = false
        activeRecordId = "native-${System.currentTimeMillis()}"
        nativeRecordStartedAt = System.currentTimeMillis()
        DeviceStatusIndicator.setVideoRecording(true)
        val videoDir = PhoneCameraHelper.videoDir(appContext)
        RecordingPipelineWatchdog.start(videoDir)
        StorageRetentionWatchdog.start(appContext, videoDir)
        VideoStreamCoordinator.onRecordStartedForStream()
        if (CommandCallController.isInRoom()) {
            CommandCallController.ensureCoCaptureWhileInCall()
        }
        if (VideoStreamCoordinator.isPreviewStreaming() && NativeRecorder.isUsingPipeline()) {
            StreamingPipelineWatchdog.onStopStreaming = { stopVideoStream("stream-watchdog") }
            StreamingPipelineWatchdog.start()
        }
        videoItems.add(
            0,
            VideoItem(
                id = activeRecordId,
                startedAt = nativeRecordStartedAt,
                stoppedAt = 0,
                durationMs = 0,
                note = if (segmentContinue) {
                    "本机录像中（续段）· ${DeviceProfile.VIDEO_WIDTH}p"
                } else {
                    "本机录像中 · ${DeviceProfile.VIDEO_WIDTH}p"
                },
            ),
        )
        notifyStatus()
        if (segmentContinue) {
            Log.i(TAG, "segment continue: recording resumed")
        } else {
            // 不 Toast：状态栏「录制中」+ 红灯即可
            publishRecordStateEvent(true)
        }
    }

    private fun onNativeRecordStopped(
        file: File,
        toastMessage: String? = null,
        speakSaved: Boolean = true,
    ) {
        RecordingForegroundService.releaseIfIdle(appContext)
        val startedAt = nativeRecordStartedAt
        val stoppedAt = System.currentTimeMillis()
        val wallMs = (stoppedAt - startedAt).coerceAtLeast(0)
        val sizeKb = file.length() / 1024
        val savedItemId = activeRecordId.ifEmpty { "native-${System.currentTimeMillis()}" }
        videoItems.find { it.id == activeRecordId }?.let { item ->
            item.stoppedAt = stoppedAt
            item.durationMs = wallMs
            item.file = file
            item.note = "本机录像 ${DeviceProfile.VIDEO_WIDTH}p · ${wallMs / 1000}s · 保存中…"
        } ?: run {
            videoItems.add(
                0,
                VideoItem(
                    id = savedItemId,
                    startedAt = startedAt,
                    stoppedAt = stoppedAt,
                    durationMs = wallMs,
                    note = "本机录像 ${DeviceProfile.VIDEO_WIDTH}p · ${wallMs / 1000}s · 保存中…",
                    file = file,
                ),
            )
        }
        activeRecordId = ""
        nativeRecordStartedAt = 0L
        // MP4 finalize 已在 stopRecording 完成；相册复制/读时长在后台进行，不应阻塞全局「正在保存录像」
        nativeVideoSaving = false
        notifyStatus()

        ioExecutor.execute {
            val saveStartedMs = System.currentTimeMillis()
            val mediaMs = VideoMetadata.durationMs(file)
            val durationMs = resolveRecordDurationMs(wallMs, mediaMs)
            val galleryOk = GallerySaver.saveVideoToGallery(appContext, file)
            Log.i(
                TAG,
                "video save background done ${file.name} mediaMs=$mediaMs galleryOk=$galleryOk " +
                    "elapsed=${System.currentTimeMillis() - saveStartedMs}ms size=${file.length()}",
            )
            mainHandler.post {
                finishNativeVideoSave(
                    file = file,
                    savedItemId = savedItemId,
                    durationMs = durationMs,
                    wallMs = wallMs,
                    mediaMs = mediaMs,
                    sizeKb = sizeKb,
                    galleryOk = galleryOk,
                    toastMessage = toastMessage,
                    speakSaved = speakSaved,
                )
            }
        }
    }

    private fun resolveRecordDurationMs(wallMs: Long, mediaMs: Long): Long = when {
        mediaMs > 0 && wallMs > 0 && mediaMs < wallMs - 3_000 -> mediaMs
        mediaMs > 0 -> mediaMs
        else -> wallMs
    }

    private fun finishNativeVideoSave(
        file: File,
        savedItemId: String,
        durationMs: Long,
        wallMs: Long,
        mediaMs: Long,
        sizeKb: Long,
        galleryOk: Boolean,
        toastMessage: String?,
        @Suppress("UNUSED_PARAMETER") speakSaved: Boolean,
        publishRecordEnd: Boolean = true,
        showUserFeedback: Boolean = true,
    ) {
        nativeVideoSaving = false
        videoItems.find { it.id == savedItemId || it.file == file }?.let { item ->
            item.stoppedAt = item.stoppedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
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
        }
        notifyStatus()
        // 正常保存静默；仅中断原因（如存储不足）保留 Toast
        if (showUserFeedback && !toastMessage.isNullOrBlank()) {
            showToast(
                if (galleryOk) toastMessage
                else "$toastMessage（系统相册写入失败，文件在应用内）",
            )
        }
        if (publishRecordEnd) {
            publishRecordStateEvent(false)
        }
        publishMediaEvent(file.name, file.length(), "video")
    }

    /** 停录失败 / 取消启动：释放 FGS、清 UI 状态、移除「录像中」占位项 */
    private fun abortNativeRecordingSession(message: String, toast: Boolean) {
        nativeVideoSaving = false
        segmentRolloverActive = false
        RecordingPipelineWatchdog.stop()
        StorageRetentionWatchdog.stop()
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
        if (RecordingSegmentPolicy.isSegmentRollover(reason) && !NativeRecorder.isSeamlessLoopMode()) {
            handleSegmentRollover(reason)
            return
        }
        beginNativeVideoSaveUi()
        NativeRecorder.stopRecording { file, err ->
            mainHandler.post {
                recordingInterruptHandling = false
                if (file != null) {
                    val isStorageStop = reason.contains("存储空间不足")
                    onNativeRecordStopped(
                        file,
                        toastMessage = reason,
                        speakSaved = !isStorageStop,
                    )
                    if (isStorageStop) {
                        TtsSpeaker.speak("存储空间不足，录像已自动保存")
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

    /** 单文件达 1GB：保存当前片并自动开下一段；不停 FGS/看门狗，红灯保持闪烁 */
    private fun handleSegmentRollover(reason: String) {
        segmentRolloverActive = true
        syncZe69Indicators()
        Log.i(TAG, "segment rollover begin: $reason")
        NativeRecorder.stopRecording { file, err ->
            mainHandler.post {
                recordingInterruptHandling = false
                if (file != null) {
                    onNativeRecordSegmentSaved(file)
                    mainHandler.postDelayed({
                        if (NativeRecorder.isBusy()) return@postDelayed
                        val ok = startNativeRecord(segmentContinue = true)
                        if (!ok) {
                            segmentRolloverActive = false
                            syncZe69Indicators()
                            RecordingPipelineWatchdog.stop()
                            StorageRetentionWatchdog.stop()
                            RecordingForegroundService.releaseIfIdle(appContext)
                            showToast(lastErrorLocal.ifBlank { "分段续录失败" })
                            Log.w(TAG, "segment continue failed: $lastErrorLocal")
                        }
                    }, RecordingSegmentPolicy.continueDelayMs())
                } else {
                    segmentRolloverActive = false
                    syncZe69Indicators()
                    abortNativeRecordingSession(
                        message = err.ifBlank { reason }.ifBlank { "分段保存失败" },
                        toast = true,
                    )
                }
            }
        }
    }

    /** 热换片：旧文件 finalize 完成，录像 Session 未断 */
    private fun onNativeRecordSeamlessSegmentRotated(file: File) {
        onNativeRecordSegmentSaved(file)
        if (!isRecording()) return
        activeRecordId = "native-${System.currentTimeMillis()}"
        nativeRecordStartedAt = System.currentTimeMillis()
        videoItems.add(
            0,
            VideoItem(
                id = activeRecordId,
                startedAt = nativeRecordStartedAt,
                stoppedAt = 0,
                durationMs = 0,
                note = "本机录像中（续片）· ${DeviceProfile.VIDEO_WIDTH}p",
            ),
        )
        DeviceStatusIndicator.setVideoRecording(true)
        notifyStatus()
        Log.i(TAG, "seamless segment UI rolled to $activeRecordId")
    }

    /** 分段完成：后台保存，不 Toast/TTS，不发布 record/end */
    private fun onNativeRecordSegmentSaved(file: File) {
        val startedAt = nativeRecordStartedAt
        val stoppedAt = System.currentTimeMillis()
        val wallMs = (stoppedAt - startedAt).coerceAtLeast(0)
        val savedItemId = activeRecordId.ifEmpty { "native-${System.currentTimeMillis()}" }
        videoItems.find { it.id == activeRecordId }?.let { item ->
            item.stoppedAt = stoppedAt
            item.durationMs = wallMs
            item.file = file
            item.note = "本机录像 ${DeviceProfile.VIDEO_WIDTH}p · ${wallMs / 1000}s · 保存中…"
        } ?: run {
            videoItems.add(
                0,
                VideoItem(
                    id = savedItemId,
                    startedAt = startedAt,
                    stoppedAt = stoppedAt,
                    durationMs = wallMs,
                    note = "本机录像 ${DeviceProfile.VIDEO_WIDTH}p · ${wallMs / 1000}s · 保存中…",
                    file = file,
                ),
            )
        }
        activeRecordId = ""
        nativeRecordStartedAt = 0L
        notifyStatus()

        ioExecutor.execute {
            val saveStartedMs = System.currentTimeMillis()
            val mediaMs = VideoMetadata.durationMs(file)
            val durationMs = resolveRecordDurationMs(wallMs, mediaMs)
            val galleryOk = GallerySaver.saveVideoToGallery(appContext, file)
            Log.i(
                TAG,
                "segment saved ${file.name} mediaMs=$mediaMs galleryOk=$galleryOk " +
                    "elapsed=${System.currentTimeMillis() - saveStartedMs}ms",
            )
            mainHandler.post {
                finishNativeVideoSave(
                    file = file,
                    savedItemId = savedItemId,
                    durationMs = durationMs,
                    wallMs = wallMs,
                    mediaMs = mediaMs,
                    sizeKb = file.length() / 1024,
                    galleryOk = galleryOk,
                    toastMessage = null,
                    speakSaved = false,
                    publishRecordEnd = false,
                    showUserFeedback = false,
                )
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
                // 专家咨询抓拍：静默
                publishMediaEvent(savedFile.name, savedFile.length(), "photo")
                return@post
            }
            onImageCaptured(jpeg, savedFile)
            // 拍照成功静默：不 Toast / 不播报
            publishMediaEvent(savedFile.name, savedFile.length(), "photo")
        }
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
            // 录像保存静默
        }
    }

    fun analyzeUploadedImage(jpeg: ByteArray, onDone: (explanation: String, err: String) -> Unit) {
        if (!isLoggedIn()) {
            onDone("", MSG_NEED_BIND)
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

    fun analyzeUploadedVideo(frames: List<ByteArray>, frameCount: Int, onDone: (explanation: String, err: String) -> Unit) {
        if (!isLoggedIn()) {
            onDone("", MSG_NEED_BIND)
            return
        }
        val imagesBase64 = frames.map { Base64.getEncoder().encodeToString(it) }
        ApiClient.postVideo(workerToken, sessionId, imagesBase64, frameCount) { ok, body, err ->
            mainHandler.post {
                if (!ok || body == null) {
                    if (err == ApiClient.ERR_AUTH_EXPIRED) {
                        reloginAndRetry(
                            onSuccess = { analyzeUploadedVideo(frames, frameCount, onDone) },
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
        // 按键同步：开录请求后 isPreparing 即为 true，不必等相机打开；停录后 nativeVideoSaving 立即灭灯
        DeviceStatusIndicator.setCommandCallActive(CommandCallController.isInCall())
        DeviceStatusIndicator.setCommandCallPtt(CommandCallIntercom.isTalking())
        DeviceStatusIndicator.setVideoRecording(shouldShowVideoRecordingLed())
        DeviceStatusIndicator.setAudioRecording(isAudioRecording())
        DeviceStatusIndicator.setAiListening(isAiBusy())
    }

    private fun shouldShowVideoRecordingLed(): Boolean =
        !nativeVideoSaving &&
            (
                isRecording() ||
                    NativeRecorder.isPreparing() ||
                    segmentRolloverActive
                )

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
                DeviceCmd.CMD_START_AI_LISTEN -> setRemoteAiListening(true)
                DeviceCmd.CMD_STOP_AI_LISTEN -> setRemoteAiListening(false)
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
        if (!OfficerProfileStore.isBoundLocally()) {
            clearAuthState()
            return
        }
        VerificationStateStore.markLoginComplete()
        startWebRtcCommandPoll()
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
        stopWebRtcCommandPoll()
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
        clearBindLocal()
        onFail(MSG_SESSION_EXPIRED)
    }

    private fun showToast(@Suppress("UNUSED_PARAMETER") msg: String) {
        // 专机 UI：不使用 Toast 等原生浮层提示
    }

    private fun notifyStatus() {
        mainHandler.removeCallbacks(notifyStatusRunnable)
        mainHandler.post(notifyStatusRunnable)
    }

    private val notifyStatusRunnable = Runnable {
        statusListeners.forEach { it.onSessionChanged() }
    }

    companion object {
        private const val TAG = "SessionManager"
        private const val MSG_NEED_BIND = "请先到「我的」页扫码绑定人员"
        private const val MSG_SESSION_EXPIRED = "登录已过期，请到「我的」页重新扫码绑定"

        @Volatile
        private var instance: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(context).also { instance = it }
            }
        }
    }
}
