package com.seriousdev.caltracker.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Persistent app-level flags stored in DataStore preferences.
 */
open class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val themeModeFlow: Flow<ThemeMode> =
        dataStore.data.map { it[KEY_THEME_MODE] }.map { name -> ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.SYSTEM }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    open suspend fun isSeedImported(): Boolean = firstValue(KEY_SEED_IMPORTED)

    open suspend fun setSeedImported(imported: Boolean) {
        dataStore.edit { it[KEY_SEED_IMPORTED] = imported }
    }

    open suspend fun isOnboardingDone(): Boolean = firstValue(KEY_ONBOARDING_DONE)

    open suspend fun setOnboardingDone(done: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_DONE] = done }
    }

    private suspend fun firstValue(key: Preferences.Key<Boolean>): Boolean =
        dataStore.data.map { it[key] ?: false }.first()

    companion object {
        val KEY_SEED_IMPORTED = booleanPreferencesKey("seed_imported")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
