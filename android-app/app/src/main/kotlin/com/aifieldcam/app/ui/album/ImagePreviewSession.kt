package com.aifieldcam.app.ui.album

/**
 * Holds the photo path list for the fullscreen preview dialog.
 * Avoids parceling a large ArrayList through Fragment arguments on the main thread.
 */
object ImagePreviewSession {
    @Volatile
    var paths: List<String> = emptyList()
        private set

    @Volatile
    var startIndex: Int = 0
        private set

    fun prepare(photoPaths: List<String>, index: Int) {
        paths = photoPaths
        startIndex = if (photoPaths.isEmpty()) 0 else index.coerceIn(0, photoPaths.lastIndex)
    }

    fun clear() {
        paths = emptyList()
        startIndex = 0
    }
}
