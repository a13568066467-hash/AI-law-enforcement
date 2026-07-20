package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeVoiceProtocolTest {

    @Test
    fun parsesWhitelistedToolCall() {
        val event = RealtimeVoiceProtocol.parseServerText(
            """{"type":"tool_call","call_id":"c1","name":"capture_and_explain","arguments":{"question":"读一下仪表"}}""",
        )

        assertTrue(event is RealtimeVoiceEvent.ToolCall)
        event as RealtimeVoiceEvent.ToolCall
        assertEquals("c1", event.callId)
        assertEquals("capture_and_explain", event.name)
        assertEquals("读一下仪表", event.arguments.optString("question"))
    }
}
