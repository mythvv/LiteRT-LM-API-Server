package dev.jenny.litertlm.data

data class ModelItem(
    val id: String,
    val name: String,
    val localPath: String,
    val downloadUrl: String? = null,
    val size: Long = 0,
    val status: String = "READY",
    val preferBackend: String = "GPU",
    val createTime: Long = System.currentTimeMillis(),
    val tokensPerSecond: Double = 0.0,
    val totalTokensGenerated: Int = 0,
    val contextSize: Int = 0,
    val maxContextSize: Int = 4096,
    val requestCount: Int = 0,
    val downloadProgress: Int = 0,
    val temperature: Double = 1.0,
    val topK: Int = 40,
    val topP: Double = 0.95,
    val systemPrompt: String = "",
    val enableSpeculativeDecoding: Boolean = false,
    val supportImage: Boolean = false,
    val supportAudio: Boolean = false,
    val originalUri: String? = null
) {
    companion object {
        val DEFAULT_TEMPERATURE = 1.0
        val DEFAULT_TOP_K = 40
        val DEFAULT_TOP_P = 0.95
        val DEFAULT_MAX_CONTEXT = 4096
        val DEFAULT_SYSTEM_PROMPT = ""
    }
    
    fun withDefaults(): ModelItem = copy(
        temperature = DEFAULT_TEMPERATURE,
        topK = DEFAULT_TOP_K,
        topP = DEFAULT_TOP_P,
        maxContextSize = DEFAULT_MAX_CONTEXT,
        systemPrompt = DEFAULT_SYSTEM_PROMPT,
        enableSpeculativeDecoding = false,
        supportImage = false,
        supportAudio = false
    )
}