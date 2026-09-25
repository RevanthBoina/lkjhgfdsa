package com.aniob.app.workflow

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aniob.core.workflow.DestinationCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.destinationDataStore by preferencesDataStore(name = "workflow_destinations")

/**
 * DestinationPreferences: DataStore-backed preference store for category defaults,
 * browser selection, and user-confirmed destination pins.
 *
 * Invariant: Never stores cookies, credentials, passwords, or persistent approval grants.
 */
class DestinationPreferences(private val context: Context) {

    private val dataStore = context.destinationDataStore

    companion object {
        val KEY_BUILDER = stringPreferencesKey("dest_builder")
        val KEY_REASONING = stringPreferencesKey("dest_reasoning")
        val KEY_NOTE = stringPreferencesKey("dest_note")
        val KEY_BROWSER_PKG = stringPreferencesKey("dest_browser_package")
    }

    private fun keyForCategory(category: DestinationCategory) = when (category) {
        DestinationCategory.BUILDER_SERVICE -> KEY_BUILDER
        DestinationCategory.REASONING_SERVICE -> KEY_REASONING
        DestinationCategory.DOCUMENT_HANDLER -> KEY_NOTE
        else -> stringPreferencesKey("dest_${category.name.lowercase()}")
    }

    fun getPreferredDestination(category: DestinationCategory): Flow<String?> {
        val key = keyForCategory(category)
        return dataStore.data.map { prefs -> prefs[key] }
    }

    suspend fun setPreferredDestination(category: DestinationCategory, destination: String) {
        val key = keyForCategory(category)
        dataStore.edit { prefs ->
            prefs[key] = destination
        }
    }

    suspend fun clearPreferredDestination(category: DestinationCategory) {
        val key = keyForCategory(category)
        dataStore.edit { prefs ->
            prefs.remove(key)
        }
    }

    fun getPreferredBrowserPackage(): Flow<String?> {
        return dataStore.data.map { prefs -> prefs[KEY_BROWSER_PKG] }
    }

    suspend fun setPreferredBrowserPackage(pkg: String?) {
        dataStore.edit { prefs ->
            if (pkg != null) {
                prefs[KEY_BROWSER_PKG] = pkg
            } else {
                prefs.remove(KEY_BROWSER_PKG)
            }
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
