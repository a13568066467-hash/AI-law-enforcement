package com.aifieldcam.app.ui.scenes

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.aifieldcam.app.databinding.DialogSceneDemoBinding
import com.aifieldcam.app.demo.DemoScenarios
import com.google.android.material.chip.Chip
import java.util.Locale

class SceneDemoDialogFragment : DialogFragment(), TextToSpeech.OnInitListener {

    private var _binding: DialogSceneDemoBinding? = null
    private val binding get() = _binding!!
    private var tts: TextToSpeech? = null
    private var pendingSpeech: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        tts = TextToSpeech(requireContext(), this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogSceneDemoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val result = requireArguments().getSerializable(ARG_RESULT) as? DemoScenarios.SceneResult
            ?: return
        binding.tvTitle.text = result.title
        binding.tvVoice.text = "AI语音播报：${result.voiceBroadcast}"
        binding.tvReply.text = result.reply
        binding.tvPlatform.text = result.platformSync
        binding.chipGroup.removeAllViews()
        result.highlights.forEach { label ->
            val chip = Chip(requireContext()).apply {
                text = label
                isClickable = false
                isCheckable = false
            }
            binding.chipGroup.addView(chip)
        }
        val alertColor = when (result.alertLevel) {
            "critical" -> com.aifieldcam.app.R.color.error
            "warn" -> com.aifieldcam.app.R.color.warning
            else -> com.aifieldcam.app.R.color.primary
        }
        binding.viewAlert.setBackgroundResource(alertColor)
        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnSpeak.setOnClickListener {
            speak(result.voiceBroadcast)
            Toast.makeText(requireContext(), "AI语音播报（演示）", Toast.LENGTH_SHORT).show()
        }
        if (result.voiceBroadcast.isNotBlank()) {
            binding.btnSpeak.isVisible = true
            pendingSpeech = result.voiceBroadcast
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.CHINA
            pendingSpeech?.let { speak(it) }
            pendingSpeech = null
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "demo_tts")
    }

    override fun onDestroyView() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_RESULT = "result"

        fun newInstance(result: DemoScenarios.SceneResult): SceneDemoDialogFragment {
            return SceneDemoDialogFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_RESULT, result)
                }
            }
        }
    }
}
