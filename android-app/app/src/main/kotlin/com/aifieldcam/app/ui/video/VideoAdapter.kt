package com.aifieldcam.app.ui.video

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.ItemVideoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoAdapter(
    private val onVideoClick: (SessionManager.VideoItem) -> Unit,
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    private val items = mutableListOf<SessionManager.VideoItem>()

    fun submitList(data: List<SessionManager.VideoItem>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding, onVideoClick)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        private val binding: ItemVideoBinding,
        private val onVideoClick: (SessionManager.VideoItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SessionManager.VideoItem) {
            val hasFile = item.file?.exists() == true
            val title = if (hasFile) "手机录像 ${item.file!!.name}" else "会话 ${item.id}"
            binding.tvTitle.text = title
            val actionHint = if (hasFile) " · 点击播放" else " · 文件待传输"
            binding.tvMeta.text = "${formatTime(item.startedAt)} · ${item.note}$actionHint"
            binding.root.isClickable = hasFile
            binding.root.setOnClickListener {
                if (hasFile) onVideoClick(item)
            }
        }

        private fun formatTime(ts: Long): String {
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        }
    }
}
