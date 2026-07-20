package com.aifieldcam.app.platform.commandcall

/**
 * 假 TRTC 房间：同步置 [CommandCallRoomState.IN_ROOM]，不连接腾讯云。
 * 供单测与 Issue 2 呼叫骨架在无 SDK 时验收进房/退房。
 */
class FakeCommandCallRoomAdapter : CommandCallRoomAdapter {
    @Volatile
    override var state: CommandCallRoomState = CommandCallRoomState.IDLE
        private set

    @Volatile
    var lastJoinedCredentials: CommandCallCredentials? = null
        private set

    @Volatile
    var joinCount: Int = 0
        private set

    @Volatile
    var leaveCount: Int = 0
        private set

    override fun isInRoom(): Boolean = state == CommandCallRoomState.IN_ROOM

    override fun join(credentials: CommandCallCredentials): Boolean {
        if (state == CommandCallRoomState.IN_ROOM || state == CommandCallRoomState.JOINING) {
            return false
        }
        state = CommandCallRoomState.JOINING
        lastJoinedCredentials = credentials
        joinCount += 1
        state = CommandCallRoomState.IN_ROOM
        return true
    }

    override fun leave() {
        if (state == CommandCallRoomState.IDLE) return
        leaveCount += 1
        lastJoinedCredentials = null
        state = CommandCallRoomState.IDLE
    }
}
