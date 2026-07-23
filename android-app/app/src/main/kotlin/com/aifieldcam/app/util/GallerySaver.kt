package com.aifieldcam.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.aifieldcam.app.platform.RecordingSegmentPolicy
import java.io.File
import java.io.FileInputStream

object GallerySaver {

    private const val TAG = "GallerySaver"
    private const val IMAGE_DIR = "AIFieldCam"
    private const val VIDEO_DIR = "AIFieldCam"
    /** 超过分片上限不再整文件复制（单段 1GB 可复制；旧版 4GB 大文件跳过） */
    private val maxGalleryCopyBytes: Long
        get() = RecordingSegmentPolicy.maxSegmentBytes() + 50L * 1024 * 1024

    fun saveImageToGallery(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "skip image save: missing or empty ${file.name}")
            return false
        }
        // 存储空间不足时跳过 MediaStore 复制
        val freeMb = MediaStorageLocator.freeMb(file.parentFile ?: file)
        val needMb = (file.length() / (1024 * 1024)) + 10
        if (freeMb < needMb) {
            Log.w(TAG, "skip gallery copy: need ${needMb}MB but only ${freeMb}MB free (file ${file.name})")
            return false
        }
        val name = file.name.ifBlank { "AIFieldCam_${System.currentTimeMillis()}.jpg" }
        val volume = MediaStorageLocator.mediaStoreVolumeName(context, file)
        val ok = insertMedia(
            context = context,
            collection = imageCollection(volume),
            displayName = name,
            mimeType = "image/jpeg",
            relativePath = "${Environment.DIRECTORY_PICTURES}/$IMAGE_DIR",
            file = file,
        )
        if (!ok) Log.w(TAG, "gallery copy skipped for image ${file.name}")
        return ok
    }

    fun saveVideoToGallery(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "skip video save: missing or empty ${file.name}")
            return false
        }
        if (file.length() > maxGalleryCopyBytes) {
            Log.i(
                TAG,
                "skip gallery copy for large video ${file.name} (${file.length()}B > " +
                    "${maxGalleryCopyBytes}B); file kept in app storage",
            )
            return false
        }
        // 存储空间不足时跳过 MediaStore 复制（文件已在 app 私有目录中安全保存）
        val freeMb = MediaStorageLocator.freeMb(file.parentFile ?: file)
        val needMb = (file.length() / (1024 * 1024)) + 50  // 留 50MB 余量
        if (freeMb < needMb) {
            Log.w(TAG, "skip gallery copy: need ${needMb}MB but only ${freeMb}MB free (file ${file.name})")
            return false
        }
        val name = file.name.ifBlank { "AIFieldCam_${System.currentTimeMillis()}.mp4" }
        val volume = MediaStorageLocator.mediaStoreVolumeName(context, file)
        val startedMs = System.currentTimeMillis()
        Log.i(TAG, "gallery copy start $name (${file.length()}B)")
        val ok = insertMedia(
            context = context,
            collection = videoCollection(volume),
            displayName = name,
            mimeType = "video/mp4",
            relativePath = "${Environment.DIRECTORY_MOVIES}/$VIDEO_DIR",
            file = file,
        )
        val elapsedMs = System.currentTimeMillis() - startedMs
        if (ok) {
            Log.i(TAG, "gallery copy done $name in ${elapsedMs}ms")
        } else {
            Log.w(TAG, "gallery copy skipped for video ${file.name} (${file.length()}B) after ${elapsedMs}ms")
        }
        return ok
    }

    /** 按文件名删除系统相册中的 AIFieldCam 照片副本（与 [saveImageToGallery] 相对路径一致） */
    fun deleteImageFromGallery(context: Context, file: File): Int =
        deleteFromGallery(context, file, image = true)

    /** 按文件名删除系统相册中的 AIFieldCam 录像副本（与 [saveVideoToGallery] 相对路径一致） */
    fun deleteVideoFromGallery(context: Context, file: File): Int =
        deleteFromGallery(context, file, image = false)

    private fun deleteFromGallery(context: Context, file: File, image: Boolean): Int {
        val name = file.name
        if (name.isBlank()) return 0
        val resolver = context.applicationContext.contentResolver
        val volume = MediaStorageLocator.mediaStoreVolumeName(context, file)
        val collection = if (image) imageCollection(volume) else videoCollection(volume)
        val deleted = resolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(name),
        )
        if (deleted > 0) {
            Log.i(TAG, "gallery deleted $deleted row(s) for $name")
        }
        return deleted
    }

    private fun imageCollection(volume: String): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(volume)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private fun videoCollection(volume: String): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(volume)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun insertMedia(
        context: Context,
        collection: Uri,
        displayName: String,
        mimeType: String,
        relativePath: String,
        file: File,
    ): Boolean {
        val resolver = context.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values)
        if (uri == null) {
            Log.w(TAG, "insert row failed for $displayName")
            return false
        }
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("openOutputStream failed")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            true
        } catch (e: Exception) {
            // 常见于存储空间不足，降级为 WARN（文件已在 app 私有目录中安全保存）
            val reason = if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true) {
                "存储空间不足"
            } else {
                e.message ?: "unknown"
            }
            Log.w(TAG, "gallery insert skipped ($reason): $displayName")
            resolver.delete(uri, null, null)
            false
        }
    }
}
