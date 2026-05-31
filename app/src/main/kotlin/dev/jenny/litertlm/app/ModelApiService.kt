package dev.jenny.litertlm.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.jenny.litertlm.app.data.ChatRequest
import dev.jenny.litertlm.models.ChatResponse
import dev.jenny.litertlm.app.data.HealthResponse
import dev.jenny.litertlm.app.data.ModelData
import dev.jenny.litertlm.app.data.ModelManager
import dev.jenny.litertlm.models.ModelsResponse
import dev.jenny.litertlm.app.data.SessionInfo
import dev.jenny.litertlm.app.data.SessionsResponse
import dev.jenny.litertlm.app.data.StatsResponse
import dev.jenny.litertlm.engine.ModelEngineManager
import dev.jenny.litertlm.session.SessionManager
import dev.jenny.litertlm.tools.DynamicToolSet
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.gson.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

class ModelApiService : Service() {
    companion object {
        private const val REQUEST_TIMEOUT_MS = 120_000L
        private const val MAX_CONCURRENT_REQUESTS = 2
    }
    
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "litert_api_server"
    private val gson get() = LiteRtApplication.gson
    private val engineManager by lazy { ModelEngineManager.getInstance(this) }
    private val modelManager by lazy { ModelManager.getInstance(this) }
    private val requestCounter = AtomicInteger(0)
    private var sessionManager: SessionManager? = null
    private val inferenceSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
    
    private var engineWatchJob: Job? = null
    @Volatile
    private var httpServerStarted = false
    private var httpServer: ApplicationEngine? = null
    @Volatile
    private var serviceStartTime: Long = 0
    
    // Stats tracking
    private val statsLock = Any()
    @Volatile private var totalRequests: Long = 0
    @Volatile private var totalTokens: Long = 0
    @Volatile private var avgLatencyMs: Double = 0.0
    @Volatile private var maxLatencyMs: Long = 0
    @Volatile private var minLatencyMs: Long = Long.MAX_VALUE
    
    private val prefs by lazy { getSharedPreferences("server_config", Context.MODE_PRIVATE) }
    private val serverPort by lazy { prefs.getInt("port", 8080) }
    
