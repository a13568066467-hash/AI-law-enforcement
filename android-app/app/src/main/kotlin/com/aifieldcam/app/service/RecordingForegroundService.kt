package com.aifieldcam.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.aifieldcam.app.MainActivity
import com.aifieldcam.app.R
import com.aifieldcam.app.platform.RecordingForegroundHold

/**
 * 息屏续录 / 息屏拍照：以 camera(+mic) 前台服务声明长时摄录，避免 Activity paused 后相机被系统收回。
 */
class RecordingForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var mode: Mode = Mode.RECORDING

    private enum class Mode { RECORDING, CAMERA_HOLD }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_CAMERA_HOLD -> Mode.CAMERA_HOLD
            else -> Mode.RECORDING
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (mode == Mode.CAMERA_HOLD) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "aifieldcam:recording",
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.recording_notification_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_home_recorder)
            .setContentTitle(
                if (mode == Mode.RECORDING) {
                    getString(R.string.recording_notification_title)
                } else {
                    getString(R.string.camera_hold_notification_title)
                },
            )
            .setContentText(
                if (mode == Mode.RECORDING) {
                    getString(R.string.recording_notification_text)
                } else {
                    getString(R.string.camera_hold_notification_text)
                },
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_MODE = "mode"
        private const val MODE_CAMERA_HOLD = "camera_hold"

        /** 打开相机前调用，保证息屏下仍有 camera FGS 类型。 */
        fun ensureRunning(context: Context, forRecording: Boolean = true) {
            synchronized(RecordingForegroundService::class.java) {
                RecordingForegroundHold.acquire()
                startInternal(context, if (forRecording) MODE_RECORDING else MODE_CAMERA_HOLD)
            }
        }

        /** 相机操作结束且未在录像时释放前台服务。 */
        fun releaseIfIdle(context: Context) {
            synchronized(RecordingForegroundService::class.java) {
                RecordingForegroundHold.release()
                if (RecordingForegroundHold.shouldStopAfterRelease()) {
                    context.stopService(Intent(context, RecordingForegroundService::class.java))
                }
            }
        }

        fun start(context: Context) {
            ensureRunning(context, forRecording = true)
        }

        fun stop(context: Context) {
            RecordingForegroundHold.reset()
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }

        internal fun holdCountForTest(): Int = RecordingForegroundHold.count()

        private const val MODE_RECORDING = "recording"

        private fun startInternal(context: Context, mode: String) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                putExtra(EXTRA_MODE, mode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
