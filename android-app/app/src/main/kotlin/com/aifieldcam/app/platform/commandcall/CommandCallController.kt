package com.aifieldcam.app.platform.commandcall

import com.aifieldcam.app.platform.NativeRecorder

/**
 * 指挥连线 / 画面监看控制器：占用侧持房，监看/连线只切换推流与对讲态。
 * 设备不发送 answer / busy / hangup 上行控制消息。
 */
object CommandCallController {

    enum class Mode {
        IDLE,
        /** 占用进房占坑，不推视频 */
        HOLDING,
        WATCHING,
        IN_CALL,
    }

    @Volatile
    private var activeCallId: String = ""

    @Volatile
    private var mode: Mode = Mode.IDLE

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
        mode = Mode.IDLE
        lastFailureReason = ""
        frameSourceFactory = { null }
        jpegScaler = IdentityCommandCallJpegScaler
        CommandCallRoom.resetToFake()
    }

    fun useFrameSourceFactoryForTests(factory: () -> CommandCallFrameSource?) {
        frameSourceFactory = factory
    }

    fun useJpegScalerForTests(scaler: CommandCallJpegScaler) {
        jpegScaler = scaler
    }

    fun activeCallId(): String = activeCallId

    fun lastFailureReason(): String = lastFailureReason

    fun currentMode(): Mode = mode

    fun isInCall(): Boolean = mode == Mode.IN_CALL && CommandCallRoom.current().isInRoom()

    fun isWatching(): Boolean = mode == Mode.WATCHING && CommandCallRoom.current().isInRoom()

    fun isHolding(): Boolean = mode == Mode.HOLDING && CommandCallRoom.current().isInRoom()

    fun isInRoom(): Boolean = CommandCallRoom.current().isInRoom()

    fun isCoCaptureActive(): Boolean = CommandCallCoCapture.isActive()

    /**
     * 占用侧进房占坑：进房但不推流、不对讲。
     * [occupancyKey] 仅作本地关联（可用 roomId）。
     */
    fun onOccupyRoom(occupancyKey: String, credentials: CommandCallCredentials): Boolean {
        val key = occupancyKey.trim().ifEmpty { credentials.roomId.trim() }
        if (key.isEmpty()) return false
        val room = CommandCallRoom.current()
        if (room.isInRoom() && mode == Mode.HOLDING) {
            lastFailureReason = ""
            return true
        }
        if (room.state != CommandCallRoomState.IDLE) {
            CommandCallIntercom.forceStop()
            CommandCallCoCapture.unbind()
            room.leave()
            activeCallId = ""
            mode = Mode.IDLE
        }
        lastFailureReason = ""
        val joined = room.join(credentials)
        if (!joined) {
            failAndCleanup("join_failed")
            return false
        }
        activeCallId = ""
        mode = Mode.HOLDING
        notifyCommandCallLeds(inCall = false, ptt = false)
        return true
    }

    /** 画面监看：若已在占用房则只开推流；否则进房并推流。 */
    fun onWatchStart(callId: String, credentials: CommandCallCredentials): Boolean {
        return joinOrResumeSession(callId, credentials, Mode.WATCHING)
    }

    /** 指挥连线开始（冷启动）：进房或切到连线态并推流。 */
    fun onCallStart(callId: String, credentials: CommandCallCredentials): Boolean {
        return joinOrResumeSession(callId, credentials, Mode.IN_CALL)
    }

    /**
     * 监看同房升级为指挥连线：已在房则只切模式；未在房则按连线进房。
     */
    fun onCallUpgrade(callId: String, credentials: CommandCallCredentials): Boolean {
        val id = callId.trim()
        if (id.isEmpty()) return false
        if (isInRoom() && (activeCallId.isEmpty() || activeCallId == id || mode == Mode.HOLDING || mode == Mode.WATCHING)) {
            activeCallId = id
            mode = Mode.IN_CALL
            lastFailureReason = ""
            bindCoCaptureIfPossible()
            notifyCommandCallLeds(inCall = true, ptt = false)
            return true
        }
        return onCallStart(id, credentials)
    }

    /**
     * 监看/连线业务结束：停推流与对讲，**留在占用房**（HOLDING）。
     * 解绑退房走 [onOccupyRoomEnd]。
     */
    fun onSessionEnd(callId: String = "") {
        val expected = callId.trim()
        if (expected.isNotEmpty() && activeCallId.isNotEmpty() && expected != activeCallId) {
            return
        }
        CommandCallIntercom.forceStop()
        CommandCallCoCapture.unbind()
        activeCallId = ""
        if (CommandCallRoom.current().isInRoom()) {
            mode = Mode.HOLDING
        } else {
            mode = Mode.IDLE
        }
        notifyCommandCallLeds(inCall = false, ptt = false)
    }

    /** 兼容旧名：业务会话结束，留房。 */
    fun onCallEnd(callId: String = "") {
        onSessionEnd(callId)
    }

    /** 占用结束：退房清房。 */
    fun onOccupyRoomEnd() {
        failAndCleanup("")
    }

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
        mode = Mode.IDLE
        notifyCommandCallLeds(inCall = false, ptt = false)
    }

    private fun joinOrResumeSession(
        callId: String,
        credentials: CommandCallCredentials,
        target: Mode,
    ): Boolean {
        val id = callId.trim()
        if (id.isEmpty()) return false
        val room = CommandCallRoom.current()
        // 已在占用房或同会话：只切模式并开推流
        if (room.isInRoom() && (mode == Mode.HOLDING || activeCallId.isEmpty() || activeCallId == id)) {
            activeCallId = id
            mode = target
            lastFailureReason = ""
            bindCoCaptureIfPossible()
            notifyCommandCallLeds(inCall = target == Mode.IN_CALL, ptt = false)
            return true
        }
        if (room.isInRoom() && activeCallId.isNotEmpty() && activeCallId != id) {
            // 已有其它业务会话，拒绝抢占
            lastFailureReason = "busy"
            return false
        }
        if (room.state != CommandCallRoomState.IDLE) {
            CommandCallIntercom.forceStop()
            CommandCallCoCapture.unbind()
            room.leave()
            activeCallId = ""
            mode = Mode.IDLE
        }
        lastFailureReason = ""
        val joined = room.join(credentials)
        if (joined) {
            activeCallId = id
            mode = target
            bindCoCaptureIfPossible()
            notifyCommandCallLeds(inCall = target == Mode.IN_CALL, ptt = false)
            return true
        }
        failAndCleanup("join_failed")
        return false
    }

    private fun notifyCommandCallLeds(inCall: Boolean, ptt: Boolean) {
        try {
            com.aifieldcam.app.platform.DeviceStatusIndicator.setCommandCallActive(inCall)
            com.aifieldcam.app.platform.DeviceStatusIndicator.setCommandCallPtt(ptt)
        } catch (_: Throwable) {
        }
    }

    internal fun notifyCommandCallPttLed(talking: Boolean) {
        try {
            com.aifieldcam.app.platform.DeviceStatusIndicator.setCommandCallPtt(talking)
        } catch (_: Throwable) {
        }
    }

    fun ensureCoCaptureWhileInCall() {
        if (!isInRoom() || mode == Mode.HOLDING || mode == Mode.IDLE) return
        if (CommandCallCoCapture.isActive()) return
        bindCoCaptureIfPossible()
    }

    private fun bindCoCaptureIfPossible() {
        if (mode == Mode.HOLDING || mode == Mode.IDLE) return
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
