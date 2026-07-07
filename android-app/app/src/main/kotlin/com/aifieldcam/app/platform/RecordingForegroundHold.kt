package com.aifieldcam.app.platform

/**
 * 前台服务持有计数：录像与息屏拍照各 acquire/release 一次，归零方可停止服务。
 */
object RecordingForegroundHold {

  private var holdCount = 0

  fun acquire(): Int = synchronized(this) { ++holdCount }

  fun release(): Int = synchronized(this) {
    holdCount = (holdCount - 1).coerceAtLeast(0)
    holdCount
  }

  fun reset() = synchronized(this) { holdCount = 0 }

  fun count(): Int = synchronized(this) { holdCount }

  fun shouldStopAfterRelease(): Boolean = synchronized(this) { holdCount == 0 }
}
