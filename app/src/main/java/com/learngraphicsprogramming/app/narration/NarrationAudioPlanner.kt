package com.learngraphicsprogramming.app.narration

import android.content.Context
import android.util.AtomicFile
import com.learngraphicsprogramming.app.AppearanceSettingsRepository
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

internal enum class NarrationAudioPriority { CURRENT_PAGE, UNIT, COURSE, GENERAL }

/** Scheduling metadata only; audio validity and completed speech remain owned by the real cache. */
internal class NarrationAudioPlan(
    stored: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val persist: (Snapshot) -> Unit = {}
) {
    data class Selection(val routeId: String? = null, val courseId: String? = null,
                         val lessonId: String? = null, val pageIndex: Int? = null,
                         val pageRevision: Long? = null, val unitId: String? = null)
    data class Snapshot(val epoch: Long, val context: Selection, val nextRoute: String,
                        val cursors: Map<String, String>) {
        fun encode(): String = JSONObject().put("format", 1).put("epoch", epoch)
            .put("context", JSONObject().put("route", context.routeId ?: JSONObject.NULL)
                .put("course", context.courseId ?: JSONObject.NULL)
                .put("lesson", context.lessonId ?: JSONObject.NULL)
                .put("unit", context.unitId ?: JSONObject.NULL)
                .put("page", context.pageIndex ?: JSONObject.NULL)
                .put("pageRevision", context.pageRevision ?: JSONObject.NULL))
            .put("nextRoute", nextRoute).put("cursors", JSONObject(cursors)).toString()
    }
    data class PlannedPage(val page: NarrationCurriculum.Page, val priority: NarrationAudioPriority, val epoch: Long)

    private var current = stored?.let { runCatching { decode(it) }.getOrNull() }
        ?: Snapshot(0L, Selection(), ROUTES.first(), emptyMap())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val deferred = linkedMapOf<String, Long>()

    @Synchronized fun snapshot(): Snapshot = current
    @Synchronized fun isCurrent(epoch: Long): Boolean = current.epoch == epoch

    fun enter(routeId: String? = null, courseId: String? = null, lessonId: String? = null,
              pageIndex: Int? = null, pageRevision: Long? = null, unitId: String? = null): Snapshot {
        val requested = normalize(Selection(routeId, courseId, lessonId, pageIndex, pageRevision, unitId))
        val next = synchronized(this) {
            val selection = if (requested.pageRevision == null &&
                requested.copy(pageRevision = current.context.pageRevision) == current.context)
                requested.copy(pageRevision = current.context.pageRevision) else requested
            if (current.context == selection) return current
            val value = current.copy(epoch = current.epoch + 1L, context = selection)
            persist(value)
            current = value
            value
        }
        listeners.forEach { runCatching { it() } }
        return next
    }

    fun onChanged(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    /** Call only after the worker verifies all of this page's actual cached recordings. */
    @Synchronized fun completed(planned: PlannedPage, generated: Boolean): Boolean {
        if (planned.epoch != current.epoch || planned.page.routeId !in ROUTES) return false
        // A cache hit is already durable in the audio store. Scanning thousands of valid pages
        // must not turn into thousands of scheduler writes or consume alternating turns.
        if (!generated) { deferred.remove(planned.page.identity); return true }
        val nextRoute = if (current.context.routeId == null && planned.priority == NarrationAudioPriority.GENERAL)
            other(planned.page.routeId) else current.nextRoute
        val value = current.copy(nextRoute = nextRoute,
            cursors = current.cursors + (planned.page.routeId to planned.page.identity))
        if (value != current) { persist(value); current = value }
        deferred.remove(planned.page.identity)
        return true
    }

    /** A failed/unavailable page stays pending. Its other area can continue during this retry delay. */
    @Synchronized fun defer(planned: PlannedPage, delayMs: Long = 20_000L): Boolean {
        if (planned.epoch != current.epoch || planned.page.routeId !in ROUTES) return false
        val next = if (current.context.routeId == null && planned.priority == NarrationAudioPriority.GENERAL)
            current.copy(nextRoute = other(planned.page.routeId)) else current
        if (next != current) { persist(next); current = next }
        if (deferred.size >= 512) deferred.remove(deferred.minBy { it.value }.key)
        deferred[planned.page.identity] = now() + delayMs.coerceIn(0L, 600_000L)
        return true
    }

    @Synchronized fun hasDeferredWork(): Boolean = deferred.isNotEmpty()
    @Synchronized private fun eligible(page: NarrationCurriculum.Page): Boolean {
        return deferred[page.identity]?.let { it <= now() } ?: true
    }

    /**
     * The consumer checks cache validity. After each yielded page it calls completed/defer;
     * cached-only pages do not consume an alternating turn. Cursors are hints, never grounds
     * for skipping earlier files that may now be absent or invalid.
     */
    // Original public contract remains unchanged for callers that provide
    // their catalog as a trailing lambda (including the curriculum and tests).
    fun candidates(pageQueue: NarrationPageQueue.Snapshot?,
                   catalog: (route: String, course: String?) -> Sequence<NarrationCurriculum.Page>): Sequence<PlannedPage> =
        candidates(pageQueue, isCoursePaused = { _, _ -> false }, catalog = catalog)

    /** Pausing a course omits only speculative COURSE/GENERAL work. A page the
     * learner has opened remains eligible even when its course is paused. */
    fun candidates(pageQueue: NarrationPageQueue.Snapshot?,
                   isCoursePaused: (route: String, course: String) -> Boolean,
                   catalog: (route: String, course: String?) -> Sequence<NarrationCurriculum.Page>): Sequence<PlannedPage> = sequence {
        val start = snapshot()
        val selection = start.context
        val seen = mutableSetOf<String>()
        fun planned(page: NarrationCurriculum.Page, priority: NarrationAudioPriority): PlannedPage? {
            // Skip before touching deduplication, completion hints or persisted cursors.
            if (priority != NarrationAudioPriority.CURRENT_PAGE &&
                isCoursePaused(page.routeId, page.courseId)) return null
            if (page.sentences.isEmpty() || !seen.add(page.identity) || !eligible(page)) return null
            return PlannedPage(page, priority, start.epoch)
        }
        if (selection.routeId != null && selection.lessonId != null && selection.pageIndex != null) {
            val queue = pageQueue?.takeIf { it.sentences.isNotEmpty() }
            val currentPage = if (queue != null && selection.pageRevision == queue.revision) {
                NarrationCurriculum.Page(selection.routeId, selection.courseId.orEmpty(),
                    selection.unitId.orEmpty(), selection.lessonId, selection.pageIndex, queue.sentences)
            } else {
                catalog(selection.routeId, selection.courseId).firstOrNull {
                    it.lessonId == selection.lessonId && it.pageIndex == selection.pageIndex
                }?.let { actual ->
                    if (queue != null && queue.sentences.map(NarrationPageQueue::hash).distinct() ==
                        actual.sentences.map(NarrationPageQueue::hash).distinct()) actual.copy(sentences = queue.sentences)
                    else actual
                }
            }
            if (!isCurrent(start.epoch)) return@sequence
            currentPage?.let { page -> planned(page, NarrationAudioPriority.CURRENT_PAGE)?.let { yield(it) } }
        }
        if (selection.routeId != null && selection.courseId != null &&
            !isCoursePaused(selection.routeId, selection.courseId)) {
            if (selection.unitId != null) {
                for (page in catalog(selection.routeId, selection.courseId)) {
                    if (!isCurrent(start.epoch)) return@sequence
                    if (page.unitId == selection.unitId)
                        planned(page, NarrationAudioPriority.UNIT)?.let { yield(it) }
                }
            }
            for (page in catalog(selection.routeId, selection.courseId)) {
                if (!isCurrent(start.epoch)) return@sequence
                planned(page, NarrationAudioPriority.COURSE)?.let { yield(it) }
            }
        }
        val iterators = mutableMapOf<String, Iterator<NarrationCurriculum.Page>>()
        val exhausted = mutableSetOf<String>()
        while (exhausted.size < ROUTES.size) {
            if (!isCurrent(start.epoch)) return@sequence
            val preferred = selection.routeId ?: snapshot().nextRoute
            val route = if (preferred !in exhausted) preferred else other(preferred)
            val iterator = iterators.getOrPut(route) { catalog(route, null).iterator() }
            var next: PlannedPage? = null
            while (iterator.hasNext() && next == null) {
                if (!isCurrent(start.epoch)) return@sequence
                next = planned(iterator.next(), NarrationAudioPriority.GENERAL)
            }
            if (next == null) exhausted += route else yield(next)
        }
    }

    companion object {
        val ROUTES = listOf(AppearanceSettingsRepository.ROUTE_PROGRAMMING, AppearanceSettingsRepository.ROUTE_MATH_PHYSICS)
        private fun other(route: String) = if (route == ROUTES.first()) ROUTES.last() else ROUTES.first()
        private fun normalize(value: Selection): Selection {
            if (value.routeId !in ROUTES) return Selection()
            val lesson = value.lessonId?.takeIf { it.isNotBlank() }
            val index = value.pageIndex?.takeIf { lesson != null && it >= 0 }
            val unit = value.unitId?.takeIf { it.isNotBlank() }
                ?: lesson?.substringBeforeLast("__", "")?.takeIf { it.isNotBlank() }
            return value.copy(courseId = value.courseId?.takeIf { it.isNotBlank() }, lessonId = lesson,
                pageIndex = index,
                pageRevision = value.pageRevision?.takeIf { index != null && it >= 0L }, unitId = unit)
        }
        private fun decode(value: String): Snapshot {
            val json = JSONObject(value)
            require(json.getInt("format") == 1)
            val spec = json.getJSONObject("context")
            fun string(name: String) = (spec.opt(name) as? String)?.takeIf { it.isNotBlank() }
            val selection = normalize(Selection(string("route"), string("course"), string("lesson"),
                if (spec.isNull("page")) null else spec.getInt("page"),
                if (spec.isNull("pageRevision")) null else spec.getLong("pageRevision"), string("unit")))
            val cursorJson = json.optJSONObject("cursors") ?: JSONObject()
            val cursors = ROUTES.mapNotNull { route -> (cursorJson.opt(route) as? String)?.let { route to it } }.toMap()
            return Snapshot(json.getLong("epoch").coerceAtLeast(0L), selection,
                json.optString("nextRoute").takeIf { it in ROUTES } ?: ROUTES.first(), cursors)
        }
    }
}

internal typealias NarrationAudioPlanStore = NarrationAudioPlan

internal object NarrationAudioPlanner {
    const val FILE_NAME = "narration-audio-plan.json"
    private val stores = mutableMapOf<File, NarrationAudioPlan>()
    /** Optional scheduling must never prevent navigation, page rendering or playback. */
    fun enter(context: Context, routeId: String? = null, courseId: String? = null,
              lessonId: String? = null, pageIndex: Int? = null, pageRevision: Long? = null,
              unitId: String? = null) {
        runCatching { store(context).enter(routeId, courseId, lessonId, pageIndex, pageRevision, unitId) }
            .onFailure { KokoroNarrationDiagnostics.event(context, "KokoroBackground",
                "Could not persist the latest narration scheduling context", it) }
    }
    @Synchronized fun store(context: Context): NarrationAudioPlanStore {
        val file = File(context.applicationContext.filesDir, FILE_NAME).canonicalFile
        return stores.getOrPut(file) {
            val stored = runCatching { AtomicFile(file).openRead().bufferedReader().use { it.readText() } }.getOrNull()
            NarrationAudioPlan(stored = stored, persist = { KokoroPagePreparationQueue.writeAtomic(file, it.encode()) })
        }
    }
    fun candidates(context: Context, pageQueue: NarrationPageQueue.Snapshot?): Sequence<NarrationAudioPlan.PlannedPage> =
        store(context).candidates(pageQueue) { route, course -> NarrationCurriculum.pages(context, route, course) }
}
