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
 * 执法仪存储路由（DSJ-ZECN6A1）：
 * 1. 优先 SanDisk / adoptable SD 卡（如 mmcblk1 SK32G，~30GB f2fs）
 * 2. 其次其他外置卷（剩余空间最大）
 * 3. 最后内置 emulated / filesDir
 *
 * 注意：`/storage/49A5-0AFB` 为出厂 eMMC 上的 vfat 公共分区（~21GB），
 * 不是 SanDisk TF 卡，不应作为首选录像卷。
 */
object MediaStorageLocator {

    private const val TAG = "MediaStorage"
    private const val APP_FOLDER = "AIFieldCam"
    /** 出厂 eMMC vfat 公共分区 UUID（误标为 TF 卡时降级） */
    private const val FACTORY_VFAT_VOLUME = "49A5-0AFB"

    data class Layout(
        val videoDir: File,
        val albumDir: File,
        val audioDir: File,
        /** MediaStore 卷名，如 external_primary 或 adoptable uuid */
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
            append(storageLabel(app, moviesRoot))
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

    data class StorageUsage(
        val totalBytes: Long,
        val availableBytes: Long,
        val usedBytes: Long,
        /** 0–100，卷已用空间占比 */
        val usedPercent: Int,
    )

    fun freeMb(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes / (1024 * 1024)
        } catch (_: Throwable) {
            0L
        }
    }

    fun storageUsage(dir: File): StorageUsage {
        return try {
            val stat = StatFs(dir.absolutePath)
            val total = stat.totalBytes.coerceAtLeast(1L)
            val available = stat.availableBytes.coerceAtLeast(0L)
            val used = (total - available).coerceAtLeast(0L)
            StorageUsage(
                totalBytes = total,
                availableBytes = available,
                usedBytes = used,
                usedPercent = computeUsedPercent(total, used),
            )
        } catch (_: Throwable) {
            StorageUsage(totalBytes = 1, availableBytes = 0, usedBytes = 1, usedPercent = 100)
        }
    }

