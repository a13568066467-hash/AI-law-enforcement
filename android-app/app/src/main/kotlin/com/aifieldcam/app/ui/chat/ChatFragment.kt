package com.aifieldcam.app.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.aifieldcam.app.data.BackendDiscovery
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentChatBinding
import com.aifieldcam.app.demo.DemoScenarios
import com.aifieldcam.app.platform.PttSnapAskController
import com.aifieldcam.app.ui.VisibleTabFragment
import com.aifieldcam.app.ui.common.ThemisTopBar
import com.aifieldcam.app.ui.scenes.SceneDemoDialogFragment
import com.aifieldcam.app.util.ImageUtils
import com.aifieldcam.app.util.VideoFrameExtractor
import com.aifieldcam.app.util.PhotoPermissionHelper
import com.aifieldcam.app.util.TtsSpeaker

class ChatFragment : VisibleTabFragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }

    override fun sessionManager(): SessionManager = session
    private var analyzingPhoto = false
    private var analyzingVideo = false
    private val ttsListener = TtsSpeaker.Listener {
        syncWaveState()
    }

    private val messages = mutableListOf<ChatMessage>()
    private val messageAdapter = ChatMessageAdapter()

    private val photoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            launchPhotoPicker()
        }
    }

    private val pickVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) uploadPhoto(uri)
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // 部分相册 URI 不支持持久授权，临时读取权限仍可用
        }
        uploadPhoto(uri)
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) uploadVideo(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (messages.isEmpty()) {
            messages.add(
                ChatMessage.Text(
                    "赢筑AI助手\n" +
                        "按住 PTT 或输入口语指令，例如：\n" +
                        "· 开启班前安全演讲录制\n" +
                        "· 本机点位设备状态检测\n" +
                        "· 呼叫技术专家",
                ),
            )
        }

        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = messageAdapter
        messageAdapter.submitList(messages.toList())

        refreshStatus()

        binding.btnUploadPhoto.setOnClickListener { startPhotoUpload() }
        binding.btnUploadVideo.setOnClickListener { startVideoUpload() }
        binding.btnSend.setOnClickListener { sendMessage() }
        setupPttButton()
        syncWaveState()
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
    }

    override fun onTabVisible() {
        TtsSpeaker.addListener(ttsListener)
        syncWaveState()
        checkBackend()
        refreshStatus()
    }

    override fun onTabHidden() {
        TtsSpeaker.removeListener(ttsListener)
        _binding?.listeningWave?.stop()
    }

    override fun onSessionChanged() {
        if (_binding == null || !isAdded) return
        refreshStatus()
        syncWaveState()
    }

    private fun startPhotoUpload() {
        if (analyzingPhoto) return
        if (!session.isDeviceBound()) return
        val missing = PhotoPermissionHelper.missing(requireContext())
        if (missing.isNotEmpty()) {
            photoPermissionLauncher.launch(missing.toTypedArray())
        } else {
            launchPhotoPicker()
        }
    }

    private fun launchPhotoPicker() {
        val pickMedia = ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(requireContext())
        if (pickMedia) {
            pickVisualMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        } else {
            openDocumentLauncher.launch(arrayOf("image/*"))
        }
    }

    private fun uploadPhoto(uri: Uri) {
        val result = ImageUtils.readJpegBytes(requireContext(), uri)
        val jpeg = result.bytes
        if (jpeg == null) {
            val msg = result.error ?: "无法读取照片"
            appendTextMessage("系统: $msg")
            return
        }

        analyzingPhoto = true
        binding.btnUploadPhoto.isEnabled = false
        appendPhotoMessage("AI 识别中…", jpeg)

        session.analyzeUploadedImage(jpeg) { explanation, err ->
            if (_binding == null || !isAdded) return@analyzeUploadedImage
            analyzingPhoto = false
            binding.btnUploadPhoto.isEnabled = true
            when {
                err.isNotEmpty() -> appendTextMessage("系统: $err")
                explanation.isNotEmpty() -> appendTextMessage("AI 识图:\n$explanation")
                else -> appendTextMessage("系统: 识图无结果，请换一张照片重试")
            }
        }
    }

    private fun startVideoUpload() {
        if (analyzingPhoto || analyzingVideo) return
        if (!session.isDeviceBound()) return
        launchVideoPicker()
    }

    private fun launchVideoPicker() {
        val pickMedia = ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(requireContext())
        if (pickMedia) {
            pickVideoLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
            )
        } else {
            openDocumentLauncher.launch(arrayOf("video/*"))
        }
    }

    private fun uploadVideo(uri: Uri) {
        val file = try {
            val tmp = java.io.File(requireContext().cacheDir, "video_upload_${System.currentTimeMillis()}.mp4")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.exists() && tmp.length() > 0) tmp else null
        } catch (e: Exception) {
            null
        }
        if (file == null) {
            appendTextMessage("系统: 无法读取视频文件")
            return
        }
        val result = VideoFrameExtractor.extract(file)
        if (result.error != null || result.frames.isEmpty()) {
            val msg = result.error ?: "视频抽帧失败"
            appendTextMessage("系统: $msg")
            return
        }

        analyzingVideo = true
        appendTextMessage("AI 视频分析中（共 ${result.frames.size} 帧，约 ${result.durationMs / 1000} 秒）…")

        session.analyzeUploadedVideo(result.frames, result.frames.size) { explanation, err ->
            if (_binding == null || !isAdded) return@analyzeUploadedVideo
            analyzingVideo = false
            when {
                err.isNotEmpty() -> appendTextMessage("系统: $err")
                explanation.isNotEmpty() -> appendTextMessage("AI 视频分析:\n$explanation")
                else -> appendTextMessage("系统: 视频分析无结果")
            }
        }
    }

    private fun setupPttButton() {
        binding.btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    PttSnapAskController.onPttDown(session)
                    syncWaveState()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    PttSnapAskController.onPttUp()
                    syncWaveState()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    PttSnapAskController.cancel()
                    syncWaveState()
                    true
                }
                else -> false
            }
        }
    }

    private fun sendMessage(forcedText: String? = null) {
        val text = forcedText ?: binding.etInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        appendTextMessage("我（PTT）: $text")
        if (forcedText == null) binding.etInput.text?.clear()
        if (DemoScenarios.matchFromText(text) == "expert_call") {
            appendTextMessage("系统: 正在连接 AI 技术专家…")
            session.runExpertConsult(question = text, captureFirst = false) { result, err ->
                if (_binding == null || !isAdded) return@runExpertConsult
                syncWaveState()
                when {
                    err.isNotEmpty() -> appendTextMessage("系统: $err")
                    result != null -> {
                        appendTextMessage("AI专家: ${result.reply}")
                        showDemoResult(result)
                    }
                    else -> appendTextMessage("系统: 专家咨询无回复")
                }
            }
            syncWaveState()
            return
        }
        session.sendChatText(text) { reply, err, demo ->
            if (_binding == null || !isAdded) return@sendChatText
            syncWaveState()
            when {
                err.isNotEmpty() -> appendTextMessage("系统: $err")
                reply.isNotEmpty() -> {
                    appendTextMessage("赢筑AI: $reply")
                    demo?.let { showDemoResult(it) }
                }
                else -> appendTextMessage("系统: AI 无回复，请到设置页检测后端并重新登录")
            }
        }
        syncWaveState()
    }

    private fun showDemoResult(demo: DemoScenarios.SceneResult) {
        SceneDemoDialogFragment.newInstance(demo)
            .show(parentFragmentManager, "chat_demo")
    }

    private fun refreshStatus() {
        ThemisTopBar.bind(
            session,
            binding.themisTopBar.statusDot,
            binding.themisTopBar.tvStatus,
            binding.themisTopBar.tvBattery,
            requireContext(),
        )
    }

    private fun syncWaveState() {
        if (_binding == null || !isAdded) return
        setWaveState(
            AssistantWaveState.resolve(
                ttsSpeaking = TtsSpeaker.isSpeaking() || session.isAiRealtimeSpeaking(),
                aiListening = session.isAiListening(),
                aiProcessing = session.isAiProcessing(),
            ),
        )
    }

    private fun setWaveState(next: AssistantWaveState) {
        val currentBinding = _binding ?: return
        currentBinding.listeningWave.setState(next)
        currentBinding.ivAssistantWave.visibility =
            if (next == AssistantWaveState.IDLE) View.VISIBLE else View.GONE
    }

    private fun checkBackend() {
        BackendDiscovery.ensureReachable { _, _ -> }
    }

    private fun appendTextMessage(line: String) {
        val message = ChatMessage.Text(line)
        messages.add(message)
        messageAdapter.append(message)
        scrollToBottom()
    }

    private fun appendPhotoMessage(caption: String, imageBytes: ByteArray) {
        val message = ChatMessage.Photo(caption, imageBytes)
        messages.add(message)
        messageAdapter.append(message)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        binding.rvMessages.post {
            val last = messageAdapter.itemCount() - 1
            if (last >= 0) {
                binding.rvMessages.smoothScrollToPosition(last)
            }
        }
    }

    override fun onDestroyView() {
        TtsSpeaker.removeListener(ttsListener)
        _binding?.listeningWave?.stop()
        _binding = null
        super.onDestroyView()
    }
}
