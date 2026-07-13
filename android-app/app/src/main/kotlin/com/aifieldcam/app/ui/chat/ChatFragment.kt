package com.aifieldcam.app.ui.chat

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.aifieldcam.app.data.BackendDiscovery
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentChatBinding
import com.aifieldcam.app.demo.DemoScenarios
import com.aifieldcam.app.ui.scenes.SceneDemoDialogFragment
import com.aifieldcam.app.util.ImageUtils
import com.aifieldcam.app.util.VideoFrameExtractor
import com.aifieldcam.app.util.PhotoPermissionHelper
import com.aifieldcam.app.util.TtsSpeaker

class ChatFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }
    private var analyzingPhoto = false
    private var analyzingVideo = false
    private val rippleAnimators = mutableListOf<Animator>()
    private val ttsListener = TtsSpeaker.Listener { speaking ->
        if (_binding != null && isAdded) {
            setVoiceRippleActive(speaking)
        }
    }

    private val messages = mutableListOf<ChatMessage>()
    private val messageAdapter = ChatMessageAdapter()

    private val photoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            launchPhotoPicker()
        } else {
            Toast.makeText(
                requireContext(),
                "需要相册权限才能选择本地照片",
                Toast.LENGTH_LONG,
            ).show()
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
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        session.addStatusListener(this)
        TtsSpeaker.addListener(ttsListener)
        checkBackend()
        refreshStatus()
    }

    override fun onStop() {
        TtsSpeaker.removeListener(ttsListener)
        setVoiceRippleActive(false)
        session.removeStatusListener(this)
        super.onStop()
    }

    override fun onSessionChanged() {
        if (_binding == null || !isAdded) return
        refreshStatus()
    }

    private fun startPhotoUpload() {
        if (analyzingPhoto) {
            Toast.makeText(requireContext(), "正在识别上一张照片", Toast.LENGTH_SHORT).show()
            return
        }
        if (!session.isLoggedIn()) {
            Toast.makeText(requireContext(), "请先在设置页登录", Toast.LENGTH_LONG).show()
            return
        }
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
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
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
        if (analyzingPhoto || analyzingVideo) {
            Toast.makeText(requireContext(), "正在处理中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (!session.isLoggedIn()) {
            Toast.makeText(requireContext(), "请先在设置页登录", Toast.LENGTH_LONG).show()
            return
        }
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
            Toast.makeText(requireContext(), "无法读取视频文件", Toast.LENGTH_LONG).show()
            return
        }
        val result = VideoFrameExtractor.extract(file)
        if (result.error != null || result.frames.isEmpty()) {
            val msg = result.error ?: "视频抽帧失败"
            appendTextMessage("系统: $msg")
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
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
        val pttPhrases = listOf(
            "开启班前安全演讲录制",
            "本机点位设备状态检测",
            "生成今日施工现场工作日志",
            "呼叫技术专家",
            "开启旁站施工合规监督",
        )
        var pttIndex = 0
        binding.btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    binding.tvPttLabel.text = "正在聆听…"
                    session.setAiListening(true)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    binding.tvPttLabel.text = "按住说话"
                    session.setAiListening(false)
                    val phrase = pttPhrases[pttIndex % pttPhrases.size]
                    pttIndex++
                    binding.etInput.setText(phrase)
                    sendMessage(phrase)
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
                when {
                    err.isNotEmpty() -> appendTextMessage("系统: $err")
                    result != null -> {
                        appendTextMessage("AI专家: ${result.reply}")
                        showDemoResult(result)
                    }
                    else -> appendTextMessage("系统: 专家咨询无回复")
                }
            }
            return
        }
        session.sendChatText(text) { reply, err, demo ->
            if (_binding == null || !isAdded) return@sendChatText
            when {
                err.isNotEmpty() -> appendTextMessage("系统: $err")
                reply.isNotEmpty() -> {
                    appendTextMessage("赢筑AI: $reply")
                    demo?.let { showDemoResult(it) }
                }
                else -> appendTextMessage("系统: AI 无回复，请到设置页检测后端并重新登录")
            }
        }
    }

    private fun showDemoResult(demo: DemoScenarios.SceneResult) {
        SceneDemoDialogFragment.newInstance(demo)
            .show(parentFragmentManager, "chat_demo")
    }

    private fun refreshStatus() {
        val showRecording = session.isRecording() ||
            (session.isRecorderBusy() && !session.isVideoSaving())
        if (showRecording) {
            binding.statusDot.visibility = View.VISIBLE
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = "录制中"
        } else {
            binding.statusDot.visibility = View.GONE
            binding.tvStatus.visibility = View.GONE
            binding.tvStatus.text = ""
        }
        binding.tvBattery.text = batteryPercentText()
    }

    private fun setVoiceRippleActive(active: Boolean) {
        if (active) {
            startVoiceRipple()
        } else {
            stopVoiceRipple()
        }
    }

    private fun startVoiceRipple() {
        if (rippleAnimators.isNotEmpty()) return
        listOf(
            binding.orbRippleInner,
            binding.orbRippleMiddle,
            binding.orbRippleOuter,
        ).forEachIndexed { index, view ->
            view.alpha = 0f
            view.scaleX = 0.86f
            view.scaleY = 0.86f
            val delay = index * 260L
            val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 0.86f, 1.48f)
            val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.86f, 1.48f)
            val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, 0.58f, 0f)
            listOf(scaleX, scaleY, alpha).forEach {
                it.duration = 1_300L
                it.startDelay = delay
                it.repeatCount = ValueAnimator.INFINITE
                it.repeatMode = ValueAnimator.RESTART
                it.interpolator = LinearInterpolator()
            }
            AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
                start()
                rippleAnimators.add(this)
            }
        }
    }

    private fun stopVoiceRipple() {
        rippleAnimators.forEach { it.cancel() }
        rippleAnimators.clear()
        if (_binding == null) return
        listOf(
            binding.orbRippleInner,
            binding.orbRippleMiddle,
            binding.orbRippleOuter,
        ).forEach {
            it.alpha = 0f
            it.scaleX = 1f
            it.scaleY = 1f
        }
    }

    private fun checkBackend() {
        BackendDiscovery.ensureReachable { _, _ -> }
    }

    private fun batteryPercentText(): String {
        val intent = requireContext().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return "--"
        return "${level * 100 / scale}%"
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
        stopVoiceRipple()
        super.onDestroyView()
        _binding = null
    }
}
