package com.aifieldcam.app.platform.commandcall

/**
 * MQTT 与 HTTP poll 对任务房/连线 start 信令去重（SPEC-DEV-TASKROOM-001 §6.2）。
 * 键含 action/kind、业务 id、TRTC room、push_video，避免同房仅改推流被误丢弃。
 */
object TaskRoomSignalDeduper {

    @Volatile
    private var lastKey: String = ""

    private val lock = Any()

    fun resetForTests() {
        synchronized(lock) {
            lastKey = ""
        }
    }

    /**
     * @return true 表示应处理；false 表示与上一条相同，跳过。
     */
    fun note(key: String): Boolean {
        val k = key.trim()
        if (k.isEmpty()) return true
        synchronized(lock) {
            if (k == lastKey) return false
            lastKey = k
            return true
        }
    }

    fun keyForStart(start: CommandCallSignalParser.StartSignal): String {
        return buildString {
            append(start.kind.name)
            append(':')
            append(start.callId)
            append(':')
            append(start.credentials.roomId)
            append(":pv=")
            append(start.pushVideo)
        }
    }

    fun keyForLeave(taskRoomId: String, roomId: String = ""): String {
        return "LEAVE:${taskRoomId.trim()}:${roomId.trim()}"
    }
}
