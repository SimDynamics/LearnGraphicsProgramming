package com.learngraphicsprogramming.app.narration

import org.junit.Assert.*
import org.junit.Test

class NarrationAudioPlannerTest {
    private val programming = NarrationAudioPlan.ROUTES.first()
    private val math = NarrationAudioPlan.ROUTES.last()
    private fun page(route: String, index: Int, course: String = "course1", text: String = "$route page $index") =
        NarrationCurriculum.Page(route, course, "${course}__unit1", "${course}__unit1__lesson1", index,
            listOf(NarrationCurriculum.Sentence(text, NarrationLanguage.SPANISH, "ef_dora", "$route/$course/unit1/lesson1/${index + 1}")))
    private fun catalog(pages: List<NarrationCurriculum.Page>): (String, String?) -> Sequence<NarrationCurriculum.Page> =
        { route, course -> pages.asSequence().filter { it.routeId == route && (course == null || it.courseId == course) } }

    @Test fun currentPageIsFirstWithoutScanningTheCatalogWhenItsQueueRevisionIsKnown() {
        val plan = NarrationAudioPlan()
        val target = page(math, 2)
        val queue = NarrationPageQueue.Snapshot(7L, target.sentences, emptySet())
        plan.enter(math, target.courseId, target.lessonId, target.pageIndex, queue.revision)
        var scans = 0
        val next = plan.candidates(queue) { _, _ -> scans++; emptySequence() }.first()
        assertEquals(0, scans)
        assertEquals(NarrationAudioPriority.CURRENT_PAGE, next.priority)
        assertSame(queue.sentences, next.page.sentences)
        assertEquals(target.identity, next.page.identity)
    }

    @Test fun currentPageThenPendingCoursePagesRetainTheirCurricularOrder() {
        val pages = listOf(page(programming, 0), page(math, 0), page(math, 1), page(math, 2), page(math, 0, "course2"))
        val target = pages[2]
        val queue = NarrationPageQueue.Snapshot(2L, target.sentences, emptySet())
        val plan = NarrationAudioPlan()
        plan.enter(math, target.courseId, target.lessonId, target.pageIndex, queue.revision)
        val result = plan.candidates(queue, catalog(pages)).toList()
        assertEquals(listOf(target.identity, pages[1].identity, pages[3].identity, pages[4].identity, pages[0].identity),
            result.map { it.page.identity })
        assertEquals(listOf(NarrationAudioPriority.CURRENT_PAGE, NarrationAudioPriority.UNIT,
            NarrationAudioPriority.UNIT, NarrationAudioPriority.GENERAL, NarrationAudioPriority.GENERAL), result.map { it.priority })
        assertEquals(result.size, result.map { it.page.identity }.distinct().size)
    }

    @Test fun homeAlternatesPendingPagesRatherThanCountingAlreadyCachedPagesAsTurns() {
        val pages = (0..2).map { page(programming, it) } + (0..2).map { page(math, it) }
        val cached = setOf(pages[0].identity, pages[3].identity)
        val plan = NarrationAudioPlan()
        val generated = mutableListOf<String>()
        plan.candidates(null, catalog(pages)).forEach { planned ->
            val missing = planned.page.identity !in cached
            if (missing) generated += planned.page.identity
            assertTrue(plan.completed(planned, generated = missing))
        }
        assertEquals(listOf(pages[1].identity, pages[4].identity, pages[2].identity, pages[5].identity), generated)
    }

    @Test fun nextAreaAndEachAreaCursorSurviveRecreationWithoutStoringSpeechText() {
        var persisted = ""
        val pages = listOf(page(programming, 0, text = "Private original lesson text."), page(math, 0))
        val plan = NarrationAudioPlan(persist = { persisted = it.encode() })
        val first = plan.candidates(null, catalog(pages)).first()
        plan.completed(first, generated = true)
        assertFalse(persisted.contains("Private original lesson text"))
        val restored = NarrationAudioPlan(persisted)
        assertEquals(math, restored.snapshot().nextRoute)
        assertEquals(mapOf(programming to pages[0].identity), restored.snapshot().cursors)
        val second = restored.candidates(null, catalog(pages)).first()
        assertEquals(math, second.page.routeId)
        restored.completed(second, generated = true)
        assertEquals(pages[0].identity, restored.snapshot().cursors[programming])
        assertEquals(pages[1].identity, restored.snapshot().cursors[math])
    }

