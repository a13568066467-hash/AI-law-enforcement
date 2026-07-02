package com.aifieldcam.app.ui.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.aifieldcam.app.R
import com.aifieldcam.app.data.DeviceCmd
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentVideoBinding
import com.aifieldcam.app.platform.DeviceProfile
import com.aifieldcam.app.ui.settings.MeFragment
import com.aifieldcam.app.util.MediaViewer

class VideoFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }
    private val adapter = VideoAdapter { item ->
        val file = item.file
        if (file != null) {
            MediaViewer.openMedia(requireContext(), file)
        } else {
            Toast.makeText(requireContext(), "录像文件不可用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentVideoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.header.tvTitle.text = getString(R.string.nav_video)
        binding.header.btnBack.setOnClickListener {
            val meParent = parentFragment as? MeFragment
            if (meParent != null) {
                meParent.onChildBack()
            } else {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
        binding.rvVideo.layoutManager = LinearLayoutManager(requireContext())
        binding.rvVideo.adapter = adapter
        refreshUi()

        binding.btnStart.setOnClickListener { runRecorderCmd { session.startRecord() } }
        binding.btnStop.setOnClickListener { runRecorderCmd { session.stopRecord() } }
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

    private fun runRecorderCmd(action: () -> Boolean) {
        if (!action()) {
            Toast.makeText(
                requireContext(),
                session.getLastActionError().ifBlank { "操作失败" },
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun refreshUi() {
        val recording = session.isRecording()
        val recorderBusy = session.isRecorderBusy()
        binding.tvStatus.text = session.getRecorderSummary()
        binding.tvFsm.text = "设备状态: ${DeviceCmd.fsmStateLabel(
            DeviceCmd.currentFsmState(recording),
        )}"
        binding.tvHint.text =
            "本机 ${DeviceProfile.MODEL_NAME}：Camera2 ${DeviceProfile.VIDEO_WIDTH}p H.264"
        binding.btnStart.isEnabled = DeviceProfile.isDsjZecn6a1 && !recorderBusy
        binding.btnStop.isEnabled = recorderBusy
        val items = session.getVideoItems()
        adapter.submitList(items)
        val empty = items.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvVideo.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
