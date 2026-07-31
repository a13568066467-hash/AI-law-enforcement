package com.aifieldcam.app.platform.commandcall

/**
 * 连线对讲：设备侧半双工——按住才上行，松开静音。
 * 平台侧常开麦不在本模块（Web/座席）。
 *
 * 不依赖 android.util.Log，便于 JVM inline 单测。
 */
object CommandCallIntercom {

    @Volatile
    private var talking = false

    @Volatile
    private var capture: CommandCallAudioCapture = DefaultCommandCallAudioCapture

    fun resetForTests(captureOverride: CommandCallAudioCapture = DefaultCommandCallAudioCapture) {
        forceStop()
        capture = captureOverride
    }

    fun useCaptureForTests(captureOverride: CommandCallAudioCapture) {
        capture = captureOverride
    }

    fun isTalking(): Boolean = talking

    /**
     * 长按生效：开麦并向房间推 PCM。
     * 仅指挥连线业务态（IN_CALL）允许；监看 WATCHING 不对讲（SPEC A6/A8）。
     */
    fun startUplink() {
        if (!CommandCallController.isInCall()) {
            return
        }
        if (talking) return
        talking = true
        CommandCallController.notifyCommandCallPttLed(true)
        val room = CommandCallRoom.current()
        room.setLocalAudioMuted(false)
        capture.start(
            onPcm = { pcm ->
                if (talking && !room.isLocalAudioMuted()) {
                    room.pushAudioPcm(pcm)
                }
            },
            onStarted = { },
            onError = {
                talking = false
                room.setLocalAudioMuted(true)
                CommandCallController.notifyCommandCallPttLed(false)
            },
        )
    }

    /** 松开：停采并静音。 */
    fun stopUplink() {
        if (!talking) {
            CommandCallRoom.current().setLocalAudioMuted(true)
            CommandCallController.notifyCommandCallPttLed(false)
            return
        }
        talking = false
        CommandCallRoom.current().setLocalAudioMuted(true)
        CommandCallController.notifyCommandCallPttLed(false)
        capture.stop { }
    }

    /** 连线结束时强制清理。 */
    fun forceStop() {
        if (!talking) {
            try {
                CommandCallRoom.current().setLocalAudioMuted(true)
            } catch (_: Throwable) {
            }
            CommandCallController.notifyCommandCallPttLed(false)
            return
        }
        talking = false
        try {
            CommandCallRoom.current().setLocalAudioMuted(true)
        } catch (_: Throwable) {
        }
        CommandCallController.notifyCommandCallPttLed(false)
        capture.stop {}
    }
}
