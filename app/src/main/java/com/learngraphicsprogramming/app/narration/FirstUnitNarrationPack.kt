package com.learngraphicsprogramming.app.narration

import android.content.Context
import android.content.res.Configuration
import com.learngraphicsprogramming.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Manifest of the historical Spanish/Dora starter pack, independent of the
 * active course catalog and the student's current narration preferences.
 * It uses the shared text extractor and splitter, never another text source.
 */
internal object FirstUnitNarrationPack {
    const val MANIFEST_FILE = "kokoro-first-unit-manifest.json"
    private const val UNIT_PREFIX =
        "programming/cpp_fundamentals/cpp_fundamentals__intro_programming/"
    private val lessons = setOf(
        "cpp_fundamentals__intro_programming__what_is_programming",
        "cpp_fundamentals__intro_programming__first_contact_cpp",
        "cpp_fundamentals__intro_programming__program_structure"
    )

    fun writeManifest(context: Context) {
        val entries = JSONArray()
        val seen = mutableSetOf<String>()
        val presentLessons = mutableSetOf<String>()
        val spanish = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
        })
        for (lessonId in lessons) {
            val name = lessonId.substringAfterLast("__")
            val path = "content/es/programming/cpp_fundamentals/intro_programming/lesson_$name.json"
            val lesson = JSONObject(context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() })
            check(lesson.getString("id") == lessonId) { "Identidad del paquete de voz incorrecta: $path" }
            val pages = lesson.getJSONArray("pages")
            check(pages.length() > 0) { "Lección del paquete de voz sin páginas: $path" }
            presentLessons += lessonId
            for (page in 0 until pages.length()) {
                // Require an actual page object rather than silently skipping malformed data.
                pages.getJSONObject(page)
                val texts = LessonNarrationText.page(lesson, page, NarrationLanguage.SPANISH) { type ->
                    when (type) {
                        "order_code" -> spanish.getString(R.string.exercise_order_instruction)
                        "drag_fill_code" -> spanish.getString(R.string.exercise_fill_instruction)
                        else -> ""
                    }
                }
                for (text in NarrationSegments.sentences(texts)) {
                    val hash = KokoroNarrationCache.key("es", "ef_dora", text)
                    if (seen.add(hash)) {
                        entries.put(JSONObject()
                            .put("hash", hash)
                            .put("language", "es")
                            .put("voice", "ef_dora")
                            .put("lesson", lessonId)
                            .put("location", "$UNIT_PREFIX$lessonId/${page + 1}"))
                    }
                }
            }
        }
        check(presentLessons == lessons && entries.length() > 0) {
            "Primera unidad incompleta: ${lessons - presentLessons}"
        }
        val manifest = JSONObject()
            .put("format", 1)
            .put("course", "cpp_fundamentals")
            .put("unit", "intro_programming")
            .put("lessons", JSONArray(lessons.toList()))
            .put("uniqueSentences", entries.length())
            .put("entries", entries)
        val output = File(context.filesDir, MANIFEST_FILE)
        val partial = File(context.filesDir, "$MANIFEST_FILE.part")
        try {
            partial.writeText(manifest.toString(), Charsets.UTF_8)
            check(partial.renameTo(output)) { "No se pudo publicar el manifiesto de voz" }
        } finally { partial.delete() }
    }
}
