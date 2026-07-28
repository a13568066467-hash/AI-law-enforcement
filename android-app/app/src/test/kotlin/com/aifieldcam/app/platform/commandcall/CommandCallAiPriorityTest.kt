package com.aifieldcam.app.platform.commandcall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandCallAiPriorityTest {

    @Before
    fun resetLatch() {
        CommandCallAiSuppressLatch.resetForTests()
    }

    @Test
    fun call_start_interrupts_ai_and_end_does_not_resume() {
        assertTrue(CommandCallAiPriority.shouldInterruptAiOnCallStart())
        assertFalse(CommandCallAiPriority.shouldResumeAiAfterCallEnd())
    }

    @Test
    fun ai_realtime_blocked_while_command_call_active() {
        assertFalse(CommandCallAiPriority.allowsAiRealtime(commandCallActive = true))
        assertTrue(CommandCallAiPriority.allowsAiRealtime(commandCallActive = false))
    }

    @Test
    fun suppress_latch_blocks_ai_before_isInCall() {
        assertFalse(CommandCallAiSuppressLatch.blocksAiRealtime(inCall = false))
        CommandCallAiSuppressLatch.arm()
        assertTrue(CommandCallAiSuppressLatch.blocksAiRealtime(inCall = false))
        assertFalse(
            CommandCallAiPriority.allowsAiRealtime(
                CommandCallAiSuppressLatch.blocksAiRealtime(inCall = false),
            ),
        )
        CommandCallAiSuppressLatch.clear()
        assertFalse(CommandCallAiSuppressLatch.blocksAiRealtime(inCall = false))
    }

    @Test
    fun gate_arms_latch_on_start_clears_on_end_and_on_failed() {
        var interrupted = 0
        val gate = CommandCallAiGate(
            interruptAi = { interrupted += 1 },
        )

        gate.onCallStart()
        assertTrue(CommandCallAiSuppressLatch.isArmed())
        assertEquals(1, interrupted)

        gate.onCallStartFailed()
        assertFalse(CommandCallAiSuppressLatch.isArmed())

        gate.onCallStart()
        assertTrue(CommandCallAiSuppressLatch.isArmed())
        gate.onCallEnd()
        assertFalse(CommandCallAiSuppressLatch.isArmed())
        assertEquals(0, gate.resumeCount)
    }

    @Test
    fun gate_interrupts_on_start_and_never_resumes_on_end() {
        var interrupted = 0
        var resumed = 0
        val gate = CommandCallAiGate(
            interruptAi = { interrupted += 1 },
            resumeAi = { resumed += 1 },
        )

        gate.onCallStart()
        gate.onCallEnd()

        assertEquals(1, gate.interruptCount)
        assertEquals(0, gate.resumeCount)
        assertEquals(1, interrupted)
        assertEquals(0, resumed)
    }
}
