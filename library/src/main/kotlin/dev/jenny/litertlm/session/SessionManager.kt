package dev.jenny.litertlm.session

import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

data class SessionEntry(
    val conversation: Conversation,
    val tools: List<ToolProvider>?,
    val toolsHash: Int,
    var lastAccessTime: Long,
    var accumulatedTokens: Int = 0,
    var messageCount: Int = 0,
    var lastRole: String? = null
)

class SessionManager(
    private val createConversation: (List<ToolProvider>?) -> Conversation?
) {
    companion object {
        private const val TAG = "SessionManager"
        private const val MAX_SESSIONS = 1
        private const val SESSION_TIMEOUT_MS = 15 * 60 * 1000L
        private const val CLEANUP_INTERVAL_MS = 60 * 1000L
        
        private fun computeToolsHash(tools: List<ToolProvider>?): Int {
            if (tools == null) return 0
            return tools.fold(1) { acc, tool -> acc * 31 + tool.hashCode() }
        }
    }

    private val lock = ReentrantLock()
    private val sessions = LinkedHashMap<String, SessionEntry>(16, 0.75f, true)
    @Volatile private var closed = false
    private var cleanupJob: Job? = null

    init {
        startCleanupTask()
    }

    private fun startCleanupTask() {
        cleanupJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                cleanupExpiredSessions()
            }
        }
    }

    private fun cleanupExpiredSessions() {
        lock.lock()
        try {
            if (closed) return
            
            val now = System.currentTimeMillis()
            val expiredKeys = sessions.entries
                .filter { now - it.value.lastAccessTime > SESSION_TIMEOUT_MS }
                .map { it.key }
            
            expiredKeys.forEach { key ->
                sessions.remove(key)?.let { entry ->
                    try {
                        if (entry.conversation.isAlive) {
                            entry.conversation.close()
                        }
                    } catch (_: Throwable) {}
                }
                Log.d(TAG, "Session expired: $key")
            }
            
            if (expiredKeys.isNotEmpty()) {
                Log.d(TAG, "Cleaned up ${expiredKeys.size} expired sessions, active: ${sessions.size}")
            }
        } finally {
            lock.unlock()
        }
    }

    fun getOrCreateSession(sessionId: String?, tools: List<ToolProvider>? = null): Pair<String, Conversation?> {
        lock.lock()
        try {
            if (closed) return Pair(sessionId ?: UUID.randomUUID().toString(), null)

            val requestedId = sessionId ?: UUID.randomUUID().toString()
            val toolsHash = computeToolsHash(tools)
            
            sessions[requestedId]?.let { entry ->
                if (entry.toolsHash == toolsHash && entry.conversation.isAlive) {
                    Log.d(TAG, "Reusing session: $requestedId (msgCount: ${entry.messageCount}, lastRole: ${entry.lastRole})")
                    entry.lastAccessTime = System.currentTimeMillis()
                    return Pair(requestedId, entry.conversation)
                } else {
                    Log.d(TAG, "Tools changed or conversation dead for session: $requestedId, recreating")
                    sessions.remove(requestedId)
                    try {
                        if (entry.conversation.isAlive) {
                            entry.conversation.close()
                        }
                    } catch (_: Throwable) {}
                }
            }

            val newConversation = createConversation(tools)
            if (newConversation == null) {
                Log.e(TAG, "Failed to create conversation for session: $requestedId")
                return Pair(requestedId, null)
            }

            sessions[requestedId] = SessionEntry(
                conversation = newConversation,
                tools = tools,
                toolsHash = toolsHash,
                lastAccessTime = System.currentTimeMillis()
            )
            
            while (sessions.size > MAX_SESSIONS) {
                val eldestEntry = sessions.entries.first()
                sessions.remove(eldestEntry.key)?.let { entry ->
                    try {
                        if (entry.conversation.isAlive) {
                            entry.conversation.close()
                        }
                    } catch (_: Throwable) {}
                }
                Log.d(TAG, "Evicted eldest session: ${eldestEntry.key}")
            }
            
            Log.d(TAG, "Created session: $requestedId, total sessions: ${sessions.size}")
            return Pair(requestedId, newConversation)
        } finally {
            lock.unlock()
        }
    }

    fun updateSessionStats(sessionId: String, messageCount: Int, lastRole: String, tokens: Int) {
        lock.lock()
        try {
            sessions[sessionId]?.let { entry ->
                entry.messageCount = messageCount
                entry.lastRole = lastRole
                entry.accumulatedTokens += tokens
                entry.lastAccessTime = System.currentTimeMillis()
            }
        } finally {
            lock.unlock()
        }
    }

    fun resetSession(sessionId: String? = null) {
        lock.lock()
        try {
            if (sessionId != null) {
                sessions.remove(sessionId)?.let { entry ->
                    try {
                        if (entry.conversation.isAlive) {
                            entry.conversation.close()
                        }
                    } catch (_: Throwable) {}
                }
                Log.d(TAG, "Reset session: $sessionId")
            } else {
                sessions.forEach { (_, entry) ->
                    try {
                        if (entry.conversation.isAlive) {
                            entry.conversation.close()
                        }
                    } catch (_: Throwable) {}
                }
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
            closed = true
            cleanupJob?.cancel()
            cleanupJob = null
            
            sessions.forEach { (_, entry) ->
                try { 
                    if (entry.conversation.isAlive) {
                        entry.conversation.close() 
                    }
                } catch (_: Throwable) {}
            }
            sessions.clear()
            Log.d(TAG, "Closed all sessions")
        } finally {
            lock.unlock()
        }
    }

    fun getSessionCount(): Int {
        lock.lock()
        try {
            return sessions.size
        } finally {
            lock.unlock()
        }
    }

    fun getActiveSessionIds(): List<String> {
        lock.lock()
        try {
            return sessions.keys.toList()
        } finally {
            lock.unlock()
        }
    }

    fun getSessionTokens(sessionId: String): Int {
        lock.lock()
        try {
            return sessions[sessionId]?.accumulatedTokens ?: 0
        } finally {
            lock.unlock()
        }
    }

    fun getSessionInfo(maxContextTokens: Int): List<Map<String, Any>> {
        lock.lock()
        try {
            return sessions.entries.map { entry ->
                val accumulated = entry.value.accumulatedTokens
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
}