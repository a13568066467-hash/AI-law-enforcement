package com.aifieldcam.app.ui.chat

enum class AssistantWaveState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ;

    companion object {
        fun resolve(ttsSpeaking: Boolean, aiListening: Boolean, aiProcessing: Boolean) = when {
            ttsSpeaking -> SPEAKING
            aiListening -> LISTENING
            aiProcessing -> PROCESSING
            else -> IDLE
        }
    }

    fun onPress() = LISTENING

    fun onRelease() = if (this == LISTENING) PROCESSING else this

    fun onSpeechChanged(speaking: Boolean) =
        if (speaking) SPEAKING else if (this == SPEAKING) IDLE else this

    fun onRequestFinished(hasSpokenReply: Boolean) =
        if (hasSpokenReply) this else IDLE

    fun onAbort() = IDLE
}
