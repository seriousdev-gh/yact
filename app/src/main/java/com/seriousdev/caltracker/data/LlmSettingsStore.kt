package com.seriousdev.caltracker.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** User-supplied OpenAI-compatible vision endpoint for label recognition. */
data class LlmSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    val chatCompletionsUrl: String
        get() = baseUrl.trimEnd('/') + "/chat/completions"
}

/**
 * LLM endpoint configuration and the one-time photo-sharing consent,
 * kept in the device preferences (not part of the database backup).
 */
open class LlmSettingsStore(private val dataStore: DataStore<Preferences>) {

    open val settings: Flow<LlmSettings> = data()
        .map {
            LlmSettings(
                baseUrl = it[KEY_LLM_BASE_URL].orEmpty(),
                apiKey = it[KEY_LLM_API_KEY].orEmpty(),
                model = it[KEY_LLM_MODEL].orEmpty(),
            )
        }

    open suspend fun saveSettings(baseUrl: String, apiKey: String, model: String) {
        dataStore.edit {
            it[KEY_LLM_BASE_URL] = baseUrl.trim()
            it[KEY_LLM_API_KEY] = apiKey.trim()
            it[KEY_LLM_MODEL] = model.trim()
        }
    }

    open val consentGranted: Flow<Boolean> = data().map { it[KEY_LLM_CONSENT] ?: false }

    open suspend fun grantConsent() {
        dataStore.edit { it[KEY_LLM_CONSENT] = true }
    }

    private fun data(): Flow<Preferences> =
        dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    companion object {
        val KEY_LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val KEY_LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val KEY_LLM_MODEL = stringPreferencesKey("llm_model")
        val KEY_LLM_CONSENT = booleanPreferencesKey("llm_consent")
    }
}
