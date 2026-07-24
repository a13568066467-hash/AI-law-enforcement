package com.aifieldcam.app.platform.commandcall

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue 2 设备侧骨架：MQTT/HTTP 开始载荷 → Fake 进房 → 结束 → 清理。
 * 不断言真 TRTC；不产生 answer/busy/hangup 上行。
 */
class CommandCallSkeletonDeviceTest {

    @Before
    fun setUp() {
        CommandCallController.resetForTests()
    }

    @Test
    fun mqttStartPayload_autoJoinsFakeRoom_endLeaves() {
        val startJson = JSONObject()
            .put("action", "call_start")
            .put("call_id", "cc-skeleton-1")
            .put("caller", "指挥中心")
            .put("room_id", "room-cc-skeleton-1")
            .put("sdk_app_id", 1600152450)
            .put("user_id", "device-DSJ-SKEL")
            .put("user_sig", "sig-from-backend")

        val start = CommandCallSignalParser.parseStart(startJson)!!
        assertTrue(CommandCallController.onCallStart(start.callId, start.credentials))
        assertTrue(CommandCallController.isInCall())
        assertEquals("cc-skeleton-1", CommandCallController.activeCallId())
        val fake = CommandCallRoom.current() as FakeCommandCallRoomAdapter
        assertEquals(1, fake.joinCount)
        assertEquals("sig-from-backend", fake.lastJoinedCredentials!!.userSig)

        val endJson = JSONObject().put("action", "call_end").put("call_id", "cc-skeleton-1")
        CommandCallController.onCallEnd(CommandCallSignalParser.parseEndCallId(endJson))

        assertFalse(CommandCallController.isInCall())
        assertEquals("", CommandCallController.activeCallId())
        assertEquals(0, fake.leaveCount)
        assertTrue(CommandCallRoom.current().isInRoom())
        assertTrue(CommandCallController.isHolding())
    }
}