    internal fun computeUsedPercent(totalBytes: Long, usedBytes: Long): Int {
        if (totalBytes <= 0L) return 100
        return ((usedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    /** 内置分区可用空间（用于低存储告警） */
    fun internalFreeMb(context: Context): Long {
        val internal = context.applicationContext.filesDir
        return freeMb(internal)
    }

    private fun pickRoot(context: Context, type: String): File {
        val candidates = buildCandidates(context, type)
            .distinctBy { it.absolutePath }
            .filter { isWritable(it) }

        if (candidates.isEmpty()) {
            Log.w(TAG, "no writable storage for $type, fallback to filesDir")
            return fallbackRoot(context, type)
        }

        if (!DeviceProfile.isDsjZecn6a1) {
            return candidates.first()
        }

        val sm = context.getSystemService(StorageManager::class.java)
        val picked = candidates
            .maxByOrNull { scoreCandidate(context, sm, it) }
            ?.takeIf { scoreCandidate(context, sm, it) > Int.MIN_VALUE }

        val root = picked ?: candidates.maxByOrNull { freeMb(it) } ?: fallbackRoot(context, type)
        if (DeviceProfile.isDsjZecn6a1 &&
            !candidates.any { it.absolutePath.contains("/mnt/expand/", ignoreCase = true) } &&
            !isPortableSdPath(root.absolutePath)
        ) {
            Log.w(
                TAG,
                "no portable SD card path available; using fallback ${root.absolutePath}",
            )
        }
        if (!root.exists() && !root.mkdirs()) {
            Log.w(TAG, "mkdirs failed for $root, fallback to built-in")
            return fallbackRoot(context, type)
        }
        if (!root.canWrite()) {
            Log.w(TAG, "directory not writable: $root, fallback to built-in")
            return fallbackRoot(context, type)
        }
        return root
    }

    /** 合并 getExternalFilesDirs 与 SanDisk adoptable 卷（/mnt/expand）上的应用目录 */
    private fun buildCandidates(context: Context, type: String): List<File> {
        val fromContext = ContextCompat.getExternalFilesDirs(context, type).filterNotNull()
        val fromExpand = scanExpandSanDiskDirs(context, type)
        return fromContext + fromExpand
    }

    /**
     * SanDisk adoptable 卷不在 [StorageManager.storageVolumes] 中暴露时，
     * 直接扫描 `/mnt/expand/{uuid}/media/0/Android/data/...`。
     */
    private fun scanExpandSanDiskDirs(context: Context, type: String): List<File> {
        if (!DeviceProfile.isDsjZecn6a1) return emptyList()
        val pkg = context.packageName
        val found = mutableListOf<File>()

        val uuidDirs = mutableListOf<String>()
        val expandBase = File("/mnt/expand")
        if (expandBase.isDirectory) {
            expandBase.listFiles()?.filter { it.isDirectory }?.forEach { uuidDirs.add(it.name) }
        } else {
            Log.w(TAG, "cannot list /mnt/expand (${expandBase.exists()}), probing known adoptable uuid")
        }
        // 旧 adoptable 卷格式化便携式后可能仍存在空目录，跳过
        if (uuidDirs.isEmpty()) {
            val legacy = File("/mnt/expand/${DeviceProfile.ADOPTABLE_STORAGE_UUID}")
            if (legacy.isDirectory && freeMb(legacy) >= 1_024) {
                uuidDirs.add(DeviceProfile.ADOPTABLE_STORAGE_UUID)
            }
        }

        for (uuid in uuidDirs) {
            if (uuid.equals(FACTORY_VFAT_VOLUME, ignoreCase = true)) continue
            val root = File("/mnt/expand/$uuid")
            val free = if (root.isDirectory) freeMb(root) else 0L
            if (free < 1_024) {
                Log.w(TAG, "skip expand uuid=$uuid free=${free}MB")
                continue
            }
            val candidates = listOf(
                File(root, "media/0/Android/data/$pkg/files/$type"),
                File(root, "Android/data/$pkg/files/$type"),
            )
            for (dir in candidates) {
                if (probeWritableDir(dir)) {
                    Log.i(TAG, "expand candidate -> $dir (uuid=$uuid, ${free}MB free on volume)")
                    found.add(dir)
                } else {
                    Log.w(TAG, "expand not writable -> $dir")
                }
            }
        }
        return found
    }

    private fun probeWritableDir(dir: File): Boolean {
        return try {
            dir.mkdirs()
            if (!dir.isDirectory || !dir.canWrite()) return false
            val probe = File(dir, ".write_probe")
            probe.outputStream().use { it.write(1) }
            probe.delete()
            true
        } catch (e: Exception) {
            Log.w(TAG, "probeWritableDir ${dir.absolutePath}: ${e.message}")
            false
        }
    }

    /**
     * 在 adoptable / SanDisk 卷上构造 App 私有目录（StorageVolume API 路径）。
     */
    private fun adoptableSanDiskAppDir(context: Context, type: String): File? {
        scanExpandSanDiskDirs(context, type)
            .maxByOrNull { freeMb(it) }
            ?.let { return it }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        val pkg = context.packageName

        val best = sm.storageVolumes
            .filter { it.isMounted }
            .filter { isPreferredRecordingVolume(context, it) }
            .mapNotNull { vol ->
                val root = volumeRootDir(vol) ?: return@mapNotNull null
                val appDir = File(root, "Android/data/$pkg/files/$type")
                Triple(vol, appDir, freeMb(root))
            }
            .maxByOrNull { it.third }

        best?.let { (vol, appDir, free) ->
            appDir.mkdirs()
            if (isWritable(appDir)) {
                val label = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    vol.getDescription(context)
                } else {
                    vol.uuid
                }
                Log.i(TAG, "adoptable storage dir -> $appDir ($label, ${free}MB free)")
                return appDir
            }
        }
        return null
    }

    /** SanDisk / adoptable SD，排除出厂 eMMC vfat 公共分区 */
    private fun isPreferredRecordingVolume(context: Context, vol: StorageVolume): Boolean {
        if (vol.isPrimary) return false
        val uuid = vol.uuid?.uppercase() ?: ""
        if (uuid == FACTORY_VFAT_VOLUME) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val desc = vol.getDescription(context)?.lowercase() ?: ""
            if (desc.contains("sandisk") || desc.contains("sd 卡") || desc.contains("sd card")) {
                return true
            }
            // adoptable 内置扩展 SD：可移除 + 非 primary
            if (vol.isRemovable) return true
        }
        val rootPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            vol.directory?.absolutePath?.lowercase()
        } else {
            null
        }
        return rootPath?.contains("/mnt/expand/") == true
    }