    private val modelUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "dev.jenny.litertlm.app.MODEL_STARTED" -> {
                    val modelName = intent.getStringExtra("modelName") ?: "未知模型"
                    updateNotification(modelName)
                }
                "dev.jenny.litertlm.app.MODEL_STOPPED" -> {
                    sessionManager?.resetSession()
                }
            }
        }
    }
    
    private fun updateNotification(modelName: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mobile API 服务运行中")
            .setContentText("当前模型: $modelName | 端口: $serverPort")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onCreate() {
        super.onCreate()
        serviceStartTime = System.currentTimeMillis()
        createNotificationChannel()
        val initialModel = engineManager.runningModel?.name ?: "无运行模型"
        startForeground(NOTIFICATION_ID, buildNotification(initialModel))
        
        sessionManager = SessionManager { tools ->
            engineManager.createConversation(false, tools)
        }
        
        CoroutineScope(Dispatchers.IO).launch { startHttpServer() }
        
        engineWatchJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(5000)
                val model = engineManager.runningModel
                val engine = engineManager.getCurrentEngine()
                if (model != null && engine == null) {
                    Log.w("ModelApiService", "Engine lost while model is running: ${model.name}")
                    withContext(Dispatchers.Main) {
                        updateNotification("模型已丢失 - 请重启")
                    }
                }
            }
        }
        
        val filter = IntentFilter()
        filter.addAction("dev.jenny.litertlm.app.MODEL_STARTED")
        filter.addAction("dev.jenny.litertlm.app.MODEL_STOPPED")
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(modelUpdateReceiver, filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(modelUpdateReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e("ModelApiService", "Failed to register receiver: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    private fun startHttpServer() {
        if (httpServerStarted) {
            return
        }
        httpServerStarted = true
        httpServer = embeddedServer(Netty, port = serverPort, host = "0.0.0.0") {
            install(CORS) {
                anyHost()
                allowHeader("*")
            }
            install(ContentNegotiation) { gson() }
            routing {
                get("/v1/") {
                    val runningModel = engineManager.runningModel
                    call.respond(mapOf(
                        "name" to "LiteRtLmApiServer",
                        "version" to "2.0.2",
                        "model" to (runningModel?.name ?: "未运行"),
                        "endpoints" to listOf(
                            mapOf("method" to "GET", "path" to "/v1/", "description" to "API 概述"),
                            mapOf("method" to "GET", "path" to "/v1/models", "description" to "可用模型列表"),
                            mapOf("method" to "POST", "path" to "/v1/chat/completions", "description" to "聊天补全（OpenAI 兼容）"),
                            mapOf("method" to "GET", "path" to "/v1/sessions", "description" to "活跃会话详情"),
                            mapOf("method" to "DELETE", "path" to "/v1/sessions/{sessionId}", "description" to "删除指定会话"),
                            mapOf("method" to "GET", "path" to "/v1/stats", "description" to "性能统计"),
                            mapOf("method" to "GET", "path" to "/v1/health", "description" to "健康检查")
                        )
                    ))
                }
                
                get("/v1/models") {
                    val models = modelManager.getAllModels()
                    val response = ModelsResponse(
                        data = models.map { model ->
                            ModelData(
                                id = model.id,
                                created = model.createTime / 1000
                            )
                        }
                    )
                    call.respond(response)
                }
                
                get("/v1/health") {
                    val runningModel = engineManager.runningModel
                    val engine = engineManager.getCurrentEngine()
                    val status = if (runningModel != null && engine != null) "ok" else "no_model"
                    call.respond(HealthResponse(
                        status = status,
                        engine_state = if (engine != null) "ready" else "idle",
                        current_model = runningModel?.name,
                        uptime_ms = System.currentTimeMillis() - serviceStartTime
                    ))
                }
                
                get("/v1/stats") {
                    val runningModel = engineManager.runningModel
                    val backend = runningModel?.preferBackend ?: "CPU"
                    val tps = runningModel?.tokensPerSecond ?: 0.0
                    call.respond(StatsResponse(
                        total_requests = totalRequests,
                        total_tokens = totalTokens,
                        avg_latency_ms = avgLatencyMs,
                        max_latency_ms = maxLatencyMs,
                        min_latency_ms = if (minLatencyMs == Long.MAX_VALUE) 0 else minLatencyMs,
                        active_sessions = sessionManager?.getSessionCount() ?: 0,
                        current_model = runningModel?.name,
                        backend = backend,
                        tokens_per_second = tps
                    ))
                }
                
                post("/v1/chat/completions") {
                    val runningModel = engineManager.runningModel
                    if (runningModel == null) {
                        Log.e("ModelApiService", "No model running")
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "请先启动一个模型"))
                        return@post
                    }

                    val request = call.receive<ChatRequest>()
                    if (request.messages.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "消息列表为空"))
                        return@post
                    }
                    
                    val sessMgr = sessionManager
                    if (sessMgr == null) {
                        Log.e("ModelApiService", "SessionManager not initialized")
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "服务未初始化，请重启应用"))
                        return@post
                    }
                    
                    val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                        inferenceSemaphore.acquire()
                        try {
                            processChatRequest(call, request, runningModel, sessMgr)
                        } catch (e: Exception) {
                            Log.e("ModelApiService", "Error processing request: ${e.message}", e)
                            try {
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Unknown error")))
                            } catch (_: Exception) {}
                        } finally {
                            inferenceSemaphore.release()
                        }
                    }
                    
                    if (result == null) {
                        Log.w("ModelApiService", "Request timeout after ${REQUEST_TIMEOUT_MS}ms")
                        call.respond(HttpStatusCode.RequestTimeout, mapOf("error" to "请求超时（120秒）"))
                    }
                }
                
                delete("/v1/sessions/{sessionId}") {
                    val sessionId = call.parameters["sessionId"]
                    val sessMgr = sessionManager
                    if (sessMgr == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "服务未初始化"))
                        return@delete
                    }
                    
                    if (sessionId != null) {
                        sessMgr.resetSession(sessionId)
                        call.respond(mapOf("status" to "ok", "message" to "Session deleted: $sessionId"))
                    } else {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session_id"))
                    }
                }
                
                get("/v1/sessions") {
                    val sessMgr = sessionManager
                    if (sessMgr == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "服务未初始化"))
                        return@get
                    }
                    val maxCtx = engineManager.getMaxContextSize()
                    val sessionInfoList = sessMgr.getSessionInfo(maxCtx).map { info ->
                        SessionInfo(
                            id = info["id"] as String,
                            message_count = info["message_count"] as Int,
                            total_tokens = info["total_tokens"] as Int,
                            context_tokens = info["total_tokens"] as Int,
                            max_context_tokens = info["max_context_tokens"] as Int,
                            last_access = info["last_access"] as Long
                        )
                    }
                    call.respond(SessionsResponse(
                        count = sessMgr.getSessionCount(),
                        sessions = sessionInfoList
                    ))
                }
            }
        }.start(wait = false)
    }

    private suspend fun processChatRequest(
        call: ApplicationCall,
        request: ChatRequest,
        runningModel: dev.jenny.litertlm.app.data.ModelItem,
        sessMgr: SessionManager
    ) {
        val startTime = System.currentTimeMillis()
        
        val liteRtTools = request.tools?.let {
            DynamicToolSet.fromChatRequest(it).toToolProviders().map { tool(it) }
        }
        
        // 提取 system 消息
        val systemMessage = request.messages.find { it.role == "system" }
        val systemPrompt = when (systemMessage?.content) {
            is ChatRequest.MessageContent.Text -> (systemMessage.content as ChatRequest.MessageContent.Text).text
            is ChatRequest.MessageContent.MultiPart ->
                (systemMessage.content as ChatRequest.MessageContent.MultiPart).parts
                    .filter { it.type == "text" }
                    .joinToString(" ") { it.text ?: "" }
            else -> null
        }
        
        // 请求参数覆盖
        val overrideTemperature = request.temperature
        val overrideTopP = request.top_p
        val overrideMaxTokens = request.max_tokens
        
        // 区分无状态请求和有状态请求
        val isStateless = request.session_id == null
        val sessionId: String?
        val conversation: com.google.ai.edge.litertlm.Conversation?
        
        if (isStateless) {
            // 无状态请求：先清理旧session，创建临时conversation
            sessMgr.resetSession()
            sessionId = null
            conversation = engineManager.createConversation(
                false, liteRtTools, overrideTemperature, overrideTopP, null, systemPrompt
            )
        } else {
            // 有状态请求：使用session manager管理
            val (sid, conv) = sessMgr.getOrCreateSession(request.session_id, liteRtTools)
            sessionId = sid
            conversation = conv
        }
        
        if (conversation == null) {
            Log.e("ModelApiService", "Failed to create conversation")
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "无法创建会话，模型可能未初始化，请重启模型"))
            return
        }
        
        val currentRequestCount = requestCounter.incrementAndGet()
        
        val lastMessage = request.messages.last()
        val contents = parseMessageContent(lastMessage.content)

        if (request.stream) {
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                try {
                    val buffer = StringBuilder()
                    var tokenCount = 0
                    var hasToolCalls = false
                    var toolCallsList: List<ChatResponse.ToolCall>? = null
                    var messageCount = 0
                    var totalChars = 0
                    val maxChars = if (overrideMaxTokens != null && overrideMaxTokens > 0) overrideMaxTokens * 3 else Int.MAX_VALUE
                    var truncated = false
                    
                    conversation.sendMessageAsync(com.google.ai.edge.litertlm.Contents.of(contents)).collect { message ->
                        messageCount++
                        
                        if (!hasToolCalls && message.toolCalls.isNotEmpty()) {
                            hasToolCalls = true
                            toolCallsList = message.toolCalls.mapIndexed { index, tc ->
                                ChatResponse.ToolCall(
                                    id = "call_${System.currentTimeMillis()}_${index}",
                                    type = "function",
                                    function = ChatResponse.FunctionCall(
                                        name = tc.name,
                                        arguments = gson.toJson(tc.arguments)
                                    )
                                )
                            }
                        }
                        
                        val textContent = message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        
                        if (textContent.isNotEmpty() && totalChars < maxChars && !hasToolCalls) {
                            val remaining = maxChars - totalChars
                            val toAdd = if (textContent.length > remaining) textContent.take(remaining) else textContent
                            buffer.append(toAdd)
                            totalChars += toAdd.length
                            tokenCount++
                            
                            if (totalChars >= maxChars) {
                                truncated = true
                                if (buffer.isNotEmpty()) {
                                    val chunk = ChatResponse(
                                        model = runningModel.name,
                                        choices = listOf(ChatResponse.Choice(
                                            delta = ChatResponse.Delta(content = buffer.toString()),
                                            finish_reason = "length"
                                        )),
                                        session_id = sessionId
                                    )
                                    write("data: ${gson.toJson(chunk)}\n\n")
                                    flush()
                                    buffer.clear()
                                }
                                return@collect
                            }
                        } else if (!hasToolCalls) {
                            buffer.append(textContent)
                            tokenCount++
                        }
                        
                        if (tokenCount >= 3 || buffer.length > 50) {
                            val chunk = ChatResponse(
                                model = runningModel.name,
                                choices = listOf(ChatResponse.Choice(
                                    delta = ChatResponse.Delta(content = buffer.toString())
                                )),
                                session_id = sessionId
                            )
                            write("data: ${gson.toJson(chunk)}\n\n")
                            flush()
                            buffer.clear()
                            tokenCount = 0
                        }
                    }
                    
                    if (buffer.isNotEmpty()) {
                        val chunk = ChatResponse(
                            model = runningModel.name,
                            choices = listOf(ChatResponse.Choice(
                                delta = ChatResponse.Delta(content = buffer.toString())
                            )),
                            session_id = sessionId
                        )
                        write("data: ${gson.toJson(chunk)}\n\n")
                        flush()
                    }
                        
                    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
                    val benchmark = conversation.getBenchmarkInfo()
                    val usage = buildUsage(benchmark, sessionId, sessMgr)
                    
                    // 只有有状态请求才更新session统计
                    if (!isStateless && sessionId != null) {
                        sessMgr.updateSessionStats(sessionId, messageCount, "assistant", usage.total_tokens)
                    }
                    
                    // 发送最终chunk，包含tool_calls和finish_reason
                    val finishReason = when {
                        hasToolCalls -> "tool_calls"
                        truncated -> "length"
                        else -> "stop"
                    }
                    val finalChunk = ChatResponse(
                        model = runningModel.name,
                        choices = listOf(ChatResponse.Choice(
                            delta = ChatResponse.Delta(
                                tool_calls = toolCallsList
                            ),
                            finish_reason = finishReason
                        )),
                        session_id = sessionId,
                        usage = usage
                    )
                    write("data: ${gson.toJson(finalChunk)}\n\n")
                    write("data: [DONE]\n\n")
                    flush()
                    
                    engineManager.updateBenchmarkInfo(benchmark, currentRequestCount)
                    
                    // 更新全局 stats
                    val elapsed = System.currentTimeMillis() - startTime
                    updateStats(elapsed, usage.total_tokens)
                    
                    // 无状态请求完成后关闭临时conversation
                    if (isStateless) {
                        try { conversation.close() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    val errorMsg = e.message ?: ""
                    if (errorMsg.contains("roles must alternate") || errorMsg.contains("Conversation roles")) {
                        Log.w("ModelApiService", "Session corrupted (roles not alternating), resetting session")
                        sessMgr.resetSession(sessionId)
                        try {
                            write("data: ${gson.toJson(mapOf("error" to "会话已重置，请重试"))}\n\n")
                            flush()
                        } catch (_: Exception) {}
                    } else if (errorMsg.contains("not alive") || errorMsg.contains("Conversation is not alive")) {
                        Log.w("ModelApiService", "Session conversation not alive, resetting session")
                        sessMgr.resetSession(sessionId)
                        try {
                            write("data: ${gson.toJson(mapOf("error" to "会话已重置，请重试"))}\n\n")
                            flush()
                        } catch (_: Exception) {}
                    } else if (errorMsg.contains("Cannot write") || errorMsg.contains("ClosedChannel") || errorMsg.contains("Broken pipe") || e is java.io.IOException) {
                        // 客户端已断开连接（如 Activity 被销毁），不需要重置服务端会话
                        Log.w("ModelApiService", "Client disconnected during stream for session: $sessionId")
                    } else if (e is kotlinx.coroutines.CancellationException) {
                        // 协程被取消（请求超时或服务关闭），不重置会话
                        Log.w("ModelApiService", "Stream cancelled for session: $sessionId")
                        throw e
                    } else {
                        Log.e("ModelApiService", "Stream error for session: $sessionId: $errorMsg")
                        try {
                            write("data: ${gson.toJson(mapOf("error" to errorMsg))}\n\n")
                            flush()
                        } catch (_: Exception) {}
                    }
                }
            }
        } else {
            try {
                val message = conversation.sendMessage(com.google.ai.edge.litertlm.Contents.of(contents))
                
                @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
                val benchmark = conversation.getBenchmarkInfo()
                val usage = buildUsage(benchmark, sessionId, sessMgr)
                
                // 只有有状态请求才更新session统计
                if (!isStateless && sessionId != null) {
                    sessMgr.updateSessionStats(sessionId, 1, "assistant", usage.total_tokens)
                }
                engineManager.updateBenchmarkInfo(benchmark, currentRequestCount)
                
                // 更新全局 stats
                val elapsed = System.currentTimeMillis() - startTime
                updateStats(elapsed, usage.total_tokens)
                
                val toolCalls = message.toolCalls
                if (toolCalls.isNotEmpty()) {
                    // OpenAI标准格式的tool_calls
                    val toolCallsList = toolCalls.mapIndexed { index, tc ->
                        ChatResponse.ToolCall(
                            id = "call_${System.currentTimeMillis()}_${index}",
                            type = "function",
                            function = ChatResponse.FunctionCall(
                                name = tc.name,
                                arguments = gson.toJson(tc.arguments)
                            )
                        )
                    }
                    
                    call.respond(ChatResponse(
                        id = "chatcmpl-${System.currentTimeMillis()}",
                        `object` = "chat.completion",
                        model = runningModel.name,
                        choices = listOf(ChatResponse.Choice(
                            index = 0,
                            message = ChatResponse.MessageResponse(
                                role = "assistant",
                                content = null,
                                tool_calls = toolCallsList
                            ),
                            finish_reason = "tool_calls"
                        )),
                        session_id = sessionId,
                        usage = usage
                    ))
                    
                    // 无状态请求完成后关闭临时conversation
                    if (isStateless) {
                        try { conversation.close() } catch (_: Exception) {}
                    }
                } else {
                    var textContent = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    
                    var truncated = false
                    if (overrideMaxTokens != null && overrideMaxTokens > 0) {
                        val maxChars = overrideMaxTokens * 3
                        if (textContent.length > maxChars) {
                            textContent = textContent.take(maxChars)
                            truncated = true
                        }
                    }
                    
                    val finishReason = when {
                        truncated -> "length"
                        else -> "stop"
                    }
                    
                    call.respond(ChatResponse(
                        id = "chatcmpl-${System.currentTimeMillis()}",
                        `object` = "chat.completion",
                        model = runningModel.name,
                        choices = listOf(ChatResponse.Choice(
                            index = 0,
                            message = ChatResponse.MessageResponse(
                                role = "assistant",
                                content = textContent
                            ),
                            finish_reason = finishReason
                        )),
                        session_id = sessionId,
                        usage = usage
                    ))
                    
                    // 无状态请求完成后关闭临时conversation
                    if (isStateless) {
                        try { conversation.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: ""
                if (errorMsg.contains("roles must alternate") || errorMsg.contains("Conversation roles")) {
                    Log.w("ModelApiService", "Session corrupted (roles not alternating), resetting session")
                    sessMgr.resetSession(sessionId)
                    try { call.respond(HttpStatusCode.InternalServerError, mapOf<String, String>("error" to "会话已重置，请重试")) } catch (_: Exception) {}
                } else if (errorMsg.contains("not alive") || errorMsg.contains("Conversation is not alive")) {
                    Log.w("ModelApiService", "Session conversation not alive, resetting session")
                    sessMgr.resetSession(sessionId)
                    try { call.respond(HttpStatusCode.InternalServerError, mapOf<String, String>("error" to "会话已重置，请重试")) } catch (_: Exception) {}
                } else if (e is kotlinx.coroutines.CancellationException) {
                    Log.w("ModelApiService", "Non-stream request cancelled for session: $sessionId")
                    throw e
                } else {
                    Log.e("ModelApiService", "Non-stream error for session: $sessionId: $errorMsg")
                    try { call.respond(HttpStatusCode.InternalServerError, mapOf<String, String>("error" to errorMsg)) } catch (_: Exception) {}
                }
                return
            }
        }
    }
    
    private fun updateStats(latencyMs: Long, tokens: Int) {
        synchronized(statsLock) {
            totalRequests++
            totalTokens += tokens
            if (totalRequests == 1L) {
                avgLatencyMs = latencyMs.toDouble()
            } else {
                avgLatencyMs = (avgLatencyMs * (totalRequests - 1) + latencyMs) / totalRequests
            }
            maxLatencyMs = maxOf(maxLatencyMs, latencyMs)
            minLatencyMs = minOf(minLatencyMs, latencyMs)
        }
    }

    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    private fun buildUsage(
        benchmark: com.google.ai.edge.litertlm.BenchmarkInfo,
        sessionId: String?,
        sessMgr: SessionManager
    ): ChatResponse.Usage {
        val promptTokens = benchmark.lastPrefillTokenCount ?: 0
        val completionTokens = benchmark.lastDecodeTokenCount ?: 0
        val totalTokens = promptTokens + completionTokens
        val maxContextTokens = engineManager.getMaxContextSize()
        val accumulatedTokens = if (sessionId != null) sessMgr.getSessionTokens(sessionId) else 0
        val contextRemaining = maxContextTokens - accumulatedTokens - totalTokens
        
        return ChatResponse.Usage(
            prompt_tokens = promptTokens,
            completion_tokens = completionTokens,
            total_tokens = totalTokens,
            max_context_tokens = maxContextTokens,
            context_remaining = maxOf(0, contextRemaining)
        )
    }

    private fun parseMessageContent(content: ChatRequest.MessageContent): List<Content> {
        return when (content) {
            is ChatRequest.MessageContent.Text -> {
                listOf(Content.Text(content.text))
            }
            is ChatRequest.MessageContent.MultiPart -> {
                content.parts.mapNotNull { part ->
                    when (part.type) {
                        "text" -> {
                            part.text?.let { Content.Text(it) }
                        }
                        "image_url" -> {
                            val url = part.image_url?.url ?: return@mapNotNull null
                            parseImageUrl(url)
                        }
                        "input_audio" -> {
                            val audio = part.input_audio ?: return@mapNotNull null
                            try {
                                val audioBytes = Base64.getDecoder().decode(audio.data)
                                Content.AudioBytes(audioBytes)
                            } catch (e: Exception) {
                                Log.e("ModelApiService", "Failed to decode audio", e)
                                null
                            }
                        }
                        else -> null
                    }
                }
            }
        }
    }
    
    private fun parseImageUrl(url: String): Content? {
        return try {
            when {
                url.startsWith("data:image") -> {
                    val base64Data = url.substringAfter("base64,")
                    val imageBytes = Base64.getDecoder().decode(base64Data)
                    Content.ImageBytes(imageBytes)
                }
                url.startsWith("http://") || url.startsWith("https://") -> {
                    Content.ImageFile(url)
                }
                url.startsWith("/") -> {
                    Content.ImageFile(url)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("ModelApiService", "Failed to parse image URL", e)
            null
        }
    }

    private fun buildNotification(modelName: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mobile API 服务运行中")
            .setContentText("当前模型: $modelName | 端口: $serverPort")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Mobile API 服务", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "保持 Mobile API 服务在后台运行"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        engineWatchJob?.cancel()
        engineWatchJob = null
        
        try {
            unregisterReceiver(modelUpdateReceiver)
        } catch (e: Exception) {
            Log.w("ModelApiService", "Failed to unregister receiver: ${e.message}")
        }
        
        httpServer?.stop(1000, 2000)
        httpServer = null
        httpServerStarted = false
        
        sessionManager?.closeAllSessions()
        sessionManager = null
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                engineManager.stopCurrentModel()
            } catch (e: Exception) {
                Log.e("ModelApiService", "Failed to stop model: ${e.message}")
            }
        }
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null
}