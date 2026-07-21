package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 状态指示灯（厂商说明书 §三 + 指挥连线）：
 * - 待机：绿灯常亮
 * - 录音：黄灯闪烁
 * - 录像：红灯闪烁
 * - 拍照：红灯闪一次
 * - 充电：红灯常亮
 * - 充满：绿灯常亮
 * - 指挥连线中：红灯常亮（优先于录像闪）
 * - 连线中按住 PTT / AI 聆听按住 PTT：黄灯常亮
 *
 * 与侧键同步要求：开/停录、拍照在按键路径上立即调用本类，不等异步回调。
 */
object DeviceStatusIndicator {

    private const val TAG = "StatusLed"
    private const val BLINK_MS = 500L

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private var charging = false
    private var fullCharge = false
    private var videoRecording = false
    private var videoStreaming = false
    private var audioRecording = false
    private var commandCallActive = false
    private var commandCallPtt = false
    private var aiListening = false
    private var blinkOn = false
    private var blinkScheduled = false

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!Ze69Hardware.ledNodesWritable) {
                stopBlink()
                return
            }
            blinkOn = !blinkOn
            applyBlinkFrame()
            handler.postDelayed(this, BLINK_MS)
        }
    }

    fun onBatteryChanged(isCharging: Boolean, isFull: Boolean) {
        charging = isCharging
        fullCharge = isFull
        refresh()
    }

    fun setVideoRecording(active: Boolean) {
        if (videoRecording == active) return
        videoRecording = active
        refresh()
    }

    fun setVideoStreaming(active: Boolean) {
        if (videoStreaming == active) return
        videoStreaming = active
        refresh()
    }

    fun setAudioRecording(active: Boolean) {
        if (audioRecording == active) return
        audioRecording = active
        refresh()
    }

    fun setCommandCallActive(active: Boolean) {
        if (commandCallActive == active) return
        commandCallActive = active
        if (!active) {
            commandCallPtt = false
        }
        refresh()
    }

    fun setCommandCallPtt(active: Boolean) {
        if (commandCallPtt == active) return
        commandCallPtt = active
        refresh()
    }

    fun setAiListening(active: Boolean) {
        if (aiListening == active) return
        aiListening = active
        refresh()
    }

    /** 说明书：拍照时红灯闪一次 */
    fun pulsePhotoCapture() {
        if (!Ze69Hardware.ledNodesWritable) return
        stopBlink()
        Ze69Hardware.setIndicatorRed(true)
        Ze69Hardware.setIndicatorGreen(false)
        handler.postDelayed({
            refresh()
        }, 200L)
    }

    fun currentPatternForTests(): StatusLedPattern = resolvePattern()

    fun refresh() {
        if (!Ze69Hardware.isZe69Platform) return
        if (!Ze69Hardware.ledNodesWritable) {
            stopBlink()
            return
        }
        when (val pattern = resolvePattern()) {
            StatusLedPattern.COMMAND_CALL_PTT_YELLOW_STEADY,
            StatusLedPattern.AI_LISTEN_YELLOW_STEADY,
            -> showYellowSteady(pattern.name)

            StatusLedPattern.COMMAND_CALL_RED_STEADY -> showCommandCallRed()
            StatusLedPattern.STREAM_RG_BLINK -> showStreamBlink()
            StatusLedPattern.VIDEO_RED_BLINK -> showVideoBlink()
            StatusLedPattern.AUDIO_YELLOW_BLINK -> showAudioBlink()
            StatusLedPattern.FULL_GREEN_STEADY -> showFullCharge()
            StatusLedPattern.CHARGE_RED_STEADY -> showCharging()
            StatusLedPattern.STANDBY_GREEN_STEADY -> showStandby()
        }
    }

    fun resetAll() {
        charging = false
        fullCharge = false
        videoRecording = false
        videoStreaming = false
        audioRecording = false
        commandCallActive = false
        commandCallPtt = false
        aiListening = false
        stopBlink()
        if (Ze69Hardware.ledNodesWritable) {
            Ze69Hardware.setIndicatorRed(false)
            Ze69Hardware.setIndicatorGreen(false)
        }
        refresh()
    }

    private fun resolvePattern(): StatusLedPattern =
        StatusLedPriority.resolve(
            commandCallPtt = commandCallPtt,
            aiListening = aiListening,
            commandCallActive = commandCallActive,
            videoStreaming = videoStreaming,
            videoRecording = videoRecording,
            audioRecording = audioRecording,
            charging = charging,
            fullCharge = fullCharge,
        )

    private fun showStandby() {
        stopBlink()
        Ze69Hardware.setIndicatorRed(false)
        Ze69Hardware.setIndicatorGreen(true)
        Log.d(TAG, "standby: green steady")
    }

    private fun showCharging() {
        stopBlink()
        Ze69Hardware.setIndicatorRed(true)
        Ze69Hardware.setIndicatorGreen(false)
        Log.d(TAG, "charging: red steady")
    }

    private fun showFullCharge() {
        stopBlink()
        Ze69Hardware.setIndicatorRed(false)
        Ze69Hardware.setIndicatorGreen(true)
        Log.d(TAG, "full charge: green steady")
    }

    private fun showCommandCallRed() {
        stopBlink()
        Ze69Hardware.setIndicatorRed(true)
        Ze69Hardware.setIndicatorGreen(false)
        Log.d(TAG, "command call: red steady")
    }

    private fun showYellowSteady(reason: String) {
        stopBlink()
        Ze69Hardware.setIndicatorRed(true)
        Ze69Hardware.setIndicatorGreen(true)
        Log.d(TAG, "$reason: yellow steady")
    }

    private fun showStreamBlink() {
        if (!blinkScheduled) startBlink()
        Log.d(TAG, "stream: red+green blink")
    }

    private fun showVideoBlink() {
        if (!blinkScheduled) startBlink()
        Log.d(TAG, "video: red blink")
    }

    private fun showAudioBlink() {
        if (!blinkScheduled) startBlink()
        Log.d(TAG, "audio: yellow blink")
    }

    private fun startBlink() {
        if (!blinkScheduled && Ze69Hardware.ledNodesWritable) {
            blinkScheduled = true
            blinkOn = true
            applyBlinkFrame()
            handler.postDelayed(blinkRunnable, BLINK_MS)
        }
    }

    private fun stopBlink() {
        blinkScheduled = false
        handler.removeCallbacks(blinkRunnable)
    }

    private fun applyBlinkFrame() {
        when (resolvePattern()) {
            StatusLedPattern.STREAM_RG_BLINK -> {
                Ze69Hardware.setIndicatorRed(blinkOn)
                Ze69Hardware.setIndicatorGreen(!blinkOn)
            }
            StatusLedPattern.VIDEO_RED_BLINK -> {
                Ze69Hardware.setIndicatorRed(blinkOn)
                Ze69Hardware.setIndicatorGreen(false)
            }
            StatusLedPattern.AUDIO_YELLOW_BLINK -> {
                Ze69Hardware.setIndicatorRed(blinkOn)
                Ze69Hardware.setIndicatorGreen(blinkOn)
            }
            else -> {
                // 非闪烁态不应走到 blink runnable；安全熄灭闪烁调度
                stopBlink()
                refresh()
            }
        }
    }
}
