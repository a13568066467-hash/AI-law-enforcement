package com.aifieldcam.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantWaveStateTest {

    @Test
    fun resolveReturnsIdleWhenNoActivityIsActive() {
        assertEquals(
            AssistantWaveState.IDLE,
            AssistantWaveState.resolve(
                ttsSpeaking = false,
                aiListening = false,
                aiProcessing = false,
            ),
        )
    }

    @Test
    fun resolveReturnsProcessingWhenOnlyProcessingIsActive() {
        assertEquals(
            AssistantWaveState.PROCESSING,
            AssistantWaveState.resolve(
                ttsSpeaking = false,
                aiListening = false,
                aiProcessing = true,
            ),
        )
    }

    @Test
    fun resolvePrefersListeningOverProcessing() {
        assertEquals(
            AssistantWaveState.LISTENING,
            AssistantWaveState.resolve(
                ttsSpeaking = false,
                aiListening = true,
                aiProcessing = true,
            ),
        )
    }

    @Test
    fun resolvePrefersSpeakingOverListeningAndProcessing() {
        assertEquals(
            AssistantWaveState.SPEAKING,
            AssistantWaveState.resolve(
                ttsSpeaking = true,
                aiListening = true,
                aiProcessing = true,
            ),
        )
    }

    @Test
    fun pressStartsListening() {
        assertEquals(AssistantWaveState.LISTENING, AssistantWaveState.IDLE.onPress())
    }

    @Test
    fun releaseAfterListeningStartsProcessing() {
        assertEquals(
            AssistantWaveState.PROCESSING,
            AssistantWaveState.LISTENING.onRelease(),
        )
    }

    @Test
    fun speechStartBeginsSpeaking() {
        assertEquals(
            AssistantWaveState.SPEAKING,
            AssistantWaveState.PROCESSING.onSpeechChanged(true),
        )
    }

    @Test
    fun speechEndReturnsToIdle() {
        assertEquals(
            AssistantWaveState.IDLE,
            AssistantWaveState.SPEAKING.onSpeechChanged(false),
        )
    }

    @Test
    fun staleSpeechEndDoesNotInterruptNewListeningOrProcessingState() {
        listOf(
            AssistantWaveState.LISTENING,
            AssistantWaveState.PROCESSING,
        ).forEach { state ->
            assertEquals(state, state.onSpeechChanged(false))
        }
    }

    @Test
    fun failedRequestReturnsToIdle() {
        assertEquals(
            AssistantWaveState.IDLE,
            AssistantWaveState.PROCESSING.onRequestFinished(hasSpokenReply = false),
        )
    }

    @Test
    fun abortReturnsEveryActiveStateToIdle() {
        listOf(
            AssistantWaveState.LISTENING,
            AssistantWaveState.PROCESSING,
            AssistantWaveState.SPEAKING,
        ).forEach { state ->
            assertEquals(AssistantWaveState.IDLE, state.onAbort())
        }
    }
}
