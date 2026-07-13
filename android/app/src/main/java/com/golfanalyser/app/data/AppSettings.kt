package com.golfanalyser.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.golfanalyser.app.BuildConfig

data class OpenAiSettings(
    val apiKey: String,
    val model: String,
) {
    val hasApiKey: Boolean = apiKey.isNotBlank()
}

class AppSettings(context: Context) {
    private val preferences: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "golf_analyser_secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences("golf_analyser_settings", Context.MODE_PRIVATE)
    }

    fun openAiSettings(): OpenAiSettings =
        OpenAiSettings(
            apiKey = preferences.getString(KEY_OPENAI_API_KEY, null)
                ?: BuildConfig.DEBUG_OPENAI_API_KEY,
            model = preferences.getString(KEY_OPENAI_MODEL, null)
                ?: BuildConfig.DEFAULT_OPENAI_MODEL,
        )

    fun saveOpenAiSettings(settings: OpenAiSettings) {
        preferences.edit()
            .putString(KEY_OPENAI_API_KEY, settings.apiKey.trim())
            .putString(KEY_OPENAI_MODEL, settings.model.trim().ifBlank { BuildConfig.DEFAULT_OPENAI_MODEL })
            .apply()
    }

    companion object {
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_MODEL = "openai_model"
    }
}
