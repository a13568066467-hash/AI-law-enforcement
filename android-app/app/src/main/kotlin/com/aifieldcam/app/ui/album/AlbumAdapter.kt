package com.aifieldcam.app.ui.album

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.ItemAlbumBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlbumAdapter : RecyclerView.Adapter<AlbumAdapter.Holder>() {

    private val items = mutableListOf<SessionManager.AlbumItem>()

    fun submitList(data: List<SessionManager.AlbumItem>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Holder(private val binding: ItemAlbumBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SessionManager.AlbumItem) {
            val bytes = item.file.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                binding.ivThumb.setImageBitmap(bitmap)
            }
            binding.tvMeta.text = "${formatSize(item.size)} · ${formatTime(item.createdAt)}"
            binding.tvExplain.text = item.explanation.ifEmpty { "识图中…" }
        }

        private fun formatSize(n: Int): String {
            return if (n < 1024) "$n B" else String.format(Locale.getDefault(), "%.1f KB", n / 1024.0)
        }

        private fun formatTime(ts: Long): String {
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        }
    }
}
