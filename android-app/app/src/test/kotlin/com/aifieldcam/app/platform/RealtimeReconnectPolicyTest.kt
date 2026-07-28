package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeReconnectPolicyTest {

    @Test
    fun backoff_caps_at_ten_seconds() {
        assertEquals(500L, RealtimeReconnectPolicy.delayMs(0))
        assertEquals(1_000L, RealtimeReconnectPolicy.delayMs(1))
        assertEquals(2_000L, RealtimeReconnectPolicy.delayMs(2))
        assertEquals(5_000L, RealtimeReconnectPolicy.delayMs(3))
        assertEquals(10_000L, RealtimeReconnectPolicy.delayMs(4))
        assertEquals(10_000L, RealtimeReconnectPolicy.delayMs(99))
    }

    @Test
    fun keep_alive_never_reports_connection_failure() {
        assertFalse(RealtimeReconnectPolicy.shouldReportFailure(keepAlive = true, failedAttempts = 1))
        assertFalse(RealtimeReconnectPolicy.shouldReportFailure(keepAlive = true, failedAttempts = 100))
    }

    @Test
    fun non_keep_alive_reports_after_second_failure() {
        assertFalse(RealtimeReconnectPolicy.shouldReportFailure(keepAlive = false, failedAttempts = 1))
        assertTrue(RealtimeReconnectPolicy.shouldReportFailure(keepAlive = false, failedAttempts = 2))
    }
}
