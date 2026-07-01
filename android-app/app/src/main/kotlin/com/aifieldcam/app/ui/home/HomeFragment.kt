package com.aifieldcam.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.aifieldcam.app.MainActivity
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
    private var pendingPhotoFile: File? = null
    private var pendingVideoFile: File? = null
    private var videoStartedAt: Long = 0L

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = pendingPhotoFile
        pendingPhotoFile = null
        if (!success || file == null || !file.exists() || file.length() == 0L) {
            Toast.makeText(requireContext(), "拍照已取消", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        session.onPhonePhotoCaptured(file.readBytes(), file)
    }

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

        binding.btnStartRec.setOnClickListener {
            if (DeviceProfile.isDsjZecn6a1) {
                withCameraPermission { runRecorderCmd { session.startRecord() } }
            } else {
                withCameraPermission { startPhoneVideo() }
            }
        }
        binding.btnStopRec.setOnClickListener {
            if (DeviceProfile.isDsjZecn6a1) {
                runRecorderCmd { session.stopRecord() }
            } else {
                Toast.makeText(requireContext(), "当前未在录像", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnCapture.setOnClickListener {
            if (DeviceProfile.isDsjZecn6a1) {
                withCameraPermission(CameraPermissionHelper.capturePermissions()) {
                    runRecorderCmd { session.triggerCapture() }
                }
            } else {
                withCameraPermission(CameraPermissionHelper.capturePermissions()) {
                    startPhoneCapture()
                }
            }
        }
        binding.btnScenes.setOnClickListener {
            (activity as? MainActivity)?.openScenesTab()
        }
        binding.btnViewVideos.setOnClickListener {
            (activity as? MainActivity)?.openVideoList()
        }
        binding.btnSos.setOnClickListener {
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

    private fun startPhoneCapture() {
        val file = PhoneCameraHelper.newPhotoFile(requireContext())
        pendingPhotoFile = file
        takePictureLauncher.launch(PhoneCameraHelper.fileUri(requireContext(), file))
    }

    private fun startPhoneVideo() {
        videoStartedAt = System.currentTimeMillis()
        session.onPhoneVideoStarted()
        val file = PhoneCameraHelper.newVideoFile(requireContext())
        pendingVideoFile = file
        captureVideoLauncher.launch(PhoneCameraHelper.fileUri(requireContext(), file))
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
        val recording = session.isRecording()
        binding.tvStatus.text = session.getRecorderSummary()
        binding.tvLogin.text = session.getLoginSummary()
        binding.tvDevice.text = session.getDeviceSummary()
        binding.btnConnect.visibility = View.GONE
        binding.btnDisconnect.visibility = View.GONE

        val canUseCamera = DeviceProfile.isDsjZecn6a1 ||
            CameraPermissionHelper.hasCamera(requireContext())
        binding.btnCapture.isEnabled = canUseCamera && !recording
        binding.btnStartRec.isEnabled = canUseCamera && !recording && hasRecordPermissions()
        binding.btnStopRec.isEnabled = recording

        binding.btnCapture.text = if (DeviceProfile.isDsjZecn6a1) "拍照（本机）" else "拍照（开发机）"
        binding.btnStartRec.text = if (DeviceProfile.isDsjZecn6a1) {
            "开始录像（Camera2）"
        } else {
            "录像（开发机）"
        }
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
