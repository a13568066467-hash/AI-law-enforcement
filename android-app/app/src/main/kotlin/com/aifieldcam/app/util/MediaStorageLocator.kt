package com.aifieldcam.app.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.aifieldcam.app.platform.DeviceProfile
import java.io.File

/**
 * 执法仪存储路由：优先可移除 TF 卡（SanDisk），其次外置分区，最后内置 filesDir。
 *
 * DSJ-ZECN6A1 内置 userdata 仅约 3.6GB，TF 卡挂载在 `/storage/49A5-0AFB` 等路径。
 */
object MediaStorageLocator {

    private const val TAG = "MediaStorage"
    private const val APP_FOLDER = "AIFieldCam"

    data class Layout(
        val videoDir: File,
        val albumDir: File,
        val audioDir: File,
        /** MediaStore 卷名，如 external_primary 或 49A5-0AFB */
        val mediaStoreVolume: String,
        val summary: String,
    )

    fun resolve(context: Context): Layout {
        val app = context.applicationContext
        val moviesRoot = pickRoot(app, Environment.DIRECTORY_MOVIES)
        val picturesRoot = resolveSameVolumeDir(app, moviesRoot, Environment.DIRECTORY_PICTURES)

        val videoDir = File(moviesRoot, "$APP_FOLDER/videos").apply { mkdirs() }
        val albumDir = File(picturesRoot, "$APP_FOLDER/album").apply { mkdirs() }
        val audioDir = File(moviesRoot, "$APP_FOLDER/audio").apply { mkdirs() }
        val volume = mediaStoreVolumeName(app, moviesRoot)
        val summary = buildString {
            append(if (isRemovablePath(moviesRoot)) "TF卡" else "内置")
            append(" · ")
            append(moviesRoot.absolutePath)
            append(" · 可用 ")
            append(freeMb(moviesRoot))
            append("MB")
        }
        Log.i(TAG, "media storage -> $summary (MediaStore volume=$volume)")
        return Layout(
            videoDir = videoDir,
            albumDir = albumDir,
            audioDir = audioDir,
            mediaStoreVolume = volume,
            summary = summary,
        )
    }

    fun freeMb(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes / (1024 * 1024)
        } catch (_: Throwable) {
            0L
        }
    }

    /** 内置分区可用空间（用于低存储告警） */
    fun internalFreeMb(context: Context): Long {
        val internal = context.applicationContext.filesDir
        return freeMb(internal)
    }

    private fun pickRoot(context: Context, type: String): File {
        val candidates = ContextCompat.getExternalFilesDirs(context, type)
            .filterNotNull()
            .distinctBy { it.absolutePath }
            .filter { isWritable(it) }

        if (candidates.isEmpty()) {
            Log.w(TAG, "no externalFilesDir for $type, fallback to filesDir")
            return fallbackRoot(context, type)
        }

        if (!DeviceProfile.isDsjZecn6a1) {
            return candidates.first()
        }

        val removable = candidates.filter { isRemovablePath(it) }
        val pool = if (removable.isNotEmpty()) removable else candidates
        return pool.maxByOrNull { freeMb(it) }?.also {
            // 确保目录可写，mkdirs 失败时回退到内置
            if (!it.exists() && !it.mkdirs()) {
                Log.w(TAG, "mkdirs failed for $it, fallback to built-in")
                return fallbackRoot(context, type)
            }
            if (!it.canWrite()) {
                Log.w(TAG, "directory not writable: $it, fallback to built-in")
                return fallbackRoot(context, type)
            }
        } ?: fallbackRoot(context, type)
    }

    private fun fallbackRoot(context: Context, type: String): File {
        val base = context.applicationContext.filesDir
        return when (type) {
            Environment.DIRECTORY_MOVIES -> File(base, "videos")
            Environment.DIRECTORY_PICTURES -> File(base, "album")
            else -> base
        }
    }

    /**
     * 确保 Pictures 目录与 Movies 在同一卷上，避免 MediaStore 卷名不一致。
     * 例如 moviesRoot 在 TF 卡上，Pictures 也必须在同一 TF 卡。
     */
    private fun resolveSameVolumeDir(
        context: Context,
        moviesRoot: File,
        type: String,
    ): File {
        val candidates = ContextCompat.getExternalFilesDirs(context, type)
            .filterNotNull()
            .filter { isWritable(it) }

        val moviesPath = moviesRoot.absolutePath
        val sameVolume = candidates.firstOrNull { candidate ->
            val cPath = candidate.absolutePath
            // 同卷判断：路径前缀包含相同的 /storage/xxx 段
            val moviesVol = moviesPath.substringBefore("/Android/", "")
            val candidateVol = cPath.substringBefore("/Android/", "")
            moviesVol.isNotEmpty() && candidateVol.isNotEmpty() && moviesVol == candidateVol
        }
        if (sameVolume != null) return sameVolume

        // 如果找不到同卷候选，就在 moviesRoot 旁边建 Pictures
        val fallback = File(moviesRoot.parentFile, type)
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }

    private fun isWritable(dir: File): Boolean {
        return try {
            if (!dir.exists()) dir.mkdirs()
            dir.isDirectory && dir.canWrite()
        } catch (_: Exception) {
            false
        }
    }

    private fun isRemovablePath(dir: File): Boolean {
        val path = dir.absolutePath
        return isRemovableStoragePath(path)
    }

    /** 纯路径判断，方便单元测试直接调用 */
    internal fun isRemovableStoragePath(path: String): Boolean {
        if (path.contains("/emulated/")) return false
        if (path.matches(Regex("/storage/[^/]+/.*"))) {
            val volumeId = path.removePrefix("/storage/").substringBefore('/')
            return volumeId != "emulated" && volumeId != "self"
        }
        return false
    }

    fun mediaStoreVolumeName(context: Context, mediaDir: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            val volume: StorageVolume? = sm?.getStorageVolume(mediaDir)
            volume?.mediaStoreVolumeName?.let {
                // MediaStore 卷名统一小写，避免大小写不匹配
                return it.lowercase()
            }
        }
        return if (isRemovablePath(mediaDir)) {
            // 从路径提取卷 ID，统一小写
            mediaDir.absolutePath.removePrefix("/storage/").substringBefore('/').lowercase()
        } else {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        }
    }

    fun mediaStoreVolume(context: Context): String =
        mediaStoreVolumeName(context, resolve(context).videoDir)
}
