package com.aifieldcam.app.platform

/** MediaMuxer 须在视频轨与伴随音轨都就绪后再 start。 */
object MuxerTrackGate {
    fun canStart(videoReady: Boolean, audioReady: Boolean): Boolean =
        videoReady && audioReady
}
