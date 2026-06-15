package com.aifieldcam.app.ui.chat

sealed class ChatMessage {
    data class Text(
        val line: String,
    ) : ChatMessage()

    data class Photo(
        val caption: String,
        val imageBytes: ByteArray,
    ) : ChatMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Photo) return false
            return caption == other.caption && imageBytes.contentEquals(other.imageBytes)
        }

        override fun hashCode(): Int {
            var result = caption.hashCode()
            result = 31 * result + imageBytes.contentHashCode()
            return result
        }
    }
}
