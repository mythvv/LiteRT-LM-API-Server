package dev.jenny.litertlm.data

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.JsonAdapter
import java.lang.reflect.Type

data class ChatRequest(
    val messages: List<Message>?,
    val model: String = "",
    val stream: Boolean = false,
    val session_id: String? = null,
    val tools: List<Tool>? = null,
    val temperature: Double? = null,
    val top_p: Double? = null,
    val max_tokens: Int? = null,
    val n: Int? = null
) {
    data class Message(
        val role: String,
        @JsonAdapter(ChatRequestContentSerializer::class)
        val content: MessageContent? = null
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

class ChatRequestContentSerializer : JsonSerializer<ChatRequest.MessageContent>, JsonDeserializer<ChatRequest.MessageContent> {
    override fun serialize(
        src: ChatRequest.MessageContent,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        return when (src) {
            is ChatRequest.MessageContent.Text -> context.serialize(src.text)
            is ChatRequest.MessageContent.MultiPart -> context.serialize(src.parts)
        }
    }
    
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): ChatRequest.MessageContent {
        return when {
            json.isJsonPrimitive -> {
                ChatRequest.MessageContent.Text(json.asString)
            }
            json.isJsonArray -> {
                val parts = json.asJsonArray.mapNotNull { element ->
                    if (element.isJsonObject) {
                        val obj = element.asJsonObject
                        val type = obj.get("type")?.asString ?: return@mapNotNull null
                        when (type) {
                            "text" -> {
                                val text = obj.get("text")?.asString ?: ""
                                ChatRequest.ContentPart(type = "text", text = text)
                            }
                            "image_url" -> {
                                val url = obj.getAsJsonObject("image_url")?.get("url")?.asString ?: ""
                                ChatRequest.ContentPart(type = "image_url", image_url = ChatRequest.ImageUrl(url))
                            }
                            "input_audio" -> {
                                val audioObj = obj.getAsJsonObject("input_audio")
                                val data = audioObj?.get("data")?.asString ?: ""
                                val format = audioObj?.get("format")?.asString ?: "wav"
                                ChatRequest.ContentPart(type = "input_audio", input_audio = ChatRequest.InputAudio(data, format))
                            }
                            else -> null
                        }
                    } else null
                }
                ChatRequest.MessageContent.MultiPart(parts)
            }
            else -> ChatRequest.MessageContent.Text("")
        }
    }
}
