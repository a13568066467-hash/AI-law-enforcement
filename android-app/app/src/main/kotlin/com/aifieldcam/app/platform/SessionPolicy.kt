package com.aifieldcam.app.platform

import android.content.Context

/**
 * 交互设计 §5：录像 ↔ AI 互斥、低电策略。
 */
object SessionPolicy {

    fun isStopRecordingText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return listOf("停止录像", "结束录像", "停录", "停止录制").any { t.contains(it) }
    }

    fun recordingBlocksChat(text: String): Boolean = !isStopRecordingText(text)

    @Suppress("UNUSED_PARAMETER")
    fun showLowBatteryToastIfNeeded(context: Context) = Unit
}
