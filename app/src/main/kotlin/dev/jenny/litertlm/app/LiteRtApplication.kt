package dev.jenny.litertlm.app

import android.app.Application
import dev.jenny.litertlm.app.data.ModelManager
import dev.jenny.litertlm.engine.ModelEngineManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class LiteRtApplication : Application() {
    companion object {
        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
        
        val gson: Gson by lazy { Gson() }
        
        private val _ipAddress = MutableStateFlow("127.0.0.1")
        val ipAddress: StateFlow<String> = _ipAddress
        
        fun refreshIpAddress() {
            try {
                val ip = NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .find { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    ?.hostAddress ?: "127.0.0.1"
                _ipAddress.value = ip
            } catch (e: Exception) {
                _ipAddress.value = "127.0.0.1"
            }
        }
    }
    
    private var ipRefreshJob: Job? = null
    private val appScope = CoroutineScope(Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()
        ModelManager.init(this)
        ModelEngineManager.init(this)
        refreshIpAddress()
        
        ipRefreshJob = appScope.launch {
            while (true) {
                delay(30000)
                refreshIpAddress()
            }
        }
    }
}