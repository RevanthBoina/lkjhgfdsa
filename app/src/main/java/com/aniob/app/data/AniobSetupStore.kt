package com.aniob.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.setupDataStore: DataStore<Preferences> by preferencesDataStore(name = "aniob_setup")

/** Wizard progress that survives process death and app restarts (UX-1 §1). */
data class SetupState(
    val stepReached: Int = 0,
    val completed: Boolean = false,
    val skippedFresh: Boolean = false,
    val remindAfter: Long = 0L
)

/**
 * Persists onboarding progress so "Skip" and "resume where you left off" are real (UX-1).
 */
class AniobSetupStore(private val context: Context) {

    private val keyStep = intPreferencesKey("setup_step_reached")
    private val keyCompleted = booleanPreferencesKey("setup_completed")
    private val keySkipped = booleanPreferencesKey("setup_skipped_fresh")
    private val keyRemind = longPreferencesKey("setup_remind_after")

    val state: Flow<SetupState> = context.setupDataStore.data.map { prefs ->
        SetupState(
            stepReached = prefs[keyStep] ?: 0,
            completed = prefs[keyCompleted] ?: false,
            skippedFresh = prefs[keySkipped] ?: false,
            remindAfter = prefs[keyRemind] ?: 0L
        )
    }

    suspend fun current(): SetupState = state.first()

    suspend fun setStep(step: Int) {
        context.setupDataStore.edit { it[keyStep] = step }
    }

    suspend fun setCompleted(completed: Boolean) {
        context.setupDataStore.edit { it[keyCompleted] = completed }
    }

    suspend fun setSkippedFresh(skipped: Boolean) {
        context.setupDataStore.edit {
            it[keySkipped] = skipped
            if (skipped) it[keyRemind] = System.currentTimeMillis() + REMIND_INTERVAL_MS
        }
    }
}

private const val REMIND_INTERVAL_MS = 24L * 60L * 60L * 1000L
