package com.aifieldcam.app.platform

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.aifieldcam.app.util.PhoneCameraHelper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/** 说明书「录音键」：仅录音频 M4A/AAC，不占用摄像头。 */
object NativeAudioRecorder {

    private const val TAG = "NativeAudioRecorder"

    private var executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val recording = AtomicBoolean(false)

    fun isRecording(): Boolean = recording.get()

    fun startRecording(context: Context, onStarted: () -> Unit, onError: (String) -> Unit) {
        if (!DeviceProfile.isDsjZecn6a1) {
            onError("非执法仪本机模式")
            return
        }
        if (recording.get()) {
            onError("已在录音中")
            return
        }
        val file = PhoneCameraHelper.newAudioFile(context.applicationContext)
        outputFile = file
        executor.execute {
            try {
                val recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                mediaRecorder = recorder
                recording.set(true)
                Log.i(TAG, "audio started -> ${file.name}")
                onStarted()
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                cleanupQuietly()
                onError(e.message ?: "无法开始录音")
            }
        }
    }

    fun stopRecording(onStopped: (File?, String) -> Unit) {
        if (!recording.get()) {
            onStopped(null, "当前未在录音")
            return
        }
        executor.execute {
            val file = outputFile
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "stop: ${e.message}")
            }
            cleanupQuietly()
            recording.set(false)
            if (file != null && file.exists() && file.length() > 0L) {
                Log.i(TAG, "audio stopped -> ${file.name}")
                onStopped(file, "")
            } else {
                onStopped(null, "录音文件为空")
            }
        }
    }

    fun release() {
        executor.execute { cleanupQuietly() }
        recording.set(false)
        executor.shutdown()
    }

    private fun cleanupQuietly() {
        try {
            mediaRecorder?.reset()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        outputFile = null
    }
}
