package com.aifieldcam.app.ui.album

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.aifieldcam.app.R
import com.aifieldcam.app.databinding.DialogImagePreviewBinding
import java.io.File

class ImagePreviewDialogFragment : DialogFragment() {

    private var _binding: DialogImagePreviewBinding? = null
    private val binding get() = _binding!!

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
        val path = requireArguments().getString(ARG_PATH).orEmpty()
        val explain = requireArguments().getString(ARG_EXPLAIN).orEmpty()
        val file = File(path)
        if (!file.exists()) {
            dismiss()
            return
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
            binding.ivFull.setImageBitmap(bitmap)
        }

        binding.tvExplain.text = when {
            explain.isNotBlank() -> explain
            else -> getString(R.string.preview_no_description)
        }

        binding.btnClose.setOnClickListener { dismiss() }
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
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_PATH = "path"
        private const val ARG_EXPLAIN = "explain"

        fun show(
            host: androidx.fragment.app.Fragment,
            file: File,
            explanation: String = "",
        ) {
            if (!file.exists()) return
            ImagePreviewDialogFragment().apply {
                arguments = bundleOf(
                    ARG_PATH to file.absolutePath,
                    ARG_EXPLAIN to explanation,
                )
            }.show(host.parentFragmentManager, "image_preview")
        }
    }
}
