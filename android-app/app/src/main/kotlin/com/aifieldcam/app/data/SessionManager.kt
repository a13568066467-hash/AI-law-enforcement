package com.aifieldcam.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.aifieldcam.app.ble.BleConfig
import com.aifieldcam.app.data.AuthConfig
import com.aifieldcam.app.data.OfficerProfileStore
import com.aifieldcam.app.data.VerificationStateStore
import com.aifieldcam.app.demo.DemoScenarios
import com.aifieldcam.app.ble.BleConnState
import com.aifieldcam.app.ble.BleManager
import com.aifieldcam.app.platform.DeviceProfile
import com.aifieldcam.app.platform.NightVisionController
import com.aifieldcam.app.platform.Ze69Hardware
import com.aifieldcam.app.util.GallerySaver
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 页面统一入口（Session / BLE / 云端 API）
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
    private val ble = BleManager.getInstance(appContext)

    private var workerToken = ""
    private var sessionId = ""
    private var officerName = ""
    private var officerPhone = ""
    private var officerDepartment = ""
    private var officerEmployeeId = ""
    private var officerDeviceId = ""
    private var boundDeviceId = ""
    private var activeRecordId = ""
    private var pendingVideoRecordId = ""

    private val albumItems = CopyOnWriteArrayList<AlbumItem>()
    private val videoItems = CopyOnWriteArrayList<VideoItem>()
    private val statusListeners = CopyOnWriteArrayList<StatusListener>()

    private val imageListener = object : BleManager.ImageListener {
        override fun onImageReceived(jpeg: ByteArray, savedFile: File) {
            onImageFromBle(jpeg, savedFile)
        }
    }

    private val cmdEventListener = object : BleManager.CmdEventListener {
        override fun onCmdEvent(evtId: Int, payload: ByteArray) {
            onBleCmdEvent(evtId, payload)
        }
    }

    private val videoListener = object : BleManager.VideoListener {
        override fun onVideoReceived(data: ByteArray, savedFile: File) {
            onVideoFromBle(data, savedFile)
        }
    }

    private val bleStatusListener = object : BleManager.StatusListener {
        override fun onStatusChanged() {
            boundDeviceId = if (ble.connState == BleConnState.CONNECTED) "connected" else ""
            syncZe69Indicators()
            notifyStatus()
        }
    }

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

    fun getBleSummary(): String = ble.getStatusSummary()

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

    fun connectCamera(onDone: (Boolean, String) -> Unit) {
        ble.startConnect { ok, msg ->
            mainHandler.post { onDone(ok, msg) }
        }
    }

    fun disconnectCamera() {
        ble.disconnect()
        boundDeviceId = ""
        notifyStatus()
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
                        applyBleCmds(local.bleCmds)
                        onDone(local, "")
                    } else {
                        onDone(null, err)
                    }
                    return@post
                }
                applyBleCmds(result.bleCmds)
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
        val bleState = ble.deviceState
        if (bleState == BleConfig.FSM_RECORD &&
            (text.contains("识别") || text.contains("拍照"))
        ) {
            onDone("", "录像中请先停止录像", null)
            return
        }
        val devId = officerDeviceId.ifEmpty { boundDeviceId.ifEmpty { "unknown" } }
        ApiClient.postChat(workerToken, sessionId, devId, text, bleState) { ok, body, err ->
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
                applyBleCmds(body.bleCmds)
                onDone(body.reply, "", body.demo)
            }
        }
    }

    fun startRecord(): Boolean {
        if (!isBleConnected()) {
            lastErrorLocal = "未连接设备"
            return false
        }
        if (ble.deviceState == BleConfig.FSM_RECORD) {
            lastErrorLocal = "已在录像中"
            return false
        }
        return ble.writeCmd(BleConfig.CMD_START_RECORD)
    }

    fun stopRecord(): Boolean {
        if (!isBleConnected()) {
            lastErrorLocal = "未连接设备"
            return false
        }
        return ble.writeCmd(BleConfig.CMD_STOP_RECORD)
    }

    fun triggerCapture(): Boolean {
        if (!isBleConnected()) {
            lastErrorLocal = "未连接设备"
            return false
        }
        if (ble.deviceState == BleConfig.FSM_RECORD) {
            lastErrorLocal = "录像中无法拍照"
            return false
        }
        return ble.writeCmd(BleConfig.CMD_CAPTURE)
    }

    fun getLastActionError(): String = lastErrorLocal.ifBlank { ble.lastError }

    private var lastErrorLocal = ""

    fun isBleConnected(): Boolean = ble.connState == BleConnState.CONNECTED

    fun onPhonePhotoCaptured(jpeg: ByteArray, savedFile: File) {
        mainHandler.post {
            GallerySaver.saveImageToGallery(appContext, savedFile)
            onImageFromBle(jpeg, savedFile)
            showToast("照片已保存到手机相册")
        }
    }

    fun getDeviceSummary(): String = when {
        isBleConnected() -> "执法仪已连接"
        DeviceProfile.isDsjZecn6a1 -> DeviceProfile.summaryLine()
        else -> "执法仪未连接"
    }

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

    private fun onImageFromBle(jpeg: ByteArray, savedFile: File) {
        val item = AlbumItem(
            id = "img-${System.currentTimeMillis()}",
            file = savedFile,
            size = jpeg.size,
            explanation = if (savedFile.name.contains("_phone")) "手机拍摄" else "",
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

    private fun onVideoFromBle(data: ByteArray, savedFile: File) {
        val sizeKb = data.size / 1024
        val targetId = pendingVideoRecordId
        if (targetId.isNotEmpty()) {
            videoItems.find { it.id == targetId }?.let { item ->
                item.file = savedFile
                item.note = buildString {
                    append("BLE 录像")
                    if (item.durationMs > 0) append(" · ${item.durationMs}ms")
                    append(" · ${sizeKb}KB")
                    if (savedFile.extension.equals("jpg", ignoreCase = true)) {
                        append(" · 快照")
                    }
                }
            }
            pendingVideoRecordId = ""
        } else {
            videoItems.add(
                0,
                VideoItem(
                    id = "ble-${System.currentTimeMillis()}",
                    startedAt = System.currentTimeMillis(),
                    stoppedAt = System.currentTimeMillis(),
                    durationMs = 0,
                    note = "BLE 录像 · ${sizeKb}KB",
                    file = savedFile,
                ),
            )
        }
        notifyStatus()
        showToast("录像文件已保存")
    }

    private fun onBleCmdEvent(evtId: Int, payload: ByteArray) {
        when (evtId) {
            BleConfig.EVT_RECORD_STARTED -> {
                activeRecordId = "rec-${System.currentTimeMillis()}"
                Ze69Hardware.setRecordingIndicator(true)
                NightVisionController.onRecordingStarted()
                videoItems.add(
                    0,
                    VideoItem(
                        id = activeRecordId,
                        startedAt = System.currentTimeMillis(),
                        stoppedAt = 0,
                        durationMs = 0,
                        note = "录像中",
                    ),
                )
                notifyStatus()
            }
            BleConfig.EVT_RECORD_STOPPED -> {
                Ze69Hardware.setRecordingIndicator(false)
                NightVisionController.onRecordingStopped()
                pendingVideoRecordId = activeRecordId
                var durationMs = 0L
                var fileSize = 0L
                if (payload.size >= 5) {
                    durationMs = ByteBuffer.wrap(payload, 1, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int.toLong() and 0xFFFFFFFFL
                }
                if (payload.size >= 9) {
                    fileSize = ByteBuffer.wrap(payload, 5, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int.toLong() and 0xFFFFFFFFL
                }
                if (activeRecordId.isNotEmpty()) {
                    videoItems.find { it.id == activeRecordId }?.let { item ->
                        item.stoppedAt = System.currentTimeMillis()
                        item.durationMs = if (durationMs > 0) durationMs else {
                            (item.stoppedAt - item.startedAt).coerceAtLeast(0)
                        }
                        item.note = buildString {
                            append("已停止")
                            if (item.durationMs > 0) append(" · ${item.durationMs}ms")
                            if (fileSize > 0) append(" · 接收中 ${fileSize / 1024}KB")
                        }
                    }
                }
                activeRecordId = ""
                notifyStatus()
            }
            BleConfig.EVT_ERROR -> {
                val code = if (payload.size >= 2) payload[1].toInt() and 0xFF else 0
                val msg = when (code) {
                    BleConfig.ERR_AI_WHILE_RECORD -> "录像中无法开启 AI，请先停止录像"
                    BleConfig.ERR_CAPTURE_FAILED -> "拍照失败，请重试"
                    BleConfig.ERR_CAPTURE_BLOCKED -> "录像中无法拍照，请先停止录像"
                    BleConfig.ERR_OTA_UNSUPPORTED -> "固件 OTA 尚未支持"
                    else -> "设备错误(0x${code.toString(16)})"
                }
                showToast(msg)
            }
            BleConfig.EVT_LOW_BATTERY -> {
                if (payload.size >= 2) {
                    val pct = payload[1].toInt() and 0xFF
                    Ze69Hardware.setLowBatteryIndicator(true)
                    showToast("电量低 $pct%")
                }
            }
        }
    }

    private fun syncZe69Indicators() {
        if (!DeviceProfile.isDsjZecn6a1 && !Ze69Hardware.isZe69Platform) return
        when (ble.deviceState) {
            BleConfig.FSM_RECORD -> Ze69Hardware.setRecordingIndicator(true)
            BleConfig.FSM_AI -> Ze69Hardware.setAiListeningIndicator(true)
            else -> {
                Ze69Hardware.setRecordingIndicator(false)
                Ze69Hardware.setAiListeningIndicator(false)
            }
        }
    }

    private fun applyBleCmds(cmds: List<Int>) {
        cmds.forEach { cmd ->
            if (ble.deviceState == BleConfig.FSM_RECORD &&
                (cmd == BleConfig.CMD_START_AI_LISTEN || cmd == BleConfig.CMD_CAPTURE)
            ) {
                return@forEach
            }
            when (cmd) {
                BleConfig.CMD_START_RECORD -> ble.writeCmd(BleConfig.CMD_START_RECORD)
                BleConfig.CMD_STOP_RECORD -> ble.writeCmd(BleConfig.CMD_STOP_RECORD)
                BleConfig.CMD_CAPTURE -> ble.writeCmd(BleConfig.CMD_CAPTURE)
                BleConfig.CMD_START_AI_LISTEN -> ble.writeCmd(BleConfig.CMD_START_AI_LISTEN)
                BleConfig.CMD_STOP_AI_LISTEN -> ble.writeCmd(BleConfig.CMD_STOP_AI_LISTEN)
            }
        }
    }

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
        ApiClient.login(BleConfig.DEMO_PHONE, BleConfig.DEMO_PASSWORD) { ok, token, _ ->
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

    fun bindBleCallbacks() {
        ble.addImageListener(imageListener)
        ble.addVideoListener(videoListener)
        ble.setCmdEventListener(cmdEventListener)
        ble.addStatusListener(bleStatusListener)
    }

    companion object {
        @Volatile
        private var instance: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(context).also {
                    it.bindBleCallbacks()
                    instance = it
                }
            }
        }
    }
}
