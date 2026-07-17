package com.aifieldcam.app.ui.album

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
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
    private val adapter = AlbumAdapter { item ->
        if (item.isVideo) {
            MediaViewer.openVideo(requireContext(), item.file)
        } else {
            ImagePreviewDialogFragment.show(this, item.file, item.explanation)
        }
    }
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
        binding.rvAlbum.layoutManager = GridLayoutManager(requireContext(), SPAN_COUNT)
        binding.rvAlbum.adapter = adapter
        refreshTopBar()
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
        loadExecutor.execute {
            val items = session.getAlbumMediaItems()
            root.post {
                val currentBinding = _binding
                if (
                    currentBinding == null ||
                    currentBinding !== taskBinding ||
                    !isAdded ||
                    gen != loadGeneration.get()
                ) return@post
                adapter.submitList(items)
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
    }
}
