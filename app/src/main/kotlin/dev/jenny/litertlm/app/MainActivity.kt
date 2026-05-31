package dev.jenny.litertlm.app

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.widget.ImageView
import dev.jenny.litertlm.models.ModelItem
import dev.jenny.litertlm.app.data.ModelManager
import dev.jenny.litertlm.app.databinding.ActivityMainBinding
import dev.jenny.litertlm.app.manager.DownloadWorker
import dev.jenny.litertlm.engine.ModelEngineManager
import dev.jenny.litertlm.app.ui.ModelAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var modelAdapter: ModelAdapter
    private val modelManager by lazy { ModelManager.getInstance(this) }
    private val engineManager by lazy { ModelEngineManager.getInstance(this) }
    private val REQUEST_PERMISSION = 1001
    private val REQUEST_PICK_FILE = 1002
    private val REQUEST_PICK_DIR = 1003
    
    private val serverPrefs by lazy { getSharedPreferences("server_config", Context.MODE_PRIVATE) }
    private var serverPort: Int = 8080
    
    @Volatile
    private var isRequestingPermission = false
    
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        
        if (permissions.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    isRequestingPermission = false
                    initializeApp()
                } else {
                    requestManageStoragePermission()
                }
            } else {
                isRequestingPermission = false
                initializeApp()
            }
            return@registerForActivityResult
        }
        
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestManageStoragePermission()
            } else {
                isRequestingPermission = false
                initializeApp()
            }
        } else {
            val deniedPermissions = permissions.filter { !it.value }.keys
            Toast.makeText(this, "需要存储权限才能读取模型文件\n被拒绝: ${deniedPermissions.joinToString()}", Toast.LENGTH_LONG).show()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestManageStoragePermission()
            } else {
                isRequestingPermission = false
                finish()
            }
        }
    }
    
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        isRequestingPermission = false
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                initializeApp()
            } else {
                Toast.makeText(this, "需要所有文件访问权限才能读取模型文件", Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            initializeApp()
        }
    }
    
    data class FoundModel(val uri: Uri, val name: String, val size: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverPort = serverPrefs.getInt("port", 8080)

        requestPermissionsAndInitialize()
    }
    
    private fun showAppSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_settings, null)
        
        val etPort = dialogView.findViewById<android.widget.EditText>(R.id.etPort)
        etPort.setText(serverPort.toString())
        
        MaterialAlertDialogBuilder(this)
            .setTitle("应用设置")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val newPort = etPort.text.toString().toIntOrNull()
                
                if (newPort != null && newPort >= 1024 && newPort <= 65535) {
                    serverPrefs.edit().putInt("port", newPort).apply()
                    serverPort = newPort
                    Toast.makeText(this, "端口已修改，重启应用生效", Toast.LENGTH_LONG).show()
                }
                
                resetStatusPanel()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showModelSettingsDialog(model: ModelItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_model_settings, null)
        
        val spinnerContext = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerContext)
        val seekTemperature = dialogView.findViewById<android.widget.SeekBar>(R.id.seekTemperature)
        val tvTemperature = dialogView.findViewById<android.widget.TextView>(R.id.tvTemperature)
        val seekTopK = dialogView.findViewById<android.widget.SeekBar>(R.id.seekTopK)
        val tvTopK = dialogView.findViewById<android.widget.TextView>(R.id.tvTopK)
        val seekTopP = dialogView.findViewById<android.widget.SeekBar>(R.id.seekTopP)
        val tvTopP = dialogView.findViewById<android.widget.TextView>(R.id.tvTopP)
        val etSystemPrompt = dialogView.findViewById<android.widget.EditText>(R.id.etSystemPrompt)
        val cbSpeculativeDecoding = dialogView.findViewById<android.widget.CheckBox>(R.id.cbSpeculativeDecoding)
        val cbSupportImage = dialogView.findViewById<android.widget.CheckBox>(R.id.cbSupportImage)
        val cbSupportAudio = dialogView.findViewById<android.widget.CheckBox>(R.id.cbSupportAudio)
        
        val contextOptions = arrayOf("4K (4096)", "8K (8192)", "16K (16384)", "32K (32768)", "64K (65536)")
        val contextValues = intArrayOf(4096, 8192, 16384, 32768, 65536)
        val contextAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, contextOptions)
        contextAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerContext.adapter = contextAdapter
        val contextIndex = contextValues.indexOfFirst { it == model.maxContextSize }.coerceAtLeast(0)
        spinnerContext.setSelection(contextIndex)
        
        seekTemperature.progress = (model.temperature * 100).toInt()
        tvTemperature.text = String.format("%.2f", model.temperature)
        seekTemperature.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                tvTemperature.text = String.format("%.2f", progress / 100.0)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        seekTopK.progress = model.topK
        tvTopK.text = model.topK.toString()
        seekTopK.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                tvTopK.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        seekTopP.progress = (model.topP * 100).toInt()
        tvTopP.text = String.format("%.2f", model.topP)
        seekTopP.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                tvTopP.text = String.format("%.2f", progress / 100.0)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        etSystemPrompt.setText(model.systemPrompt)
        etSystemPrompt.hint = "例如：你是一个有帮助的AI助手。"
        cbSpeculativeDecoding.isChecked = model.enableSpeculativeDecoding
        cbSupportImage.isChecked = model.supportImage
        cbSupportAudio.isChecked = model.supportAudio
        
        MaterialAlertDialogBuilder(this)
            .setTitle("模型设置 - ${model.name}")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val updated = model.copy(
                    maxContextSize = contextValues[spinnerContext.selectedItemPosition],
                    temperature = seekTemperature.progress / 100.0,
                    topK = seekTopK.progress.coerceAtLeast(1),
                    topP = seekTopP.progress / 100.0,
                    systemPrompt = etSystemPrompt.text.toString(),
                    enableSpeculativeDecoding = cbSpeculativeDecoding.isChecked,
                    supportImage = cbSupportImage.isChecked,
                    supportAudio = cbSupportAudio.isChecked
                )
                lifecycleScope.launch {
                    modelManager.updateModel(updated)
                    Toast.makeText(this@MainActivity, "设置已保存", Toast.LENGTH_SHORT).show()
                    refreshModelList()
                }
            }
            .setNeutralButton("恢复默认") { _, _ ->
                val defaultModel = model.withDefaults()
                lifecycleScope.launch {
                    modelManager.updateModel(defaultModel)
                    Toast.makeText(this@MainActivity, "已恢复默认设置", Toast.LENGTH_SHORT).show()
                    refreshModelList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSystemResources() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val totalMem = memInfo.totalMem / (1024 * 1024)
        val availMem = memInfo.availMem / (1024 * 1024)
        val usedMem = totalMem - availMem
        val memPercent = (usedMem * 100 / totalMem)
        
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory() / (1024 * 1024)
        val usedHeap = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        
        val statFs = StatFs(Environment.getDataDirectory().path)
        val totalStorage = statFs.totalBytes / (1024 * 1024 * 1024)
        val availStorage = statFs.availableBytes / (1024 * 1024 * 1024)
        
        val runningModel = engineManager.runningModel
        val modelInfo = if (runningModel != null) {
            val modelSize = File(runningModel.localPath).length() / (1024.0 * 1024.0 * 1024.0)
            "运行中模型: ${runningModel.name}\n模型大小: %.2f GB\n上下文: ${runningModel.maxContextSize} tokens\n\n".format(modelSize)
        } else {
            "运行中模型: 无\n\n"
        }
        
        val message = buildString {
            append(modelInfo)
            append("=== 系统内存 ===\n")
            append("总内存: ${totalMem} MB\n")
            append("已用: ${usedMem} MB ($memPercent%)\n")
            append("可用: ${availMem} MB\n\n")
            append("=== 应用内存 ===\n")
            append("最大堆内存: ${maxHeap} MB\n")
            append("已用堆内存: ${usedHeap} MB\n\n")
            append("=== 存储空间 ===\n")
            append("总容量: ${totalStorage} GB\n")
            append("可用: ${availStorage} GB")
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("系统资源")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun refreshModelList() {
        lifecycleScope.launch {
            val models = modelManager.getAllModels()
            modelAdapter.submitList(models)
        }
    }

    private fun updateStatusPanel(model: ModelItem) {
        binding.tvApiAddress.text = "http://${LiteRtApplication.ipAddress.value}:$serverPort/v1/chat/completions"
        binding.tvRequestCount.text = model.requestCount.toString()
        binding.tvTokensPerSecond.text = if (model.tokensPerSecond > 0) {
            "${model.tokensPerSecond.toInt()}"
        } else {
            "--"
        }
        binding.tvRunningModel.text = model.name
        binding.layoutContextInfo.visibility = android.view.View.VISIBLE
        binding.tvContextSize.text = "${model.contextSize}/${model.maxContextSize}"
        val progress = (model.contextSize * 100 / model.maxContextSize).coerceIn(0, 100)
        binding.progressContext.progress = progress
    }

    private fun resetStatusPanel() {
        binding.tvApiAddress.text = "http://${LiteRtApplication.ipAddress.value}:$serverPort/v1/chat/completions"
        binding.tvRequestCount.text = "0"
        binding.tvTokensPerSecond.text = "--"
        binding.tvRunningModel.text = "无"
        binding.layoutContextInfo.visibility = android.view.View.GONE
    }
    
    private fun testApi() {
        val runningModel = engineManager.runningModel
        if (runningModel == null) {
            Toast.makeText(this, "请先启动一个模型", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client = LiteRtApplication.httpClient
                    val json = """{"messages":[{"role":"user","content":"Hello"}],"stream":false}"""
                    val body = json.toRequestBody("application/json".toMediaType())
                    val url = "http://${LiteRtApplication.ipAddress.value}:$serverPort/v1/chat/completions"
                    val request = Request.Builder()
                        .url(url)
                        .post(body)
                        .build()
                    
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val respBody = response.body?.string() ?: "无响应"
                        withContext(Dispatchers.Main) {
                            MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle("API测试成功")
                                .setMessage(respBody.take(500))
                                .show()
                        }
                    } else {
                        val errorBody = response.body?.string() ?: "无错误信息"
                        Log.e("MainActivity", "API error: ${response.code} - $errorBody")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "请求失败: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "API test exception", e)
                val errorMsg = when (e) {
                    is java.net.SocketTimeoutException -> "请求超时"
                    is java.io.IOException -> "网络错误: ${e.message}"
                    else -> e.message ?: "未知错误"
                }
                Toast.makeText(this@MainActivity, "请求失败: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun retryDownload(model: ModelItem) {
        val url = model.downloadUrl ?: return
        val savePath = model.localPath
        lifecycleScope.launch {
            modelManager.updateModel(model.copy(status = "DOWNLOADING", downloadProgress = 0))
            refreshModelList()
        }
        
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putString("modelId", model.id)
                    .putString("downloadUrl", url)
                    .putString("savePath", savePath)
                    .build()
            )
            .build()
        androidx.work.WorkManager.getInstance(this).enqueue(workRequest)
    }

    private fun showUrlDownloadDialog() {
        val urlInput = android.widget.EditText(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("输入模型下载链接")
            .setView(urlInput)
            .setPositiveButton("下载") { _, _ ->
                val url = urlInput.text.toString()
                if (!url.endsWith(".litertlm", ignoreCase = true)) {
                    Toast.makeText(this, "请输入.litertlm格式模型链接", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val fileName = url.substringAfterLast("/")
                val saveDir = File(filesDir, "models")
                saveDir.mkdirs()
                val savePath = File(saveDir, fileName).absolutePath
                val modelId = UUID.randomUUID().toString()
                lifecycleScope.launch {
                    val model = ModelItem(
                        id = modelId,
                        name = fileName.removeSuffix(".litertlm"),
                        localPath = savePath,
                        downloadUrl = url,
                        size = 0,
                        status = "DOWNLOADING"
                    )
                    modelManager.addModel(model)
                    val workRequest = androidx.work.OneTimeWorkRequestBuilder<DownloadWorker>()
                        .setInputData(
                            androidx.work.Data.Builder()
                                .putString("modelId", modelId)
                                .putString("downloadUrl", url)
                                .putString("savePath", savePath)
                                .build()
                        )
                        .build()
                    androidx.work.WorkManager.getInstance(this@MainActivity).enqueue(workRequest)
                    Toast.makeText(this@MainActivity, "开始下载", Toast.LENGTH_SHORT).show()
                    refreshModelList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickLocalModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PICK_FILE)
    }
    
    private fun getRealPathFromUri(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        
        if (uri.scheme == "content") {
            try {
                val projection = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATA)
                        val path = cursor.getString(columnIndex)
                        if (!path.isNullOrEmpty() && File(path).exists()) {
                            return path
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to get path from MediaStore: ${e.message}")
            }
            
            try {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                
                if (docId.startsWith("primary:")) {
                    val path = "${Environment.getExternalStorageDirectory()}/${docId.substring(8)}"
                    if (File(path).exists()) {
                        return path
                    }
                }
                
                if (docId.startsWith("/storage/") || docId.startsWith("/sdcard/")) {
                    if (File(docId).exists()) {
                        return docId
                    }
                }
                
                val segments = docId.split(":")
                if (segments.size == 2) {
                    val possiblePath = "/storage/emulated/0/${segments[1]}"
                    if (File(possiblePath).exists()) {
                        return possiblePath
                    }
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to parse document ID: ${e.message}")
            }
        }
        
        return null
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PICK_FILE -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri ->
                        var fileName = "model.litertlm"
                        var fileSize = 0L
                        
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                                if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
                            }
                        }
                        
                        if (!fileName.endsWith(".litertlm", ignoreCase = true)) {
                            Toast.makeText(this, "请选择.litertlm格式模型", Toast.LENGTH_SHORT).show()
                            return
                        }
                        
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        
                        val realPath = getRealPathFromUri(uri)
                        
                        lifecycleScope.launch {
                            val model = ModelItem(
                                id = UUID.randomUUID().toString(),
                                name = fileName.removeSuffix(".litertlm"),
                                localPath = realPath ?: uri.toString(),
                                size = fileSize,
                                originalUri = uri.toString()
                            )
                            modelManager.addModel(model)
                            Toast.makeText(this@MainActivity, "模型添加成功", Toast.LENGTH_SHORT).show()
                            refreshModelList()
                        }
                    }
                }
            }
            REQUEST_PICK_DIR -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { treeUri ->
                        contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        scanDirectoryForModels(treeUri)
                    }
                }
            }
        }
    }
    
    private fun scanDirectoryForModels(treeUri: Uri) {
        lifecycleScope.launch {
            val foundModels = mutableListOf<FoundModel>()
            
            withContext(Dispatchers.IO) {
                val treeDoc = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                )
                val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                )
                
                contentResolver.query(childrenUri, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_SIZE,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null)?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_SIZE)
                    
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex)
                        val size = cursor.getLong(sizeIndex)
                        
                        if (name.endsWith(".litertlm", ignoreCase = true)) {
                            val docId = cursor.getString(idIndex)
                            val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            foundModels.add(FoundModel(docUri, name, size))
                        }
                    }
                }
            }
            
            if (foundModels.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "该目录下未发现.litertlm模型", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            
            val existingNames = modelManager.getAllModels().map { it.name }.toSet()
            val newModels = foundModels.filter { it.name.removeSuffix(".litertlm") !in existingNames }
            
            if (newModels.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "所有模型已添加过", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            
            val modelNames = newModels.map { "${it.name.removeSuffix(".litertlm")} (${formatSize(it.size)})" }.toTypedArray()
            
            val selectedModels = mutableListOf<FoundModel>()
            
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("发现 ${newModels.size} 个模型")
                    .setMultiChoiceItems(modelNames, null) { _, which, isChecked ->
                        if (isChecked) {
                            selectedModels.add(newModels[which])
                        } else {
                            selectedModels.remove(newModels[which])
                        }
                    }
                    .setPositiveButton("添加") { _, _ ->
                        if (selectedModels.isEmpty()) {
                            Toast.makeText(this@MainActivity, "未选择任何模型", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                selectedModels.forEach { model ->
                                    val realPath = getRealPathFromUri(model.uri)
                                    
                                    val newItem = ModelItem(
                                        id = UUID.randomUUID().toString(),
                                        name = model.name.removeSuffix(".litertlm"),
                                        localPath = realPath ?: model.uri.toString(),
                                        size = model.size,
                                        originalUri = model.uri.toString()
                                    )
                                    modelManager.addModel(newItem)
                                }
                            }
                            
                            Toast.makeText(this@MainActivity, "已添加 ${selectedModels.size} 个模型", Toast.LENGTH_SHORT).show()
                            refreshModelList()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun scanModelDirectory() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        startActivityForResult(intent, REQUEST_PICK_DIR)
    }
    
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    private fun requestPermissionsAndInitialize() {
        if (isRequestingPermission) {
            return
        }
        
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                initializeApp()
                return
            }
        }
        
        requestStoragePermissions()
    }
    
    private fun requestStoragePermissions() {
        if (isRequestingPermission) {
            return
        }
        
        isRequestingPermission = true
        
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            }
            else -> {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }
        }
        
        val needRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        
        if (needRequest.isEmpty()) {
            isRequestingPermission = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestManageStoragePermission()
            } else {
                initializeApp()
            }
        } else {
            storagePermissionLauncher.launch(needRequest)
        }
    }
    
    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                initializeApp()
            } else {
                isRequestingPermission = true
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    manageStorageLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to open manage storage settings", e)
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageStorageLauncher.launch(intent)
                }
            }
        } else {
            initializeApp()
        }
    }
    
    private fun initializeApp() {
        lifecycleScope.launch {
            modelManager.refreshModels()
            modelManager.getAllModels().forEach { model ->
                if (model.status == "DOWNLOADING") {
                    modelManager.updateModel(model.copy(status = "READY", downloadProgress = 0))
                }
            }
            
            val runningModel = engineManager.runningModel
            val currentEngine = engineManager.getCurrentEngine()
            
            if (runningModel != null && currentEngine != null) {
                val models = modelManager.getAllModels()
                models.find { it.id == runningModel.id }?.let { storedModel ->
                    if (storedModel.status != "RUNNING") {
                        modelManager.updateModel(storedModel.copy(status = "RUNNING"))
                    }
                }
                updateStatusPanel(runningModel)
            } else if (runningModel != null && currentEngine == null) {
                Log.w("MainActivity", "Engine lost, resetting model state")
                modelManager.getAllModels().find { it.id == runningModel.id }?.let { model ->
                    modelManager.updateModel(model.copy(status = "READY"))
                }
                engineManager.stopCurrentModel()
                resetStatusPanel()
            } else {
                modelManager.getAllModels().find { it.status == "RUNNING" }?.let { model ->
                    modelManager.updateModel(model.copy(status = "READY"))
                }
            }
        }

        modelAdapter = ModelAdapter(
            onStartClick = { model ->
                lifecycleScope.launch {
                    val progressDialog = android.app.ProgressDialog(this@MainActivity).apply {
                        setMessage("正在启动模型...")
                        setCancelable(false)
                        show()
                    }
                    val result = engineManager.startModel(model)
                    progressDialog.dismiss()
                    if (result.isSuccess) {
                        Toast.makeText(this@MainActivity, "模型启动成功", Toast.LENGTH_SHORT).show()
                        refreshModelList()
                    } else {
                        Toast.makeText(this@MainActivity, "启动失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onStopClick = {
                lifecycleScope.launch {
                    engineManager.cancelInference()
                    engineManager.stopCurrentModel()
                    refreshModelList()
                    resetStatusPanel()
                }
            },
            onDeleteClick = { model ->
                lifecycleScope.launch {
                    modelManager.deleteModel(model.id)
                    refreshModelList()
                }
            },
            onBackendChange = { model, backend ->
                lifecycleScope.launch {
                    if (backend == "RETRY" && model.downloadUrl != null) {
                        retryDownload(model)
                    } else {
                        modelManager.updateModel(model.copy(preferBackend = backend))
                        refreshModelList()
                    }
                }
            },
            onSettingsClick = { model ->
                showModelSettingsDialog(model)
            },
            onChatClick = { model ->
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("modelName", model.name)
                startActivity(intent)
            }
        )
        binding.rvModels.adapter = modelAdapter
        binding.rvModels.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            engineManager.runningModelState.collect { runningModel ->
                if (runningModel != null) {
                    updateStatusPanel(runningModel)
                }
            }
        }

        lifecycleScope.launch {
            modelManager.getAllModelsFlow().collect { models ->
                modelAdapter.submitList(models)
                val running = models.find { it.status == "RUNNING" }
                if (running == null) {
                    resetStatusPanel()
                }
            }
        }

        binding.btnAddModel.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("添加模型")
                .setItems(arrayOf("URL下载", "本地导入", "扫描目录")) { _, which ->
                    when (which) {
                        0 -> showUrlDownloadDialog()
                        1 -> pickLocalModel()
                        2 -> scanModelDirectory()
                    }
                }
                .show()
        }

        binding.btnTestApi.setOnClickListener {
            testApi()
        }

        binding.btnQrCode.setOnClickListener {
            showQrCodeDialog()
        }

        binding.btnCopyAddress.setOnClickListener {
            val address = "http://${LiteRtApplication.ipAddress.value}:$serverPort/v1/chat/completions"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("API地址", address)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "已复制: $address", Toast.LENGTH_SHORT).show()
        }
        
        binding.btnCopyAddress.setOnLongClickListener {
            showAppSettingsDialog()
            true
        }

        binding.btnCheckResource.setOnClickListener {
            showSystemResources()
        }
        
        binding.btnAppSettings.setOnClickListener {
            showAppSettingsDialog()
        }
        
        startApiService()
    }

    private fun showQrCodeDialog() {
        val baseUrl = "http://${LiteRtApplication.ipAddress.value}:$serverPort"
        val address = "$baseUrl/v1/chat/completions"
        
        val qrBitmap = generateQrCode(baseUrl, 512)
        
        if (qrBitmap != null) {
            val imageView = ImageView(this).apply {
                setImageBitmap(qrBitmap)
                setPadding(32, 32, 32, 32)
            }
            
            MaterialAlertDialogBuilder(this)
                .setTitle("API地址二维码")
                .setMessage("Base URL: $baseUrl\n\n使用手机扫描添加到API配置")
                .setView(imageView)
                .setPositiveButton("复制地址") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("API地址", baseUrl)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "已复制: $baseUrl", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("关闭", null)
                .show()
        } else {
            Toast.makeText(this, "生成二维码失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun generateQrCode(content: String, size: Int): Bitmap? {
        return try {
            val hints = mapOf<com.google.zxing.EncodeHintType, Any>(
                com.google.zxing.EncodeHintType.MARGIN to 1,
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val bitMatrix = com.google.zxing.qrcode.QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::modelAdapter.isInitialized && !isRequestingPermission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    requestPermissionsAndInitialize()
                }
            }
        }
    }

    private fun startApiService() {
        try {
            val intent = Intent(this, ModelApiService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "服务启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}