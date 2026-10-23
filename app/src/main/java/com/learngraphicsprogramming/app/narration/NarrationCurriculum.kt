package com.learngraphicsprogramming.app.narration

import android.content.Context
import com.learngraphicsprogramming.app.AppearanceSettingsRepository
import com.learngraphicsprogramming.app.CourseRepository
import com.learngraphicsprogramming.app.LessonRepository
import com.learngraphicsprogramming.app.UnitRepository
import java.io.FileNotFoundException

/** Stable curriculum order: route > course > unit > lesson > page > sentence. */
internal object NarrationCurriculum {
    private val failures = NarrationCurriculumFailurePolicy()

    private fun unavailable(context: Context, message: String, error: Throwable, fields: Map<String, Any>) {
        val path = error.message?.takeIf { it.isNotBlank() }
            ?: fields.values.joinToString("/")
        when (failures.classify(path, error)) {
            NarrationCurriculumFailurePolicy.Decision.FIRST_MISSING ->
                KokoroNarrationDiagnostics.event(context, "KokoroBackground", "Curriculum content not packaged",
                    fields = fields + mapOf("path" to path, "missingPaths" to failures.missingCount))
            NarrationCurriculumFailurePolicy.Decision.REPEATED_MISSING -> Unit
            NarrationCurriculumFailurePolicy.Decision.UNEXPECTED ->
                KokoroNarrationDiagnostics.event(context, "KokoroBackground", message, error, fields)
        }
    }

    data class Sentence(
        val text: String,
        val language: NarrationLanguage,
        val voice: String,
        val location: String
    )

    data class Page(
        val routeId: String,
        val courseId: String,
        val unitId: String,
        val lessonId: String,
        val pageIndex: Int,
        val sentences: List<Sentence>
    ) {
        val identity: String get() = "$routeId/$courseId/$lessonId/${pageIndex + 1}"
        val location: String get() = "$routeId/$courseId/$unitId/$lessonId/${pageIndex + 1}"
    }

    fun sentences(context: Context): Sequence<Sentence> = pages(context).flatMap { it.sentences.asSequence() }

    /** The existing manifests remain the sole source of route/course/page order. */
    fun pages(context: Context, routeFilter: String? = null, courseFilter: String? = null,
              requireComplete: Boolean = false,
              profile: NarrationGenerationProfile? = null): Sequence<Page> = sequence {
        fun checkCancelled() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Curriculum preparation cancelled")
        }
        // A lazy iterator can scan many cached/ineligible pages before yielding. Respect
        // the service's existing interrupt here too, before the worker sees its next item.
        checkCancelled()
        // A lazy scan must never mix voices from different user selections.
        // Its owner cancels the pass when this immutable profile is superseded.
        val selection = profile ?: NarrationSettingsRepository.generationProfile(context)
        val routes = listOf(
            AppearanceSettingsRepository.ROUTE_PROGRAMMING,
            AppearanceSettingsRepository.ROUTE_MATH_PHYSICS
        )
        for (route in routes.filter { routeFilter == null || it == routeFilter }) {
            checkCancelled()
            val courses = runCatching { CourseRepository.loadCourses(context, route) }
                .onFailure { unavailable(context, "Unavailable curriculum route", it,
                    mapOf("route" to route)) }
                .getOrElse { failure ->
                        if (requireComplete) throw failure
                        emptyList()
                    }.sortedBy { it.number }
            for (course in courses.filter { courseFilter == null || it.id == courseFilter }) {
                checkCancelled()
                val units = runCatching { UnitRepository.loadUnits(context, route, course.id) }
                    .onFailure { unavailable(context, "Unavailable curriculum course", it,
                        mapOf("route" to route, "course" to course.id)) }
                    .getOrElse { failure ->
                        if (requireComplete && !NarrationCurriculumScanSafety.isUnpublishedCourse(failure))
                            throw failure
                        emptyList()
                    }.sortedBy { it.number }
                for (unit in units) {
                    checkCancelled()
                    val lessons = runCatching {
                        LessonRepository.loadLessons(context, route, course.id, unit.id)
                    }.onFailure { unavailable(context, "Unavailable curriculum unit", it,
                        mapOf("route" to route, "course" to course.id, "unit" to unit.id)) }
                        .getOrElse { failure ->
                        if (requireComplete) throw failure
                        emptyList()
                    }.sortedBy { it.number }
                    for (lesson in lessons) {
                        checkCancelled()
                        val parsed = runCatching {
                            LessonRepository.loadLessonJson(context, route, lesson.id)
                        }
                        if (parsed.isFailure) {
                            val failure = requireNotNull(parsed.exceptionOrNull())
                            unavailable(context, "Skipping unavailable lesson", failure,
                                mapOf("lesson" to lesson.id))
                            if (requireComplete) throw failure
                            continue
                        }
                        val json = parsed.getOrThrow()
                        val pages = json.optJSONArray("pages")
                        if (pages == null) {
                            if (requireComplete) error("Lesson pages unavailable: $route/${lesson.id}")
                            continue
                        }
                        for (pageIndex in 0 until pages.length()) {
                            checkCancelled()
                            val language = NarrationLanguage.forLesson(json, pageIndex, selection.language)
                            val script = LessonNarrationScript.page(json, pageIndex, language,
                                forKokoro = MixedSpanishG2P.productionEnabled) { blockType ->
                                NarrationUiInstructions.forBlock(context, blockType)
                            }
                            val sentences = NarrationSegments.sentences(script.texts).map { text ->
                                Sentence(
                                    text, language, selection.voice(language),
                                    "$route/${course.id}/${unit.id}/${lesson.id}/${pageIndex + 1}"
                                )
                            }
                            checkCancelled()
                            yield(Page(route, course.id, unit.id, lesson.id, pageIndex, sentences))
                        }
                    }
                }
            }
        }
    }
}

/**
 * courses.json publishes the full future roadmap. A course without its own
 * course_<id>.json manifest has no packaged pages, so its missing manifest is
 * an expected placeholder. Once a course manifest exists, missing unit or
 * lesson assets must abort a destructive ownership scan.
 */
internal object NarrationCurriculumScanSafety {
    fun isUnpublishedCourse(failure: Throwable): Boolean = failure is FileNotFoundException
}

/** Optional future-course assets are expected; corrupt installed JSON must keep its full error. */
internal class NarrationCurriculumFailurePolicy {
    enum class Decision { FIRST_MISSING, REPEATED_MISSING, UNEXPECTED }
    private val missing = mutableSetOf<String>()
    val missingCount: Int get() = synchronized(this) { missing.size }

    @Synchronized fun classify(path: String, error: Throwable): Decision {
        if (error !is FileNotFoundException) return Decision.UNEXPECTED
        return if (missing.add(path)) Decision.FIRST_MISSING else Decision.REPEATED_MISSING
    }
}
