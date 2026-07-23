package com.aifieldcam.app.util

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 监听系统相册 AIFieldCam 副本变化：系统侧删除后，按 DISPLAY_NAME 删除应用内主文件。
 * 用持久化快照区分「从未写入系统相册」与「进程外被删」。
 */
object AlbumMediaSync {

    private const val TAG = "AlbumMediaSync"
    private const val PREFS = "album_media_sync"
    private const val KEY_IMAGES = "gallery_image_names"
    private const val KEY_VIDEOS = "gallery_video_names"
    private const val DEBOUNCE_MS = 350L
    private const val FOLDER = "AIFieldCam"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AlbumMediaSync-IO").apply { isDaemon = true }
    }
    private val localDeletes = ConcurrentHashMap.newKeySet<String>()
    private val started = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var onAppFileDeleted: ((File) -> Unit)? = null
    private var imageObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    private val reconcileRunnable = Runnable { ioExecutor.execute { reconcileLocked() } }

    fun start(context: Context, onAppFileDeleted: (File) -> Unit) {
        val app = context.applicationContext
        this.appContext = app
        this.onAppFileDeleted = onAppFileDeleted
        if (!started.compareAndSet(false, true)) return

        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                mainHandler.removeCallbacks(reconcileRunnable)
                mainHandler.postDelayed(reconcileRunnable, DEBOUNCE_MS)
            }
        }
        imageObserver = observer
        videoObserver = observer
        val resolver = app.contentResolver
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        ioExecutor.execute { reconcileLocked() }
        Log.i(TAG, "started")
    }

    fun stop() {
        val app = appContext ?: return
        imageObserver?.let { app.contentResolver.unregisterContentObserver(it) }
        // image/video share same observer instance when started together
        imageObserver = null
        videoObserver = null
        started.set(false)
        mainHandler.removeCallbacks(reconcileRunnable)
        Log.i(TAG, "stopped")
    }

    fun beginLocalDelete(displayName: String) {
        if (displayName.isNotBlank()) localDeletes.add(displayName)
    }

    fun endLocalDelete(displayName: String) {
        if (displayName.isNotBlank()) localDeletes.remove(displayName)
    }

    private fun reconcileLocked() {
        val app = appContext ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prevImages = prefs.getStringSet(KEY_IMAGES, emptySet())?.toSet().orEmpty()
        val prevVideos = prefs.getStringSet(KEY_VIDEOS, emptySet())?.toSet().orEmpty()
        val curImages = queryNames(app, image = true)
        val curVideos = queryNames(app, image = false)

        val removedImages = prevImages - curImages
        val removedVideos = prevVideos - curVideos

        for (name in removedImages) {
            if (name in localDeletes) continue
            deleteAppFile(app, name, image = true)
        }
        for (name in removedVideos) {
            if (name in localDeletes) continue
            deleteAppFile(app, name, image = false)
        }

        prefs.edit()
            .putStringSet(KEY_IMAGES, curImages)
            .putStringSet(KEY_VIDEOS, curVideos)
            .apply()
    }

    private fun deleteAppFile(context: Context, name: String, image: Boolean) {
        val file = if (image) {
            File(AlbumStore.albumDir(context), name)
        } else {
            File(PhoneCameraHelper.videoDir(context), name)
        }
        if (!file.exists()) return
        val ok = file.delete()
        Log.i(TAG, "system gallery removed $name → delete app file ok=$ok path=${file.absolutePath}")
        if (ok) {
            val cb = onAppFileDeleted
            mainHandler.post { cb?.invoke(file) }
        }
    }

    private fun queryNames(context: Context, image: Boolean): Set<String> {
        val resolver = context.contentResolver
        val collection = if (image) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val names = linkedSetOf<String>()
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("%$FOLDER%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%$FOLDER%")
        }
        return try {
            resolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(idx)?.trim().orEmpty()
                    if (name.isNotEmpty()) names.add(name)
                }
            }
            names
        } catch (e: Exception) {
            Log.w(TAG, "queryNames image=$image failed: ${e.message}")
            emptySet()
        }
    }
}
