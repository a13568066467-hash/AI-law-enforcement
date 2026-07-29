package com.aifieldcam.app.ui.album

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.aifieldcam.app.databinding.DialogImagePreviewBinding
import java.io.File

class ImagePreviewDialogFragment : DialogFragment() {

    private var _binding: DialogImagePreviewBinding? = null
    private val binding get() = _binding!!
    private var pageAdapter: PageAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogImagePreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val paths = ImagePreviewSession.paths
        if (paths.isEmpty()) {
            dismiss()
            return
        }
        val start = ImagePreviewSession.startIndex.coerceIn(0, paths.lastIndex)

        binding.btnClose.setOnClickListener { dismiss() }

        val metrics = resources.displayMetrics
        val reqW = metrics.widthPixels.coerceAtLeast(1)
        val reqH = metrics.heightPixels.coerceAtLeast(1)
        val adapter = PageAdapter(paths, reqW, reqH)
        pageAdapter = adapter
        binding.pager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        binding.pager.offscreenPageLimit = 1
        binding.pager.adapter = adapter
        binding.pager.setCurrentItem(start, false)

        // 相邻页预热，滑动时更顺
        listOf(start - 1, start + 1).forEach { i ->
            paths.getOrNull(i)?.let { AlbumPreviewLoader.prefetch(it, reqW, reqH) }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
    }

    override fun onDestroyView() {
        pageAdapter?.cancelAll()
        pageAdapter = null
        ImagePreviewSession.clear()
        super.onDestroyView()
        _binding = null
    }

    private class PageAdapter(
        private val paths: List<String>,
        private val reqW: Int,
        private val reqH: Int,
    ) : RecyclerView.Adapter<PageAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = LayoutInflater.from(parent.context)
                .inflate(com.aifieldcam.app.R.layout.item_image_preview_page, parent, false) as ImageView
            return Holder(image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(paths[position], reqW, reqH)
            // 预取邻居
            paths.getOrNull(position - 1)?.let { AlbumPreviewLoader.prefetch(it, reqW, reqH) }
            paths.getOrNull(position + 1)?.let { AlbumPreviewLoader.prefetch(it, reqW, reqH) }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.recycle()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = paths.size

        fun cancelAll() {
            // holders cancel their own requests on recycle / destroy
        }

        class Holder(private val imageView: ImageView) : RecyclerView.ViewHolder(imageView) {
            private var previewRequest: AlbumPreviewLoader.Request? = null
            private var boundPath: String? = null

            fun bind(path: String, reqW: Int, reqH: Int) {
                AlbumPreviewLoader.cancel(previewRequest)
                boundPath = path

                // 立刻用缓存/缩略图占位，避免点开先黑屏等待
                val ready = AlbumPreviewLoader.peek(path) ?: AlbumThumbLoader.peek(path)
                if (ready != null) {
                    imageView.setImageBitmap(ready)
                } else {
                    imageView.setImageDrawable(null)
                }

                if (AlbumPreviewLoader.peek(path) != null) {
                    previewRequest = null
                    return
                }

                previewRequest = AlbumPreviewLoader.loadAsync(path, reqW, reqH) { token, readyPath, bitmap ->
                    imageView.post {
                        if (boundPath != readyPath) return@post
                        if (previewRequest?.token != token) return@post
                        if (bitmap != null) imageView.setImageBitmap(bitmap)
                    }
                }
            }

            fun recycle() {
                AlbumPreviewLoader.cancel(previewRequest)
                previewRequest = null
                boundPath = null
                imageView.setImageDrawable(null)
            }
        }
    }

    companion object {
        fun show(host: androidx.fragment.app.Fragment, photoPaths: List<String>, startIndex: Int) {
            if (photoPaths.isEmpty()) return
            ImagePreviewSession.prepare(photoPaths, startIndex)
            ImagePreviewDialogFragment().show(host.parentFragmentManager, "image_preview")
        }

        fun show(host: androidx.fragment.app.Fragment, file: File) {
            if (!file.exists()) return
            show(host, listOf(file.absolutePath), 0)
        }
    }
}
