package com.aifieldcam.app.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentChatBinding
import com.aifieldcam.app.demo.DemoScenarios
import com.aifieldcam.app.ui.scenes.SceneDemoDialogFragment
import com.aifieldcam.app.util.ImageUtils
import com.aifieldcam.app.util.PhotoPermissionHelper

class ChatFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }
    private var analyzingPhoto = false

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
                        "· 呼叫技术专家\n" +
                        "也可在「场景」页点击卡片演示九大核心业务。",
                ),
            )
        }

        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = messageAdapter
        messageAdapter.submitList(messages.toList())

        refreshStatus()

        binding.btnUploadPhoto.setOnClickListener { startPhotoUpload() }
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
        refreshStatus()
    }

    override fun onStop() {
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
                    binding.btnPtt.text = "正在聆听…"
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    binding.btnPtt.text = getString(com.aifieldcam.app.R.string.ptt_hold_hint)
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
        binding.tvStatus.text = session.getBleSummary()
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
        super.onDestroyView()
        _binding = null
    }
}
