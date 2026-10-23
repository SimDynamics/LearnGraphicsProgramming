package com.learngraphicsprogramming.app.narration

import android.content.Context
import com.learngraphicsprogramming.app.R

enum class NarrationEngine {
    AUTO,
    KOKORO,
    ANDROID
}

data class NarrationSettings(
    val enabled: Boolean = true,
    val engine: NarrationEngine = NarrationEngine.AUTO,
    val kokoroVoice: String = "ef_dora",
    val androidVoice: String? = null,
    val rate: Float = 1f,
    val language: NarrationLanguage = NarrationLanguage.SPANISH
)

object NarrationSettingsRepository {

    private const val PREFERENCES_NAME = "lesson_narration_settings"
    private const val KEY_ENABLED = "enabled"
    internal const val KEY_ADVANCE_GENERATION = "kokoro_advance_generation"
    private const val KEY_PAUSED_COURSE_PREFIX = "kokoro_paused_course:"
    private const val KEY_CONTROLS_VISIBLE = "controls_visible"
    private const val KEY_ENGINE = "engine"
    private const val KEY_KOKORO_VOICE = "kokoro_voice"
    private const val KEY_ANDROID_VOICE = "android_voice"
    private const val KEY_RATE = "rate"
    private const val KEY_ENGLISH_LANGUAGE = "last_english_language"