    /** 卷根目录：优先 [StorageVolume.directory]，回退 `/mnt/expand/{uuid}` */
    private fun volumeRootDir(vol: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            vol.directory?.let { return it }
        }
        val uuid = vol.uuid ?: return null
        val expand = File("/mnt/expand/$uuid")
        if (expand.isDirectory && expand.canRead()) return expand
        val storage = File("/storage/$uuid")
        if (storage.isDirectory && storage.canRead()) return storage
        return null
    }

    private fun scoreCandidate(context: Context, sm: StorageManager?, dir: File): Int {
        var score = 0
        val path = dir.absolutePath
        val pathLower = path.lowercase()
        val vol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sm?.getStorageVolume(dir)
        } else {
            null
        }

        if (vol != null && isPreferredRecordingVolume(context, vol)) {
            score += 10_000
        } else if (isSanDiskVolume(context, vol)) {
            score += 9_000
        } else if (pathLower.contains("sandisk") || pathLower.contains("/mnt/expand/")) {
            score += 8_000
        }

        if (path.contains(FACTORY_VFAT_VOLUME, ignoreCase = true)) {
            // 出厂 eMMC vfat「Rom」公共分区：App 可写，SanDisk adoptable 不可达时的回退
            score += 4_000
        }
        if (pathLower.contains("/mnt/expand/")) {
            score += 12_000
        }
        if (pathLower.contains("/emulated/")) {
            score -= 3_000
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && vol != null) {
            if (vol.isRemovable && !vol.isPrimary) score += 500
            if (vol.isPrimary) score -= 200
        }

        score += (freeMb(dir) / 1024).toInt().coerceAtMost(2_000)
        return score
    }

    private fun isPortableSdPath(path: String): Boolean {
        if (path.contains("/emulated/")) return false
        if (path.contains(FACTORY_VFAT_VOLUME, ignoreCase = true)) return false
        return path.matches(Regex("/storage/[^/]+/Android/.*", RegexOption.IGNORE_CASE))
    }

    private fun isSanDiskVolume(context: Context, vol: StorageVolume?): Boolean {
        if (vol == null) return false
        return isPreferredRecordingVolume(context, vol)
    }

    private fun storageLabel(context: Context, dir: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = context.getSystemService(StorageManager::class.java)
            val vol = sm?.getStorageVolume(dir)
            if (vol != null) {
                val desc = vol.getDescription(context)?.takeIf { it.isNotBlank() }
                if (desc != null) {
                    return when {
                        desc.contains("SanDisk", ignoreCase = true) -> "SanDisk SD卡"
                        desc.contains("SD", ignoreCase = true) -> desc
                        vol.isPrimary -> "内置"
                        else -> desc
                    }
                }
            }
        }
        val path = dir.absolutePath
        return when {
            path.contains("/mnt/expand/", ignoreCase = true) -> "SanDisk SD卡"
            path.contains(FACTORY_VFAT_VOLUME, ignoreCase = true) -> "内置媒体分区"
            path.contains("/emulated/") -> "内置"
            path.contains("/mnt/expand/", ignoreCase = true) -> "SanDisk SD卡"
            else -> "外置存储"
        }
    }

    private val StorageVolume.isMounted: Boolean
        get() = when (state) {
            Environment.MEDIA_MOUNTED,
            Environment.MEDIA_MOUNTED_READ_ONLY,
            -> true
            else -> state.equals("mounted", ignoreCase = true)
        }

    private fun fallbackRoot(context: Context, type: String): File {
        val base = context.applicationContext.filesDir
        return when (type) {
            Environment.DIRECTORY_MOVIES -> File(base, "videos")
            Environment.DIRECTORY_PICTURES -> File(base, "album")
            else -> base
        }
    }

    private fun resolveSameVolumeDir(
        context: Context,
        moviesRoot: File,
        type: String,
    ): File {
        val moviesVol = volumeRootPath(moviesRoot.absolutePath)
        val sameVolume = buildCandidates(context, type)
            .distinctBy { it.absolutePath }
            .firstOrNull { candidate ->
                volumeRootPath(candidate.absolutePath) == moviesVol
            }
        if (sameVolume != null) return sameVolume

        val fallback = File(moviesRoot.parentFile, type)
        if (!fallback.exists()) fallback.mkdirs()
        return fallback
    }

    private fun volumeRootPath(path: String): String =
        path.substringBefore("/Android/", path)

    private fun isWritable(dir: File): Boolean {
        return try {
            if (!dir.exists()) dir.mkdirs()
            dir.isDirectory && dir.canWrite()
        } catch (_: Exception) {
            false
        }
    }

    /** @deprecated 仅测试/兼容；请用 [StorageManager.getStorageVolume] */
    internal fun isRemovableStoragePath(path: String): Boolean {
        if (path.contains("/emulated/")) return false
        if (path.matches(Regex("/storage/[^/]+/.*"))) {
            val volumeId = path.removePrefix("/storage/").substringBefore('/')
            return volumeId != "emulated" && volumeId != "self"
        }
        return path.contains("/mnt/expand/", ignoreCase = true)
    }

    fun mediaStoreVolumeName(context: Context, mediaDir: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            val volume: StorageVolume? = sm?.getStorageVolume(mediaDir)
            volume?.mediaStoreVolumeName?.let {
                return it.lowercase()
            }
            volume?.uuid?.let { uuid ->
                if (uuid.isNotBlank()) return uuid.lowercase()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm?.getStorageVolume(mediaDir)?.uuid?.let {
                if (it.isNotBlank()) return it.lowercase()
            }
        }
        return if (mediaDir.absolutePath.contains("/emulated/")) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            mediaDir.absolutePath.removePrefix("/storage/").substringBefore('/').lowercase()
        }
    }

    fun mediaStoreVolume(context: Context): String =
        mediaStoreVolumeName(context, resolve(context).videoDir)
}