    @Test fun partialPageDoesNotAdvanceTheCursorOrConsumeItsAlternatingTurn() {
        var persisted = ""
        val pages = listOf(page(programming, 0), page(math, 0))
        val plan = NarrationAudioPlan(persist = { persisted = it.encode() })
        val interrupted = plan.candidates(null, catalog(pages)).first()
        assertEquals(programming, interrupted.page.routeId)
        // A sentence may finish, but only the worker can verify the complete page.
        val restored = NarrationAudioPlan(persisted)
        assertTrue(restored.snapshot().cursors.isEmpty())
        assertEquals(interrupted.page.identity, restored.candidates(null, catalog(pages)).first().page.identity)
    }

    @Test fun latestContextInvalidatesOldCandidatesCompletionsAndFailures() {
        val pages = listOf(page(programming, 0), page(programming, 1), page(math, 0))
        val plan = NarrationAudioPlan()
        val iterator = plan.candidates(null, catalog(pages)).iterator()
        val old = iterator.next()
        plan.enter(math, "course1")
        assertFalse(iterator.hasNext())
        assertFalse(plan.completed(old, generated = true))
        assertFalse(plan.defer(old))
        assertTrue(plan.snapshot().cursors.isEmpty())
        assertEquals(math, plan.candidates(null, catalog(pages)).first().page.routeId)
    }

    @Test fun aFailedPageStaysPendingWhileTheOtherAreaContinuesAndCanBeRetried() {
        var clock = 10_000L
        val pages = listOf(page(programming, 0), page(math, 0))
        val plan = NarrationAudioPlan(now = { clock })
        val iterator = plan.candidates(null, catalog(pages)).iterator()
        val failed = iterator.next()
        plan.defer(failed, 1000L)
        assertTrue(plan.hasDeferredWork())
        assertTrue(plan.snapshot().cursors.isEmpty())
        assertEquals(math, iterator.next().page.routeId)
        assertEquals(listOf(math), plan.candidates(null, catalog(pages)).map { it.page.routeId }.toList())
        clock += 1001L
        assertTrue("The retry delay ending must not falsely complete the failed page", plan.hasDeferredWork())
        plan.enter(programming, "course1")
        val retry = plan.candidates(null, catalog(pages)).first()
        assertEquals(failed.page.identity, retry.page.identity)
        plan.completed(retry, generated = true)
        assertFalse(plan.hasDeferredWork())
    }

    @Test fun aCompleteOrUnavailableAreaNeverBlocksTheOtherArea() {
        val pages = (0..2).map { page(math, it) }
        val plan = NarrationAudioPlan()
        val generated = mutableListOf<String>()
        plan.candidates(null, catalog(pages)).forEach { next ->
            generated += next.page.identity
            plan.completed(next, generated = true)
        }
        assertEquals(pages.map { it.identity }, generated)
    }

    @Test fun oldCurrentPageQueueCannotBeAssignedToANewLessonOrCourse() {
        val previous = page(programming, 0)
        val target = page(math, 1)
        val queue = NarrationPageQueue.Snapshot(3L, previous.sentences, emptySet())
        val plan = NarrationAudioPlan()
        plan.enter(math, target.courseId, target.lessonId, target.pageIndex)
        val next = plan.candidates(queue, catalog(listOf(previous, target))).first()
        assertEquals(target.identity, next.page.identity)
        assertEquals(target.sentences, next.page.sentences)
        assertEquals(NarrationAudioPriority.CURRENT_PAGE, next.priority)
    }

    @Test fun sameContextDoesNotPreemptButLatestPageRevisionDoes() {
        val plan = NarrationAudioPlan()
        var changes = 0
        val subscription = plan.onChanged { changes++ }
        try {
            val first = plan.enter(math, "course1", "lesson1", 0, 1L)
            assertEquals(first, plan.enter(math, "course1", "lesson1", 0, 1L))
            assertEquals("A navigation refresh must not forget the known audio queue revision",
                first, plan.enter(math, "course1", "lesson1", 0))
            assertEquals(1, changes)
            val second = plan.enter(math, "course1", "lesson1", 1, 2L)
            assertTrue(second.epoch > first.epoch)
            assertEquals(2, changes)
            val home = plan.enter()
            assertNull(home.context.routeId)
            assertNull(home.context.pageRevision)
        } finally { subscription.close() }
    }

