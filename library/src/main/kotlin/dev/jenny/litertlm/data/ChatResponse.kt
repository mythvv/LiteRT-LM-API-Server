package dev.jenny.litertlm.data

data class ChatResponse(
    val id: String = "ID-${System.currentTimeMillis()}",
    val `object`: String = "chat.completion.chunk",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String = "",
    val choices: List<Choice>,
    val session_id: String? = null,
    val usage: Usage? = null
) {
    data class Usage(
        val prompt_tokens: Int,
        val completion_tokens: Int,
        val total_tokens: Int,
        val max_context_tokens: Int? = null,
        val context_remaining: Int? = null
    )
    
    data class Choice(
        val index: Int = 0,
        val message: MessageResponse? = null,  // 非流式响应
        val delta: Delta? = null,              // 流式响应
        val finish_reason: String? = null
    )
    
    data class Delta(
        val role: String? = null,
        val content: String? = null,
        val tool_calls: List<ToolCall>? = null
    )
    
    data class MessageResponse(
        val role: String = "assistant",
        val content: String? = null,
        val tool_calls: List<ToolCall>? = null
    )
    
    data class ToolCall(
        val id: String = "call_${System.currentTimeMillis()}",
        val type: String = "function",
        val function: FunctionCall
    )
    
    data class FunctionCall(
        val name: String,
        val arguments: String
    )
}

// Health API 响应
data class HealthResponse(
    val status: String,
    val engine_state: String?,
    val current_model: String?,
    val uptime_ms: Long
)
