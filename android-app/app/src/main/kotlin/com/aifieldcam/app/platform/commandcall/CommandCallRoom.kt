package com.aifieldcam.app.platform.commandcall

/**
 * 指挥连线房间适配器持有点。默认 [FakeCommandCallRoomAdapter]；
 * 生产可 [use] 注入真 TRTC 实现，测试可注入记录调用的 Fake。
 */
object CommandCallRoom {
    @Volatile
    private var adapter: CommandCallRoomAdapter = FakeCommandCallRoomAdapter()

    fun current(): CommandCallRoomAdapter = adapter

    fun use(adapter: CommandCallRoomAdapter) {
        this.adapter = adapter
    }

    fun resetToFake() {
        adapter = FakeCommandCallRoomAdapter()
    }
}
