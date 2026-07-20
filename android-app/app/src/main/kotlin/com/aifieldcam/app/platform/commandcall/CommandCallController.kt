package com.aifieldcam.app.platform.commandcall

import com.aifieldcam.app.platform.NativeRecorder

/**
 * 指挥连线控制器：收连线信令后经 [CommandCallRoom] 自动进房/退房，并绑定连线共摄旁路。
 * 设备不发送 answer / busy / hangup 上行控制消息。
 */
object CommandCallController {

    @Volatile
    private var activeCallId: String = ""

    @Volatile
    private var frameSourceFactory: () -> CommandCallFrameSource? = ::defaultFrameSource

    @Volatile
    private var jpegScaler: CommandCallJpegScaler = IdentityCommandCallJpegScaler

    fun resetForTests() {
        CommandCallIntercom.resetForTests()
        CommandCallCoCapture.unbind()
        activeCallId = ""
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

    fun isInCall(): Boolean = CommandCallRoom.current().isInRoom()

    fun isCoCaptureActive(): Boolean = CommandCallCoCapture.isActive()

    /**
     * 平台下发呼叫开始：自动进房并尝试绑定共摄旁路。
     * @return true 若成功进房；已在通话中或 join 失败时 false。
     */
    fun onCallStart(callId: String, credentials: CommandCallCredentials): Boolean {
        val id = callId.trim()
        if (id.isEmpty()) return false
        if (activeCallId.isNotEmpty() && CommandCallRoom.current().isInRoom()) {
            return false
        }
        val joined = CommandCallRoom.current().join(credentials)
        if (joined) {
            activeCallId = id
            bindCoCaptureIfPossible()
        }
        return joined
    }

    /** 平台下发结束连线：先停对讲/共摄，再退房并清理。 */
    fun onCallEnd(callId: String = "") {
        val expected = callId.trim()
        if (expected.isNotEmpty() && activeCallId.isNotEmpty() && expected != activeCallId) {
            return
        }
        CommandCallIntercom.forceStop()
        CommandCallCoCapture.unbind()
        CommandCallRoom.current().leave()
        activeCallId = ""
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
