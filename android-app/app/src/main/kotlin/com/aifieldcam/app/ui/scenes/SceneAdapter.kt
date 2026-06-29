package com.aifieldcam.app.ui.scenes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.aifieldcam.app.databinding.ItemSceneCardBinding
import com.aifieldcam.app.demo.DemoScenarios

class SceneAdapter(
    private val items: List<DemoScenarios.SceneMeta>,
    private val onClick: (DemoScenarios.SceneMeta) -> Unit,
) : RecyclerView.Adapter<SceneAdapter.VH>() {

    class VH(val binding: ItemSceneCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSceneCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvSceneTitle.text = "场景${position + 1}：${item.title}"
        holder.binding.tvSceneSubtitle.text = item.subtitle
        holder.binding.tvPttHint.text = "PTT：「${item.pttHint}」"
        holder.binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}
