package com.aifieldcam.app.platform.commandcall

/**
 * 指挥连线控制器：收连线信令后经 [CommandCallRoom] 自动进房/退房。
 * 设备不发送 answer / busy / hangup 上行控制消息。
 */
object CommandCallController {

    @Volatile
    private var activeCallId: String = ""

    fun resetForTests() {
        activeCallId = ""
        CommandCallRoom.resetToFake()
    }

    fun activeCallId(): String = activeCallId

    fun isInCall(): Boolean = CommandCallRoom.current().isInRoom()

    /**
     * 平台下发呼叫开始：自动进房。
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
        }
        return joined
    }

    /** 平台下发结束连线：退房并清理本地通话态。 */
    fun onCallEnd(callId: String = "") {
        val expected = callId.trim()
        if (expected.isNotEmpty() && activeCallId.isNotEmpty() && expected != activeCallId) {
            return
        }
        CommandCallRoom.current().leave()
        activeCallId = ""
    }
}
