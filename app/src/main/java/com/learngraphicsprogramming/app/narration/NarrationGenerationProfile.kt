package com.learngraphicsprogramming.app.narration

/** Immutable selection for one preparation pass. Rate controls playback, not cached audio. */
internal data class NarrationGenerationProfile(
    val language: NarrationLanguage,
    val voices: Map<NarrationLanguage, String>
) {
    fun voice(language: NarrationLanguage): String = voices.getValue(language)

    fun accepts(sentences: List<NarrationCurriculum.Sentence>): Boolean =
        sentences.all { it.voice == voice(it.language) }

    companion object {
        fun fromPreferences(values: Map<String, *>): NarrationGenerationProfile {
            val language = NarrationLanguage.fromTag(values["language"] as? String)
                ?: NarrationLanguage.AMERICAN_ENGLISH
            val legacy = values["kokoro_voice"] as? String
            val voices = NarrationLanguage.entries.associateWith { requested ->
                val saved = values["kokoro_voice:${requested.tag}"] as? String
                if (requested == language) (saved ?: legacy)
                    ?.takeIf { KokoroVoiceCatalog.find(it)?.language == requested } ?: requested.defaultVoice
                else saved?.takeIf { KokoroVoiceCatalog.find(it)?.language == requested }
                    ?: legacy?.takeIf { KokoroVoiceCatalog.find(it)?.language == requested } ?: requested.defaultVoice
            }
            return NarrationGenerationProfile(language, voices)
        }

        fun isPreference(key: String?): Boolean = key == null || key == "language" ||
            key == "kokoro_voice" || key.startsWith("kokoro_voice:")
    }
}
