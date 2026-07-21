package com.aifieldcam.app.platform.commandcall

import com.aifieldcam.app.platform.NativeRecorder

/**
 * 指挥连线控制器：收连线信令后经 [CommandCallRoom] 自动进房/退房，并绑定连线共摄旁路。
 * 设备不发送 answer / busy / hangup 上行控制消息。
 * 进房失败走 [failAndCleanup]，避免半连接残留。
 */
object CommandCallController {

    @Volatile
    private var activeCallId: String = ""

    @Volatile
    private var lastFailureReason: String = ""

    @Volatile
    private var frameSourceFactory: () -> CommandCallFrameSource? = ::defaultFrameSource

    @Volatile
    private var jpegScaler: CommandCallJpegScaler = IdentityCommandCallJpegScaler

    fun resetForTests() {
        CommandCallIntercom.resetForTests()
        CommandCallCoCapture.unbind()
        activeCallId = ""
        lastFailureReason = ""
        // 单测 JVM 无 Android Handler；默认不绑共摄，需显式 useFrameSourceFactoryForTests
        frameSourceFactory = { null }
        jpegScaler = IdentityCommandCallJpegScaler
        CommandCallRoom.resetToFake()
    }

    /** 单测注入帧源工厂；返回 null 表示本次不绑共摄。 */
    fun useFrameSourceFactoryForTests(factory: () -> CommandCallFrameSource?) {
        frameSourceFactory = factory
    }

    fun useJpegScalerForTests(scaler: CommandCallJpegScaler) {
        jpegScaler = scaler
    }

    fun activeCallId(): String = activeCallId

    fun lastFailureReason(): String = lastFailureReason

    fun isInCall(): Boolean = CommandCallRoom.current().isInRoom()

    fun isCoCaptureActive(): Boolean = CommandCallCoCapture.isActive()

    /**
     * 平台下发呼叫开始：自动进房并尝试绑定共摄旁路。
     * @return true 若成功进房；已在通话中或 join 失败时 false（失败会清理半连接）。
     */
    fun onCallStart(callId: String, credentials: CommandCallCredentials): Boolean {
        val id = callId.trim()
        if (id.isEmpty()) return false
        if (activeCallId.isNotEmpty() && CommandCallRoom.current().isInRoom()) {
            return false
        }
        lastFailureReason = ""
        val joined = CommandCallRoom.current().join(credentials)
        if (joined) {
            activeCallId = id
            bindCoCaptureIfPossible()
            notifyCommandCallLeds(inCall = true, ptt = false)
            return true
        }
        failAndCleanup("join_failed")
        return false
    }

    /** 平台下发结束连线：先停对讲/共摄，再退房并清理。 */
    fun onCallEnd(callId: String = "") {
        val expected = callId.trim()
        if (expected.isNotEmpty() && activeCallId.isNotEmpty() && expected != activeCallId) {
            return
        }
        failAndCleanup("")
    }

    /**
     * 半连接/失败清理：停对讲、解绑共摄、退房、清空通话 id。
     * [reason] 非空时写入 [lastFailureReason]。
     */
    fun failAndCleanup(reason: String) {
        if (reason.isNotBlank()) {
            lastFailureReason = reason.trim()
        }
        CommandCallIntercom.forceStop()
        CommandCallCoCapture.unbind()
        val room = CommandCallRoom.current()
        if (room.state != CommandCallRoomState.IDLE) {
            room.leave()
        }
        activeCallId = ""
        // 退房后 isInCall=false：F6 长按恢复 AI 对讲能力（不自动接回被打断会话）
        notifyCommandCallLeds(inCall = false, ptt = false)
    }

    private fun notifyCommandCallLeds(inCall: Boolean, ptt: Boolean) {
        try {
            com.aifieldcam.app.platform.DeviceStatusIndicator.setCommandCallActive(inCall)
            com.aifieldcam.app.platform.DeviceStatusIndicator.setCommandCallPtt(ptt)
        } catch (_: Throwable) {
        }
    }

    /** 连线对讲 PTT 灯：黄常亮 / 松开关回连线红常亮。 */
    internal fun notifyCommandCallPttLed(talking: Boolean) {
        try {
            com.aifieldcam.app.platform.DeviceStatusIndicator.setCommandCallPtt(talking)
        } catch (_: Throwable) {
        }
    }

    /** 录像已开始后补绑共摄（进房时尚未在录的情况）。 */
    fun ensureCoCaptureWhileInCall() {
        if (!isInCall() || CommandCallCoCapture.isActive()) return
        bindCoCaptureIfPossible()
    }

    private fun bindCoCaptureIfPossible() {
        val source = frameSourceFactory() ?: return
        CommandCallCoCapture.bind(CommandCallRoom.current(), source, jpegScaler)
    }

    private fun defaultFrameSource(): CommandCallFrameSource? {
        return try {
            if (!NativeRecorder.isRecording()) return null
            jpegScaler = RecordingCommandCallFrameSource.BitmapJpegScaler
            RecordingCommandCallFrameSource()
        } catch (_: Throwable) {
            null
        }
    }
}
