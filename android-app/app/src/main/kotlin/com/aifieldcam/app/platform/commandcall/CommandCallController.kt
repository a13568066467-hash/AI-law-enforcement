package com.aifieldcam.app.platform.commandcall

import com.aifieldcam.app.platform.NativeRecorder

/**
 * 指挥连线 / 画面监看 / 公司任务房控制器。
 * 任务房：一机一 TRTC 房；按 push_video 切换 HOLDING/WATCHING；连线态同房升 IN_CALL。
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
    private var activeTrtcRoomId: String = ""

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
        activeTrtcRoomId = ""
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

    fun activeTrtcRoomId(): String = activeTrtcRoomId

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
            activeTrtcRoomId = ""
            mode = Mode.IDLE
        }
        lastFailureReason = ""
        val joined = room.join(credentials)
        if (!joined) {
            failAndCleanup("join_failed")
            return false
        }
        activeCallId = ""
        activeTrtcRoomId = credentials.roomId
        mode = Mode.HOLDING
        notifyCommandCallLeds(inCall = false, ptt = false)
        return true
    }

    /** 画面监看：若已在占用房则只开推流；否则进房并推流。 */
    fun onWatchStart(callId: String, credentials: CommandCallCredentials): Boolean {
        return joinOrResumeSession(callId, credentials, Mode.WATCHING)
    }

    /**
     * 公司任务房：进指定 TRTC 房；[pushVideo] 为真时进入监看态并开共摄。
     * 若当前在其它房间（如占用占坑房），先退房再进任务房。
     */
    fun onTaskRoomJoin(
        taskRoomId: String,
        credentials: CommandCallCredentials,
        pushVideo: Boolean,
    ): Boolean {
        val id = taskRoomId.trim().ifEmpty { credentials.roomId.trim() }
        if (id.isEmpty()) return false
        val targetRoom = credentials.roomId.trim()
        if (targetRoom.isEmpty()) return false
        val room = CommandCallRoom.current()
        val sameRoom = room.isInRoom() && activeTrtcRoomId == targetRoom
        if (!sameRoom && room.state != CommandCallRoomState.IDLE) {
            debugLog("task_room switch leave old=$activeTrtcRoomId new=$targetRoom")
            CommandCallIntercom.forceStop()
            CommandCallCoCapture.unbind()
            room.leave()
            activeCallId = ""
            activeTrtcRoomId = ""
            mode = Mode.IDLE
        }
        // 连线业务态同房仅更新推流时保持 IN_CALL
        val target =
            when {
                sameRoom && mode == Mode.IN_CALL -> Mode.IN_CALL
                pushVideo -> Mode.WATCHING
                else -> Mode.HOLDING
            }
        debugLog(
            "task_room_join id=$id room=$targetRoom push=$pushVideo user=${credentials.userId} target=$target",
        )
        return joinOrResumeSession(id, credentials, target)
    }

    /** 离开任务房：停推流并退房，不解绑业务占用；已 IDLE 则幂等。 */
    fun onTaskRoomLeave(taskRoomId: String = "") {
        val expected = taskRoomId.trim()
        debugLog("task_room_leave id=$expected room=$activeTrtcRoomId mode=$mode")
        CommandCallIntercom.forceStop()
        CommandCallCoCapture.unbind()
        val room = CommandCallRoom.current()
        if (room.state != CommandCallRoomState.IDLE) {
            room.leave()
        }
        activeCallId = ""
        activeTrtcRoomId = ""
        mode = Mode.IDLE
        notifyCommandCallLeds(inCall = false, ptt = false)
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
        activeTrtcRoomId = ""
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
        // 已在同一 TRTC 房：只切模式；HOLDING 须停共摄，推流态再绑
        if (room.isInRoom() && activeTrtcRoomId == credentials.roomId) {
            activeCallId = id
            mode = target
            lastFailureReason = ""
            if (target == Mode.HOLDING || target == Mode.IDLE) {
                CommandCallIntercom.forceStop()
                CommandCallCoCapture.unbind()
            } else {
                bindCoCaptureIfPossible()
            }
            notifyCommandCallLeds(inCall = target == Mode.IN_CALL, ptt = false)
            return true
        }
        if (room.isInRoom() && activeTrtcRoomId.isNotEmpty() && activeTrtcRoomId != credentials.roomId) {
            CommandCallIntercom.forceStop()
            CommandCallCoCapture.unbind()
            room.leave()
            activeCallId = ""
            activeTrtcRoomId = ""
            mode = Mode.IDLE
        }
        if (room.state != CommandCallRoomState.IDLE) {
            CommandCallIntercom.forceStop()
            CommandCallCoCapture.unbind()
            room.leave()
            activeCallId = ""
            activeTrtcRoomId = ""
            mode = Mode.IDLE
        }
        lastFailureReason = ""
        val joined = room.join(credentials)
        if (joined) {
            activeCallId = id
            activeTrtcRoomId = credentials.roomId
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

    /** JVM 单测无完整 android.util.Log 时忽略。 */
    private fun debugLog(message: String) {
        try {
            android.util.Log.i("CommandCallController", message)
        } catch (_: Throwable) {
        }
    }
}
