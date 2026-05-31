@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package dev.jenny.litertlm.app

import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.jenny.litertlm.app.databinding.ActivityChatBinding
import dev.jenny.litertlm.engine.ModelEngineManager
import dev.jenny.litertlm.app.data.ChatRequest
import dev.jenny.litertlm.models.ChatResponse
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

class ChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatBinding
    private val engineManager by lazy { ModelEngineManager.getInstance(this) }
    private val messages = mutableListOf<Pair<String, Boolean>>()
    private lateinit var messageAdapter: MessageAdapter
    private var contextPercent = 0
    private val SUMMARY_THRESHOLD = 75
    private var generateJob: Job? = null
    private var isGenerating = false
    private lateinit var markwon: Markwon
    private val selectedImages = mutableListOf<Pair<Uri, ByteArray>>()
    private val selectedAudios = mutableListOf<Pair<Uri, ByteArray>>()
    private val client get() = LiteRtApplication.httpClient
    private val gson get() = LiteRtApplication.gson
    private var sessionId: String = java.util.UUID.randomUUID().toString()
    private var eventSource: EventSource? = null
    
    private val prefs by lazy { getSharedPreferences("server_config", Context.MODE_PRIVATE) }
    private val serverPort by lazy { prefs.getInt("port", 8080) }
    
    private val REQUEST_PICK_IMAGE = 1004
    private val REQUEST_PICK_AUDIO = 1005

    companion object {
        private const val KEY_MESSAGES = "saved_messages"
        private const val KEY_CONTEXT_PERCENT = "context_percent"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val modelName = intent.getStringExtra("modelName") ?: "未知模型"
        binding.tvModelName.text = modelName

        markwon = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .build()

        messageAdapter = MessageAdapter(messages, markwon)
        binding.rvMessages.adapter = messageAdapter
        binding.rvMessages.layoutManager = LinearLayoutManager(this)

        savedInstanceState?.let { state ->
            val savedMessages = state.getStringArrayList(KEY_MESSAGES)
            savedMessages?.forEach { msg ->
                val parts = msg.split("|")
                if (parts.size == 2) {
                    messages.add(Pair(parts[1], parts[0] == "U"))
                }
            }
            messageAdapter.notifyDataSetChanged()
            contextPercent = state.getInt(KEY_CONTEXT_PERCENT, 0)
            updateContextPercentUI()
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.btnSend.isEnabled = s != null && s.isNotEmpty()
            }
        })

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString()
            if (text.isNotEmpty() || selectedImages.isNotEmpty()) {
                sendMessage(text)
            }
        }

        binding.btnAttachImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, REQUEST_PICK_IMAGE)
        }

        binding.btnAttachAudio.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            startActivityForResult(intent, REQUEST_PICK_AUDIO)
        }

        lifecycleScope.launch {
            engineManager.benchmarkInfo.collect { info ->
                info?.let {
                    binding.tvSpeed.text = "${it.lastDecodeTokensPerSecond.toInt()} t/s"
                    updateContextPercent(it.lastPrefillTokenCount)
                }
            }
        }

        lifecycleScope.launch {
            engineManager.runningModelState.collect { model ->
                model?.let {
                    updateContextPercent(it.contextSize)
                    binding.btnAttachImage.visibility = if (it.supportImage) View.VISIBLE else View.GONE
                    binding.btnAttachAudio.visibility = if (it.supportAudio) View.VISIBLE else View.GONE
                }
            }
        }

        binding.btnSummarize.setOnClickListener {
            summarizeConversation()
        }

        binding.btnStop.setOnClickListener {
            stopGeneration()
        }
        
        if (engineManager.runningModel == null) {
            binding.etMessage.hint = "模型未初始化，无法对话"
            binding.etMessage.isEnabled = false
            binding.btnSend.isEnabled = false
        }
    }

    private fun sendMessage(text: String) {
        binding.etMessage.text.clear()
        binding.btnSend.isEnabled = false
        binding.progressSending.visibility = View.VISIBLE
        binding.btnStop.visibility = View.VISIBLE
        binding.btnSend.visibility = View.GONE
        isGenerating = true

        val displayText = buildString {
            if (selectedImages.isNotEmpty()) {
                append("[${selectedImages.size}张图片] ")
            }
            if (selectedAudios.isNotEmpty()) {
                append("[${selectedAudios.size}段音频] ")
            }
            append(text)
        }
        messages.add(Pair(displayText, true))
        messageAdapter.notifyItemInserted(messages.size - 1)
        binding.rvMessages.scrollToPosition(messages.size - 1)

        val contents = buildContentParts(text)
        selectedImages.clear()
        selectedAudios.clear()
        binding.scrollImages.visibility = View.GONE
        binding.layoutImages.removeAllViews()

        generateJob = lifecycleScope.launch {
            try {
                if (engineManager.runningModel == null) {
                    withContext(Dispatchers.Main) {
                        messages.add(Pair("错误: 模型未初始化", false))
                        messageAdapter.notifyItemInserted(messages.size - 1)
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                        finishGenerating()
                    }
                    return@launch
                }

                val responseBuilder = StringBuilder()
                var responseIndex = messages.size
                messages.add(Pair("", false))
                messageAdapter.notifyItemInserted(responseIndex)

                val request = ChatRequest(
                    messages = listOf(ChatRequest.Message(role = "user", content = ChatRequest.MessageContent.MultiPart(contents))),
                    stream = true,
                    session_id = sessionId
                )
                
                val requestBody = RequestBody.create("application/json".toMediaType(), gson.toJson(request))
                val url = "http://${LiteRtApplication.ipAddress.value}:$serverPort/v1/chat/completions"
                
                withContext(Dispatchers.IO) {
                    val okRequest = Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build()
                    
                    val factory = EventSources.createFactory(client)
                    eventSource = factory.newEventSource(okRequest, object : EventSourceListener() {
                        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                            if (data == "[DONE]") {
                                lifecycleScope.launch {
                                    if (!isDestroyed && !isFinishing) {
                                        finishGenerating()
                                    }
                                }
                                return
                            }
                            
                            try {
                                val response = gson.fromJson(data, ChatResponse::class.java)
                                val content = response.choices.firstOrNull()?.delta?.content ?: ""
                                responseBuilder.append(content)
                                lifecycleScope.launch {
                                    if (!isDestroyed && !isFinishing) {
                                        messages[responseIndex] = Pair(responseBuilder.toString(), false)
                                        messageAdapter.notifyItemChanged(responseIndex)
                                        binding.rvMessages.scrollToPosition(responseIndex)
                                    }
                                }
                            } catch (e: Exception) {
                            }
                        }
                        
                        override fun onClosed(eventSource: EventSource) {
                            lifecycleScope.launch {
                                if (!isDestroyed && !isFinishing) {
                                    finishGenerating()
                                }
                            }
                        }
                        
                        override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                            lifecycleScope.launch {
                                if (!isDestroyed && !isFinishing) {
                                    finishGenerating()
                                    messages.add(Pair("错误: ${t?.message ?: "网络请求失败"}", false))
                                    messageAdapter.notifyItemInserted(messages.size - 1)
                                    binding.rvMessages.scrollToPosition(messages.size - 1)
                                }
                            }
                        }
                    })
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    finishGenerating()
                    if (e.message?.contains("cancelled", ignoreCase = true) != true) {
                        messages.add(Pair("错误: ${e.message}", false))
                        messageAdapter.notifyItemInserted(messages.size - 1)
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                    }
                }
            }
        }
    }

    private fun buildContentParts(text: String): List<ChatRequest.ContentPart> {
        val parts = mutableListOf<ChatRequest.ContentPart>()
        
        selectedImages.forEach { (_, bytes) ->
            val base64 = Base64.getEncoder().encodeToString(bytes)
            parts.add(ChatRequest.ContentPart(
                type = "image_url",
                image_url = ChatRequest.ImageUrl("data:image/jpeg;base64,$base64")
            ))
        }
        
        selectedAudios.forEach { (_, bytes) ->
            val base64 = Base64.getEncoder().encodeToString(bytes)
            parts.add(ChatRequest.ContentPart(
                type = "input_audio",
                input_audio = ChatRequest.InputAudio(base64, "wav")
            ))
        }
        
        if (text.isNotEmpty()) {
            parts.add(ChatRequest.ContentPart(type = "text", text = text))
        }
        
        return if (parts.isEmpty()) {
            listOf(ChatRequest.ContentPart(type = "text", text = ""))
        } else {
            parts
        }
    }

    private fun stopGeneration() {
        generateJob?.cancel()
        eventSource?.cancel()
        eventSource = null
        finishGenerating()
        Toast.makeText(this, "已停止生成", Toast.LENGTH_SHORT).show()
    }

    private fun finishGenerating() {
        isGenerating = false
        binding.progressSending.visibility = View.GONE
        binding.btnStop.visibility = View.GONE
        binding.btnSend.visibility = View.VISIBLE
        binding.btnSend.isEnabled = binding.etMessage.text.isNotEmpty()
    }

    private fun updateContextPercent(contextSize: Int) {
        val maxContext = engineManager.getMaxContextSize()
        contextPercent = (contextSize * 100 / maxContext).coerceIn(0, 100)
        updateContextPercentUI()
    }

    private fun updateContextPercentUI() {
        binding.tvContext.text = "$contextPercent%"
        
        if (contextPercent >= SUMMARY_THRESHOLD) {
            binding.tvContext.setTextColor(getColor(android.R.color.holo_orange_dark))
            binding.btnSummarize.visibility = View.VISIBLE
        } else {
            binding.tvContext.setTextColor(getColor(android.R.color.darker_gray))
            binding.btnSummarize.visibility = View.GONE
        }
    }

    private fun summarizeConversation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("整理会话")
            .setMessage("上下文已使用 $contextPercent%，将总结对话内容并清空历史。继续？")
            .setPositiveButton("整理") { _, _ ->
                doSummarize()
            }
            .setNegativeButton("取消", null)
            .show()
    }

