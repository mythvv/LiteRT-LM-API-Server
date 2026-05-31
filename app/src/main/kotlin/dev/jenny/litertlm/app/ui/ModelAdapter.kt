package dev.jenny.litertlm.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.jenny.litertlm.app.R
import dev.jenny.litertlm.models.ModelItem
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.text.DecimalFormat

class ModelAdapter(
    private val onStartClick: (ModelItem) -> Unit,
    private val onStopClick: () -> Unit,
    private val onDeleteClick: (ModelItem) -> Unit,
    private val onBackendChange: (ModelItem, String) -> Unit = { _, _ -> },
    private val onSettingsClick: (ModelItem) -> Unit = {},
    private val onChatClick: (ModelItem) -> Unit = {}
) : ListAdapter<ModelItem, ModelAdapter.ModelViewHolder>(DiffCallback) {

    companion object {
        private val SIZE_FORMAT = DecimalFormat("#.##")
        private val BACKENDS = arrayOf("GPU", "CPU", "NPU")
    }

    inner class ModelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvName)
        val tvSize: TextView = itemView.findViewById(R.id.tvSize)
        val tvContextSetting: TextView = itemView.findViewById(R.id.tvContextSetting)
        val chipStatus: Chip = itemView.findViewById(R.id.chipStatus)
        val spinnerBackend: Spinner = itemView.findViewById(R.id.spinnerBackend)
        val layoutStats: LinearLayout = itemView.findViewById(R.id.layoutStats)
        val tvSpeed: TextView = itemView.findViewById(R.id.tvSpeed)
        val tvTokens: TextView = itemView.findViewById(R.id.tvTokens)
        val tvContext: TextView = itemView.findViewById(R.id.tvContext)
        val progressDownload: ProgressBar = itemView.findViewById(R.id.progressDownload)
        val progressLoading: ProgressBar = itemView.findViewById(R.id.progressLoading)
        val btnAction: MaterialButton = itemView.findViewById(R.id.btnAction)
        val btnDelete: MaterialButton = itemView.findViewById(R.id.btnDelete)
        val btnSettings: ImageButton = itemView.findViewById(R.id.btnSettings)
        val btnChat: MaterialButton = itemView.findViewById(R.id.btnChat)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model, parent, false)
        return ModelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val model = getItem(position)
        
        holder.tvName.text = model.name
        
        val sizeText = when {
            model.size > 1024 * 1024 * 1024 -> "${SIZE_FORMAT.format(model.size / 1024f / 1024f / 1024f)} GB"
            model.size > 1024 * 1024 -> "${SIZE_FORMAT.format(model.size / 1024f / 1024f)} MB"
            else -> "${SIZE_FORMAT.format(model.size / 1024f)} KB"
        }
        holder.tvSize.text = sizeText

        val contextText = when (model.maxContextSize) {
            in 0..4096 -> "4K上下文"
            in 4097..8192 -> "8K上下文"
            in 8193..16384 -> "16K上下文"
            in 16385..32768 -> "32K上下文"
            else -> "${model.maxContextSize / 1024}K上下文"
        }
        holder.tvContextSetting.text = contextText

        val backendAdapter = ArrayAdapter(holder.itemView.context, 
            android.R.layout.simple_spinner_item, BACKENDS)
        backendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        holder.spinnerBackend.adapter = backendAdapter
        val backendIndex = BACKENDS.indexOf(model.preferBackend).coerceAtLeast(0)
        holder.spinnerBackend.setSelection(backendIndex, false)
        holder.spinnerBackend.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                if (pos != backendIndex) {
                    onBackendChange(model, BACKENDS[pos])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        holder.btnSettings.setOnClickListener { onSettingsClick(model) }

        when (model.status) {
            "RUNNING" -> {
                holder.chipStatus.text = "运行中"
                holder.chipStatus.chipBackgroundColor = 
                    holder.itemView.context.getColorStateList(R.color.status_running)
                holder.layoutStats.visibility = View.VISIBLE
                holder.tvSpeed.text = "${model.tokensPerSecond.toInt()} t/s"
                holder.tvTokens.text = "${model.totalTokensGenerated} tokens"
                holder.tvContext.text = "${model.contextSize}/${model.maxContextSize}"
                holder.progressDownload.visibility = View.GONE
                holder.progressLoading.visibility = View.GONE
                holder.btnAction.text = "停止"
                holder.btnAction.setOnClickListener { onStopClick() }
                holder.btnDelete.isEnabled = false
                holder.spinnerBackend.isEnabled = false
                holder.btnSettings.isEnabled = false
                holder.btnChat.visibility = View.VISIBLE
                holder.btnChat.setOnClickListener { onChatClick(model) }
            }
            "READY" -> {
                holder.chipStatus.text = "就绪"
                holder.chipStatus.chipBackgroundColor = 
                    holder.itemView.context.getColorStateList(R.color.status_ready)
                holder.layoutStats.visibility = View.GONE
                holder.btnAction.text = "启动"
                holder.btnAction.setOnClickListener { onStartClick(model) }
                holder.btnDelete.isEnabled = true
                holder.spinnerBackend.isEnabled = true
                holder.btnSettings.isEnabled = true
                holder.btnChat.visibility = View.GONE
            }
            "DOWNLOADING" -> {
                holder.chipStatus.text = "下载中"
                holder.chipStatus.chipBackgroundColor = 
                    holder.itemView.context.getColorStateList(R.color.status_downloading)
                holder.layoutStats.visibility = View.VISIBLE
                holder.tvSpeed.text = "--"
                holder.tvTokens.text = "--"
                holder.tvContext.text = "--"
                holder.progressDownload.visibility = View.VISIBLE
                holder.progressDownload.progress = model.downloadProgress
                holder.progressLoading.visibility = View.GONE
                holder.btnAction.text = "下载中"
                holder.btnAction.isEnabled = false
                holder.btnDelete.isEnabled = false
                holder.spinnerBackend.isEnabled = false
                holder.btnSettings.isEnabled = false
                holder.btnChat.visibility = View.GONE
            }
            else -> {
                holder.chipStatus.text = "错误"
                holder.chipStatus.chipBackgroundColor = 
                    holder.itemView.context.getColorStateList(R.color.status_error)
                holder.layoutStats.visibility = View.GONE
                holder.btnAction.text = "重试"
                holder.btnAction.setOnClickListener { 
                    model.downloadUrl?.let { url ->
                        onBackendChange(model.copy(status = "DOWNLOADING"), "RETRY")
                    }
                }
                holder.btnDelete.isEnabled = true
                holder.spinnerBackend.isEnabled = true
                holder.btnSettings.isEnabled = true
                holder.btnChat.visibility = View.GONE
            }
        }
        holder.btnDelete.setOnClickListener { onDeleteClick(model) }
    }

    object DiffCallback : DiffUtil.ItemCallback<ModelItem>() {
        override fun areItemsTheSame(oldItem: ModelItem, newItem: ModelItem): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: ModelItem, newItem: ModelItem): Boolean {
            return oldItem == newItem
        }
    }
}