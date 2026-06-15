package com.aifieldcam.app.ui.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.aifieldcam.app.ble.BleConfig
import com.aifieldcam.app.ble.BleConnState
import com.aifieldcam.app.ble.BleManager
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentVideoBinding

class VideoFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }
    private val ble by lazy { BleManager.getInstance(requireContext()) }
    private val adapter = VideoAdapter()

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
        binding.rvVideo.layoutManager = LinearLayoutManager(requireContext())
        binding.rvVideo.adapter = adapter
        refreshUi()

        binding.btnStart.setOnClickListener { runBleCmd { session.startRecord() } }
        binding.btnStop.setOnClickListener { runBleCmd { session.stopRecord() } }
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

    private fun runBleCmd(action: () -> Boolean) {
        if (!action()) {
            Toast.makeText(requireContext(), ble.lastError.ifBlank { "请先连接相机" }, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshUi() {
        binding.tvStatus.text = session.getBleSummary()
        binding.tvFsm.text = "设备状态: ${BleConfig.fsmStateLabel(ble.deviceState)}"
        val connected = ble.connState == BleConnState.CONNECTED
        binding.btnStart.isEnabled = connected
        binding.btnStop.isEnabled = connected
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
