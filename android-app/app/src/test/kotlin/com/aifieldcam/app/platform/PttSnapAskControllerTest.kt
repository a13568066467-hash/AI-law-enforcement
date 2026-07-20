package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class PttSnapAskControllerTest {

    @Test
    fun longPress_release_and_audio_form_one_turn() {
        var phase = PttRealtimeReducer.reduce(
            RealtimeVoicePhase.IDLE,
            PttRealtimeReducer.Event.PRESS_READY,
        )
        assertEquals(RealtimeVoicePhase.LISTENING, phase)

        phase = PttRealtimeReducer.reduce(phase, PttRealtimeReducer.Event.RELEASE)
        assertEquals(RealtimeVoicePhase.THINKING, phase)

        phase = PttRealtimeReducer.reduce(phase, PttRealtimeReducer.Event.AUDIO)
        assertEquals(RealtimeVoicePhase.SPEAKING, phase)

        phase = PttRealtimeReducer.reduce(phase, PttRealtimeReducer.Event.DONE)
        assertEquals(RealtimeVoicePhase.IDLE, phase)
    }

    @Test
    fun pressing_while_speaking_starts_new_listening_turn() {
        val phase = PttRealtimeReducer.reduce(
            RealtimeVoicePhase.SPEAKING,
            PttRealtimeReducer.Event.PRESS_READY,
        )

        assertEquals(RealtimeVoicePhase.LISTENING, phase)
    }

    @Test
    fun release_before_connection_ready_returns_idle() {
        val phase = PttRealtimeReducer.reduce(
            RealtimeVoicePhase.CONNECTING,
            PttRealtimeReducer.Event.RELEASE,
        )

        assertEquals(RealtimeVoicePhase.IDLE, phase)
    }
}
