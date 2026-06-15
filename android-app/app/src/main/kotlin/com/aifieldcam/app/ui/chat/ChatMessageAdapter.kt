package com.aifieldcam.app.ui.chat

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aifieldcam.app.R

class ChatMessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ChatMessage>()

    fun submitList(data: List<ChatMessage>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    fun append(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.lastIndex)
    }

    fun itemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ChatMessage.Text -> VIEW_TEXT
        is ChatMessage.Photo -> VIEW_PHOTO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_PHOTO -> PhotoHolder(
                inflater.inflate(R.layout.item_chat_photo, parent, false),
            )
            else -> TextHolder(
                inflater.inflate(R.layout.item_chat_text, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ChatMessage.Text -> (holder as TextHolder).bind(item)
            is ChatMessage.Photo -> (holder as PhotoHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private class TextHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView = itemView as TextView

        fun bind(item: ChatMessage.Text) {
            textView.text = item.line
        }
    }

    private class PhotoHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val captionView: TextView = itemView.findViewById(R.id.tv_caption)
        private val imageView: ImageView = itemView.findViewById(R.id.iv_photo)

        fun bind(item: ChatMessage.Photo) {
            captionView.text = item.caption
            val bitmap = BitmapFactory.decodeByteArray(item.imageBytes, 0, item.imageBytes.size)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.setImageDrawable(null)
            }
        }
    }

    companion object {
        private const val VIEW_TEXT = 0
        private const val VIEW_PHOTO = 1
    }
}
