package dev.jenny.litertlm.data

import android.content.Context
import dev.jenny.litertlm.data.repository.SharedPrefsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ModelManager private constructor(private val context: Context) {
    private val repository = SharedPrefsRepository(context)
    private val _modelsFlow = MutableStateFlow<List<ModelItem>>(emptyList())
    val modelsFlow: Flow<List<ModelItem>> = _modelsFlow.asStateFlow()
    private val mutex = Mutex()
    
    fun getAllModelsFlow(): Flow<List<ModelItem>> = modelsFlow
    
    suspend fun refreshModels() {
        mutex.withLock {
            _modelsFlow.value = repository.getAllModels()
        }
    }
    
    suspend fun getAllModels(): List<ModelItem> = repository.getAllModels()
    
    suspend fun getModelById(id: String): ModelItem? = repository.getModelById(id)
    
    suspend fun getRunningModel(): ModelItem? = repository.getRunningModel()
    
    suspend fun addModel(model: ModelItem) {
        mutex.withLock {
            repository.addModel(model)
            _modelsFlow.value = repository.getAllModels()
        }
    }
    
    suspend fun updateModel(updated: ModelItem) {
        mutex.withLock {
            repository.updateModel(updated)
            _modelsFlow.value = repository.getAllModels()
        }
    }
    
    suspend fun deleteModel(id: String) {
        mutex.withLock {
            repository.deleteModel(id)
            _modelsFlow.value = repository.getAllModels()
        }
    }
    
    companion object {
        @Volatile
        private var INSTANCE: ModelManager? = null
        
        fun init(context: Context) {
            if (INSTANCE == null) {
                synchronized(this) {
                    if (INSTANCE == null) {
                        INSTANCE = ModelManager(context.applicationContext)
                    }
                }
            }
        }
        
        fun getInstance(context: Context): ModelManager {
            return INSTANCE ?: synchronized(this) {
                val instance = ModelManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}