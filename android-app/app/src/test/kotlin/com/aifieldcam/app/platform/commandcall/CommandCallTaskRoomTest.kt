package com.aifieldcam.app.platform.commandcall

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SPEC-DEV-TASKROOM-001 验收单测：A1/A4/A5/A6/A7/A10 + Parser/去重。
 */
class CommandCallTaskRoomTest {

    private lateinit var frameSource: FakeCommandCallFrameSource

    @Before
    fun setUp() {
        CommandCallController.resetForTests()
        TaskRoomSignalDeduper.resetForTests()
        frameSource = FakeCommandCallFrameSource()
        CommandCallController.useFrameSourceFactoryForTests { frameSource }
        CommandCallController.useJpegScalerForTests(IdentityCommandCallJpegScaler)
    }

    private fun creds(room: String, user: String = "device-DSJ-1") =
        CommandCallCredentials(1600152450, room, user, "sig")

    @Test
    fun a1_joinTaskRoom_entersRoomWithDeviceUser() {
        val c = creds("room-task-aaa")
        assertTrue(CommandCallController.onTaskRoomJoin("task-1", c, pushVideo = true))
        assertTrue(CommandCallController.isWatching())
        assertEquals("room-task-aaa", CommandCallController.activeTrtcRoomId())
        assertEquals("device-DSJ-1", (CommandCallRoom.current() as FakeCommandCallRoomAdapter).lastJoinedCredentials!!.userId)
        assertTrue(CommandCallController.isCoCaptureActive())
    }

    @Test
    fun uc2_pushVideoFalse_holdingWithoutCoCapture() {
        val c = creds("room-task-hold")
        assertTrue(CommandCallController.onTaskRoomJoin("task-h", c, pushVideo = false))
        assertTrue(CommandCallController.isHolding())
        assertFalse(CommandCallController.isCoCaptureActive())
        assertFalse(CommandCallController.isWatching())
    }

    @Test
    fun a4_switchRooms_leavesOldThenJoinsNew() {
        val a = creds("room-task-a")
        val b = creds("room-task-b")
        assertTrue(CommandCallController.onTaskRoomJoin("task-a", a, pushVideo = true))
        val fake = CommandCallRoom.current() as FakeCommandCallRoomAdapter
        val leavesBefore = fake.leaveCount
        assertTrue(CommandCallController.onTaskRoomJoin("task-b", b, pushVideo = true))
        assertEquals("room-task-b", CommandCallController.activeTrtcRoomId())
        assertTrue(fake.leaveCount > leavesBefore)
        assertEquals(b, fake.lastJoinedCredentials)
        assertTrue(CommandCallController.isWatching())
    }

    @Test
    fun a5_leave_goesIdleAndStopsCoCapture() {
        val c = creds("room-task-x")
        assertTrue(CommandCallController.onTaskRoomJoin("task-x", c, pushVideo = true))
        CommandCallController.onTaskRoomLeave("task-x")
        assertEquals(CommandCallController.Mode.IDLE, CommandCallController.currentMode())
        assertFalse(CommandCallRoom.current().isInRoom())
        assertFalse(CommandCallController.isCoCaptureActive())
        assertEquals("", CommandCallController.activeTrtcRoomId())
        // 幂等
        CommandCallController.onTaskRoomLeave("task-x")
        assertEquals(CommandCallController.Mode.IDLE, CommandCallController.currentMode())
    }

    @Test
    fun a6_watching_doesNotCountAsInCall_aiAllowed() {
        assertTrue(CommandCallController.onTaskRoomJoin("task-w", creds("room-task-w"), true))
        assertTrue(CommandCallController.isWatching())
        assertFalse(CommandCallController.isInCall())
        assertTrue(CommandCallAiPriority.allowsAiRealtime(CommandCallController.isInCall()))
        assertFalse(CommandCallAiSuppressLatch.blocksAiRealtime(CommandCallController.isInCall()))
    }

    @Test
    fun a7_upgradeToInCall_blocksAi() {
        val c = creds("room-task-u")
        assertTrue(CommandCallController.onTaskRoomJoin("task-u", c, pushVideo = true))
        assertTrue(CommandCallController.onCallUpgrade("seat-1", c))
        assertTrue(CommandCallController.isInCall())
        assertEquals("room-task-u", CommandCallController.activeTrtcRoomId())
        assertFalse(CommandCallAiPriority.allowsAiRealtime(CommandCallController.isInCall()))
    }

