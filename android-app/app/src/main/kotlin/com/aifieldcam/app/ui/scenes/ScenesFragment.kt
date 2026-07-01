package com.aifieldcam.app.ui.scenes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.databinding.FragmentScenesBinding
import com.aifieldcam.app.demo.DemoScenarios

class ScenesFragment : Fragment(), SessionManager.StatusListener {

    private var _binding: FragmentScenesBinding? = null
    private val binding get() = _binding!!
    private val session by lazy { SessionManager.getInstance(requireContext()) }
    private var running = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentScenesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvScenes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvScenes.adapter = SceneAdapter(DemoScenarios.all) { meta ->
            runScene(meta.id)
        }
        refreshStatus()
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

    private fun runScene(scenarioId: String) {
        if (running) return
        if (!session.isLoggedIn()) {
            Toast.makeText(requireContext(), "请先在设置页完成巡查员认证", Toast.LENGTH_LONG).show()
            return
        }
        running = true
        if (scenarioId == "expert_call") {
            session.runExpertConsult(captureFirst = true) { result, err ->
                finishSceneRun(result, err)
            }
            return
        }
        session.runDemoScenario(scenarioId) { result, err ->
            finishSceneRun(result, err)
        }
    }

    private fun finishSceneRun(result: DemoScenarios.SceneResult?, err: String) {
        if (_binding == null || !isAdded) return
        running = false
        if (result == null) {
            Toast.makeText(requireContext(), err.ifBlank { "演示失败" }, Toast.LENGTH_SHORT).show()
            return
        }
        SceneDemoDialogFragment.newInstance(result)
            .show(parentFragmentManager, "scene_demo")
    }

    private fun refreshStatus() {
        binding.tvStatus.text = session.getLoginSummary()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
