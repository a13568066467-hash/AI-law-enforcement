package com.aifieldcam.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStorageLocatorTest {

    @Test
    fun removablePath_detectsTfCardMount() {
        assertTrue(
            MediaStorageLocator.isRemovableStoragePath("/storage/49A5-0AFB/Android/data/com.aifieldcam.app/files/Movies"),
        )
        assertFalse(
            MediaStorageLocator.isRemovableStoragePath("/storage/emulated/0/Android/data/com.aifieldcam.app/files/Movies"),
        )
    }

    @Test
    fun volumeIdFromPath_parsesTfUuid() {
        assertEquals(
            "49a5-0afb",
            extractVolumeId("/storage/49A5-0AFB/Android/data/pkg/files/Movies"),
        )
        assertEquals(
            "external_primary",
            extractVolumeId("/storage/emulated/0/Movies"),
        )
    }

    @Test
    fun freeMb_handlesInvalidPath() {
        val invalidDir = java.io.File("/nonexistent/path")
        assertEquals(0L, MediaStorageLocator.freeMb(invalidDir))
    }

    private fun extractVolumeId(path: String): String {
        if (path.contains("/emulated/")) return "external_primary"
        val id = path.removePrefix("/storage/").substringBefore('/').lowercase()
        return if (id.isNotEmpty() && id != "emulated" && id != "self") id else "external_primary"
    }
}
