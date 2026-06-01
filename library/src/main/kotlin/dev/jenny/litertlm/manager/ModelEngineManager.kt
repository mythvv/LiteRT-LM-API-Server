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
                val builder = EngineConfig.Builder(modelFile.absolutePath)

                val backends = mutableListOf<Backend>()
                if (model.enableNpu) {
                    backends.add(Backend.ACCELERATOR)
                }
                backends.add(Backend.CPU)
                builder.setBackends(backends)

                if (model.enableSpeculativeDecoding) {
                    builder.setSpeculativeDecodingEnabled(true)
                }

                val engineConfig = builder.build()
                val engine = Engine.createEngine(engineConfig)

                currentEngine = engine

                modelToUpdate = model.copy(isRunning = true)
                _runningModel = modelToUpdate
                _runningModelState.value = modelToUpdate

                result = Result.success(Unit)
                Log.d("ModelEngineManager", "Model started: ${model.name}")

                context.sendBroadcast(Intent("dev.jenny.litertlm.MODEL_STARTED"))
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
            val updated = oldModel.copy(isRunning = false)
            _runningModel = null
            _runningModelState.value = null
            modelManager.updateModel(updated)
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
        when {
            model.filePath.isNotEmpty() -> File(model.filePath)

            model.url.isNotEmpty() -> {
                val fileName = model.url.substringAfterLast("/").substringBefore("?")
                val targetFile = File(context.filesDir, "models/$fileName")
                if (!targetFile.exists()) {
                    throw IllegalStateException("Model file not downloaded: $fileName")
                }
                targetFile
            }

            model.assetPath.isNotEmpty() -> {
                val inputFile = context.assets.open(model.assetPath)
                val tempFile = File(context.cacheDir, "model_${System.currentTimeMillis()}.task")
                inputFile.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                tempModelFile = tempFile
                tempFile
            }

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
