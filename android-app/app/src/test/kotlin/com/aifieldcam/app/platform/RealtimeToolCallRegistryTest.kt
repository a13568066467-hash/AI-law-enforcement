package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeToolCallRegistryTest {

    @Test
    fun allows_only_first_whitelisted_call_id() {
        val registry = RealtimeToolCallRegistry()

        assertEquals(
            RealtimeToolCallRegistry.Decision.ALLOW,
            registry.evaluate("call-1", "start_recording"),
        )
        assertEquals(
            RealtimeToolCallRegistry.Decision.DUPLICATE,
            registry.evaluate("call-1", "start_recording"),
        )
        assertEquals(
            RealtimeToolCallRegistry.Decision.DENY,
            registry.evaluate("call-2", "format_storage"),
        )
    }
}