    val KOKORO_VOICES get() = KokoroVoiceCatalog.voices.map { it.id }
    val RATES = listOf(0.5f, 1f, 1.5f, 2f)

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )

    private val initializationLock = Any()

    /** Persist missing defaults once; explicit engine/language/voice choices always win. */
    private fun initializeDefaults(context: Context) {
        val prefs = preferences(context)
        if (prefs.contains(KEY_ENGINE) && prefs.contains("language")) return
        synchronized(initializationLock) {
            val editor = prefs.edit()
            if (!prefs.contains(KEY_ENGINE)) editor.putString(KEY_ENGINE, NarrationEngine.KOKORO.name)
            if (!prefs.contains("language")) {
                // Older installations could save a voice before a separate language key existed.
                val legacy = KokoroVoiceCatalog.find(prefs.getString(KEY_KOKORO_VOICE, "").orEmpty())?.language
                val language = legacy ?: NarrationInitialSettings.language(
                    android.content.res.Resources.getSystem().configuration.locales.toLanguageTags())
                editor.putString("language", language.tag)
            }
            editor.apply()
        }
    }

    fun get(context: Context): NarrationSettings {
        initializeDefaults(context)
        val preferences = preferences(context)
        val engineName = preferences.getString(KEY_ENGINE, NarrationEngine.KOKORO.name)
        val language = NarrationLanguage.fromTag(preferences.getString("language", null)) ?: NarrationLanguage.AMERICAN_ENGLISH
        val voice = preferences.getString("kokoro_voice:${language.tag}", null)
            ?: preferences.getString(KEY_KOKORO_VOICE, language.defaultVoice)

        return NarrationSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, true),
            engine = NarrationEngine.entries.firstOrNull { it.name == engineName }
                ?: NarrationEngine.KOKORO,
            kokoroVoice = voice?.takeIf { KokoroVoiceCatalog.find(it)?.language == language } ?: language.defaultVoice,
            androidVoice = preferences.getString("android_voice:${language.tag}", null)
                ?: if (language == NarrationLanguage.SPANISH) preferences.getString(KEY_ANDROID_VOICE, null) else null,
            rate = normalizedRate(preferences.getFloat(KEY_RATE, 1f)),
            language = language
        )
    }

    fun saveEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Controls optional next-lesson/course generation, not the currently visited page. */
    fun advanceGenerationEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ADVANCE_GENERATION, true)

    fun saveAdvanceGenerationEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ADVANCE_GENERATION, enabled).apply()
    }

    /** Per-course control affects pre-generation only, never on-demand narration or cache. */
    private fun coursePauseKey(routeId: String, courseId: String): String {
        require(routeId in NarrationAudioPlan.ROUTES && courseId.isNotBlank()) {
            "Unknown curricular route or course"
        }
        return "$KEY_PAUSED_COURSE_PREFIX$routeId:$courseId"
    }

    fun courseGenerationPaused(context: Context, routeId: String, courseId: String): Boolean =
        preferences(context).getBoolean(coursePauseKey(routeId, courseId), false)

    fun saveCourseGenerationPaused(context: Context, routeId: String, courseId: String, paused: Boolean) {
        val key = coursePauseKey(routeId, courseId)
        val prefs = preferences(context)
        if (prefs.getBoolean(key, false) == paused) return
        val editor = prefs.edit()
        if (paused) editor.putBoolean(key, true) else editor.remove(key)
        editor.apply()
    }

    /** Used by the background service to react immediately to a changed course. */
    internal fun isCourseGenerationPreference(key: String?): Boolean =
        key?.startsWith(KEY_PAUSED_COURSE_PREFIX) == true

    internal fun generationProfile(context: Context): NarrationGenerationProfile {
        initializeDefaults(context)
        // SharedPreferences publishes an edit atomically. Do not combine the old
        // language with a newly selected voice by reading individual keys in a loop.
        return NarrationGenerationProfile.fromPreferences(preferences(context).all)
    }

    internal fun isGenerationProfileCurrent(context: Context, profile: NarrationGenerationProfile): Boolean =
        NarrationGenerationProfile.fromPreferences(preferences(context).all) == profile

    /** The active worker owns this short-lived subscription, also when launched
     * outside the service. Cancellation remains scoped to that worker's ORT lease. */
    internal fun observeGenerationProfile(context: Context, changed: () -> Unit): AutoCloseable {
        val prefs = preferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (NarrationGenerationProfile.isPreference(key)) changed()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** Also checked by queued work, before a settings observer can run on main. */
    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, true)

    /** Cancellation hot path: no settings/voice objects and no observer delivery required. */
    internal fun isEngineAllowed(context: Context, engine: NarrationEngine): Boolean = preferences(context).let {
        it.getBoolean(KEY_ENABLED, true) &&
            (it.getString(KEY_ENGINE, NarrationEngine.KOKORO.name) == NarrationEngine.ANDROID.name) ==
            (engine == NarrationEngine.ANDROID)
    }

    /** Presentation only: hiding the controls does not disable background narration. */
    fun controlsVisible(context: Context): Boolean =
        preferences(context).getBoolean(KEY_CONTROLS_VISIBLE, true)

    fun saveControlsVisible(context: Context, visible: Boolean) {
        preferences(context).edit().putBoolean(KEY_CONTROLS_VISIBLE, visible).apply()
    }

    fun saveEngine(context: Context, engine: NarrationEngine) {
        synchronized(initializationLock) {
            preferences(context).edit().putString(KEY_ENGINE, engine.name).apply()
        }
    }

    fun saveKokoroVoice(context: Context, voice: String) {
        val selected = KokoroVoiceCatalog.find(voice) ?: return
        val previousLanguage = get(context).language
        val editor = preferences(context).edit()
            .putString(KEY_KOKORO_VOICE, voice)
            .putString("kokoro_voice:${selected.language.tag}", voice)
            .putString("language", selected.language.tag)
        val english = selected.language.takeIf { it.resourceGroup == "en" }
            ?: previousLanguage.takeIf { it.resourceGroup == "en" }
        if (english != null) editor.putString(KEY_ENGLISH_LANGUAGE, english.tag)
        editor.apply()
    }

    fun saveAndroidVoice(context: Context, voice: String?) {
        val language = get(context).language
        val editor = preferences(context).edit()
            .putString("android_voice:${language.tag}", voice?.trim()?.takeIf { it.isNotEmpty() })
        if (language == NarrationLanguage.SPANISH) editor.remove(KEY_ANDROID_VOICE)
        editor.apply()
    }

    fun saveLanguage(context: Context, language: NarrationLanguage) {
        val current = get(context)
        val editor = preferences(context).edit()
            .putString("kokoro_voice:${current.language.tag}", current.kokoroVoice)
            .putString("android_voice:${current.language.tag}", current.androidVoice)
            .putString("language", language.tag)
        if (current.language == NarrationLanguage.SPANISH) editor.remove(KEY_ANDROID_VOICE)
        val english = language.takeIf { it.resourceGroup == "en" }
            ?: current.language.takeIf { it.resourceGroup == "en" }
        if (english != null) editor.putString(KEY_ENGLISH_LANGUAGE, english.tag)
        editor.apply()
    }

    /** Called exclusively by the app-language picker, never during binding or app startup. */
    fun synchronizeWithUiLanguage(context: Context, selectedUiTags: String) {
        val prefs = preferences(context)
        val current = get(context)
        val previousEnglish = current.language.takeIf { it.resourceGroup == "en" }
            ?: NarrationLanguage.fromTag(prefs.getString(KEY_ENGLISH_LANGUAGE, null))
                ?.takeIf { it.resourceGroup == "en" }
            // Existing installations had no dedicated variant key. Their last chosen
            // voice, or an unambiguous saved British voice, retains that choice too.
            ?: KokoroVoiceCatalog.find(prefs.getString(KEY_KOKORO_VOICE, "").orEmpty())
                ?.language?.takeIf { it.resourceGroup == "en" }
            ?: NarrationLanguage.BRITISH_ENGLISH.takeIf {
                prefs.contains("kokoro_voice:en-GB") && !prefs.contains("kokoro_voice:en-US")
            }
        val next = NarrationUiLanguageSelection.resolve(selectedUiTags,
            android.content.res.Resources.getSystem().configuration.locales.toLanguageTags(),
            previousEnglish)
        if (next != current.language) saveLanguage(context, next)
    }

    fun forLanguage(context: Context, language: NarrationLanguage): NarrationSettings {
        val current = get(context)
        if (current.language == language) return current
        val prefs = preferences(context)
        return current.copy(language = language,
            kokoroVoice = prefs.getString("kokoro_voice:${language.tag}", null)
                ?.takeIf { KokoroVoiceCatalog.find(it)?.language == language }
                ?: prefs.getString(KEY_KOKORO_VOICE, null)?.takeIf { KokoroVoiceCatalog.find(it)?.language == language }
                ?: language.defaultVoice,
            androidVoice = prefs.getString("android_voice:${language.tag}", null)
                ?: if (language == NarrationLanguage.SPANISH) prefs.getString(KEY_ANDROID_VOICE, null) else null)
    }

    fun savePosition(context: Context, x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        val prefs = preferences(context)
        if (prefs.getFloat("bar_x",1f) == x && prefs.getFloat("bar_y",0.85f) == y) return
        prefs.edit().putFloat("bar_x",x).putFloat("bar_y",y).apply()
    }

    fun position(context: Context): Pair<Float, Float> = preferences(context).let {
        (it.getFloat("bar_x",1f).takeIf { value -> value.isFinite() } ?: 1f) to
            (it.getFloat("bar_y",0.85f).takeIf { value -> value.isFinite() } ?: 0.85f)
    }

    fun saveRate(context: Context, rate: Float) {
        preferences(context).edit().putFloat(KEY_RATE, normalizedRate(rate)).apply()
    }

    fun nextRate(rate: Float): Float {
        val index = RATES.indexOf(normalizedRate(rate))
        return RATES[(index + 1) % RATES.size]
    }

    fun rateLabel(context: Context, rate: Float): String = context.getString(when (normalizedRate(rate)) {
        0.5f -> R.string.narration_rate_half
        1.5f -> R.string.narration_rate_one_half
        2f -> R.string.narration_rate_double
        else -> R.string.narration_rate_normal
    })

    // Stable diagnostic labels remain available to non-UI callers.
    fun rateLabel(rate: Float): String = when (normalizedRate(rate)) {
        0.5f -> "0.5X"
        1.5f -> "1.5X"
        2f -> "2X"
        else -> "1X"
    }

    private fun normalizedRate(rate: Float): Float =
        RATES.firstOrNull { it == rate } ?: 1f
}
