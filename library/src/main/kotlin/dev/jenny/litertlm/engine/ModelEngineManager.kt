@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package dev.jenny.litertlm.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.util.Log
import dev.jenny.litertlm.models.ModelItem
import dev.jenny.litertlm.models.ModelManager
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
                } else {
                    ExperimentalFlags.enableSpeculativeDecoding = false
                }
                
                val backend = when (model.preferBackend) {
                    "CPU" -> Backend.CPU()
                    "NPU" -> {
                        Os.setenv("ADSP_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir, true)
                        Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
                    }
                    else -> Backend.GPU()
                }
                val maxTokens = model.maxContextSize
                
                var modelPath = model.localPath
                
                if (modelPath.startsWith("content://")) {
                    try {
                        val uri = Uri.parse(modelPath)
                        val fileName = model.name + ".litertlm"
                        val tempFile = File(context.filesDir, "models/$fileName")
                        tempFile.parentFile?.mkdirs()
                        
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        modelPath = tempFile.absolutePath
                        tempModelFile = tempFile
                    } catch (e: Exception) {
                        Log.e("ModelEngineManager", "Failed to copy content URI", e)
                        result = Result.failure(Exception("无法读取模型文件: ${e.message}"))
                        return@withLock
                    }
                }
                
                if (!modelPath.startsWith("/") || !File(modelPath).canRead()) {
                    Log.w("ModelEngineManager", "Cannot read file: $modelPath")
                    result = Result.failure(Exception("无法读取模型文件\n请前往设置开启「所有文件访问权限」"))
                    return@withLock
                }
                
                ExperimentalFlags.enableBenchmark = true
                
                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = if (model.supportImage) Backend.GPU() else null,
                    audioBackend = if (model.supportAudio) Backend.CPU() else null,
                    cacheDir = context.cacheDir.path,
                    maxNumTokens = maxTokens
                )
                
                var engine: Engine? = null
                var initError: Exception? = null
                try {
                    engine = Engine(engineConfig)
                    engine.initialize()
                } catch (e: Exception) {
                    initError = e
                    Log.e("ModelEngineManager", "Engine initialization failed: ${e.message}", e)
                    // Don't call close() on uninitialized engine - it throws another exception
                }
                
                if (initError != null) {
                    try {
                        engine?.close()
                    } catch (closeEx: Exception) {
                        Log.w("ModelEngineManager", "Error closing engine: ${closeEx.message}")
                    }
                    result = Result.failure(Exception("模型初始化失败: ${initError.message}"))
                    return@withLock
                }
                
                synchronized(stateLock) {
                    currentEngine = engine
                    val runningModelItem = model.copy(status = "RUNNING")
                    _runningModel = runningModelItem
                    _runningModelState.value = runningModelItem
                }
                
                modelToUpdate = model.copy(status = "RUNNING")
                
                val intent = Intent("dev.jenny.litertlm.MODEL_STARTED")
                intent.putExtra("modelName", model.name)
                intent.setPackage(context.packageName)
                context.sendBroadcast(intent)
                
                result = Result.success(Unit)
            } catch (e: Exception) {
                Log.e("ModelEngineManager", "Failed to start model", e)
                result = Result.failure(e)
            }
        }
        
        modelToUpdate?.let { modelManager.updateModel(it) }
        return@withContext result ?: Result.failure(Exception("Unknown error"))
    }

    suspend fun stopCurrentModel() = withContext(Dispatchers.IO) {
        var modelToUpdate: ModelItem? = null
        lock.withLock {
            synchronized(stateLock) {
                modelToUpdate = _runningModel?.copy(
                    status = "READY",
                    tokensPerSecond = 0.0,
                    contextSize = 0
                )
            }
            stopCurrentModelInternal()
        }
        modelToUpdate?.let { modelManager.updateModel(it) }
    }
    
    private fun stopCurrentModelInternal() {
        synchronized(stateLock) {
            try {
                currentEngine?.close()
            } catch (e: Exception) {
                Log.w("ModelEngineManager", "Error closing engine: ${e.message}")
            }
            currentEngine = null
            _runningModelState.value = null
            _runningModel = null
            _benchmarkInfo.value = null
        }
        
        tempModelFile?.let { file ->
            if (file.exists()) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    Log.w("ModelEngineManager", "Failed to delete temp file: ${e.message}")
                }
            }
            tempModelFile = null
        }
        
        val intent = Intent("dev.jenny.litertlm.MODEL_STOPPED")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
    
    fun createConversation(setAsCurrent: Boolean = true, tools: List<ToolProvider>? = null,
                           overrideTemperature: Double? = null, overrideTopP: Double? = null,
                           overrideTopK: Int? = null, overrideSystemPrompt: String? = null): Conversation? {
        synchronized(stateLock) {
            val engine = currentEngine ?: return null
            val model = _runningModel ?: return null
            
            val samplerConfig = SamplerConfig(
                topK = overrideTopK ?: model.topK,
                topP = overrideTopP ?: model.topP,
                temperature = overrideTemperature ?: model.temperature,
                seed = 0
            )
            
            val systemText = overrideSystemPrompt ?: model.systemPrompt
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
            return conversation
        }
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