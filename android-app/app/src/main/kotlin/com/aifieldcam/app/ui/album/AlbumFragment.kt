package com.aifieldcam.app.ui.album

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import com.aifieldcam.app.R
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentAlbumBinding
import com.aifieldcam.app.ui.VisibleTabFragment
import com.aifieldcam.app.ui.common.ThemisTopBar
import com.aifieldcam.app.util.MediaViewer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AlbumFragment : VisibleTabFragment() {

    private var _binding: FragmentAlbumBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }
    private val adapter = AlbumAdapter(
        onClick = { item -> handleItemClick(item) },
        onLongClick = { item -> handleItemLongClick(item) },
    )
    private val loadExecutor = Executors.newSingleThreadExecutor()
    private val loadGeneration = AtomicInteger(0)

    override fun sessionManager(): SessionManager = session

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAlbumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvAlbum.setHasFixedSize(true)
        binding.rvAlbum.setItemViewCacheSize(24)
        binding.rvAlbum.layoutManager = GridLayoutManager(requireContext(), SPAN_COUNT)
        binding.rvAlbum.adapter = adapter
        binding.btnSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnCancelSelection.setOnClickListener { exitSelectionMode() }
        binding.btnDeleteSelected.setOnClickListener { confirmBatchDelete() }
        refreshTopBar()
        updateSelectionBar()
    }

    override fun onTabVisible() {
        refreshTopBar()
        refreshAlbumListAsync()
    }

    override fun onSessionChanged() {
        if (_binding == null || !isAdded) return
        refreshTopBar()
        refreshAlbumListAsync()
    }

    private fun handleItemClick(item: SessionManager.AlbumMediaItem) {
        if (adapter.isSelectionMode()) {
            adapter.toggleSelection(item)
            updateSelectionBar()
            return
        }
        if (item.isVideo) {
            MediaViewer.openVideo(requireContext(), item.file)
        } else {
            // Keep this off the filesystem — exists() on every photo stalls first open.
            val paths = adapter.currentItems()
                .asSequence()
                .filter { !it.isVideo }
                .map { it.file.absolutePath }
                .toList()
            val index = paths.indexOf(item.file.absolutePath).coerceAtLeast(0)
            ImagePreviewDialogFragment.show(this, paths, index)
        }
    }

    private fun handleItemLongClick(item: SessionManager.AlbumMediaItem) {
        if (adapter.isSelectionMode()) {
            adapter.toggleSelection(item)
        } else {
            adapter.enterSelection(item)
        }
        updateSelectionBar()
    }

    private fun toggleSelectAll() {
        if (adapter.isAllSelected()) {
            adapter.clearSelectionKeepMode()
        } else {
            adapter.selectAll()
        }
        updateSelectionBar()
    }

    private fun exitSelectionMode() {
        adapter.exitSelection()
        updateSelectionBar()
    }

    private fun confirmBatchDelete() {
        if (!isAdded) return
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.album_delete_batch_title)
            .setMessage(getString(R.string.album_delete_batch_message, selected.size))
            .setPositiveButton(R.string.album_delete_confirm) { _, _ ->
                val snapshot = selected.toList()
                loadExecutor.execute {
                    session.deleteAlbumMediaBatch(snapshot)
                    _binding?.root?.post {
                        if (!isAdded || _binding == null) return@post
                        exitSelectionMode()
                    }
                }
            }
            .setNegativeButton(R.string.album_delete_cancel, null)
            .show()
    }

    private fun updateSelectionBar() {
        val currentBinding = _binding ?: return
        val selecting = adapter.isSelectionMode()
        currentBinding.selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        if (!selecting) return
        val count = adapter.selectedCount()
        currentBinding.btnDeleteSelected.text = getString(R.string.album_delete_selected, count)
        currentBinding.btnDeleteSelected.isEnabled = count > 0
        currentBinding.btnSelectAll.text = getString(
            if (adapter.isAllSelected()) R.string.album_deselect_all else R.string.album_select_all,
        )
    }

    private fun refreshTopBar() {
        val currentBinding = _binding ?: return
        if (!isAdded) return
        ThemisTopBar.bind(
            session,
            currentBinding.themisTopBar.statusDot,
            currentBinding.themisTopBar.tvStatus,
            currentBinding.themisTopBar.tvBattery,
            requireContext(),
        )
    }

    private fun refreshAlbumListAsync() {
        val taskBinding = _binding ?: return
        val root = taskBinding.root
        val gen = loadGeneration.incrementAndGet()
        val oldSnapshot = adapter.currentItems()
        loadExecutor.execute {
            val items = session.getAlbumMediaItems()
            // 首屏缩略图预热，进入后少空白格子
            AlbumThumbLoader.prefetch(
                items.take(PREFETCH_THUMBS).map { it.file.absolutePath to it.isVideo },
            )
            val diff = AlbumAdapter.diff(oldSnapshot, items)
            root.post {
                val currentBinding = _binding
                if (
                    currentBinding == null ||
                    currentBinding !== taskBinding ||
                    !isAdded ||
                    gen != loadGeneration.get()
                ) return@post
                adapter.applyDiff(items, diff)
                if (adapter.isSelectionMode() && items.isEmpty()) {
                    adapter.exitSelection()
                }
                updateSelectionBar()
                val empty = items.isEmpty()
                currentBinding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                currentBinding.rvAlbum.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        loadGeneration.incrementAndGet()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val SPAN_COUNT = 4
        private const val PREFETCH_THUMBS = 24
    }
}
