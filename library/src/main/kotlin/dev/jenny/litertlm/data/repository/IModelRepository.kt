package dev.jenny.litertlm.data.repository

import dev.jenny.litertlm.data.ModelItem
import kotlinx.coroutines.flow.Flow

interface IModelRepository {
    fun getAllModelsFlow(): Flow<List<ModelItem>>
    suspend fun getAllModels(): List<ModelItem>
    suspend fun getModelById(id: String): ModelItem?
    suspend fun getRunningModel(): ModelItem?
    suspend fun addModel(model: ModelItem)
    suspend fun updateModel(model: ModelItem)
    suspend fun deleteModel(id: String)
}