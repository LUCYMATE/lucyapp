package com.soltini.app.settings

import android.content.Context
import com.soltini.app.BuildConfig

/**
 * AppSettings — persistent user-configurable AI settings.
 *
 * Stored in SharedPreferences so they survive app restarts.
 * Changes take effect the next time GeminiLiveManager.connect() is called
 * (triggered by the "Save & Reconnect" button in the settings UI).
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Gemini API Key ────────────────────────────────────────────────────────

    var geminiApiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    /**
     * Returns the user's own key if set, otherwise falls back to the
     * BuildConfig key bundled at compile time.
     */
    fun effectiveApiKey(): String {
        val userKey = geminiApiKey.trim()
        return if (userKey.isNotBlank()) userKey else BuildConfig.GEMINI_API_KEY
    }

    // ── AI Persona ────────────────────────────────────────────────────────────

    var aiPersona: String
        get() = prefs.getString(KEY_PERSONA, DEFAULT_PERSONA) ?: DEFAULT_PERSONA
        set(value) = prefs.edit().putString(KEY_PERSONA, value).apply()

    // ── Voice Model ───────────────────────────────────────────────────────────

    var voiceName: String
        get() = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    companion object {
        private const val PREFS_NAME = "soltini_ai_settings"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_PERSONA  = "ai_persona"
        private const val KEY_VOICE    = "voice_name"
        const val DEFAULT_VOICE = "Aoede"

        /** All voices available in Gemini Live prebuilt voice catalog. */
        val AVAILABLE_VOICES = listOf("Aoede", "Charon", "Fenrir", "Kore", "Puck", "Zephyr")

        /**
         * Default persona prefix — the part before the tool documentation.
         * Users can override this to change Soltini's personality.
         */
        const val DEFAULT_PERSONA = """You are Soltini, an elegant and highly capable AI assistant running as the device's default assistant on Android.

Speak in a polite, elegant, and concise manner with a natural South Asian accent."""
    }
}
