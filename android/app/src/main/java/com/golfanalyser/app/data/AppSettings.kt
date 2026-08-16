package com.golfanalyser.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

const val OPENAI_ASSESSMENT_MODEL = "gpt-5.5"

data class OpenAiSettings(
    val apiKey: String,
    val model: String = OPENAI_ASSESSMENT_MODEL,
) {
    val hasApiKey: Boolean = apiKey.isNotBlank()
}

class AppSettings(context: Context) {
    private val preferences: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        preferences = EncryptedSharedPreferences.create(
            context,
            "golf_analyser_secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun openAiSettings(): OpenAiSettings =
        OpenAiSettings(
            apiKey = preferences.getString(KEY_OPENAI_API_KEY, "").orEmpty(),
        )

    fun saveOpenAiSettings(settings: OpenAiSettings) {
        preferences.edit()
            .putString(KEY_OPENAI_API_KEY, settings.apiKey.trim())
            .remove(KEY_OPENAI_MODEL)
            .apply()
    }

    fun coachingFocus(): CoachingFocusDto = CoachingFocusDto(
        goals = preferences.getStringSet(KEY_COACHING_GOALS, emptySet()).orEmpty().toList(),
        customNote = preferences.getString(KEY_COACHING_NOTE, null),
    ).normalized()

    fun saveCoachingFocus(focus: CoachingFocusDto) {
        val normalized = focus.normalized()
        preferences.edit()
            .putStringSet(KEY_COACHING_GOALS, normalized.goals.toSet())
            .putString(KEY_COACHING_NOTE, normalized.customNote)
            .apply()
    }

    fun clearAll() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_COACHING_GOALS = "coaching_focus_goals"
        private const val KEY_COACHING_NOTE = "coaching_focus_note"
        // Removed from the UI; retained only so upgrades erase older saved overrides.
        private const val KEY_OPENAI_MODEL = "openai_model"
    }
}
