@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package dev.jenny.litertlm.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.util.Log
import dev.jenny.litertlm.data.ModelItem
import dev.jenny.litertlm.data.ModelManager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class ModelEngineManager private constructor(private val context: Context) {

    private val conversationCache = HashMap<String, Conversation>()
    @Volatile private var activeConversation: Conversation? = null

    private val lock = Mutex()
    private val stateLock = Any()
    @Volatile private var currentEngine: Engine? = null
    @Volatile private var _runningModel: ModelItem? = null
    @Volatile private var tempModelFile: File? = null

    val runningModel: ModelItem? get() = synchronized(stateLock) { _runningModel }
    private val modelManager by lazy { ModelManager.getInstance(context) }

    private val _benchmarkInfo = MutableStateFlow<BenchmarkInfo?>(null)
    val benchmarkInfo: StateFlow<BenchmarkInfo?> = _benchmarkInfo.asStateFlow()

    private val _runningModelState = MutableStateFlow<ModelItem?>(null)
    val runningModelState: StateFlow<ModelItem?> = _runningModelState.asStateFlow()

    init {
        ExperimentalFlags.enableBenchmark = true
    }

    suspend fun startModel(model: ModelItem): Result<Unit> = withContext(Dispatchers.IO) {
        var modelToUpdate: ModelItem? = null
        var result: Result<Unit>? = null

        lock.withLock {
            try {
                stopCurrentModelInternal()

                if (model.enableSpeculativeDecoding) {
                    ExperimentalFlags.enableSpeculativeDecoding = true
                }

                val modelFile = resolveModelFile(model)

                val backend = when (model.preferBackend) {
                    "CPU" -> Backend.CPU()
                    "NPU" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
                    else -> Backend.GPU()
                }

                val engineConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    visionBackend = null,
                    audioBackend = null,
                    maxNumTokens = model.maxContextSize,
                    maxNumImages = null,
                    cacheDir = null
                )
                val engine = Engine(engineConfig)

                currentEngine = engine

                modelToUpdate = model
                _runningModel = modelToUpdate
                _runningModelState.value = modelToUpdate

                result = Result.success(Unit)
                Log.d("ModelEngineManager", "Model started: ${model.name}")
            } catch (e: Exception) {
                Log.e("ModelEngineManager", "Failed to start model: ${e.message}")
                currentEngine = null
                _runningModel = null
                _runningModelState.value = null
                result = Result.failure(e)
            }
        }

        modelToUpdate?.let { modelManager.updateModel(it) }
        result!!
    }

    private suspend fun stopCurrentModelInternal() {
        closeAllConversations()

        currentEngine?.let { engine ->
            try {
                engine.close()
            } catch (e: Exception) {
                Log.w("ModelEngineManager", "Error closing engine: ${e.message}")
            }
            currentEngine = null
        }

        _runningModel?.let { oldModel ->
            _runningModel = null
            _runningModelState.value = null
            modelManager.updateModel(oldModel)
        }

        tempModelFile?.let { file ->
            if (file.exists()) {
                file.delete()
                tempModelFile = null
            }
        }
    }

    suspend fun stopCurrentModel() {
        lock.withLock {
            try {
                stopCurrentModelInternal()
                Log.d("ModelEngineManager", "Model stopped")
                context.sendBroadcast(Intent("dev.jenny.litertlm.MODEL_STOPPED"))
            } catch (e: Exception) {
                Log.e("ModelEngineManager", "Error stopping model: ${e.message}")
            }
        }
    }

    private suspend fun resolveModelFile(model: ModelItem): File = withContext(Dispatchers.IO) {
        val path = model.localPath
        when {
            path.startsWith("content://") -> {
                val uri = Uri.parse(path)
                var resolved: String? = null
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val fdPath = java.io.File("/proc/self/fd/${pfd.fd}").canonicalPath
                        if (fdPath.startsWith("/storage/") || fdPath.startsWith("/data/")) {
                            if (java.io.File(fdPath).exists()) resolved = fdPath
                        }
                    }
                } catch (_: Exception) {}

                if (resolved != null) {
                    File(resolved!!)
                } else {
                    val fileName = model.name + ".litertlm"
                    val tempFile = File(context.filesDir, "models/$fileName")
                    tempFile.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    tempModelFile = tempFile
                    tempFile
                }
            }
            path.startsWith("/") -> File(path)
            else -> throw IllegalStateException("No model source specified")
        }
    }

    fun createConversation(
        sessionId: String,
        samplerConfig: SamplerConfig,
        tools: List<ToolProvider>? = null,
        overrideSystemPrompt: String? = null
    ): Conversation? {
        val engine = synchronized(stateLock) { currentEngine } ?: return null

        synchronized(stateLock) {
            val systemText = overrideSystemPrompt ?: _runningModel?.systemPrompt ?: ""
            val systemInstruction = if (systemText.isNotEmpty()) {
                Contents.of(systemText)
            } else null

            val config = ConversationConfig(
                systemInstruction = systemInstruction,
                samplerConfig = samplerConfig,
                tools = tools ?: emptyList(),
                automaticToolCalling = false
            )

            val conversation = engine.createConversation(config)
            conversationCache[sessionId] = conversation
            activeConversation = conversation
            return conversation
        }
    }

    /**
     * Get or create a cached Conversation for the given sessionId.
     * If the cached Conversation is still alive, reuse it.
     * If dead or missing, create a new one with the provided config.
     */
    fun getOrCreateConversation(
        sessionId: String,
        tools: List<ToolProvider>? = null,
        temperature: Double? = null,
        topP: Double? = null,
        maxTokens: Int? = null,
        systemPrompt: String? = null
    ): Conversation? {
        // Check cache first
        val existing = conversationCache[sessionId]
        if (existing != null) {
            if (existing.isAlive) {
                activeConversation = existing
                return existing
            }
            // Dead conversation — clean up
            try { existing.close() } catch (_: Exception) {}
            conversationCache.remove(sessionId)
        }

        // Close any other active conversation (engine allows only one at a time)
        activeConversation?.let { oldConv ->
            if (oldConv !== existing) {
                try { oldConv.close() } catch (_: Exception) {}
                conversationCache.entries.removeIf { it.value === oldConv }
            }
        }
        activeConversation = null

        // Build sampler config from model defaults + overrides
        val model = _runningModel
        val samplerConfig = SamplerConfig(
            topK = model?.topK ?: 40,
            topP = topP ?: model?.topP ?: 0.95,
            temperature = temperature ?: model?.temperature ?: 0.8,
            seed = 0
        )

        // Create new conversation via existing createConversation
        return createConversation(
            sessionId = sessionId,
            samplerConfig = samplerConfig,
            tools = tools,
            overrideSystemPrompt = systemPrompt
        )
    }

    fun getConversation(sessionId: String): Conversation? = conversationCache[sessionId]

    fun closeConversation(sessionId: String) {
        conversationCache.remove(sessionId)?.let {
            try { it.close() } catch (_: Exception) {}
        }
        Log.d("ModelEngineManager", "closeConversation: $sessionId, remaining: ${conversationCache.size}")
    }

    fun closeAllConversations() {
        conversationCache.values.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        conversationCache.clear()
        activeConversation = null
        Log.d("ModelEngineManager", "closeAllConversations: all cleared")
    }

    fun cancelInference() {
        synchronized(stateLock) {
            try {
                currentEngine?.close()
            } catch (e: Exception) {
                Log.w("ModelEngineManager", "Error cancelling inference: ${e.message}")
            }
        }
    }

    suspend fun updateBenchmarkInfo(info: BenchmarkInfo?, requestCount: Int) {
        _benchmarkInfo.value = info

        var updated: ModelItem? = null
        synchronized(stateLock) {
            _runningModel?.let { model ->
                updated = model.copy(
                    tokensPerSecond = info?.lastDecodeTokensPerSecond ?: 0.0,
                    totalTokensGenerated = model.totalTokensGenerated + (info?.lastDecodeTokenCount ?: 0),
                    contextSize = info?.lastPrefillTokenCount ?: 0,
                    requestCount = requestCount
                )
                _runningModel = updated
                _runningModelState.value = updated
            }
        }

        updated?.let { modelManager.updateModel(it) }
    }

    fun getMaxContextSize(): Int {
        synchronized(stateLock) {
            return currentEngine?.engineConfig?.maxNumTokens ?: 4096
        }
    }

    fun getCurrentEngine(): Engine? = synchronized(stateLock) { currentEngine }

    companion object {
        @Volatile
        private var INSTANCE: ModelEngineManager? = null

        fun init(context: Context) {
            if (INSTANCE == null) {
                synchronized(this) {
                    if (INSTANCE == null) {
                        INSTANCE = ModelEngineManager(context.applicationContext)
                    }
                }
            }
        }

        fun getInstance(context: Context): ModelEngineManager {
            return INSTANCE ?: synchronized(this) {
                val instance = ModelEngineManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
