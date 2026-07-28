package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiAssistantStartPolicyTest {

    @Test
    fun field_event_busy_denied() {
        assertEquals(
            AiAssistantStartPolicy.DenyReason.FIELD_EVENT_BUSY,
            AiAssistantStartPolicy.allowStart(fieldEventCapturing = true),
        )
        assertEquals(
            "事件上报中，请稍后再使用语音助手",
            AiAssistantStartPolicy.denyMessage(
                AiAssistantStartPolicy.DenyReason.FIELD_EVENT_BUSY,
            ),
        )
    }

    @Test
    fun idle_allowed() {
        assertNull(AiAssistantStartPolicy.allowStart(fieldEventCapturing = false))
    }
}
