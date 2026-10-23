package com.learngraphicsprogramming.app.narration

import org.junit.Assert.*
import org.junit.Test

class NarrationGenerationProfileTest {
    @Test fun languageAndEveryVoiceAreCapturedTogetherWithoutMixingAFutureSelection() {
        val saved = mutableMapOf<String, Any>("language" to "es", "kokoro_voice:es" to "em_santa")
        val before = NarrationGenerationProfile.fromPreferences(saved)
        saved["kokoro_voice:es"] = "ef_dora"
        saved["language"] = "fr"
        val after = NarrationGenerationProfile.fromPreferences(saved)
        assertEquals(NarrationLanguage.SPANISH, before.language)
        assertEquals("em_santa", before.voice(NarrationLanguage.SPANISH))
        assertEquals(NarrationLanguage.FRENCH, after.language)
        assertEquals("ef_dora", after.voice(NarrationLanguage.SPANISH))
        assertEquals("ff_siwis", after.voice(NarrationLanguage.FRENCH))
        assertNotEquals(before, after)
    }

    @Test fun voiceDefaultsAndLegacySelectionsRemainLanguageSpecific() {
        val profile = NarrationGenerationProfile.fromPreferences(mapOf("language" to "es", "kokoro_voice" to "em_santa"))
        assertEquals("em_santa", profile.voice(NarrationLanguage.SPANISH))
        assertEquals("af_heart", profile.voice(NarrationLanguage.AMERICAN_ENGLISH))
        assertEquals("bf_emma", profile.voice(NarrationLanguage.BRITISH_ENGLISH))
        assertEquals("ff_siwis", profile.voice(NarrationLanguage.FRENCH))
    }

    @Test fun anOldCurrentPageQueueCannotGenerateThePreviouslySelectedVoice() {
        val profile = NarrationGenerationProfile.fromPreferences(mapOf("language" to "es", "kokoro_voice:es" to "em_santa"))
        val old = NarrationCurriculum.Sentence("Texto original", NarrationLanguage.SPANISH, "ef_dora", "current-page/1")
        assertFalse(profile.accepts(listOf(old)))
        assertTrue(profile.accepts(listOf(old.copy(voice = "em_santa"))))
        assertTrue(profile.accepts(listOf(old.copy(language = NarrationLanguage.FRENCH, voice = "ff_siwis"))))
    }

    @Test fun playbackRateControlsAndOtherPreferencesDoNotChangeAudioPreparationIdentity() {
        val baseline = mapOf<String, Any>("language" to "es", "kokoro_voice:es" to "em_santa")
        assertEquals(NarrationGenerationProfile.fromPreferences(baseline),
            NarrationGenerationProfile.fromPreferences(baseline + mapOf("rate" to 2f, "bar_x" to 0.5f, "controls_visible" to false)))
        for (key in listOf("language", "kokoro_voice", "kokoro_voice:es", "kokoro_voice:en-US", "kokoro_voice:fr"))
            assertTrue(key, NarrationGenerationProfile.isPreference(key))
        for (key in listOf("rate", "bar_x", "controls_visible", "android_voice:es"))
            assertFalse(key, NarrationGenerationProfile.isPreference(key))
    }
}
