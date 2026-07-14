package com.aifieldcam.app.ui.album



import android.os.Bundle

import android.view.LayoutInflater

import android.view.View

import android.view.ViewGroup

import com.aifieldcam.app.data.SessionManager

import com.aifieldcam.app.databinding.FragmentAlbumBinding

import com.aifieldcam.app.platform.DeviceProfile

import com.aifieldcam.app.ui.VisibleTabFragment

import com.aifieldcam.app.util.MediaViewer

import androidx.recyclerview.widget.GridLayoutManager

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

        refreshUiLight()



        binding.btnCapture.setOnClickListener {
            session.triggerCapture()
        }

    }



    override fun onTabVisible() {

        refreshUi()

    }



    override fun onSessionChanged() {

        if (_binding == null || !isAdded) return

        refreshUiLight()

        refreshAlbumListAsync()

    }



    private fun refreshUi() {

        refreshUiLight()

        refreshAlbumListAsync()

    }



    private fun refreshUiLight() {

        if (_binding == null) return

        binding.tvStatus.text = session.getRecorderSummary()

        binding.btnCapture.isEnabled = DeviceProfile.isDsjZecn6a1 && !session.isRecording()

    }



    private fun refreshAlbumListAsync() {

        val gen = loadGeneration.incrementAndGet()

        loadExecutor.execute {

            val items = session.getAlbumMediaItems()

            binding.root.post {

                if (_binding == null || !isAdded || gen != loadGeneration.get()) return@post

                adapter.submitList(items)

                val empty = items.isEmpty()

                binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE

                binding.tvEmpty.text = "暂无照片或录像\n现场拍摄与循环录像将显示在此"

                binding.rvAlbum.visibility = if (empty) View.GONE else View.VISIBLE

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

