package com.aifieldcam.app.ui.album

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.ItemAlbumBinding

class AlbumAdapter(
    private val onClick: (SessionManager.AlbumMediaItem) -> Unit,
    private val onLongClick: (SessionManager.AlbumMediaItem) -> Unit,
) : RecyclerView.Adapter<AlbumAdapter.Holder>() {

    private val items = mutableListOf<SessionManager.AlbumMediaItem>()
    private val selectedPaths = linkedSetOf<String>()
    private var selectionMode = false

    fun applyDiff(data: List<SessionManager.AlbumMediaItem>, diff: DiffUtil.DiffResult) {
        val valid = data.map { it.file.absolutePath }.toSet()
        selectedPaths.retainAll(valid)
        items.clear()
        items.addAll(data)
        diff.dispatchUpdatesTo(this)
        if (selectionMode) {
            notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION)
        }
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
        notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION)
    }

    fun exitSelection() {
        if (!selectionMode && selectedPaths.isEmpty()) return
        selectionMode = false
        selectedPaths.clear()
        notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION)
    }

    fun toggleSelection(item: SessionManager.AlbumMediaItem) {
        val path = item.file.absolutePath
        if (path in selectedPaths) selectedPaths.remove(path) else selectedPaths.add(path)
        val index = items.indexOfFirst { it.file.absolutePath == path }
        if (index >= 0) notifyItemChanged(index, PAYLOAD_SELECTION)
        else notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION)
    }

    fun selectAll() {
        selectedPaths.clear()
        items.forEach { selectedPaths.add(it.file.absolutePath) }
        notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION)
    }

    fun clearSelectionKeepMode() {
        selectedPaths.clear()
        notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item, selectionMode, item.file.absolutePath in selectedPaths, full = true)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty() || payloads.any { it != PAYLOAD_SELECTION }) {
            onBindViewHolder(holder, position)
            return
        }
        val item = items[position]
        holder.bind(item, selectionMode, item.file.absolutePath in selectedPaths, full = false)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        private val binding: ItemAlbumBinding,
        private val onClick: (SessionManager.AlbumMediaItem) -> Unit,
        private val onLongClick: (SessionManager.AlbumMediaItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundPath: String? = null
        private var thumbRequest: AlbumThumbLoader.Request? = null

        fun bind(
            item: SessionManager.AlbumMediaItem,
            selectionMode: Boolean,
            selected: Boolean,
            full: Boolean,
        ) {
            val path = item.file.absolutePath
            val checkVisibility = if (selectionMode && selected) View.VISIBLE else View.GONE
            binding.viewSelectedScrim.visibility = checkVisibility
            binding.ivCheck.visibility = checkVisibility
            if (!full) return

            // 不在主线程做 file.exists()：列表构建时已过滤
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }

            if (boundPath == path && binding.ivThumb.drawable != null) {
                return
            }

            AlbumThumbLoader.cancel(thumbRequest)
            boundPath = path
            val cached = AlbumThumbLoader.peek(path)
            if (cached != null) {
                binding.ivThumb.setImageBitmap(cached)
                thumbRequest = null
                return
            }
            binding.ivThumb.setImageDrawable(null)
            thumbRequest = AlbumThumbLoader.loadAsync(path, item.isVideo) { token, readyPath, thumb ->
                binding.root.post {
                    if (boundPath != readyPath) return@post
                    if (thumbRequest?.token != token) return@post
                    if (thumb != null) binding.ivThumb.setImageBitmap(thumb)
                    else binding.ivThumb.setImageDrawable(null)
                }
            }
        }

        fun recycle() {
            AlbumThumbLoader.cancel(thumbRequest)
            thumbRequest = null
            boundPath = null
            binding.ivThumb.setImageDrawable(null)
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION = "selection"

        fun diff(
            old: List<SessionManager.AlbumMediaItem>,
            data: List<SessionManager.AlbumMediaItem>,
        ): DiffUtil.DiffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = data.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                old[oldItemPosition].file.absolutePath == data[newItemPosition].file.absolutePath

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val a = old[oldItemPosition]
                val b = data[newItemPosition]
                // 避免 file.length() 等主线程/后台 Diff 里的磁盘 I/O
                return a.isVideo == b.isVideo && a.createdAt == b.createdAt && a.id == b.id
            }
        })
    }
}