private fun doSummarize() {
        binding.progressSending.visibility = View.VISIBLE
        binding.btnSummarize.isEnabled = false
        binding.btnStop.visibility = View.VISIBLE
        isGenerating = true
        
        generateJob = lifecycleScope.launch {
            try {
                if (engineManager.runningModel == null) {
                    withContext(Dispatchers.Main) {
                        finishGenerating()
                        binding.btnSummarize.isEnabled = true
                        Toast.makeText(this@ChatActivity, "模型未初始化", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val summaryBuilder = StringBuilder()
                var responseIndex = messages.size
                messages.add(Pair("", false))
                messageAdapter.notifyItemInserted(responseIndex)
                
                val request = ChatRequest(
                    messages = listOf(ChatRequest.Message(
                        role = "user", 
                        content = ChatRequest.MessageContent.Text("请用简洁的语言总结我们刚才的对话内容，保留关键信息。")
                    )),
                    stream = true,
                    session_id = sessionId
                )
                
                val requestBody = RequestBody.create("application/json".toMediaType(), gson.toJson(request))
                val url = "http://${LiteRtApplication.ipAddress.value}:$serverPort/v1/chat/completions"
                
                withContext(Dispatchers.IO) {
                    val okRequest = Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .build()
                    
                    val factory = EventSources.createFactory(client)
                    eventSource = factory.newEventSource(okRequest, object : EventSourceListener() {
                        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                            if (data == "[DONE]") {
                                lifecycleScope.launch {
                                    if (isDestroyed || isFinishing) return@launch
                                    finishGenerating()
                                    binding.btnSummarize.isEnabled = true
                                    
                                    val summary = summaryBuilder.toString()
                                    if (summary.isNotEmpty()) {
                                        messages.clear()
                                        messageAdapter.notifyDataSetChanged()
                                        
                                        messages.add(Pair("[对话总结] $summary", false))
                                        messageAdapter.notifyItemInserted(0)
                                        binding.rvMessages.scrollToPosition(0)
                                        
                                        sessionId = java.util.UUID.randomUUID().toString()
                                        
                                        contextPercent = 0
                                        updateContextPercentUI()
                                    }
                                }
                                return
                            }
                            
                            try {
                                val response = gson.fromJson(data, ChatResponse::class.java)
                                val content = response.choices.firstOrNull()?.delta?.content ?: ""
                                summaryBuilder.append(content)
                                lifecycleScope.launch {
                                    if (!isDestroyed && !isFinishing) {
                                        messages[responseIndex] = Pair(summaryBuilder.toString(), false)
                                        messageAdapter.notifyItemChanged(responseIndex)
                                        binding.rvMessages.scrollToPosition(responseIndex)
                                    }
                                }
                            } catch (e: Exception) {
                            }
                        }
                        
                        override fun onClosed(eventSource: EventSource) {
                            lifecycleScope.launch {
                                if (!isDestroyed && !isFinishing) {
                                    finishGenerating()
                                    binding.btnSummarize.isEnabled = true
                                }
                            }
                        }
                        
                        override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                            lifecycleScope.launch {
                                if (!isDestroyed && !isFinishing) {
                                    finishGenerating()
                                    binding.btnSummarize.isEnabled = true
                                    Toast.makeText(this@ChatActivity, "总结失败: ${t?.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    })
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    finishGenerating()
                    binding.btnSummarize.isEnabled = true
                    Toast.makeText(this@ChatActivity, "总结失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PICK_IMAGE -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri -> addImage(uri) }
                }
            }
            REQUEST_PICK_AUDIO -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri -> addAudio(uri) }
                }
            }
        }
    }

    private fun addImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val compressedBytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val bitmap = BitmapFactory.decodeStream(input)
                        bitmap?.toCompressedByteArray(maxSizeKB = 512)
                    }
                }
                
                if (compressedBytes != null && compressedBytes.size < 2 * 1024 * 1024) {
                    selectedImages.add(Pair(uri, compressedBytes))
                    addImagePreview(uri)
                    binding.scrollImages.visibility = View.VISIBLE
                    binding.btnSend.isEnabled = true
                } else {
                    Toast.makeText(this@ChatActivity, "图片压缩失败或太大", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "添加图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun Bitmap.toCompressedByteArray(maxSizeKB: Int = 512): ByteArray {
        var quality = 90
        val stream = ByteArrayOutputStream()
        this.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        
        while (stream.size() > maxSizeKB * 1024 && quality > 10) {
            stream.reset()
            quality -= 10
            this.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        }
        
        return stream.toByteArray()
    }

    private fun addAudio(uri: Uri) {
        lifecycleScope.launch {
            try {
                val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    cursor.getString(nameIndex)
                } ?: "audio"
                
                if (!fileName.endsWith(".wav", ignoreCase = true)) {
                    Toast.makeText(
                        this@ChatActivity,
                        "仅支持 WAV 格式音频\n请使用录音机录制或转换后选择",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                
                if (bytes != null && bytes.size < 10 * 1024 * 1024) {
                    selectedAudios.add(Pair(uri, bytes))
                    binding.btnSend.isEnabled = true
                    Toast.makeText(this@ChatActivity, "已添加音频 (${bytes.size / 1024}KB)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ChatActivity, "音频太大或无法读取（限制10MB）", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "添加音频失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addImagePreview(uri: Uri) {
        val imageView = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(200, 200)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(4, 4, 4, 4)
            setImageURI(uri)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@ChatActivity)
                    .setTitle("移除图片")
                    .setMessage("确定要移除这张图片吗？")
                    .setPositiveButton("移除") { _, _ ->
                        val index = selectedImages.indexOfFirst { it.first == uri }
                        if (index >= 0) {
                            selectedImages.removeAt(index)
                            binding.layoutImages.removeView(this)
                            if (selectedImages.isEmpty()) {
                                binding.scrollImages.visibility = View.GONE
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        binding.layoutImages.addView(imageView)
    }

    override fun onDestroy() {
        super.onDestroy()
        generateJob?.cancel()
        eventSource?.cancel()
        eventSource = null

        // 退出时通知服务端删除当前会话，避免残留会话占用推理资源
        val sid = sessionId
        val port = serverPort
        val ip = LiteRtApplication.ipAddress.value
        Thread {
            try {
                val url = "http://$ip:$port/v1/sessions/$sid"
                val request = Request.Builder().url(url).delete().build()
                client.newCall(request).execute().close()
                Log.d("ChatActivity", "Session $sid deleted on server exit")
            } catch (e: Exception) {
                Log.w("ChatActivity", "Failed to delete session on exit: ${e.message}")
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val savedMessages = ArrayList<String>()
        messages.forEach { (text, isUser) ->
            savedMessages.add("${if (isUser) "U" else "A"}|$text")
        }
        outState.putStringArrayList(KEY_MESSAGES, savedMessages)
        outState.putInt(KEY_CONTEXT_PERCENT, contextPercent)
    }

    class MessageAdapter(
        private val messages: List<Pair<String, Boolean>>,
        private val markwon: Markwon
    ) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

        class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val cardMessage: View = itemView.findViewById(R.id.cardMessage)
            val cardResponse: View = itemView.findViewById(R.id.cardResponse)
            val tvUserMessage: android.widget.TextView = itemView.findViewById(R.id.tvUserMessage)
            val tvAssistantMessage: android.widget.TextView = itemView.findViewById(R.id.tvAssistantMessage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message, parent, false)
            return MessageViewHolder(view)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val (text, isUser) = messages[position]
            if (isUser) {
                holder.cardMessage.visibility = View.VISIBLE
                holder.cardResponse.visibility = View.GONE
                holder.tvUserMessage.text = text
            } else {
                holder.cardMessage.visibility = View.GONE
                holder.cardResponse.visibility = View.VISIBLE
                holder.tvAssistantMessage.movementMethod = LinkMovementMethod.getInstance()
                markwon.setMarkdown(holder.tvAssistantMessage, text)
            }
        }

        override fun getItemCount() = messages.size
    }
}