package dev.jenny.litertlm.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.jenny.litertlm.data.ModelItem
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class SharedPrefsRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().setLenient().create()
    private val key = "model_list"
    
    private val _modelsFlow = MutableStateFlow<List<ModelItem>>(emptyList())
    
    init {
        loadModels()
    }
    
    private fun loadModels() {
        val json = prefs.getString(key, "[]") ?: "[]"
        val models = parseModels(json)
        _modelsFlow.value = models
    }
    
    private fun parseModels(json: String): List<ModelItem> {
        return try {
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val rawList: List<Map<String, Any?>> = gson.fromJson(json, type)
            rawList.map { map ->
                ModelItem(
                    id = (map["id"] as? String) ?: "",
                    name = (map["name"] as? String) ?: "",
                    localPath = (map["localPath"] as? String) ?: "",
                    downloadUrl = map["downloadUrl"] as? String,
                    size = (map["size"] as? Number)?.toLong() ?: 0,
                    status = (map["status"] as? String) ?: "READY",
                    preferBackend = (map["preferBackend"] as? String) ?: "GPU",
                    createTime = (map["createTime"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    tokensPerSecond = (map["tokensPerSecond"] as? Number)?.toDouble() ?: 0.0,
                    totalTokensGenerated = (map["totalTokensGenerated"] as? Number)?.toInt() ?: 0,
                    contextSize = (map["contextSize"] as? Number)?.toInt() ?: 0,
                    maxContextSize = (map["maxContextSize"] as? Number)?.toInt() ?: 4096,
                    requestCount = (map["requestCount"] as? Number)?.toInt() ?: 0,
                    downloadProgress = (map["downloadProgress"] as? Number)?.toInt() ?: 0,
                    temperature = (map["temperature"] as? Number)?.toDouble() ?: 1.0,
                    topK = (map["topK"] as? Number)?.toInt() ?: 40,
                    topP = (map["topP"] as? Number)?.toDouble() ?: 0.95,
                    systemPrompt = (map["systemPrompt"] as? String) ?: "",
                    enableSpeculativeDecoding = (map["enableSpeculativeDecoding"] as? Boolean) ?: false,
                    supportImage = (map["supportImage"] as? Boolean) ?: false,
                    supportAudio = (map["supportAudio"] as? Boolean) ?: false,
                    originalUri = map["originalUri"] as? String
                )
            }
        } catch (e: Exception) {
            Log.e("SharedPrefsRepository", "Failed to parse models: ${e.message}")
            prefs.edit().remove(key).apply()
            emptyList()
        }
    }
    
    private fun saveModels(models: List<ModelItem>) {
        val json = gson.toJson(models)
        prefs.edit().putString(key, json).apply()
        _modelsFlow.value = models
    }
    
    fun getAllModelsFlow(): Flow<List<ModelItem>> = _modelsFlow
    
    suspend fun getAllModels(): List<ModelItem> = withContext(Dispatchers.IO) {
        _modelsFlow.value
    }
    
    suspend fun getModelById(id: String): ModelItem? = withContext(Dispatchers.IO) {
        _modelsFlow.value.find { it.id == id }
    }
    
    suspend fun getRunningModel(): ModelItem? = withContext(Dispatchers.IO) {
        _modelsFlow.value.find { it.status == "RUNNING" }
    }
    
    suspend fun addModel(model: ModelItem) = withContext(Dispatchers.IO) {
        val models = _modelsFlow.value.toMutableList()
        models.add(model)
        saveModels(models)
    }
    
    suspend fun updateModel(updated: ModelItem) = withContext(Dispatchers.IO) {
        val models = _modelsFlow.value.toMutableList()
        val index = models.indexOfFirst { it.id == updated.id }
        if (index != -1) {
            models[index] = updated
            saveModels(models)
        }
    }
    
    suspend fun deleteModel(id: String) = withContext(Dispatchers.IO) {
        val models = _modelsFlow.value.filter { it.id != id }
        saveModels(models)
    }
}