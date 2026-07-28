package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldEventTranscriptWaitPolicyTest {

    @Test
    fun submit_when_final_ready() {
        val r = FieldEventTranscriptWaitPolicy.resolve(
            finalTranscript = "  隧道落石，申请调机械  ",
            timedOut = false,
        )
        assertTrue(r is FieldEventTranscriptWaitPolicy.Resolve.Submit)
        assertEquals(
            "隧道落石，申请调机械",
            (r as FieldEventTranscriptWaitPolicy.Resolve.Submit).transcript,
        )
    }

    @Test
    fun wait_when_not_ready_and_not_timed_out() {
        assertEquals(
            FieldEventTranscriptWaitPolicy.Resolve.Wait,
            FieldEventTranscriptWaitPolicy.resolve(finalTranscript = null, timedOut = false),
        )
        assertEquals(
            FieldEventTranscriptWaitPolicy.Resolve.Wait,
            FieldEventTranscriptWaitPolicy.resolve(finalTranscript = "  ", timedOut = false),
        )
    }

    @Test
    fun reject_when_timed_out_empty() {
        assertEquals(
            FieldEventTranscriptWaitPolicy.Resolve.RejectEmpty,
            FieldEventTranscriptWaitPolicy.resolve(finalTranscript = null, timedOut = true),
        )
        assertEquals(
            FieldEventTranscriptWaitPolicy.Resolve.RejectEmpty,
            FieldEventTranscriptWaitPolicy.resolve(finalTranscript = "", timedOut = true),
        )
    }
}
