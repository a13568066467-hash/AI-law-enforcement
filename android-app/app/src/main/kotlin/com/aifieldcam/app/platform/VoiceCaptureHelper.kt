package com.aifieldcam.app.platform

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PTT 长按短语音采集（16kHz PCM）。
 * 循环录像中优先走 [PcmTeeBridge] 旁路伴随音，避免第二路 AudioRecord 抢麦导致录像断声。
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
    private var usingTee = false

    fun isCapturing(): Boolean = capturing.get()

    fun start(onStarted: () -> Unit, onError: (String) -> Unit) {
        if (!capturing.compareAndSet(false, true)) {
            onError("已在采集语音")
            return
        }
        executor.execute {
            try {
                if (MediaEncoderPipeline.canProvidePcmTee() && PcmTeeBridge.attach()) {
                    usingTee = true
                    captureStartMs = System.currentTimeMillis()
                    Log.i(TAG, "voice capture via recording PCM tee (共麦)")
                    mainHandler.post { onStarted() }
                    return@execute
                }
                usingTee = false
                startDedicatedMic(onStarted, onError)
            } catch (e: Exception) {
                Log.e(TAG, "start voice capture failed", e)
                capturing.set(false)
                mainHandler.post { onError(e.message ?: "麦克风异常") }
            }
        }
    }

    private fun startDedicatedMic(onStarted: () -> Unit, onError: (String) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf < 0) {
            capturing.set(false)
            mainHandler.post { onError("音频设备不可用") }
            return
        }
        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2)
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
            return
        }
        audioRecord = recorder
        captureStartMs = System.currentTimeMillis()
        recorder.startRecording()
        Log.i(TAG, "voice capture started (dedicated mic)")
        mainHandler.post { onStarted() }
    }

    fun stop(onResult: (ByteArray?) -> Unit) {
        if (!capturing.get()) {
            mainHandler.post { onResult(null) }
            return
        }
        executor.execute {
            var pcm: ByteArray? = null
            try {
                if (usingTee) {
                    pcm = PcmTeeBridge.detachAndTake()
                    val elapsedMs = System.currentTimeMillis() - captureStartMs
                    Log.i(TAG, "tee voice captured: ${pcm?.size ?: 0}B in ${elapsedMs}ms")
                } else {
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
                }
            } catch (e: Exception) {
                Log.w(TAG, "stop voice capture: ${e.message}")
            } finally {
                try {
                    audioRecord?.release()
                } catch (_: Exception) {
                }
                audioRecord = null
                captureStartMs = 0L
                usingTee = false
                capturing.set(false)
                mainHandler.post { onResult(pcm) }
            }
        }
    }

    fun maxDurationMs(): Long = MAX_DURATION_MS

    internal fun sampleRateForTest(): Int = SAMPLE_RATE
}
