package com.aniob.core.memory

/**
 * Thread-safe synchronized conversation buffer.
 */
class AniobContextualMemory(private val maxMessages: Int = 20) {

    data class Message(
        val role: String, // "user", "assistant", "system"
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val messageBuffer = ArrayDeque<Message>(maxMessages + 1)

    @Synchronized
    fun addMessage(role: String, content: String) {
        if (messageBuffer.size >= maxMessages && messageBuffer.isNotEmpty()) {
            messageBuffer.removeFirst()
        }
        messageBuffer.addLast(Message(role = role, content = content))
    }

    @Synchronized
    fun getRecentHistory(): List<Message> {
        return messageBuffer.toList()
    }

    @Synchronized
    fun clear() {
        messageBuffer.clear()
    }
}