    @Test fun persistenceFailureCannotPublishFalseAudioProgressOrConsumeATurn() {
        var failWrites = false
        val plan = NarrationAudioPlan(persist = { if (failWrites) throw java.io.IOException("No storage") })
        val next = plan.candidates(null, catalog(listOf(page(programming, 0)))).first()
        failWrites = true
        try {
            plan.completed(next, generated = true)
            fail("Expected persistence failure")
        } catch (_: java.io.IOException) { }
        assertEquals(programming, plan.snapshot().nextRoute)
        assertTrue(plan.snapshot().cursors.isEmpty())
    }

    @Test fun scanningCachedPagesDoesNotWriteSchedulerStateOrAdvanceItsTurn() {
        var writes = 0
        val pages = (0..99).map { page(programming, it) } + listOf(page(math, 0))
        val plan = NarrationAudioPlan(persist = { writes++ })
        plan.candidates(null, catalog(pages)).forEach { assertTrue(plan.completed(it, generated = false)) }
        assertEquals(0, writes)
        assertEquals(programming, plan.snapshot().nextRoute)
        assertTrue(plan.snapshot().cursors.isEmpty())
    }

    @Test fun pausingOneCourseSkipsItsAdvancePagesButPreservesTheOpenPage() {
        val visible = page(programming, 0, course = "course1")
        val next = page(programming, 1, course = "course1")
        val other = page(programming, 0, course = "course2")
        val mathPage = page(math, 0)
        val plan = NarrationAudioPlan()
        val queue = NarrationPageQueue.Snapshot(9L, visible.sentences, emptySet())
        plan.enter(programming, visible.courseId, visible.lessonId, visible.pageIndex, queue.revision)

        val result = plan.candidates(queue,
            isCoursePaused = { route, course -> route == programming && course == "course1" },
            catalog = catalog(listOf(visible, next, other, mathPage))).toList()

        assertEquals(listOf(visible.identity, other.identity, mathPage.identity),
            result.map { it.page.identity })
        assertEquals(listOf(NarrationAudioPriority.CURRENT_PAGE,
            NarrationAudioPriority.GENERAL, NarrationAudioPriority.GENERAL),
            result.map { it.priority })
        assertTrue(plan.snapshot().cursors.isEmpty())
        assertFalse(plan.hasDeferredWork())
    }

    @Test fun resumedCourseRecoversItsSkippedAudioWithoutAlteringPlanOrFiles() {
        val stopped = page(programming, 0, course = "course1")
        val other = page(programming, 1, course = "course2")
        val mathPage = page(math, 0)
        val plan = NarrationAudioPlan()
        var paused = true
        val excluded = plan.candidates(null,
            isCoursePaused = { route, course -> paused && route == programming && course == "course1" },
            catalog = catalog(listOf(stopped, other, mathPage))).toList()
        assertEquals(listOf(other.identity, mathPage.identity), excluded.map { it.page.identity })
        assertTrue(plan.snapshot().cursors.isEmpty())

        paused = false
        val resumed = plan.candidates(null,
            isCoursePaused = { route, course -> paused && route == programming && course == "course1" },
            catalog = catalog(listOf(stopped, other, mathPage))).toList()
        assertEquals(listOf(stopped.identity, other.identity, mathPage.identity),
            resumed.map { it.page.identity })
        assertEquals(0L, plan.snapshot().epoch)
        assertTrue(plan.snapshot().cursors.isEmpty())
    }

