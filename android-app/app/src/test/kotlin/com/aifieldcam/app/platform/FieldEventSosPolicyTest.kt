package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldEventSosPolicyTest {

    @Test
    fun unbound_denied() {
        assertEquals(
            FieldEventSosPolicy.DenyReason.NOT_BOUND,
            FieldEventSosPolicy.allowCapture(
                deviceBound = false,
                commandCallActive = false,
                aiAssistantActive = false,
            ),
        )
    }

    @Test
    fun command_call_denied() {
        assertEquals(
            FieldEventSosPolicy.DenyReason.IN_COMMAND_CALL,
            FieldEventSosPolicy.allowCapture(
                deviceBound = true,
                commandCallActive = true,
                aiAssistantActive = false,
            ),
        )
    }

    @Test
    fun ai_assistant_denied() {
        assertEquals(
            FieldEventSosPolicy.DenyReason.AI_ASSISTANT_BUSY,
            FieldEventSosPolicy.allowCapture(
                deviceBound = true,
                commandCallActive = false,
                aiAssistantActive = true,
            ),
        )
    }

    @Test
    fun watch_or_recording_allowed() {
        assertNull(
            FieldEventSosPolicy.allowCapture(
                deviceBound = true,
                commandCallActive = false,
                aiAssistantActive = false,
            ),
        )
    }

    @Test
    fun deny_messages() {
        assertEquals(
            "请先扫码绑定后再上报",
            FieldEventSosPolicy.denyMessage(FieldEventSosPolicy.DenyReason.NOT_BOUND),
        )
        assertEquals(
            "连线中无法上报",
            FieldEventSosPolicy.denyMessage(FieldEventSosPolicy.DenyReason.IN_COMMAND_CALL),
        )
        assertEquals(
            "语音助手使用中，请稍后再上报",
            FieldEventSosPolicy.denyMessage(FieldEventSosPolicy.DenyReason.AI_ASSISTANT_BUSY),
        )
    }
}
