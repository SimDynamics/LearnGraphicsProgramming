package com.learngraphicsprogramming.app.narration

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishResourceAttributionTest {
    private val resources = File(requireNotNull(System.getProperty("translation.resources")))
    private val project = File(resources, "../../../..").canonicalFile
    private val assets = File(project, "english_misaki/src/main/assets")

    @Test fun installedLookupDataRetainsItsCompleteNoticeInTheLicenseScreenResource() {
        val attribution = File(resources, "raw/spacy_pos_attribution.txt").readText().replace("\r\n", "\n")
        val license = File(assets, "licenses/SPACY-LOOKUPS-MIT.txt").readText().replace("\r\n", "\n").trim()
        assertTrue(license.startsWith("MIT License"))
        assertTrue(license.contains("Copyright (c) 2019 ExplosionAI GmbH"))
        assertTrue("The app must retain the complete upstream notice", attribution.contains(license))
        assertTrue(attribution.contains("1d90ebc5fdc6ccd0f9b2447e47172986938a7ab5"))
    }

    @Test fun lookupAttributionDescribesTheUnchangedDistributedData() {
        val frontend = JSONObject(File(assets, "spacy_pos.json").readText())
        assertEquals(3510, frontend.getJSONObject("lexemeNorm").length())
        val provenance = JSONObject(File(assets, "provenance.json").readText())
        val sources = provenance.getJSONArray("sources")
        val lookup = (0 until sources.length()).map { sources.getJSONObject(it) }
            .single { it.getString("id") == "spacy-lexeme-normalization" }
        assertEquals("MIT", lookup.getString("license"))
        assertEquals("1d90ebc5fdc6ccd0f9b2447e47172986938a7ab5", lookup.getString("revision"))
    }
}
