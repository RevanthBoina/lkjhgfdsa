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

    private val messageBuffer = mutableListOf<Message>()

    @Synchronized
    fun addMessage(role: String, content: String) {
        messageBuffer.add(Message(role = role, content = content))
        if (messageBuffer.size > maxMessages) {
            messageBuffer.removeAt(0)
        }
    }

    @Synchronized
    fun getRecentHistory(): List<Message> {
        return ArrayList(messageBuffer)
    }

    @Synchronized
    fun clear() {
        messageBuffer.clear()
    }
}
