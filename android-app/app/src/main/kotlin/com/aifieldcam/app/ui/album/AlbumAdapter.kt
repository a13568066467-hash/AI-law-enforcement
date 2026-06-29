package com.aifieldcam.app.ui.album

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.ItemAlbumBinding

class AlbumAdapter(
    private val onPhotoClick: (SessionManager.AlbumItem) -> Unit,
) : RecyclerView.Adapter<AlbumAdapter.Holder>() {

    private val items = mutableListOf<SessionManager.AlbumItem>()

    fun submitList(data: List<SessionManager.AlbumItem>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding, onPhotoClick)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        private val binding: ItemAlbumBinding,
        private val onPhotoClick: (SessionManager.AlbumItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SessionManager.AlbumItem) {
            val bytes = runCatching { item.file.readBytes() }.getOrNull()
            if (bytes != null) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    binding.ivThumb.setImageBitmap(bitmap)
                }
            } else {
                binding.ivThumb.setImageDrawable(null)
            }
            binding.root.setOnClickListener {
                if (item.file.exists()) onPhotoClick(item)
            }
        }
    }
}
