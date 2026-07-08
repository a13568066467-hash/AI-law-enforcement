package com.aifieldcam.app.platform

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PTT 长按短语音采集（独立 AudioRecord，最长 15 秒）。
 * 与录像的 MediaRecorder 共享 MIC，需真机验证并发兼容性。
 */
object VoiceCaptureHelper {

    private const val TAG = "VoiceCapture"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val MAX_DURATION_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val capturing = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var captureStartMs = 0L

    fun isCapturing(): Boolean = capturing.get()

    /** 开始采集（需在非主线程调用好，由 [stop] 返回结果） */
    fun start(onStarted: () -> Unit, onError: (String) -> Unit) {
        if (!capturing.compareAndSet(false, true)) {
            onError("已在采集语音")
            return
        }
        executor.execute {
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
                if (minBuf < 0) {
                    capturing.set(false)
                    mainHandler.post { onError("音频设备不可用") }
                    return@execute
                }
                val bufSize = maxOf(minBuf, SAMPLE_RATE * 2) // 1 秒缓冲
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    bufSize,
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    capturing.set(false)
                    mainHandler.post { onError("无法初始化麦克风") }
                    return@execute
                }
                audioRecord = recorder
                captureStartMs = System.currentTimeMillis()
                recorder.startRecording()
                Log.i(TAG, "voice capture started")
                mainHandler.post { onStarted() }
            } catch (e: Exception) {
                Log.e(TAG, "start voice capture failed", e)
                capturing.set(false)
                mainHandler.post { onError(e.message ?: "麦克风异常") }
            }
        }
    }

    /** 停止采集，返回 PCM （16kHz/16bit/mono） 字节数组 */
    fun stop(onResult: (ByteArray?) -> Unit) {
        if (!capturing.get()) {
            mainHandler.post { onResult(null) }
            return
        }
        executor.execute {
            var pcm: ByteArray? = null
            try {
                val recorder = audioRecord
                if (recorder != null && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                    val elapsedMs = System.currentTimeMillis() - captureStartMs
                    val expectedBytes = (elapsedMs * SAMPLE_RATE * 2 / 1000).toInt()
                    val buf = ByteArray(expectedBytes.coerceAtLeast(44))
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        pcm = buf.copyOf(read)
                        Log.i(TAG, "voice captured: ${read}B in ${elapsedMs}ms")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "stop voice capture: ${e.message}")
            } finally {
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
                captureStartMs = 0L
                capturing.set(false)
                mainHandler.post { onResult(pcm) }
            }
        }
    }

    /** 最长录制时间 */
    fun maxDurationMs(): Long = MAX_DURATION_MS

    /** 测试用 */
    internal fun sampleRateForTest(): Int = SAMPLE_RATE
}