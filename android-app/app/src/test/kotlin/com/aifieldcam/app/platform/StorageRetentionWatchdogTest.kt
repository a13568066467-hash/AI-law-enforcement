package com.aifieldcam.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorageRetentionWatchdogTest {

    @Test
    fun triggerAt85TargetBelow50() {
        assertFalse(StorageRetentionWatchdog.shouldTriggerPurge(84))
        assertTrue(StorageRetentionWatchdog.shouldTriggerPurge(85))
        assertTrue(StorageRetentionWatchdog.shouldContinuePurge(50))
        assertFalse(StorageRetentionWatchdog.shouldContinuePurge(49))
    }

    @Test
    fun selectCandidates_oldestFirst_skipsProtectedAndNonMp4() {
        val dir = File.createTempFile("retention", "").parentFile!!
        val old = File(dir, "old.mp4").apply {
            writeText("a")
            setLastModified(1_000L)
        }
        val mid = File(dir, "mid.mp4").apply {
            writeText("bb")
            setLastModified(2_000L)
        }
        val current = File(dir, "current.mp4").apply {
            writeText("ccc")
            setLastModified(3_000L)
        }
        val photo = File(dir, "snap.jpg").apply { writeText("x") }
        val empty = File(dir, "empty.mp4")

        val picked = StorageRetentionWatchdog.selectPurgeCandidates(
            arrayOf(current, photo, mid, old, empty),
            protectedPath = current.absolutePath,
        )

        assertEquals(listOf(old, mid), picked)
        listOf(old, mid, current, photo, empty).forEach { it.delete() }
    }

    @Test
    fun tickInterval_isOneMinute() {
        assertEquals(60_000L, StorageRetentionWatchdog.tickIntervalMs())
    }
}
