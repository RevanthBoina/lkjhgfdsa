package com.aniob.core.safety

/**
 * Plan-7.6 — RENDER THE VAULT (core model half).
 *
 * `AniobTrustCenterScreen` and `AniobSkillsScreen` BOTH receive `vaultEntries` and `onForgetVault`
 * and NEITHER renders them. Aniob silently remembers your answers and gives you no way to see or
 * correct them. That is the single worst trust bug in the product: the remembering is invisible
 * and the correcting is impossible.
 *
 * The entry carries the question IN THE WORDS IT WAS ASKED, because a raw internal key
 * (`grill.destination`) tells a user nothing about what they agreed to.
 */
data class VaultEntry(
    /** Internal key. NEVER shown to the user. */
    val key: String,
    /** The question exactly as the user saw it. */
    val questionAsAsked: String,
    val answer: String,
    val recordedAt: Long,
    val source: Source
) {
    enum class Source { ASKED, INFERRED_DEFAULT, READ_FROM_DEVICE }

    /** How the row reads in the Trust Center. */
    fun displayLine(): String = when (source) {
        Source.ASKED -> "You told me: $answer"
        Source.INFERRED_DEFAULT -> "I assumed: $answer"
        Source.READ_FROM_DEVICE -> "I read from your phone: $answer"
    }
}

/**
 * The contract the Trust Center screen binds to. EDIT is part of it, not just forget: a wrong
 * remembered answer that can only be deleted forces the user to re-answer from scratch, so in
 * practice they leave it wrong.
 */
interface AniobVaultLedger {
    fun entries(): List<VaultEntry>
    fun remember(entry: VaultEntry)
    fun edit(key: String, newAnswer: String)
    fun forget(key: String)
    fun forgetAll()
}

class InMemoryVaultLedger : AniobVaultLedger {
    private val store = linkedMapOf<String, VaultEntry>()

    @Synchronized
    override fun entries(): List<VaultEntry> = store.values.sortedByDescending { it.recordedAt }

    @Synchronized
    override fun remember(entry: VaultEntry) {
        store[entry.key] = entry
    }

    @Synchronized
    override fun edit(key: String, newAnswer: String) {
        store[key]?.let {
            // Editing makes it an answer the user gave, whatever it used to be.
            store[key] = it.copy(
                answer = newAnswer,
                recordedAt = System.currentTimeMillis(),
                source = VaultEntry.Source.ASKED
            )
        }
    }

    @Synchronized
    override fun forget(key: String) {
        store.remove(key)
    }

    @Synchronized
    override fun forgetAll() = store.clear()
}
