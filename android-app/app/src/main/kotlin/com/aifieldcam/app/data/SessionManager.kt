package com.aifieldcam.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.aifieldcam.app.ble.BleConfig
import com.aifieldcam.app.ble.BleConnState
import com.aifieldcam.app.ble.BleManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 页面统一入口，对齐 apptext/services/session.uts
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
        val file: File? = null,
    )

    interface StatusListener {
        fun onSessionChanged()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ble = BleManager.getInstance(appContext)

    private var workerToken = ""
    private var sessionId = ""
    private var boundDeviceId = ""
    private var activeRecordId = ""

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

    private val bleStatusListener = object : BleManager.StatusListener {
        override fun onStatusChanged() {
            boundDeviceId = if (ble.connState == BleConnState.CONNECTED) "connected" else ""
            notifyStatus()
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

    fun getBleSummary(): String = ble.getStatusSummary()

    fun getLoginSummary(): String {
        if (!isLoggedIn()) return "未登录"
        val mode = if (workerToken.startsWith("mock-token")) "（mock）" else ""
        return "已登录$mode"
    }

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
                    workerToken = token
                    sessionId = "sess-${System.currentTimeMillis()}"
                    notifyStatus()
                    val mode = when {
                        err == "offline-mock" -> "（离线 mock，连上后端后请重新登录）"
                        token.startsWith("mock-token") -> "（mock）"
                        else -> "（云端 AI）"
                    }
                    onDone(true, "登录成功$mode")
                } else {
                    onDone(false, err)
                }
            }
        }
    }

    fun logoutWorker() {
        workerToken = ""
        sessionId = ""
        notifyStatus()
    }

    fun pingBackend(onDone: (Boolean, String) -> Unit) {
        ApiClient.pingHealth { ok, msg ->
            mainHandler.post { onDone(ok, msg) }
        }
    }

    fun sendChatText(text: String, onDone: (reply: String, err: String) -> Unit) {
        if (!isLoggedIn()) {
            onDone("", "请先登录")
            return
        }
        val bleState = ble.deviceState
        if (bleState == BleConfig.FSM_RECORD &&
            (text.contains("识别") || text.contains("拍照"))
        ) {
            onDone("", "录像中请先停止录像")
            return
        }
        val devId = boundDeviceId.ifEmpty { "unknown" }
        ApiClient.postChat(workerToken, sessionId, devId, text, bleState) { ok, body, err ->
            mainHandler.post {
                if (!ok || body == null) {
                    if (err == ApiClient.ERR_AUTH_EXPIRED) {
                        workerToken = ""
                        sessionId = ""
                        notifyStatus()
                        onDone("", "登录已过期，请到设置页重新登录")
                    } else {
                        onDone("", err)
                    }
                    return@post
                }
                applyBleCmds(body.bleCmds)
                onDone(body.reply, "")
            }
        }
    }

    fun startRecord(): Boolean = ble.writeCmd(BleConfig.CMD_START_RECORD)

    fun stopRecord(): Boolean = ble.writeCmd(BleConfig.CMD_STOP_RECORD)

    fun triggerCapture(): Boolean = ble.writeCmd(BleConfig.CMD_CAPTURE)

    fun isBleConnected(): Boolean = ble.connState == BleConnState.CONNECTED

    fun onPhonePhotoCaptured(jpeg: ByteArray, savedFile: File) {
        mainHandler.post { onImageFromBle(jpeg, savedFile) }
    }

    fun onPhoneVideoCaptured(file: File, startedAt: Long) {
        mainHandler.post {
            val stoppedAt = System.currentTimeMillis()
            val sizeKb = if (file.exists()) file.length() / 1024 else 0L
            videoItems.add(
                0,
                VideoItem(
                    id = "phone-${System.currentTimeMillis()}",
                    startedAt = startedAt,
                    stoppedAt = stoppedAt,
                    durationMs = (stoppedAt - startedAt).coerceAtLeast(0),
                    note = "手机录像 · ${sizeKb}KB",
                    file = file,
                ),
            )
            notifyStatus()
            showToast("录像已保存")
        }
    }

    fun analyzeUploadedImage(jpeg: ByteArray, onDone: (explanation: String, err: String) -> Unit) {
        if (!isLoggedIn()) {
            onDone("", "请先登录")
            return
        }
        val base64 = Base64.getEncoder().encodeToString(jpeg)
        ApiClient.postVision(workerToken, sessionId, base64) { ok, body, err ->
            mainHandler.post {
                if (!ok || body == null) {
                    if (err == ApiClient.ERR_AUTH_EXPIRED) {
                        workerToken = ""
                        sessionId = ""
                        notifyStatus()
                        onDone("", "登录已过期，请到设置页重新登录")
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
                        showToast("识图完成")
                        body.explanation
                    }
                    else -> "识图失败: $err"
                }
                notifyStatus()
            }
        }
    }

    private fun onBleCmdEvent(evtId: Int, payload: ByteArray) {
        when (evtId) {
            BleConfig.EVT_RECORD_STARTED -> {
                activeRecordId = "rec-${System.currentTimeMillis()}"
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
                var durationMs = 0L
                if (payload.size >= 5) {
                    durationMs = ByteBuffer.wrap(payload, 1, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int.toLong() and 0xFFFFFFFFL
                }
                if (activeRecordId.isNotEmpty()) {
                    videoItems.find { it.id == activeRecordId }?.let { item ->
                        item.stoppedAt = System.currentTimeMillis()
                        item.durationMs = durationMs
                        item.note = if (durationMs > 0) "已停止 · ${durationMs}ms" else "已停止"
                    }
                }
                activeRecordId = ""
                notifyStatus()
            }
            BleConfig.EVT_LOW_BATTERY -> {
                if (payload.size >= 2) {
                    val pct = payload[1].toInt() and 0xFF
                    showToast("电量低 $pct%")
                }
            }
        }
    }

    private fun applyBleCmds(cmds: List<Int>) {
        cmds.forEach { cmd ->
            when (cmd) {
                BleConfig.CMD_START_RECORD -> ble.writeCmd(BleConfig.CMD_START_RECORD)
                BleConfig.CMD_STOP_RECORD -> ble.writeCmd(BleConfig.CMD_STOP_RECORD)
                BleConfig.CMD_CAPTURE -> ble.writeCmd(BleConfig.CMD_CAPTURE)
                BleConfig.CMD_START_AI_LISTEN -> ble.writeCmd(BleConfig.CMD_START_AI_LISTEN)
                BleConfig.CMD_STOP_AI_LISTEN -> ble.writeCmd(BleConfig.CMD_STOP_AI_LISTEN)
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
