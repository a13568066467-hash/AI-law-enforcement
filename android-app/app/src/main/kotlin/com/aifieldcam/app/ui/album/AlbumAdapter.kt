package com.aifieldcam.app.ui.album

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.ItemAlbumBinding
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AlbumAdapter(
    private val onClick: (SessionManager.AlbumMediaItem) -> Unit,
    private val onLongClick: (SessionManager.AlbumMediaItem) -> Unit,
) : RecyclerView.Adapter<AlbumAdapter.Holder>() {

    private val items = mutableListOf<SessionManager.AlbumMediaItem>()
    private val selectedPaths = linkedSetOf<String>()
    private var selectionMode = false
    private val thumbExecutor = Executors.newFixedThreadPool(2)
    private val loadGeneration = AtomicInteger(0)

    fun submitList(data: List<SessionManager.AlbumMediaItem>) {
        loadGeneration.incrementAndGet()
        items.clear()
        items.addAll(data)
        val valid = data.map { it.file.absolutePath }.toSet()
        selectedPaths.retainAll(valid)
        notifyDataSetChanged()
    }

    fun currentItems(): List<SessionManager.AlbumMediaItem> = items.toList()

    fun isSelectionMode(): Boolean = selectionMode

    fun selectedCount(): Int = selectedPaths.size

    fun selectedItems(): List<SessionManager.AlbumMediaItem> =
        items.filter { it.file.absolutePath in selectedPaths }

    fun isAllSelected(): Boolean = items.isNotEmpty() && selectedPaths.size == items.size

    fun enterSelection(item: SessionManager.AlbumMediaItem) {
        selectionMode = true
        selectedPaths.clear()
        selectedPaths.add(item.file.absolutePath)
        notifyDataSetChanged()
    }

    fun exitSelection() {
        if (!selectionMode && selectedPaths.isEmpty()) return
        selectionMode = false
        selectedPaths.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(item: SessionManager.AlbumMediaItem) {
        val path = item.file.absolutePath
        if (path in selectedPaths) selectedPaths.remove(path) else selectedPaths.add(path)
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedPaths.clear()
        items.forEach { selectedPaths.add(it.file.absolutePath) }
        notifyDataSetChanged()
    }

    fun clearSelectionKeepMode() {
        selectedPaths.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding, onClick, onLongClick, thumbExecutor, loadGeneration)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item, selectionMode, item.file.absolutePath in selectedPaths)
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        private val binding: ItemAlbumBinding,
        private val onClick: (SessionManager.AlbumMediaItem) -> Unit,
        private val onLongClick: (SessionManager.AlbumMediaItem) -> Unit,
        private val thumbExecutor: Executor,
        private val loadGeneration: AtomicInteger,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundPath: String? = null
        private var bindGeneration = 0

        fun bind(
            item: SessionManager.AlbumMediaItem,
            selectionMode: Boolean,
            selected: Boolean,
        ) {
            boundPath = item.file.absolutePath
            bindGeneration = loadGeneration.get()
            binding.ivThumb.setImageDrawable(null)
            val checkVisibility = if (selectionMode && selected) View.VISIBLE else View.GONE
            binding.viewSelectedScrim.visibility = checkVisibility
            binding.ivCheck.visibility = checkVisibility
            binding.root.setOnClickListener {
                if (item.file.exists()) onClick(item)
            }
            binding.root.setOnLongClickListener {
                if (item.file.exists()) {
                    onLongClick(item)
                    true
                } else {
                    false
                }
            }
            val path = item.file.absolutePath
            val gen = bindGeneration
            thumbExecutor.execute {
                val thumb = if (item.isVideo) {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(
                        path,
                        MediaStore.Images.Thumbnails.MINI_KIND,
                    )
                } else {
                    decodePhotoThumb(path)
                }
                binding.root.post {
                    if (boundPath != path || bindGeneration != gen) return@post
                    if (thumb != null) binding.ivThumb.setImageBitmap(thumb) else binding.ivThumb.setImageDrawable(null)
                }
            }
        }
    }

    companion object {
        private fun decodePhotoThumb(path: String): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 256, 256)
            }
            return BitmapFactory.decodeFile(path, opts)
        }

        private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
            var size = 1
            var halfW = w / 2
            var halfH = h / 2
            while (halfW / size >= reqW && halfH / size >= reqH) {
                size *= 2
            }
            return size.coerceAtLeast(1)
        }
    }
}
