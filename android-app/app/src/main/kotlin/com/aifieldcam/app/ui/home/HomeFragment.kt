package com.aifieldcam.app.ui.home

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.aifieldcam.app.MainActivity
import com.aifieldcam.app.ble.BleConfig
import com.aifieldcam.app.platform.DeviceProfile
import com.aifieldcam.app.ble.BleConnState
import com.aifieldcam.app.ble.BleManager
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentHomeBinding
import com.aifieldcam.app.util.CameraPermissionHelper
import com.aifieldcam.app.util.PhoneCameraHelper
import java.io.File

class HomeFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }
    private val ble by lazy { BleManager.getInstance(requireContext()) }

    private var pendingPhotoFile: File? = null
    private var pendingVideoFile: File? = null
    private var videoStartedAt: Long = 0L
    private var pendingCameraAction: (() -> Unit)? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            pendingCameraAction?.invoke()
        } else {
            Toast.makeText(requireContext(), "需要相机权限才能使用手机拍照录像", Toast.LENGTH_LONG).show()
        }
        pendingCameraAction = null
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = pendingPhotoFile
        pendingPhotoFile = null
        if (!success || file == null || !file.exists() || file.length() == 0L) {
            Toast.makeText(requireContext(), "拍照已取消", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val jpeg = file.readBytes()
        session.onPhonePhotoCaptured(jpeg, file)
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

        binding.btnConnect.setOnClickListener {
            val activity = activity as? MainActivity
            if (activity != null && !activity.hasBlePermissions()) {
                Toast.makeText(requireContext(), "请先授予蓝牙权限", Toast.LENGTH_SHORT).show()
                activity.requestBlePermissionsAgain()
                return@setOnClickListener
            }
            if (!ble.canStartConnect()) return@setOnClickListener
            session.connectCamera { ok, msg ->
                if (!isAdded || _binding == null) return@connectCamera
                if (!ok) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                refreshUi()
            }
            refreshUi()
        }
        binding.btnDisconnect.setOnClickListener {
            session.disconnectCamera()
            Toast.makeText(requireContext(), "已断开", Toast.LENGTH_SHORT).show()
            refreshUi()
        }
        binding.btnStartRec.setOnClickListener {
            if (isBleConnected()) {
                runBleCmd { session.startRecord() }
            } else {
                startPhoneVideo()
            }
        }
        binding.btnStopRec.setOnClickListener { runBleCmd { session.stopRecord() } }
        binding.btnCapture.setOnClickListener {
            if (isBleConnected()) {
                runBleCmd { session.triggerCapture() }
            } else {
                startPhoneCapture()
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

    private fun isBleConnected(): Boolean = ble.connState == BleConnState.CONNECTED

    private fun startPhoneCapture() {
        withCameraPermission(CameraPermissionHelper.capturePermissions()) {
            val file = PhoneCameraHelper.newPhotoFile(requireContext())
            pendingPhotoFile = file
            val uri: Uri = PhoneCameraHelper.fileUri(requireContext(), file)
            takePictureLauncher.launch(uri)
        }
    }

    private fun startPhoneVideo() {
        withCameraPermission(CameraPermissionHelper.videoPermissions()) {
            videoStartedAt = System.currentTimeMillis()
            session.onPhoneVideoStarted()
            val file = PhoneCameraHelper.newVideoFile(requireContext())
            pendingVideoFile = file
            val uri: Uri = PhoneCameraHelper.fileUri(requireContext(), file)
            captureVideoLauncher.launch(uri)
        }
    }

    private fun withCameraPermission(permissions: Array<String>, action: () -> Unit) {
        val missing = CameraPermissionHelper.missing(requireContext(), permissions)
        if (missing.isEmpty()) {
            action()
        } else {
            pendingCameraAction = action
            cameraPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun runBleCmd(action: () -> Boolean) {
        if (!action()) {
            Toast.makeText(
                requireContext(),
                session.getLastActionError().ifBlank { "请先连接相机" },
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun refreshUi() {
        if (_binding == null) return
        val connected = isBleConnected()
        val recording = ble.deviceState == BleConfig.FSM_RECORD
        binding.tvStatus.text = session.getBleSummary()
        binding.tvLogin.text = session.getLoginSummary()
        binding.tvPhoneHint.text = when {
            connected -> "已连接 BLE 相机，拍照/录像由外接相机执行"
            DeviceProfile.isDsjZecn6a1 -> buildString {
                append("本机 ${DeviceProfile.MODEL_NAME}：")
                append("${DeviceProfile.VIDEO_WIDTH}p 录像 · 夜视≥${DeviceProfile.NIGHT_VISION_METERS}m · ")
                append("也可连接 BLE 外接相机")
            }
            else -> "未连接 BLE 时，可使用手机相机拍照/录像并保存到相册"
        }
        binding.tvDevice.text = session.getDeviceSummary()
        binding.btnConnect.isEnabled = ble.canStartConnect()
        binding.btnDisconnect.isEnabled = connected
        binding.btnCapture.isEnabled = (connected && !recording) ||
            (!connected && CameraPermissionHelper.hasCamera(requireContext()))
        binding.btnStartRec.isEnabled = (connected && !recording) ||
            (!connected && CameraPermissionHelper.hasCamera(requireContext()))
        binding.btnStopRec.isEnabled = connected && recording
        binding.btnCapture.text = if (connected) "拍照（BLE）" else "拍照（手机）"
        binding.btnStartRec.text = if (connected) "开始录像（BLE）" else "录像（手机）"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
