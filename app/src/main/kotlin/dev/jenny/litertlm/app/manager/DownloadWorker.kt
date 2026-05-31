package dev.jenny.litertlm.app.manager

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.jenny.litertlm.app.LiteRtApplication
import dev.jenny.litertlm.app.data.ModelManager
import okhttp3.Request
import java.io.File

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val modelId = inputData.getString("modelId") ?: return Result.failure()
        val url = inputData.getString("downloadUrl") ?: return Result.failure()
        val savePath = inputData.getString("savePath") ?: return Result.failure()
        val modelManager = ModelManager.getInstance(applicationContext)

        return try {
            val client = LiteRtApplication.httpClient
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("DownloadWorker", "Download failed: ${response.code}")
                throw Exception("HTTP ${response.code}")
            }
            val file = File(savePath)
            response.body?.byteStream()?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val model = modelManager.getAllModels().find { it.id == modelId } ?: return Result.failure()
            modelManager.updateModel(model.copy(status = "READY", size = file.length()))
            Result.success()
        } catch (e: Exception) {
            Log.e("DownloadWorker", "Download failed: ${e.message}", e)
            File(savePath).delete()
            val model = modelManager.getAllModels().find { it.id == modelId } ?: return Result.failure()
            modelManager.updateModel(model.copy(status = "DOWNLOAD_FAILED"))
            Result.failure()
        }
    }
}
