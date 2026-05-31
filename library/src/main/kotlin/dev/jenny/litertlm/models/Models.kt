package dev.jenny.litertlm.models

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.JsonAdapter
import java.lang.reflect.Type

// ===== 请求类型 =====

data class ChatCompletionRequest(
    val model: String = "",
    val messages: List<Message>,
    val temperature: Double? = 0.7,
    val top_p: Double? = 0.95,
    val top_k: Int? = 40,
    val stream: Boolean? = false,
    val max_tokens: Int? = null,
    val session_id: String? = null,
    val tools: List<Tool>? = null,
    val tool_choice: String? = null
)

data class Tool(
    val type: String = "function",
    val function: ToolFunction
)

data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>? = null
)

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction
)

data class ToolCallFunction(
    val name: String,
    val arguments: String
)

data class Message(
    val role: String,
    @JsonAdapter(MessageContentSerializer::class)
    val content: MessageContent,
    val tool_calls: List<ToolCall>? = null,
    val tool_call_id: String? = null
) {
    sealed class MessageContent {
        data class Text(val text: String) : MessageContent()
        data class MultiPart(val parts: List<ContentPart>) : MessageContent()
    }
    
    data class ContentPart(
        val type: String,
        val text: String? = null,
        val image_url: ImageUrl? = null,
        val input_audio: InputAudio? = null
    )
    
    data class ImageUrl(
        val url: String
    )
    
    data class InputAudio(
        val data: String,
        val format: String
    )
}

class MessageContentSerializer : JsonSerializer<Message.MessageContent>, JsonDeserializer<Message.MessageContent> {
    override fun serialize(
        src: Message.MessageContent,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        return when (src) {
            is Message.MessageContent.Text -> context.serialize(src.text)
            is Message.MessageContent.MultiPart -> context.serialize(src.parts)
        }
    }
    
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Message.MessageContent {
        return when {
            json.isJsonPrimitive -> {
                Message.MessageContent.Text(json.asString)
            }
            json.isJsonArray -> {
                val parts = json.asJsonArray.mapNotNull { element ->
                    if (element.isJsonObject) {
                        val obj = element.asJsonObject
                        val type = obj.get("type")?.asString ?: return@mapNotNull null
                        when (type) {
                            "text" -> {
                                val text = obj.get("text")?.asString ?: ""
                                Message.ContentPart(type = "text", text = text)
                            }
                            "image_url" -> {
                                val url = obj.getAsJsonObject("image_url")?.get("url")?.asString ?: ""
                                Message.ContentPart(type = "image_url", image_url = Message.ImageUrl(url))
                            }
                            "input_audio" -> {
                                val audioObj = obj.getAsJsonObject("input_audio")
                                val data = audioObj?.get("data")?.asString ?: ""
                                val format = audioObj?.get("format")?.asString ?: "wav"
                                Message.ContentPart(type = "input_audio", input_audio = Message.InputAudio(data, format))
                            }
                            else -> null
                        }
                    } else null
                }
                Message.MessageContent.MultiPart(parts)
            }
            else -> Message.MessageContent.Text("")
        }
    }
}

// ===== 响应类型 =====

data class ModelsResponse(
    val `object`: String = "list",
    val data: List<ModelData>
)

data class ModelData(
    val id: String,
    val `object`: String = "model",
    val created: Long,
    val owned_by: String = "local"
)

data class ErrorResponse(
    val error: ErrorDetail
)

data class ErrorDetail(
    val message: String,
    val type: String = "invalid_request_error",
    val code: String? = null
)

data class SessionsResponse(
    val count: Int,
    val sessions: List<SessionInfo>
)

data class SessionInfo(
    val id: String,
    val message_count: Int,
    val total_tokens: Int,
    val context_tokens: Int,
    val max_context_tokens: Int,
    val last_access: Long
)

data class StatsResponse(
    val total_requests: Long,
    val total_tokens: Long,
    val avg_latency_ms: Double,
    val max_latency_ms: Long,
    val min_latency_ms: Long,
    val active_sessions: Int,
    val current_model: String?,
    val backend: String?,
    val tokens_per_second: Double?
)
