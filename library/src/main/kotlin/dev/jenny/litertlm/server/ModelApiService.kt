package dev.jenny.litertlm.server

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
import dev.jenny.litertlm.data.ChatRequest
import dev.jenny.litertlm.data.ChatResponse
import dev.jenny.litertlm.data.HealthResponse
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
import dev.jenny.litertlm.data.ModelItem
import dev.jenny.litertlm.data.ModelManager
import dev.jenny.litertlm.data.ModelsResponse
import dev.jenny.litertlm.data.ModelData as ApiModelData
import dev.jenny.litertlm.data.SessionInfo
import dev.jenny.litertlm.data.SessionsResponse
import dev.jenny.litertlm.data.StatsResponse
import dev.jenny.litertlm.manager.ModelEngineManager
import dev.jenny.litertlm.manager.SessionConfig
import dev.jenny.litertlm.manager.SessionManager
import dev.jenny.litertlm.manager.SessionMeta
import dev.jenny.litertlm.tools.DynamicToolSet
import com.google.ai.edge.litertlm.Message
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
    private val gson = Gson()
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
                "dev.jenny.litertlm.MODEL_STARTED" -> {
                    val modelName = intent.getStringExtra("modelName") ?: "Unknown"
                    updateNotification(modelName)
                }
                "dev.jenny.litertlm.MODEL_STOPPED" -> {
                    sessionManager?.closeAllSessions()
                }
            }
        }
    }

    private fun updateNotification(modelName: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LiteRT API Server")
            .setContentText("Model: $modelName | Port: $serverPort")
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
        val initialModel = engineManager.runningModel?.name ?: "No model"
        startForeground(NOTIFICATION_ID, buildNotification(initialModel))

        sessionManager = SessionManager()

        CoroutineScope(Dispatchers.IO).launch { startHttpServer() }

        engineWatchJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(5000)
                val model = engineManager.runningModel
                val engine = engineManager.getCurrentEngine()
                if (model != null && engine == null) {
                    Log.w("ModelApiService", "Engine lost while model is running: ${model.name}")
                    withContext(Dispatchers.Main) {
                        updateNotification("Engine lost - restart model")
                    }
                }
            }
        }

        val filter = IntentFilter()
        filter.addAction("dev.jenny.litertlm.MODEL_STARTED")
        filter.addAction("dev.jenny.litertlm.MODEL_STOPPED")
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
        if (httpServerStarted) return
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
                        "version" to "2.0.3",
                        "model" to (runningModel?.name ?: "none"),
                        "endpoints" to listOf(
                            mapOf("method" to "GET", "path" to "/v1/", "description" to "API overview"),
                            mapOf("method" to "GET", "path" to "/v1/models", "description" to "Available models"),
                            mapOf("method" to "POST", "path" to "/v1/chat/completions", "description" to "Chat completions (OpenAI compatible)"),
                            mapOf("method" to "GET", "path" to "/v1/sessions", "description" to "Active sessions"),
                            mapOf("method" to "DELETE", "path" to "/v1/sessions/{sessionId}", "description" to "Delete session"),
                            mapOf("method" to "GET", "path" to "/v1/stats", "description" to "Performance stats"),
                            mapOf("method" to "GET", "path" to "/v1/health", "description" to "Health check")
                        )
                    ))
                }

                get("/v1/models") {
                    val models = modelManager.getAllModels()
                    val response = ModelsResponse(
                        data = models.map { model ->
                            ApiModelData(id = model.id, created = model.createTime / 1000)
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

                get("/v1/sessions") {
                    val sessMgr = sessionManager
                    if (sessMgr == null) {
                        call.respond(SessionsResponse(count = 0, sessions = emptyList()))
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

                delete("/v1/sessions/{sessionId}") {
                    val sessionId = call.parameters["sessionId"]
                    val sessMgr = sessionManager
                    if (sessionId != null && sessMgr != null) {
                        engineManager.closeConversation(sessionId)
                        sessMgr.resetSession(sessionId)
                        call.respond(mapOf("status" to "deleted", "session_id" to sessionId))
                    } else {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid session ID"))
                    }
                }

                get("/v1/stats") {
                    val stats = synchronized(statsLock) {
                        StatsResponse(
                            total_requests = totalRequests,
                            total_tokens = totalTokens,
                            avg_latency_ms = avgLatencyMs,
                            max_latency_ms = maxLatencyMs,
                            min_latency_ms = if (minLatencyMs == Long.MAX_VALUE) 0 else minLatencyMs,
                            active_sessions = sessionManager?.getSessionCount() ?: 0,
                            current_model = engineManager.runningModel?.name,
                            backend = null,
                            tokens_per_second = null
                        )
                    }
                    call.respond(stats)
                }

                post("/v1/chat/completions") {
                    val runningModel = engineManager.runningModel
                    if (runningModel == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf(
                            "error" to mapOf("message" to "No model loaded", "type" to "server_error")
                        ))
                        return@post
                    }

                    val request = try {
                        gson.fromJson(call.receiveText(), ChatRequest::class.java)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf(
                            "error" to mapOf("message" to "Invalid request: ${e.message}", "type" to "invalid_request_error")
                        ))
                        return@post
                    }

                    if (request.messages.isNullOrEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf(
                            "error" to mapOf("message" to "messages is required", "type" to "invalid_request_error")
                        ))
                        return@post
                    }

                    val sessMgr = sessionManager ?: run {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Server not initialized"))
                        return@post
                    }

                    if (!inferenceSemaphore.tryAcquire()) {
                        call.respond(HttpStatusCode.TooManyRequests, mapOf(
                            "error" to mapOf("message" to "Max concurrent requests reached", "type" to "rate_limit_error")
                        ))
                        return@post
                    }

                    try {
                        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                            processChatRequest(call, request, runningModel, sessMgr)
                        } ?: run {
                            call.respond(HttpStatusCode.GatewayTimeout, mapOf(
                                "error" to mapOf("message" to "Request timeout", "type" to "timeout")
                            ))
                        }
                    } finally {
                        inferenceSemaphore.release()
                    }
                }
            }
        }.start(wait = false)
    }

    private suspend fun processChatRequest(
        call: ApplicationCall,
        request: ChatRequest,
        runningModel: ModelItem,
        sessMgr: SessionManager
    ) {
        val messages = request.messages ?: emptyList()
        val startTime = System.currentTimeMillis()

        val liteRtTools = request.tools?.let {
            DynamicToolSet.fromChatRequest(it).toToolProviders().map { tool(it) }
        }

        val systemMessage = messages.find { it.role == "system" }
        val systemPrompt = when (systemMessage?.content) {
            is ChatRequest.MessageContent.Text -> (systemMessage!!.content as ChatRequest.MessageContent.Text).text
            is ChatRequest.MessageContent.MultiPart ->
                (systemMessage!!.content as ChatRequest.MessageContent.MultiPart).parts
                    .filter { it.type == "text" }.joinToString(" ") { it.text ?: "" }
            null -> null
            else -> null
        }

        val overrideTemperature = request.temperature
        val overrideTopP = request.top_p
        val overrideMaxTokens = request.max_tokens

        val isStateless = request.session_id == null

        // Get or create session metadata
        val sessionMeta = sessMgr.getOrCreateSession(
            request.session_id,
            SessionConfig(
                temperature = overrideTemperature ?: 0.0,
                topP = overrideTopP ?: 1.0,
                systemInstruction = systemPrompt
            )
        )
        val sessionId = if (isStateless) null else sessionMeta.id

        // Get or create cached Conversation
        val conversation = engineManager.getOrCreateConversation(
            sessionId = sessionMeta.id,
            tools = liteRtTools,
            temperature = overrideTemperature,
            topP = overrideTopP,
            maxTokens = overrideMaxTokens,
            systemPrompt = systemPrompt
        )

        if (conversation == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf(
                "error" to "Failed to create conversation. Model may not be initialized."
            ))
            return
        }

        val currentRequestCount = requestCounter.incrementAndGet()

        // Check if current request contains tool result messages (tool calling round-trip)
        val toolMessages = messages.filter { it.role == "tool" }
        if (!isStateless && toolMessages.isNotEmpty() && sessionId != null) {
            handleToolResultMessages(
                call, request, runningModel, sessMgr, sessionId, sessionMeta,
                toolMessages, messages, conversation, startTime, currentRequestCount
            )
            return
        }

        // Build full context contents from session history + current messages
        val allContents = buildAllContents(messages, sessionMeta, isStateless)

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

                    write("data: ${gson.toJson(ChatResponse(
                        id = "chatcmpl-${System.currentTimeMillis()}",
                        `object` = "chat.completion.chunk",
                        model = runningModel.name,
                        choices = listOf(ChatResponse.Choice(
                            index = 0,
                            delta = ChatResponse.Delta(role = "assistant"),
                            finish_reason = null
                        )),
                        session_id = sessionId
                    ))}\n\n")
                    flush()

                    conversation.sendMessageAsync(com.google.ai.edge.litertlm.Contents.of(allContents)).collect { message ->
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
                            return@collect
                        }

                        val text = message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }

                        if (text.isNotEmpty()) {
                            if (maxChars != Int.MAX_VALUE && totalChars + text.length > maxChars) {
                                val remaining = maxChars - totalChars
                                if (remaining > 0) {
                                    buffer.append(text, 0, remaining)
                                    totalChars += remaining
                                }
                                truncated = true
                            } else {
                                buffer.append(text)
                                totalChars += text.length
                            }

                            val chunk = ChatResponse(
                                id = "chatcmpl-${System.currentTimeMillis()}",
                                `object` = "chat.completion.chunk",
                                model = runningModel.name,
                                choices = listOf(ChatResponse.Choice(
                                    index = 0,
                                    delta = ChatResponse.Delta(content = buffer.toString()),
                                    finish_reason = null
                                )),
                                session_id = sessionId
                            )
                            write("data: ${gson.toJson(chunk)}\n\n")
                            flush()
                            buffer.clear()
                            tokenCount += text.split(" ").size
                        }
                    }

                    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
                    val benchmark = conversation.getBenchmarkInfo()
                    val usage = buildUsage(benchmark, sessionId, sessMgr)

                    if (hasToolCalls && toolCallsList != null) {
                        val toolChunk = ChatResponse(
                            id = "chatcmpl-${System.currentTimeMillis()}",
                            `object` = "chat.completion.chunk",
                            model = runningModel.name,
                            choices = listOf(ChatResponse.Choice(
                                index = 0,
                                delta = ChatResponse.Delta(
                                    tool_calls = toolCallsList
                                ),
                                finish_reason = "tool_calls"
                            )),
                            session_id = sessionId,
                            usage = usage
                        )
                        write("data: ${gson.toJson(toolChunk)}\n\n")
                        flush()

                        // Record tool call in session history for streaming path
                        if (sessionId != null) {
                            val lastUserMsg = messages.lastOrNull { it.role != "system" }
                            if (lastUserMsg != null) {
                                val userText = when (lastUserMsg.content) {
                                    is ChatRequest.MessageContent.Text -> lastUserMsg.content!!.text
                                    is ChatRequest.MessageContent.MultiPart ->
                                        lastUserMsg.content!!.parts.filter { it.type == "text" }.joinToString(" ") { it.text ?: "" }
                                    null -> ""
                                    else -> ""
                                }
                                if (userText.isNotEmpty()) {
                                    sessMgr.addUserMessage(sessionId, userText)
                                }
                            }
                            val toolCallSummary = toolCallsList!!.joinToString("; ") { "${it.function.name}(${it.function.arguments})" }
                            sessMgr.addAssistantMessage(sessionId, "[tool_calls:$toolCallSummary]")
                        }
                    } else {
                        val finishReason = when {
                            truncated -> "length"
                            else -> "stop"
                        }
                        val finalChunk = ChatResponse(
                            id = "chatcmpl-${System.currentTimeMillis()}",
                            `object` = "chat.completion.chunk",
                            model = runningModel.name,
                            choices = listOf(ChatResponse.Choice(
                                index = 0,
                                delta = ChatResponse.Delta(),
                                finish_reason = finishReason
                            )),
                            session_id = sessionId,
                            usage = usage
                        )
                        write("data: ${gson.toJson(finalChunk)}\n\n")
                        flush()
                    }

                    // Update session metadata
                    if (sessionId != null) {
                        sessMgr.updateTokens(sessionId, usage.total_tokens)
                    }
                    engineManager.updateBenchmarkInfo(benchmark, currentRequestCount)
                    val elapsed = System.currentTimeMillis() - startTime
                    updateStats(elapsed, usage.total_tokens)

                    write("data: [DONE]\n\n")
                    flush()
                } catch (e: Exception) {
                    val errorMsg = e.message ?: "Unknown error"
                    if (errorMsg.contains("roles must alternate") || errorMsg.contains("Conversation roles")) {
                        Log.w("ModelApiService", "Role alternation error, resetting session: $sessionId")
                        sessMgr.resetSession(sessionId ?: "")
                        engineManager.closeConversation(sessionId ?: "")
                        try {
                            write("data: ${gson.toJson(mapOf("error" to "Session reset, please retry"))}\n\n")
                            flush()
                        } catch (_: Exception) {}
                    } else if (errorMsg.contains("not alive") || errorMsg.contains("Conversation is not alive")) {
                        Log.w("ModelApiService", "Session conversation not alive, resetting")
                        sessMgr.resetSession(sessionId ?: "")
                        engineManager.closeConversation(sessionId ?: "")
                        try {
                            write("data: ${gson.toJson(mapOf("error" to "Session reset, please retry"))}\n\n")
                            flush()
                        } catch (_: Exception) {}
                    } else if (errorMsg.contains("Cannot write") || errorMsg.contains("ClosedChannel") || errorMsg.contains("Broken pipe") || e is java.io.IOException) {
                        Log.w("ModelApiService", "Client disconnected during stream for session: $sessionId")
                    } else if (e is kotlinx.coroutines.CancellationException) {
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
                val message = conversation.sendMessage(com.google.ai.edge.litertlm.Contents.of(allContents))

                @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
                val benchmark = conversation.getBenchmarkInfo()
                val usage = buildUsage(benchmark, sessionId, sessMgr)

                // Update session metadata
                if (sessionId != null) {
                    sessMgr.updateTokens(sessionId, usage.total_tokens)
                }
                engineManager.updateBenchmarkInfo(benchmark, currentRequestCount)

                val elapsed = System.currentTimeMillis() - startTime
                updateStats(elapsed, usage.total_tokens)

                val toolCalls = message.toolCalls
                if (toolCalls.isNotEmpty()) {
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

                    // Record tool call round-trip in session history
                    if (sessionId != null) {
                        val lastUserMsg = messages.lastOrNull { it.role != "system" }
                        if (lastUserMsg != null) {
                            val userText = when (lastUserMsg.content) {
                                is ChatRequest.MessageContent.Text -> lastUserMsg.content!!.text
                                is ChatRequest.MessageContent.MultiPart ->
                                    lastUserMsg.content!!.parts.filter { it.type == "text" }.joinToString(" ") { it.text ?: "" }
                                null -> ""
                                else -> ""
                            }
                            if (userText.isNotEmpty()) {
                                sessMgr.addUserMessage(sessionId, userText)
                            }
                        }
                        // Record assistant tool_calls as a marker in history
                        val toolCallSummary = toolCallsList.joinToString("; ") { "${it.function.name}(${it.function.arguments})" }
                        sessMgr.addAssistantMessage(sessionId, "[tool_calls:$toolCallSummary]")
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

                    // Record messages in session history
                    if (sessionId != null) {
                        val lastUserMsg = messages.lastOrNull { it.role != "system" }
                        if (lastUserMsg != null) {
                            val userText = when (lastUserMsg.content) {
                                is ChatRequest.MessageContent.Text -> lastUserMsg.content!!.text
                                is ChatRequest.MessageContent.MultiPart ->
                                    lastUserMsg.content!!.parts.filter { it.type == "text" }.joinToString(" ") { it.text ?: "" }
                                null -> ""
                                else -> ""
                            }
                            if (userText.isNotEmpty()) {
                                sessMgr.addUserMessage(sessionId, userText)
                            }
                            sessMgr.addAssistantMessage(sessionId, textContent)
                        }
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
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                Log.e("ModelApiService", "Non-stream error: $errorMsg")
                if (errorMsg.contains("roles must alternate") || errorMsg.contains("Conversation roles")) {
                    sessMgr.resetSession(sessionId ?: "")
                    engineManager.closeConversation(sessionId ?: "")
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Session reset, please retry"))
                } else if (errorMsg.contains("not alive") || errorMsg.contains("Conversation is not alive")) {
                    sessMgr.resetSession(sessionId ?: "")
                    engineManager.closeConversation(sessionId ?: "")
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Session reset, please retry"))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to errorMsg))
                }
                return
            }
        }
    }

    /**
     * Handle tool result messages for stateful tool calling round-trip.
     * Uses Content.ToolResponse for structured tool results instead of
     * rebuilding full history via buildAllContents.
     */
    private suspend fun handleToolResultMessages(
        call: ApplicationCall,
        request: ChatRequest,
        runningModel: ModelItem,
        sessMgr: SessionManager,
        sessionId: String,
        sessionMeta: SessionMeta,
        toolMessages: List<ChatRequest.Message>,
        allMessages: List<ChatRequest.Message>,
        conversation: com.google.ai.edge.litertlm.Conversation,
        startTime: Long,
        currentRequestCount: Int
    ) {
        try {
            // Build tool result contents using Content.ToolResponse
            val toolContents = mutableListOf<Content>()

            // Find the assistant message with tool_calls that preceded the tool results
            val assistantToolCallMsg = allMessages.lastOrNull {
                it.role == "assistant" && it.tool_calls != null
            }

            for (toolMsg in toolMessages) {
                val toolResultText = when (val c = toolMsg.content) {
                    is ChatRequest.MessageContent.Text -> c.text
                    is ChatRequest.MessageContent.MultiPart ->
                        c.parts.filter { it.type == "text" }.joinToString(" ") { it.text ?: "" }
                    null -> ""
                    else -> ""
                }

                // Match tool_call_id to find the function name from the preceding assistant tool_calls
                val toolCallId = toolMsg.tool_call_id
                val functionName = assistantToolCallMsg?.tool_calls
                    ?.find { it.id == toolCallId }?.function?.name ?: toolCallId ?: "unknown"

                // Use Content.ToolResponse for structured tool result
                if (toolResultText.isNotEmpty()) {
                    toolContents.add(Content.ToolResponse(functionName, toolResultText))
                }

                // Record tool result in session history
                sessMgr.addToolResultMessage(sessionId, toolCallId ?: "", toolResultText)
            }

            if (toolContents.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Empty tool result contents"))
                return
            }

            // For stateful mode: send only tool result contents to the existing conversation
            // The conversation already has the full context from previous turns
            val message = conversation.sendMessageAsync(Contents.of(toolContents))

            // Collect the model's response after processing tool results
            val responseContents = StringBuilder()
            var responseTokenCount = 0
            var hasMoreToolCalls = false
            var nextToolCallsList: List<ChatResponse.ToolCall>? = null

            var benchmark: BenchmarkInfo? = null
            message.collect { msg ->
                if (msg.contents.contents.isNotEmpty()) {
                    for (content in msg.contents.contents) {
                        if (content is Content.Text) {
                            responseContents.append(content.text)
                        }
                    }
                }
                responseTokenCount++

                if (msg.toolCalls.isNotEmpty()) {
                    hasMoreToolCalls = true
                    nextToolCallsList = msg.toolCalls.mapIndexed { index, tc ->
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

                @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
                benchmark = conversation.getBenchmarkInfo()
            }

            val usage = buildUsage(benchmark!!, sessionId, sessMgr)
            val elapsed = System.currentTimeMillis() - startTime

            if (sessionId != null) {
                sessMgr.updateTokens(sessionId, usage.total_tokens)
            }
            engineManager.updateBenchmarkInfo(benchmark, currentRequestCount)
            updateStats(elapsed, usage.total_tokens)

            // Record assistant response in session history
            val responseText = responseContents.toString()
            if (sessionId != null && responseText.isNotEmpty()) {
                sessMgr.addAssistantMessage(sessionId, responseText)
            }

            // Build response
            if (hasMoreToolCalls && nextToolCallsList != null) {
                // Model wants to make more tool calls
                call.respond(ChatResponse(
                    id = "chatcmpl-${System.currentTimeMillis()}",
                    `object` = "chat.completion",
                    model = runningModel.name,
                    choices = listOf(ChatResponse.Choice(
                        index = 0,
                        message = ChatResponse.MessageResponse(
                            role = "assistant",
                            content = null,
                            tool_calls = nextToolCallsList
                        ),
                        finish_reason = "tool_calls"
                    )),
                    session_id = sessionId,
                    usage = usage
                ))
            } else {
                // Normal text response after tool processing
                call.respond(ChatResponse(
                    id = "chatcmpl-${System.currentTimeMillis()}",
                    `object` = "chat.completion",
                    model = runningModel.name,
                    choices = listOf(ChatResponse.Choice(
                        index = 0,
                        message = ChatResponse.MessageResponse(
                            role = "assistant",
                            content = responseText
                        ),
                        finish_reason = "stop"
                    )),
                    session_id = sessionId,
                    usage = usage
                ))
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            if (errorMsg.contains("roles must alternate") || errorMsg.contains("Conversation roles")) {
                Log.w("ModelApiService", "Role alternation error in tool result handling, resetting session: $sessionId")
                sessMgr.resetSession(sessionId)
                engineManager.closeConversation(sessionId)
            }
            Log.e("ModelApiService", "Error handling tool result messages: $errorMsg", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to errorMsg))
        }
    }

    private fun buildAllContents(
        messages: List<ChatRequest.Message>,
        sessionMeta: SessionMeta?,
        isStateless: Boolean
    ): List<Content> {
        val allContents = mutableListOf<Content>()

        // Rebuild full context from session history for stateful requests
        if (!isStateless && sessionMeta != null && sessionMeta.history.isNotEmpty()) {
            for (msg in sessionMeta.history) {
                when (msg.role) {
                    "user" -> allContents.add(Content.Text(msg.textContent))
                    "assistant" -> allContents.add(Content.Text(msg.textContent))
                }
            }
        }

        // Add current request messages (skip system)
        for (msg in messages) {
            if (msg.role == "system") continue
            // Skip assistant messages with null content (e.g. tool_calls-only responses)
            if (msg.role == "assistant" && msg.content == null) continue
            // Handle tool role: embed tool result as text
            if (msg.role == "tool") {
                val toolText = when (val c = msg.content) {
                    is ChatRequest.MessageContent.Text -> c.text
                    is ChatRequest.MessageContent.MultiPart ->
                        c.parts.filter { it.type == "text" }.joinToString(" ") { it.text ?: "" }
                    null -> ""
                    else -> ""
                }
                if (toolText.isNotEmpty()) {
                    allContents.add(Content.Text("Tool result: $toolText"))
                }
                continue
            }
            val parsed = parseMessageContent(msg.content)
            allContents.addAll(parsed)
        }

        return allContents
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

    private fun parseMessageContent(content: ChatRequest.MessageContent?): List<Content> {
        if (content == null) return emptyList()
        return when (content) {
            is ChatRequest.MessageContent.Text -> listOf(Content.Text(content.text))
            is ChatRequest.MessageContent.MultiPart -> content.parts.mapNotNull { part ->
                when (part.type) {
                    "text" -> part.text?.let { Content.Text(it) }
                    "image_url" -> part.image_url?.url?.let { parseImageUrl(it) }
                    "input_audio" -> part.input_audio?.let { audio ->
                        try {
                            val audioBytes = Base64.getDecoder().decode(audio.data)
                            Content.AudioBytes(audioBytes)
                        } catch (_: Exception) { null }
                    }
                    else -> null
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
                url.startsWith("http://") || url.startsWith("https://") -> Content.ImageFile(url)
                url.startsWith("/") -> Content.ImageFile(url)
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
            .setContentTitle("LiteRT API Server")
            .setContentText("Model: $modelName | Port: $serverPort")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "LiteRT API Server", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Keep LiteRT API Server running in background"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        engineWatchJob?.cancel()
        engineWatchJob = null

        try { unregisterReceiver(modelUpdateReceiver) } catch (e: Exception) {
            Log.w("ModelApiService", "Failed to unregister receiver: ${e.message}")
        }

        httpServer?.stop(1000, 2000)
        httpServer = null
        httpServerStarted = false

        sessionManager?.closeAllSessions()
        sessionManager = null

        engineManager.closeAllConversations()

        CoroutineScope(Dispatchers.IO).launch {
            try { engineManager.stopCurrentModel() } catch (e: Exception) {
                Log.e("ModelApiService", "Failed to stop model: ${e.message}")
            }
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null
}
