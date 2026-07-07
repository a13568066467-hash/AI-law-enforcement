package com.aifieldcam.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream

object GallerySaver {

    private const val TAG = "GallerySaver"
    private const val IMAGE_DIR = "AIFieldCam"
    private const val VIDEO_DIR = "AIFieldCam"

    fun saveImageToGallery(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "skip image save: missing or empty ${file.name}")
            return false
        }
        val name = file.name.ifBlank { "AIFieldCam_${System.currentTimeMillis()}.jpg" }
        val ok = insertMedia(
            context = context,
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            displayName = name,
            mimeType = "image/jpeg",
            relativePath = "${Environment.DIRECTORY_PICTURES}/$IMAGE_DIR",
            file = file,
        ) != null
        if (!ok) Log.e(TAG, "failed to save image ${file.name}")
        return ok
    }

    fun saveVideoToGallery(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "skip video save: missing or empty ${file.name}")
            return false
        }
        val name = file.name.ifBlank { "AIFieldCam_${System.currentTimeMillis()}.mp4" }
        val ok = insertMedia(
            context = context,
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            displayName = name,
            mimeType = "video/mp4",
            relativePath = "${Environment.DIRECTORY_MOVIES}/$VIDEO_DIR",
            file = file,
        ) != null
        if (!ok) Log.e(TAG, "failed to save video ${file.name} (${file.length()}B)")
        return ok
    }

    private fun insertMedia(
        context: Context,
        collection: Uri,
        displayName: String,
        mimeType: String,
        relativePath: String,
        file: File,
    ): Uri? {
        val resolver = context.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("openOutputStream failed")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "insertMedia failed for $displayName", e)
            resolver.delete(uri, null, null)
            null
        }
    }
}
