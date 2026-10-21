package com.github.opscalehub.dong.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-device display preferences (never in backups: they belong to the person holding the phone).
 * Simple mode is the default; it only hides controls and never changes data.
 */
class UiPrefs(context: Context) {
    private val p = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _advanced = MutableStateFlow(p.getBoolean(ADVANCED, false))
    val advanced: StateFlow<Boolean> = _advanced.asStateFlow()
    fun setAdvanced(v: Boolean) { p.edit().putBoolean(ADVANCED, v).apply(); _advanced.value = v }

    /** Multiplies Android's own font scale; 1.0, 1.15 or 1.3. */
    private val _textScale = MutableStateFlow(p.getFloat(TEXT_SCALE, 1f))
    val textScale: StateFlow<Float> = _textScale.asStateFlow()
    fun setTextScale(v: Float) { p.edit().putFloat(TEXT_SCALE, v).apply(); _textScale.value = v }

    /** First time the app was opened (millis), for offering the survey after a few days. */
    val firstUse: Long = p.getLong(FIRST_USE, 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { p.edit().putLong(FIRST_USE, it).apply() }

    private val _surveyOffer = MutableStateFlow(!p.getBoolean(SURVEY_CLOSED, false))
    /** Whether Home may still offer the survey (false once answered or dismissed). */
    val surveyOffer: StateFlow<Boolean> = _surveyOffer.asStateFlow()
    fun closeSurveyOffer() { p.edit().putBoolean(SURVEY_CLOSED, true).apply(); _surveyOffer.value = false }

    companion object {
        private const val ADVANCED = "advanced"
        private const val TEXT_SCALE = "textScale"
        private const val FIRST_USE = "firstUse"
        private const val SURVEY_CLOSED = "surveyClosed"
        const val SURVEY_AFTER_MS = 5L * 24 * 60 * 60 * 1000
        val SCALES = listOf(1f, 1.15f, 1.3f)
    }
}
