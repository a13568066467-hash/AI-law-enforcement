package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldEventSosPolicyTest {

    @Test
    fun unbound_denied() {
        assertEquals(
            FieldEventSosPolicy.DenyReason.NOT_BOUND,
            FieldEventSosPolicy.allowCapture(deviceBound = false, commandCallActive = false),
        )
    }

    @Test
    fun command_call_denied() {
        assertEquals(
            FieldEventSosPolicy.DenyReason.IN_COMMAND_CALL,
            FieldEventSosPolicy.allowCapture(deviceBound = true, commandCallActive = true),
        )
    }

    @Test
    fun watch_or_recording_allowed() {
        assertNull(
            FieldEventSosPolicy.allowCapture(deviceBound = true, commandCallActive = false),
        )
    }
}
