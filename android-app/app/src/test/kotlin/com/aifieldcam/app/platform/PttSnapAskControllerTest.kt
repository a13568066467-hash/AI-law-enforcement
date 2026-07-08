package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttSnapAskControllerTest {

    @Test
    fun status_idle_byDefault() {
        assertEquals(PttSnapAskController.Status.IDLE, PttSnapAskController.status)
        assertFalse(PttSnapAskController.isActive())
    }

    @Test
    fun status_transitions() {
        // 状态枚举完整覆盖
        val values = PttSnapAskController.Status.values()
        assertEquals(4, values.size)
        val names = values.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("IDLE", "SNAPPING", "CAPTURING_VOICE", "PROCESSING")))
    }
}