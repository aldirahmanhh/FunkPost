package com.gabut.autopost.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.autopostSettingsDataStore by preferencesDataStore(name = "autopost_settings")

/** UI preferences that must survive process death: dark mode + selected bot. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val DARK_MODE: Preferences.Key<Boolean> = booleanPreferencesKey("dark_mode")
        val ACTIVE_TOKEN_ID: Preferences.Key<Long> = longPreferencesKey("active_token_id")
        val BATTERY_CARD_DISMISSED: Preferences.Key<Boolean> =
            booleanPreferencesKey("battery_card_dismissed")

        /** UI language code ("en"/"id"). Unset means "follow the phone", decided once on first run. */
        val LANGUAGE: Preferences.Key<String> = stringPreferencesKey("language")
    }

    /** Reference default is light mode (`autopost.py:12`). */
    val darkModeFlow: Flow<Boolean> =
        context.autopostSettingsDataStore.data.map { prefs -> prefs[Keys.DARK_MODE] ?: false }

    val activeTokenIdFlow: Flow<Long?> =
        context.autopostSettingsDataStore.data.map { prefs -> prefs[Keys.ACTIVE_TOKEN_ID] }

    suspend fun setDarkMode(enabled: Boolean) {
        context.autopostSettingsDataStore.edit { prefs -> prefs[Keys.DARK_MODE] = enabled }
    }

    val batteryCardDismissedFlow: Flow<Boolean> =
        context.autopostSettingsDataStore.data.map { prefs ->
            prefs[Keys.BATTERY_CARD_DISMISSED] ?: false
        }

    suspend fun setBatteryCardDismissed(dismissed: Boolean) {
        context.autopostSettingsDataStore.edit { prefs ->
            prefs[Keys.BATTERY_CARD_DISMISSED] = dismissed
        }
    }

    val languageFlow: Flow<String?> =
        context.autopostSettingsDataStore.data.map { prefs -> prefs[Keys.LANGUAGE] }

    suspend fun setLanguage(code: String) {
        context.autopostSettingsDataStore.edit { prefs -> prefs[Keys.LANGUAGE] = code }
    }

    suspend fun setActiveTokenId(tokenId: Long?) {
        context.autopostSettingsDataStore.edit { prefs ->
            if (tokenId == null) {
                prefs.remove(Keys.ACTIVE_TOKEN_ID)
            } else {
                prefs[Keys.ACTIVE_TOKEN_ID] = tokenId
            }
        }
    }
}
