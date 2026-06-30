package com.aifieldcam.app.ui.album

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.aifieldcam.app.ble.BleManager
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentAlbumBinding

class AlbumFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentAlbumBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }
    private val ble by lazy { BleManager.getInstance(requireContext()) }
    private val adapter = AlbumAdapter { item ->
        ImagePreviewDialogFragment.show(this, item.file, item.explanation)
    }

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
        refreshUi()

        binding.btnCapture.setOnClickListener {
            if (!session.triggerCapture()) {
                Toast.makeText(
                    requireContext(),
                    session.getLastActionError().ifBlank { "请先连接执法仪" },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        session.addStatusListener(this)
        refreshUi()
    }

    override fun onStop() {
        session.removeStatusListener(this)
        super.onStop()
    }

    override fun onSessionChanged() {
        if (_binding == null || !isAdded) return
        refreshUi()
    }

    private fun refreshUi() {
        binding.tvStatus.text = session.getBleSummary()
        val items = session.getAlbumItems()
        adapter.submitList(items)
        val empty = items.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvAlbum.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val SPAN_COUNT = 4
    }
}