    @Test fun pausingDuringTheLazyCurriculumScanSkipsFurtherCoursePages() {
        val first = page(programming, 0, course = "course1")
        val pending = page(programming, 1, course = "course1")
        val available = page(programming, 0, course = "course2")
        val mathPage = page(math, 0)
        val paused = mutableSetOf<Pair<String, String>>()
        val plan = NarrationAudioPlan()
        val iterator = plan.candidates(null,
            isCoursePaused = { route, course -> (route to course) in paused },
            catalog = catalog(listOf(first, pending, available, mathPage))).iterator()
        assertEquals(first.identity, iterator.next().page.identity)
        paused += (programming to "course1")
        val remainder = iterator.asSequence().toList()
        assertEquals(listOf(available.identity, mathPage.identity), remainder.map { it.page.identity })
    }

    @Test fun restoredCursorNeverSkipsAnEarlierMissingOrInvalidRecording() {
        val pages = listOf(page(programming, 0), page(programming, 1))
        val plan = NarrationAudioPlan()
        val old = plan.candidates(null, catalog(pages)).toList().last()
        plan.completed(old, generated = true)
        val restored = NarrationAudioPlan(plan.snapshot().encode())
        assertEquals(pages[0].identity, restored.candidates(null, catalog(pages)).first().page.identity)
    }

    @Test fun selectedUnitIsPreparedBeforeTheRestOfItsCourseAndRouteWithoutSkippingEarlierPages() {
        val firstUnit = page(programming, 0)
        val selected = page(programming, 1).copy(unitId = "course1__unit2", lessonId = "course1__unit2__lesson1")
        val selectedEarlier = selected.copy(pageIndex = 0)
        val nextCourse = page(programming, 0, "course2")
        val otherRoute = page(math, 0)
        val pages = listOf(firstUnit, selectedEarlier, selected, nextCourse, otherRoute)
        val plan = NarrationAudioPlan()
        plan.enter(programming, "course1", unitId = selected.unitId)

        val result = plan.candidates(null, catalog(pages)).toList()
        assertEquals(listOf(selectedEarlier, selected, firstUnit, nextCourse, otherRoute), result.map { it.page })
        assertEquals(listOf(NarrationAudioPriority.UNIT, NarrationAudioPriority.UNIT,
            NarrationAudioPriority.COURSE, NarrationAudioPriority.GENERAL, NarrationAudioPriority.GENERAL),
            result.map { it.priority })
        val restored = NarrationAudioPlan(plan.snapshot().encode())
        assertEquals(selected.unitId, restored.snapshot().context.unitId)
        assertEquals(result.map { it.page }, restored.candidates(null, catalog(pages)).map { it.page }.toList())
    }

    @Test fun currentPagePreemptsItsUnitButItsRemainingPagesKeepTheirOriginalOrder() {
        val firstUnit = page(programming, 0)
        val later = page(programming, 2).copy(unitId = "course1__unit2", lessonId = "course1__unit2__lesson1")
        val earlier = later.copy(pageIndex = 0)
        val middle = later.copy(pageIndex = 1)
        val plan = NarrationAudioPlan()
        val queue = NarrationPageQueue.Snapshot(1L, later.sentences, emptySet())
        plan.enter(programming, later.courseId, later.lessonId, later.pageIndex, queue.revision)
        val result = plan.candidates(queue, catalog(listOf(firstUnit, earlier, middle, later))).toList()
        assertEquals(listOf(later.identity, earlier.identity, middle.identity, firstUnit.identity), result.map { it.page.identity })
        assertEquals(NarrationAudioPriority.CURRENT_PAGE, result.first().priority)
        assertEquals(later.unitId, plan.snapshot().context.unitId)
    }

    @Test fun changingOnlyTheUnitPreemptsTheOldScanAndPreservesOldFormatCompatibility() {
        val plan = NarrationAudioPlan()
        plan.enter(programming, "course1", unitId = "course1__unit1")
        val iterator = plan.candidates(null, catalog(listOf(page(programming, 0), page(programming, 1)))).iterator()
        val old = iterator.next()
        plan.enter(programming, "course1", unitId = "course1__unit2")
        assertFalse(iterator.hasNext())
        assertFalse(plan.completed(old, generated = true))
        val legacy = org.json.JSONObject(plan.snapshot().encode())
        legacy.getJSONObject("context").remove("unit")
        assertNull(NarrationAudioPlan(legacy.toString()).snapshot().context.unitId)
    }
}
