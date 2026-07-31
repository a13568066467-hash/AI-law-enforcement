package com.aifieldcam.app.platform.commandcall

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCallSignalParserTest {

    @Test
    fun parseStart_snakeCase() {
        val json = JSONObject()
            .put("action", "call_start")
            .put("call_id", "cc-1")
            .put("caller", "指挥中心")
            .put("room_id", "room-cc-1")
            .put("sdk_app_id", 1600152450)
            .put("user_id", "device-DSJ-1")
            .put("user_sig", "sig-abc")

        val start = CommandCallSignalParser.parseStart(json)
        assertNotNull(start)
        assertEquals("cc-1", start!!.callId)
        assertEquals("指挥中心", start.caller)
        assertEquals(1600152450, start.credentials.sdkAppId)
        assertEquals("room-cc-1", start.credentials.roomId)
        assertEquals("device-DSJ-1", start.credentials.userId)
        assertEquals("sig-abc", start.credentials.userSig)
    }

    @Test
    fun parseStart_camelCase() {
        val json = JSONObject()
            .put("callId", "cc-2")
            .put("roomId", "room-2")
            .put("sdkAppId", 42)
            .put("userId", "u2")
            .put("userSig", "s2")

        val start = CommandCallSignalParser.parseStart(json)
        assertNotNull(start)
        assertEquals("cc-2", start!!.callId)
        assertEquals(42, start.credentials.sdkAppId)
    }

    @Test
    fun parseStart_missingUserSig_returnsNull() {
        val json = JSONObject()
            .put("call_id", "cc-3")
            .put("room_id", "r")
            .put("sdk_app_id", 1)
            .put("user_id", "u")
        assertNull(CommandCallSignalParser.parseStart(json))
    }

    @Test
    fun parseStart_taskRoomJoin() {
        val json = JSONObject()
            .put("action", "task_room_join")
            .put("task_room_id", "task-1")
            .put("room_id", "room-task-1")
            .put("sdk_app_id", 1600152450)
            .put("user_id", "device-DSJ-1")
            .put("user_sig", "sig")
            .put("push_video", true)
        val start = CommandCallSignalParser.parseStart(json)
        assertNotNull(start)
        assertEquals(CommandCallSignalParser.StartKind.TASK_ROOM, start!!.kind)
        assertEquals("task-1", start.callId)
        assertTrue(start.pushVideo)
    }

    @Test
    fun parseStart_watchAndUpgradeKinds() {
        val watch = CommandCallSignalParser.parseStart(
            JSONObject()
                .put("action", "watch_start")
                .put("call_id", "w1")
                .put("room_id", "r")
                .put("sdk_app_id", 1)
                .put("user_id", "u")
                .put("user_sig", "s"),
        )
        assertEquals(CommandCallSignalParser.StartKind.WATCH, watch!!.kind)

        val upgrade = CommandCallSignalParser.parseStart(
            JSONObject()
                .put("action", "call_upgrade")
                .put("call_id", "w1")
                .put("room_id", "r")
                .put("sdk_app_id", 1)
                .put("user_id", "u")
                .put("user_sig", "s"),
        )
        assertEquals(CommandCallSignalParser.StartKind.UPGRADE, upgrade!!.kind)
    }
}
