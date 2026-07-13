package com.aifieldcam.app.platform

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 状态指示灯（厂商说明书 §三）：
 * - 待机：绿灯常亮
 * - 录音：黄灯闪烁
 * - 录像：红灯闪烁
 * - 拍照：红灯闪一次
 * - 充电：红灯常亮
 * - 充满：绿灯常亮
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

    fun refresh() {
        if (!Ze69Hardware.isZe69Platform) return
        if (!Ze69Hardware.ledNodesWritable) {
            stopBlink()
            return
        }
        when {
            videoStreaming -> showStreamBlink()
            videoRecording -> showVideoBlink()
            audioRecording -> showAudioBlink()
            charging && fullCharge -> showFullCharge()
            charging -> showCharging()
            else -> showStandby()
        }
    }

    fun resetAll() {
        charging = false
        fullCharge = false
        videoRecording = false
        videoStreaming = false
        audioRecording = false
        stopBlink()
        if (Ze69Hardware.ledNodesWritable) {
            Ze69Hardware.setIndicatorRed(false)
            Ze69Hardware.setIndicatorGreen(false)
        }
        refresh()
    }

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
        when {
            videoStreaming -> {
                Ze69Hardware.setIndicatorRed(blinkOn)
                Ze69Hardware.setIndicatorGreen(!blinkOn)
            }
            videoRecording -> {
                Ze69Hardware.setIndicatorRed(blinkOn)
                Ze69Hardware.setIndicatorGreen(false)
            }
            audioRecording -> {
                Ze69Hardware.setIndicatorRed(blinkOn)
                Ze69Hardware.setIndicatorGreen(blinkOn)
            }
        }
    }
}
