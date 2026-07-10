package com.aifieldcam.app.ui.home

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.aifieldcam.app.MainActivity
import com.aifieldcam.app.data.BackendDiscovery
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentHomeBinding
import com.aifieldcam.app.platform.DeviceProfile
import com.aifieldcam.app.ui.scenes.SceneDemoDialogFragment
import com.aifieldcam.app.util.CameraPermissionHelper
import com.aifieldcam.app.util.PhoneCameraHelper
import java.io.File

class HomeFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }

    private var pendingCameraAction: (() -> Unit)? = null
    private var pendingVideoFile: File? = null
    private var videoStartedAt: Long = 0L
    private var backendOnline: Boolean? = null

    private val captureVideoLauncher = registerForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { success ->
        val file = pendingVideoFile
        val startedAt = videoStartedAt
        pendingVideoFile = null
        videoStartedAt = 0L
        if (!success || file == null || !file.exists() || file.length() == 0L) {
            Toast.makeText(requireContext(), "录像已取消", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        session.onPhoneVideoCaptured(file, startedAt)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            pendingCameraAction?.invoke()
        } else {
            Toast.makeText(requireContext(), "需要相机与麦克风权限", Toast.LENGTH_LONG).show()
        }
        pendingCameraAction = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshUi()

        binding.btnVoice.setOnClickListener {
            (activity as? MainActivity)?.openChatTab()
        }
        binding.btnVoice.setOnLongClickListener {
            (activity as? MainActivity)?.openChatTab()
            true
        }
        binding.cardChat.setOnClickListener {
            (activity as? MainActivity)?.openChatTab()
        }
        binding.cardRecord.setOnClickListener {
            toggleRecord()
        }
        binding.cardTranscribe.setOnClickListener {
            Toast.makeText(requireContext(), "语音转写暂未接入", Toast.LENGTH_SHORT).show()
        }
        binding.cardReport.setOnClickListener {
            if (!session.isLoggedIn()) {
                Toast.makeText(requireContext(), "请先完成巡查员认证", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            session.runDemoScenario("sos_emergency") { result, err ->
                if (_binding == null || !isAdded) return@runDemoScenario
                if (result == null) {
                    Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                    return@runDemoScenario
                }
                SceneDemoDialogFragment.newInstance(result)
                    .show(parentFragmentManager, "sos_demo")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        session.addStatusListener(this)
        checkBackend()
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

    private fun startPhoneVideo() {
        videoStartedAt = System.currentTimeMillis()
        session.onPhoneVideoStarted()
        val file = PhoneCameraHelper.newVideoFile(requireContext())
        pendingVideoFile = file
        captureVideoLauncher.launch(PhoneCameraHelper.fileUri(requireContext(), file))
    }

    private fun toggleRecord() {
        if (DeviceProfile.isDsjZecn6a1 && session.isRecorderBusy()) {
            runRecorderCmd { session.stopRecord() }
            return
        }
        if (DeviceProfile.isDsjZecn6a1) {
            withCameraPermission { runRecorderCmd { session.startRecord() } }
        } else {
            withCameraPermission { startPhoneVideo() }
        }
    }

    private fun withCameraPermission(
        permissions: Array<String> = CameraPermissionHelper.requiredPermissions(),
        action: () -> Unit,
    ) {
        val missing = CameraPermissionHelper.missing(requireContext(), permissions)
        if (missing.isEmpty()) {
            action()
        } else {
            pendingCameraAction = action
            cameraPermissionLauncher.launch(missing.toTypedArray())
        }
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
        if (_binding == null) return
        val recorderBusy = session.isRecorderBusy()
        binding.tvConnection.text = when (backendOnline) {
            true -> "已连接"
            false -> "未连接"
            null -> "连接中"
        }
        binding.tvBattery.text = batteryPercentText()

        val canUseCamera = DeviceProfile.isDsjZecn6a1 ||
            CameraPermissionHelper.hasCamera(requireContext())
        val canToggleRecord = canUseCamera && (recorderBusy || hasRecordPermissions())
        binding.cardRecord.isEnabled = canToggleRecord
        binding.cardRecord.alpha = if (canToggleRecord) 1f else 0.45f
        binding.tvRecordTitle.text = when {
            session.isVideoSaving() -> "保存中"
            recorderBusy -> "停止记录"
            else -> "执法记录"
        }
        binding.tvRecordSubtitle.text = when {
            session.isVideoStreaming() -> "视频连线中"
            session.isVideoSaving() -> "正在保存录像"
            recorderBusy -> "正在录像"
            else -> "录音录像"
        }
    }

    private fun checkBackend() {
        BackendDiscovery.ensureReachable { ok, _ ->
            if (_binding == null || !isAdded) return@ensureReachable
            backendOnline = ok
            refreshUi()
        }
    }

    private fun batteryPercentText(): String {
        val intent = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return "--"
        return "${level * 100 / scale}%"
    }

    private fun hasRecordPermissions(): Boolean =
        CameraPermissionHelper.missing(
            requireContext(),
            CameraPermissionHelper.requiredPermissions(),
        ).isEmpty()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
