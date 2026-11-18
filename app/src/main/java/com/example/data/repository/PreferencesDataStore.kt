package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AssistantLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.jarvisDataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_local_prefs")

fun sanitizeGeminiApiKey(raw: String): String {
    return raw
        .replace(Regex("[\\s\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]+"), "")
        .trim('"', '\'', '`')
}

data class LocalUiSettings(
    val darkTheme: Boolean = true,
    val isMuted: Boolean = false,
    val preferredLanguage: String = AssistantLanguage.URDU.storageValue,
    val voiceSpeechRate: Float = 1.0f,
    val voicePitch: Float = 1.0f,
    val selectedVoiceName: String? = null,
    val geminiApiKey: String = ""
)

class PreferencesDataStore(private val context: Context) {

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val IS_MUTED = booleanPreferencesKey("is_muted")
        val LANGUAGE = stringPreferencesKey("preferred_language")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val PITCH = floatPreferencesKey("pitch")
        val VOICE_NAME = stringPreferencesKey("voice_name")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    }

    val settingsFlow: Flow<LocalUiSettings> = context.jarvisDataStore.data.map { prefs ->
        LocalUiSettings(
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            isMuted = prefs[Keys.IS_MUTED] ?: false,
            preferredLanguage = prefs[Keys.LANGUAGE] ?: AssistantLanguage.URDU.storageValue,
            voiceSpeechRate = prefs[Keys.SPEECH_RATE] ?: 1.0f,
            voicePitch = prefs[Keys.PITCH] ?: 1.0f,
            selectedVoiceName = prefs[Keys.VOICE_NAME]?.takeIf { it.isNotBlank() },
            geminiApiKey = sanitizeGeminiApiKey(prefs[Keys.GEMINI_API_KEY].orEmpty())
        )
    }

    suspend fun updateSettings(settings: LocalUiSettings) {
        context.jarvisDataStore.edit { prefs ->
            prefs[Keys.DARK_THEME] = settings.darkTheme
            prefs[Keys.IS_MUTED] = settings.isMuted
            prefs[Keys.LANGUAGE] = settings.preferredLanguage
            prefs[Keys.SPEECH_RATE] = settings.voiceSpeechRate
            prefs[Keys.PITCH] = settings.voicePitch
            if (!settings.selectedVoiceName.isNullOrBlank()) {
                prefs[Keys.VOICE_NAME] = settings.selectedVoiceName
            } else {
                prefs.remove(Keys.VOICE_NAME)
            }
            val cleanedKey = sanitizeGeminiApiKey(settings.geminiApiKey)
            if (cleanedKey.isNotEmpty()) {
                prefs[Keys.GEMINI_API_KEY] = cleanedKey
            } else {
                prefs.remove(Keys.GEMINI_API_KEY)
            }
        }
    }

    suspend fun saveGeminiApiKey(apiKey: String) {
        val cleaned = sanitizeGeminiApiKey(apiKey)
        context.jarvisDataStore.edit { prefs ->
            if (cleaned.isNotEmpty()) {
                prefs[Keys.GEMINI_API_KEY] = cleaned
            } else {
                prefs.remove(Keys.GEMINI_API_KEY)
            }
        }
    }
}
