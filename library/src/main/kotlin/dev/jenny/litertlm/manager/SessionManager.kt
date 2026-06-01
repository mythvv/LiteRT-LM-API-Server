package dev.jenny.litertlm.manager

import android.util.Log
import com.google.ai.edge.litertlm.Content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

data class SessionConfig(
    val temperature: Double,
    val topP: Double,
    val systemInstruction: String?
)

data class StoredMessage(
    val role: String,
    val textContent: String
)

data class SessionMeta(
    val id: String,
    var lastAccessTime: Long = System.currentTimeMillis(),
    var totalTokens: Int = 0,
    var contextTokens: Int = 0,
    var messageCount: Int = 0,
    var config: SessionConfig,
    val history: MutableList<StoredMessage> = mutableListOf()
)

class SessionManager(
    private val maxSessions: Int = 10,
    private val sessionTimeoutMs: Long = 15 * 60 * 1000L
) {
    companion object {
        private const val TAG = "SessionManager"
    }

    private val lock = ReentrantLock()
    private val sessions = LinkedHashMap<String, SessionMeta>(16, 0.75f, true)
    private var cleanupJob: Job? = null

    init {
        startCleanupTask()
    }

    private fun startCleanupTask() {
        cleanupJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(60 * 1000L)
                cleanupExpiredSessions()
            }
        }
    }

    fun getOrCreateSession(
        sessionId: String?,
        config: SessionConfig
    ): SessionMeta {
        lock.lock()
        try {
            val id = sessionId ?: UUID.randomUUID().toString()

            sessions[id]?.let { session ->
                session.lastAccessTime = System.currentTimeMillis()
                return session
            }

            val newSession = SessionMeta(id = id, config = config)
            sessions[id] = newSession

            while (sessions.size > maxSessions) {
                val eldest = sessions.entries.first()
                sessions.remove(eldest.key)
            }

            return newSession
        } finally {
            lock.unlock()
        }
    }

    fun addUserMessage(sessionId: String, textContent: String, role: String = "user") {
        lock.lock()
        try {
            sessions[sessionId]?.let { session ->
                session.lastAccessTime = System.currentTimeMillis()
                session.history.add(StoredMessage(role, textContent))
                session.messageCount++
            }
        } finally {
            lock.unlock()
        }
    }

    fun updateTokens(sessionId: String, tokens: Int) {
        lock.lock()
        try {
            sessions[sessionId]?.let { session ->
                session.totalTokens += tokens
                session.contextTokens += tokens
                session.lastAccessTime = System.currentTimeMillis()
            }
        } finally {
            lock.unlock()
        }
    }

    fun containsSession(sessionId: String): Boolean {
        lock.lock()
        try { return sessions.containsKey(sessionId) } finally { lock.unlock() }
    }

    fun resetSession(sessionId: String?) {
        lock.lock()
        try {
            if (sessionId != null) {
                sessions.remove(sessionId)
                Log.d(TAG, "Reset session: $sessionId")
            } else {
                sessions.clear()
                Log.d(TAG, "Reset all sessions")
            }
        } finally {
            lock.unlock()
        }
    }

    fun closeAllSessions() {
        lock.lock()
        try {
            cleanupJob?.cancel()
            cleanupJob = null
            sessions.clear()
            Log.d(TAG, "Closed all sessions")
        } finally {
            lock.unlock()
        }
    }

    fun getSessionCount(): Int {
        lock.lock()
        try { return sessions.size } finally { lock.unlock() }
    }

    fun getActiveSessionIds(): List<String> {
        lock.lock()
        try { return sessions.keys.toList() } finally { lock.unlock() }
    }

    fun getSessionInfo(maxContextTokens: Int): List<Map<String, Any>> {
        lock.lock()
        try {
            return sessions.entries.map { entry ->
                val accumulated = entry.value.totalTokens
                val remaining = maxOf(0, maxContextTokens - accumulated)
                mapOf(
                    "id" to entry.key,
                    "message_count" to entry.value.messageCount,
                    "total_tokens" to accumulated,
                    "max_context_tokens" to maxContextTokens,
                    "context_remaining" to remaining,
                    "last_access" to entry.value.lastAccessTime
                )
            }
        } finally {
            lock.unlock()
        }
    }

    fun getSessionTokens(sessionId: String): Int {
        lock.lock()
        try { return sessions[sessionId]?.totalTokens ?: 0 } finally { lock.unlock() }
    }

    fun addToolResultMessage(sessionId: String, toolCallId: String, content: String) {
        lock.lock()
        try {
            sessions[sessionId]?.let { session ->
                session.lastAccessTime = System.currentTimeMillis()
                session.history.add(StoredMessage("tool", content))
                session.messageCount++
                Log.d(TAG, "Tool result added to session $sessionId, toolCallId=$toolCallId")
            }
        } finally {
            lock.unlock()
        }
    }

    fun addAssistantMessage(sessionId: String, text: String) {
        lock.lock()
        try {
            sessions[sessionId]?.let { session ->
                session.lastAccessTime = System.currentTimeMillis()
                session.history.add(StoredMessage("assistant", text))
                session.messageCount++
                Log.d(TAG, "Assistant message added to session $sessionId")
            }
        } finally {
            lock.unlock()
        }
    }
}
