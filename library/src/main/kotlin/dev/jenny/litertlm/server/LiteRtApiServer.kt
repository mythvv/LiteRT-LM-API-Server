package dev.jenny.litertlm.server

import android.util.Log
import dev.jenny.litertlm.engine.ModelEngineManager
import dev.jenny.litertlm.models.*
import dev.jenny.litertlm.session.SessionManager
import dev.jenny.litertlm.tools.DynamicToolSet
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenAI-compatible API server powered by LiteRT LM on-device inference.
 *
 * Provides endpoints:
 * - GET  /v1/models         — list loaded models
 * - POST /v1/chat/completions — chat completion (streaming & non-streaming)
 * - GET  /v1/sessions        — list active sessions
 * - DELETE /v1/sessions/{id} — reset a session
 * - GET  /v1/stats           — server statistics
 * - GET  /health             — health check
 */
class LiteRtApiServer(
    private val engineManager: ModelEngineManager,
    private val port: Int = 8080,
    private val requestTimeoutMs: Long = 120_000L,
    private val maxConcurrentRequests: Int = 2
) {
    private val gson = Gson()
    private var server: ApplicationEngine? = null
    private val requestCounter = AtomicLong(0)
    private val totalTokens = AtomicLong(0)
    private val requestSemaphore = Semaphore(maxConcurrentRequests)
    private var sessionManager: SessionManager? = null
    private val startTime = System.currentTimeMillis()

    @Volatile
    var isRunning = false
        private set

    /**
     * Start the HTTP server.
     * @param onModelUpdate Callback for persisting model state changes (optional)
     */
    fun start(onModelUpdate: ((ModelItem) -> Unit)? = null) {
        if (isRunning) {
            Log.w(TAG, "Server already running")
            return
        }

        sessionManager = SessionManager(
            maxSessions = 1,
            timeoutMs = 15 * 60 * 1000L,
            createConversation = { tools ->
                val model = engineManager.runningModel ?: return@SessionManager null
                engineManager.createConversation(
                    systemText = model.systemPrompt,
                    tools = tools
                )
            }
        )

        server = embeddedServer(Netty, port) {
            install(CORS) {
                anyHost()
                allowHeader("Content-Type")
                allowHeader("Authorization")
            }
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                    serializeNulls()
                }
            }
            install(SSE)

            configureRoutes(onModelUpdate)
        }.start(wait = false)

        isRunning = true
        Log.i(TAG, "Server started on port $port")
    }

    /** Stop the HTTP server and clean up sessions. */
    fun stop() {
        server?.stop(1000, 2000)
        server = null
        sessionManager?.closeAllSessions()
        sessionManager = null
        isRunning = false
        Log.i(TAG, "Server stopped")
    }

    private fun Application.configureRoutes(onModelUpdate: ((ModelItem) -> Unit)?) {
        routing {
            get("/health") {
                val model = engineManager.runningModel
                call.respond(HealthResponse(
                    status = if (model != null) "ok" else "no_model",
                    engine_state = if (model != null) "running" else "idle",
                    current_model = model?.name,
                    uptime_ms = System.currentTimeMillis() - startTime
                ))
            }

            get("/v1/models") {
                val model = engineManager.runningModel
                val models = if (model != null) {
                    listOf(ModelData(id = model.id, created = model.createTime / 1000))
                } else emptyList()
                call.respond(ModelsResponse(data = models))
            }

            post("/v1/chat/completions") {
                if (!requestSemaphore.tryAcquire()) {
                    call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorResponse(ErrorDetail("Too many concurrent requests"))
                    )
                    return@post
                }
                try {
                    val request = call.receive<ChatCompletionRequest>()
                    handleChatCompletion(request, onModelUpdate, call)
                } finally {
                    requestSemaphore.release()
                }
            }

            get("/v1/sessions") {
                val mgr = sessionManager ?: run {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(ErrorDetail("Server not started")))
                    return@get
                }
                val sessions = mgr.getSessionInfo(engineManager.getMaxContextSize()).map { info ->
                    SessionInfo(
                        id = info["id"] as String,
                        message_count = info["message_count"] as Int,
                        total_tokens = info["total_tokens"] as Int,
                        context_tokens = 0,
                        max_context_tokens = info["max_context_tokens"] as Int,
                        last_access = info["last_access"] as Long
                    )
                }
                call.respond(SessionsResponse(count = sessions.size, sessions = sessions))
            }

            delete("/v1/sessions/{id}") {
                val sessionId = call.parameters["id"]
                sessionManager?.resetSession(sessionId)
                call.respond(mapOf("status" to "ok", "reset" to (sessionId ?: "all")))
            }

            get("/v1/stats") {
                val model = engineManager.runningModel
                call.respond(StatsResponse(
                    total_requests = requestCounter.get(),
                    total_tokens = totalTokens.get(),
                    avg_latency_ms = 0.0,
                    max_latency_ms = 0L,
                    min_latency_ms = 0L,
                    active_sessions = sessionManager?.getSessionCount() ?: 0,
                    current_model = model?.name,
                    backend = model?.preferBackend,
                    tokens_per_second = model?.tokensPerSecond
                ))
            }
        }
    }

    private suspend fun handleChatCompletion(
        request: ChatCompletionRequest,
        onModelUpdate: ((ModelItem) -> Unit)?,
        call: ApplicationCall
    ) {
        val model = engineManager.runningModel
        if (model == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(ErrorDetail("No model loaded"))
            )
            return
        }

        val mgr = sessionManager ?: run {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(ErrorDetail("Server not started")))
            return
        }

        // Resolve tools
        val openApiTools = request.tools?.let { DynamicToolSet(it).toToolProviders() }

        // Get or create session
        val (sessionId, entry) = try {
            mgr.getOrCreateSession(request.session_id, null, openApiTools)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(ErrorDetail("Failed to create session: ${e.message}"))
            )
            return
        }

        val conversation = entry.conversation
        val isStream = request.stream ?: false
        val requestCount = requestCounter.incrementAndGet()

        try {
            // Add user messages to conversation
            val userContent = buildConversationContent(request.messages)
            conversation.addContent(userContent)

            if (isStream) {
                // SSE streaming
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    val flow = if (::conversationFlow.isInitialized) {
                        conversationFlow
                    } else {
                        conversation.predictAsync(userContent)
                    }

                    withTimeoutOrNull(requestTimeoutMs) {
                        @OptIn(ExperimentalApi::class)
                        conversation.predictStream(userContent).collect { chunk ->
                            val response = ChatResponse(
                                model = model.id,
                                session_id = sessionId,
                                choices = listOf(
                                    ChatResponse.Choice(
                                        delta = ChatResponse.Delta(content = chunk.text()),
                                        finish_reason = null
                                    )
                                )
                            )
                            write("data: ${gson.toJson(response)}\n\n")
                            flush()
                        }
                    }

                    // Send [DONE]
                    write("data: [DONE]\n\n")
                    flush()
                }
            } else {
                // Non-streaming
                val result = withTimeoutOrNull(requestTimeoutMs) {
                    @OptIn(ExperimentalApi::class)
                    withContext(Dispatchers.Default) {
                        val responseBuilder = StringBuilder()
                        conversation.predictStream(userContent).collect { chunk ->
                            responseBuilder.append(chunk.text())
                        }
                        responseBuilder.toString()
                    }
                }

                if (result != null) {
                    val response = ChatResponse(
                        model = model.id,
                        session_id = sessionId,
                        choices = listOf(
                            ChatResponse.Choice(
                                message = ChatResponse.MessageResponse(content = result),
                                finish_reason = "stop"
                            )
                        ),
                        usage = ChatResponse.Usage(
                            prompt_tokens = 0,
                            completion_tokens = 0,
                            total_tokens = 0
                        )
                    )
                    call.respond(response)
                } else {
                    call.respond(
                        HttpStatusCode.RequestTimeout,
                        ErrorResponse(ErrorDetail("Request timed out"))
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chat completion error: ${e.message}", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorDetail("Inference error: ${e.message}"))
            )
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun buildConversationContent(messages: List<Message>): Content {
        val lastUserMessage = messages.lastOrNull { it.role == "user" }
        val textContent = when (val content = lastUserMessage?.content) {
            is Message.MessageContent.Text -> content.text
            is Message.MessageContent.MultiPart -> content.parts
                .filter { it.type == "text" }
                .joinToString("\n") { it.text ?: "" }
            null -> ""
        }

        // Handle multimodal content
        val imageParts = (lastUserMessage?.content as? Message.MessageContent.MultiPart)
            ?.parts?.filter { it.type == "image_url" } ?: emptyList()

        return if (imageParts.isNotEmpty()) {
            val contents = mutableListOf<Content>()
            contents.add(Content(textContent))
            imageParts.forEach { part ->
                val url = part.image_url?.url ?: return@forEach
                when {
                    url.startsWith("data:") -> {
                        val base64Data = url.substringAfter("base64,")
                        val bytes = Base64.getDecoder().decode(base64Data)
                        contents.add(Content.ImageBytes(bytes))
                    }
                    url.startsWith("http") -> contents.add(Content.ImageFile(url))
                    url.startsWith("/") -> contents.add(Content.ImageFile(url))
                }
            }
            Contents.of(contents)
        } else {
            Content(textContent)
        }
    }

    companion object {
        private const val TAG = "LiteRtApiServer"
    }
}