    @Test
    fun a8_pttOnlyInCall() {
        val c = creds("room-task-p")
        assertTrue(CommandCallController.onTaskRoomJoin("task-p", c, pushVideo = true))
        val capture = FakeCommandCallAudioCapture()
        CommandCallIntercom.resetForTests(capture)
        CommandCallIntercom.startUplink()
        assertFalse(CommandCallIntercom.isTalking())
        assertTrue(CommandCallController.onCallUpgrade("seat-p", c))
        CommandCallIntercom.startUplink()
        assertTrue(CommandCallIntercom.isTalking())
        CommandCallIntercom.stopUplink()
        assertFalse(CommandCallIntercom.isTalking())
    }

    @Test
    fun a10_sameRoomRepeatedJoin_idempotentSingleJoin() {
        val c = creds("room-task-idem")
        assertTrue(CommandCallController.onTaskRoomJoin("task-i", c, pushVideo = true))
        val fake = CommandCallRoom.current() as FakeCommandCallRoomAdapter
        val joins = fake.joinCount
        val leaves = fake.leaveCount
        assertTrue(CommandCallController.onTaskRoomJoin("task-i", c, pushVideo = true))
        assertEquals(joins, fake.joinCount)
        assertEquals(leaves, fake.leaveCount)
        assertTrue(CommandCallController.isWatching())
    }

    @Test
    fun sameRoom_pushVideoOff_unbindsCoCapture() {
        val c = creds("room-task-pv")
        assertTrue(CommandCallController.onTaskRoomJoin("task-pv", c, pushVideo = true))
        assertTrue(CommandCallController.isCoCaptureActive())
        assertTrue(CommandCallController.onTaskRoomJoin("task-pv", c, pushVideo = false))
        assertTrue(CommandCallController.isHolding())
        assertFalse(CommandCallController.isCoCaptureActive())
    }

    @Test
    fun parser_taskRoomJoin_nestedDeviceAndCamelPush() {
        val json = JSONObject()
            .put("action", "task_room_join")
            .put("task_room_id", "task-99")
            .put("pushVideo", false)
            .put(
                "device",
                JSONObject()
                    .put("room_id", "room-task-99")
                    .put("sdk_app_id", 1600152450)
                    .put("user_id", "device-DSJ-9")
                    .put("user_sig", "sig-9"),
            )
        val start = CommandCallSignalParser.parseStart(json)
        assertNotNull(start)
        assertEquals(CommandCallSignalParser.StartKind.TASK_ROOM, start!!.kind)
        assertEquals("task-99", start.callId)
        assertEquals("room-task-99", start.credentials.roomId)
        assertEquals("device-DSJ-9", start.credentials.userId)
        assertFalse(start.pushVideo)
        assertTrue(CommandCallSignalParser.isTaskRoomLeave("task_room_leave"))
    }

    @Test
    fun deduper_allowsPushVideoFlip_andSkipsExactDuplicate() {
        val startPush = CommandCallSignalParser.parseStart(
            JSONObject()
                .put("action", "task_room_join")
                .put("task_room_id", "t1")
                .put("room_id", "r1")
                .put("sdk_app_id", 1)
                .put("user_id", "u")
                .put("user_sig", "s")
                .put("push_video", true),
        )!!
        val startHold = CommandCallSignalParser.parseStart(
            JSONObject()
                .put("action", "task_room_join")
                .put("task_room_id", "t1")
                .put("room_id", "r1")
                .put("sdk_app_id", 1)
                .put("user_id", "u")
                .put("user_sig", "s")
                .put("push_video", false),
        )!!
        assertTrue(TaskRoomSignalDeduper.note(TaskRoomSignalDeduper.keyForStart(startPush)))
        assertFalse(TaskRoomSignalDeduper.note(TaskRoomSignalDeduper.keyForStart(startPush)))
        assertTrue(TaskRoomSignalDeduper.note(TaskRoomSignalDeduper.keyForStart(startHold)))
    }
}
