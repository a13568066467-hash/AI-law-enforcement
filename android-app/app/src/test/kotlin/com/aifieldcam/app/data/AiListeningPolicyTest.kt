package com.aifieldcam.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiListeningPolicyTest {

    @Test
    fun screenAndRemoteListeningRemainBlockedWhileRecording() {
        assertFalse(
            AiListeningPolicy.allowsStart(
                source = AiListeningSource.SCREEN,
                recording = true,
            ),
        )
        assertFalse(
            AiListeningPolicy.allowsStart(
                source = AiListeningSource.REMOTE,
                recording = true,
            ),
        )
    }

    @Test
    fun physicalPttListeningCanSynchronizeStateWhileRecording() {
        assertTrue(
            AiListeningPolicy.allowsStart(
                source = AiListeningSource.PHYSICAL_PTT,
                recording = true,
            ),
        )
    }

    @Test
    fun clearingScreenDoesNotClearOverlappingPhysicalPtt() {
        val sources = AiListeningSources()

        assertTrue(sources.update(AiListeningSource.PHYSICAL_PTT, active = true, recording = true))
        assertTrue(sources.update(AiListeningSource.SCREEN, active = true, recording = false))
        assertTrue(sources.update(AiListeningSource.SCREEN, active = false, recording = false))

        assertTrue(sources.isActive())
        assertTrue(sources.update(AiListeningSource.PHYSICAL_PTT, active = false, recording = true))
        assertFalse(sources.isActive())
    }

    @Test
    fun clearingPhysicalPttDoesNotClearOverlappingRemoteListening() {
        val sources = AiListeningSources()

        assertTrue(sources.update(AiListeningSource.REMOTE, active = true, recording = false))
        assertTrue(sources.update(AiListeningSource.PHYSICAL_PTT, active = true, recording = false))
        assertTrue(sources.update(AiListeningSource.PHYSICAL_PTT, active = false, recording = false))

        assertTrue(sources.isActive())
    }

    @Test
    fun rejectedSourceDoesNotBecomeActive() {
        val sources = AiListeningSources()

        assertFalse(sources.update(AiListeningSource.SCREEN, active = true, recording = true))

        assertFalse(sources.isActive())
    }

    @Test
    fun recordingStartClearsRestrictedSourcesButPreservesPhysicalPtt() {
        val sources = AiListeningSources()
        sources.update(AiListeningSource.SCREEN, active = true, recording = false)
        sources.update(AiListeningSource.REMOTE, active = true, recording = false)
        sources.update(AiListeningSource.PHYSICAL_PTT, active = true, recording = false)

        sources.clearBlockedByRecording()

        assertTrue(sources.isActive())
        sources.update(AiListeningSource.PHYSICAL_PTT, active = false, recording = true)
        assertFalse(sources.isActive())
    }

    @Test
    fun clearAll_clears_every_listening_source_for_command_call_interrupt() {
        val sources = AiListeningSources()
        sources.update(AiListeningSource.SCREEN, active = true, recording = false)
        sources.update(AiListeningSource.PHYSICAL_PTT, active = true, recording = false)

        sources.clearAll()

        assertFalse(sources.isActive())
    }
}
