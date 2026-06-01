package dev.jenny.litertlm.tools

import android.util.Log
import dev.jenny.litertlm.data.Tool
import dev.jenny.litertlm.data.ChatRequest
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.gson.Gson

/**
 * DynamicToolSet accepts Tool definitions from either ChatRequest.Tool or Tool (Models.kt).
 * Both types have the same structure: type, function.name, function.description, function.parameters.
 */
class DynamicToolSet(
    private val tools: List<Tool>
) {
    companion object {
        private val gson = Gson()
        
        /**
         * Create DynamicToolSet from ChatRequest.Tool (legacy format)
         */
        fun fromChatRequest(tools: List<ChatRequest.Tool>): DynamicToolSet {
            return DynamicToolSet(tools.map { tool ->
                Tool(
                    type = tool.type,
                    function = dev.jenny.litertlm.data.ToolFunction(
                        name = tool.function.name,
                        description = tool.function.description,
                        parameters = tool.function.parameters
                    )
                )
            })
        }
    }
    
    fun toToolProviders(): List<OpenApiTool> {
        return tools.map { tool ->
            object : OpenApiTool {
                override fun getToolDescriptionJsonString(): String {
                    val params = tool.function.parameters ?: mapOf("type" to "object", "properties" to emptyMap<String, Any>())
                    val toolDesc = mapOf(
                        "name" to tool.function.name,
                        "description" to tool.function.description,
                        "parameters" to params
                    )
                    return gson.toJson(toolDesc)
                }
                
                override fun execute(paramsJsonString: String): String {
                    return gson.toJson(mapOf(
                        "tool_name" to tool.function.name,
                        "params" to paramsJsonString,
                        "status" to "detected"
                    ))
                }
            }
        }
    }
}