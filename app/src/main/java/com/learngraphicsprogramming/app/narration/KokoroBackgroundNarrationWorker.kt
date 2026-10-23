package com.learngraphicsprogramming.app.narration

import android.content.Context
import android.os.SystemClock
import com.learngraphicsprogramming.app.narration.phonemes.EnglishMisakiG2P
import com.learngraphicsprogramming.app.narration.phonemes.FrenchFluidG2P
import com.learngraphicsprogramming.app.narration.phonemes.SpanishFluidG2P
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Current-page work survives its activity; the curriculum resumes in its original order. */
internal class KokoroBackgroundNarrationWorker(
    private val app: Context,
    private val catalog: ((String, String?) -> Sequence<NarrationCurriculum.Page>)? = null
) {
    enum class Outcome { COMPLETE, MORE, BUSY, PAUSED, UNAVAILABLE, RETRY }

    @Volatile private var cancelFrontend: (() -> Unit)? = null
    private val cancellation = AtomicLong()

    fun cancel() {
        cancellation.incrementAndGet()
        KokoroEngineCoordinator.cancel(this)
        cancelFrontend?.invoke()
    }

    /** Called on the service's private thread, with a bounded CPU wake lock. */
    fun runSlice(activeSession: () -> Boolean): Outcome {
        val slice = cancellation.get()
        val settings = NarrationSettingsRepository.get(app)
        if (!settings.enabled || settings.engine == NarrationEngine.ANDROID ||
            !NarrationSettingsRepository.advanceGenerationEnabled(app)) return Outcome.PAUSED
        if (!activeSession() || NarrationWorkPriority.isBusy) return Outcome.BUSY
        val profile = NarrationSettingsRepository.generationProfile(app)

        val modelStore = KokoroModelStore(app)
        var english: EnglishMisakiG2P? = null
        var french: FrenchFluidG2P? = null
        var spanish: Map<String, String>? = null
        var root: File? = null
        var skippedUninstalledVoices = false
        var deferredThisPass = false
        var scanned = 0
        var generated = 0
        var reused = 0
        val submittedPage = KokoroPagePreparationQueue.snapshot(app)
        // A voice change can precede the visible controller's main-thread observer.
        // Its old durable queue must not acquire current-page priority for the new pass.
        val page = submittedPage?.takeIf { profile.accepts(it.sentences) }
        val pageRevision = submittedPage?.revision
        val plan = NarrationAudioPlanner.store(app)
        val planEpoch = plan.snapshot().epoch
        var cpuBudget: AutoCloseable? = null
        var frontendForeground: Boolean? = null
        var frontendCpuMask: List<Int>? = null
        var currentPlanned: NarrationAudioPlan.PlannedPage? = null
        val started = SystemClock.elapsedRealtime()
        val cancel: () -> Unit = { this.cancel() }
        val interruption = NarrationWorkPriority.onPreempt(cancel)
        val pageChange = KokoroPagePreparationQueue.onChanged(cancel)
        val contextChange = plan.onChanged(cancel)
        val profileChange = NarrationSettingsRepository.observeGenerationProfile(app) {
            if (!NarrationSettingsRepository.isGenerationProfileCurrent(app, profile)) cancel()
        }
        fun pageChanged() = KokoroPagePreparationQueue.snapshot(app)?.revision != pageRevision ||
            !plan.isCurrent(planEpoch) || !NarrationSettingsRepository.isGenerationProfileCurrent(app, profile)
        fun enabled() = NarrationSettingsRepository.isEngineAllowed(app, NarrationEngine.KOKORO) &&
            NarrationSettingsRepository.advanceGenerationEnabled(app)
        fun coursePermitted(): Boolean = currentPlanned?.let { planned ->
            planned.priority == NarrationAudioPriority.CURRENT_PAGE ||
                !NarrationSettingsRepository.courseGenerationPaused(
                    app, planned.page.routeId, planned.page.courseId)
        } ?: true
        fun active() = enabled() && coursePermitted() && slice == cancellation.get() &&
            activeSession() && !NarrationWorkPriority.isBusy && !pageChanged()
        fun selectBudget(foreground: Boolean) {
            fun actualMask(): List<Int>? = if (foreground)
                runCatching { NarrationCpuBudget.allowedCpus() }.getOrNull() else NarrationCpuBudget.allowedCpus()
            val observed = actualMask()
            if (frontendForeground == foreground && frontendCpuMask == observed) return
            // Android can widen an existing thread mask when moving the process
            // between cpusets. An idle ORT pool may have been widened too, even if
            // the next selection happens to choose the same pair of CPUs.
            if (frontendForeground == false && observed != null && observed.size > 2)
                KokoroEngineCoordinator.releaseIfIdle()
            // The G2P pools also inherit the worker mask when constructed. Do not carry a
            // page-created pool into the automatic profile, or a capped pool into a page.
            cancelFrontend = null
            english?.close(); english = null
            (french as? AutoCloseable)?.close(); french = null
            cpuBudget?.close(); cpuBudget = null
            if (!foreground) cpuBudget = NarrationCpuBudget.capBackground()
            frontendForeground = foreground
            frontendCpuMask = actualMask()
        }
        fun state(value: String, item: NarrationCurriculum.Sentence? = null, scope: String = "") {
            val fields = mutableMapOf<String, Any>("workerState" to value, "sliceScanned" to scanned,
                "sliceGenerated" to generated, "sliceReused" to reused)
            if (item != null) {
                fields["currentHash"] = NarrationPageQueue.hash(item)
                fields["currentLocation"] = item.location
                fields["currentScope"] = scope
                fields["language"] = item.language.tag
                fields["voice"] = item.voice
            }
            KokoroNarrationDiagnostics.update(app, fields)
        }

        try {
            state("scanning")
            page?.let { KokoroNarrationDiagnostics.page(app, it) }
            // Validate every sentence, even when a previous pass marked it complete.
            // Completion is a progress hint, never a stale cache guarantee.
            val candidates = plan.candidates(page,
                isCoursePaused = { route, course ->
                    NarrationSettingsRepository.courseGenerationPaused(app, route, course)
                }, catalog = catalog ?: { route, course -> NarrationCurriculum.pages(app, route, course, profile = profile) })
            for (planned in candidates) {
                currentPlanned = planned
                if (!coursePermitted()) continue
                if (!profile.accepts(planned.page.sentences)) {
                    // A supplied/recovered catalog must obey the same immutable
                    // voice selection as the production iterator.
                    deferredThisPass = true
                    plan.defer(planned)
                    continue
                }
                val foreground = planned.priority == NarrationAudioPriority.CURRENT_PAGE
                val scope = when (planned.priority) {
                    NarrationAudioPriority.CURRENT_PAGE -> "page"
                    NarrationAudioPriority.UNIT -> "unit"
                    NarrationAudioPriority.COURSE -> "course"
                    NarrationAudioPriority.GENERAL -> "curriculum"
                }
                val generatedBefore = generated
                val pageSentences = planned.page.sentences.distinctBy(NarrationPageQueue::hash)
                try {
                    for ((sentenceIndex, item) in pageSentences.withIndex()) {
                        if (!enabled()) return Outcome.PAUSED
                        if (!coursePermitted()) return Outcome.MORE
                        if (pageChanged()) return Outcome.MORE
                        if (slice != cancellation.get() || !activeSession() || NarrationWorkPriority.isBusy) return Outcome.BUSY
                        scanned++
                        val hash = NarrationPageQueue.hash(item)
                        if (KokoroNarrationCache.lookup(app, item.language.tag, item.voice, item.text) != null) {
                            reused++
                            if (scope == "page" && pageRevision != null) KokoroPagePreparationQueue.markCompleted(app, pageRevision, hash)
                            if (scanned % 25 == 0) state("scanning", item, scope)
                            continue
                        }
                        if (scope == "page" && pageRevision != null) KokoroPagePreparationQueue.markPending(app, pageRevision, hash)

                        // Respect the configurable limit and 1 GiB reserve. Never evict audio.
                        if (!NarrationStorageBudget.fitsNext(NarrationStorageBudget.snapshot(app), MIN_SENTENCE_HEADROOM_BYTES)) {
                            state("storage-paused", item, scope)
                            KokoroNarrationDiagnostics.event(app, TAG, "Offline narration paused: storage limit or reserve reached")
                            return Outcome.PAUSED
                        }
                        if (root == null) {
                            root = modelStore.directory()
                            if (!File(root, "model.onnx").isFile) { state("model-unavailable"); return Outcome.UNAVAILABLE }
                        }
                        val modelRoot = requireNotNull(root)
                        val voice = KokoroVoiceCatalog.find(item.voice)
                        if (voice == null || !File(modelRoot, voice.artifact.fileName).isFile) {
                            skippedUninstalledVoices = true
                            continue
                        }

                        selectBudget(foreground)
                        state("phonemizing", item, scope)
                        val phonemes = when (item.language) {
                            NarrationLanguage.SPANISH -> {
                                if (spanish == null) {
                                    val artifact = KokoroModelManifest.resources(item.language).single()
                                    val file = File(modelRoot, artifact.fileName)
                                    require(KokoroModelManifest.verify(file, artifact)) { "No está instalado el diccionario español verificado" }
                                    spanish = SpanishFluidG2P.loadLexicon(file)
                                }
                                MixedSpanishG2P.phonemizePrepared(item.text, spanish!!)
                            }
                            NarrationLanguage.FRENCH -> MixedFrenchG2P.phonemizePrepared(item.text,
                                french ?: FrenchFluidG2P(modelRoot, File(modelRoot, "tokenizer.json")).also { french = it })
                            else -> (english ?: EnglishMisakiG2P(modelRoot).also {
                                english = it
                                cancelFrontend = { it.cancelCurrentRun() }
                            })
                                .phonemize(item.text, item.language == NarrationLanguage.BRITISH_ENGLISH, ::active)
                        }
                        if (!active()) return if (pageChanged()) Outcome.MORE else Outcome.BUSY
                        state("waiting-engine", item, scope)
                        // ONE shared ONNX engine. Recheck under its exclusive lease: the
                        // interactive controller may have finished the same sentence while we waited.
                        KokoroEngineCoordinator.withEngine(this, modelRoot, { !active() }, foreground = foreground) { engine ->
                            if (KokoroNarrationCache.lookup(app, item.language.tag, item.voice, item.text) != null) {
                                reused++
                                return@withEngine
                            }
                            state("synthesizing", item, scope)
                            val parts = KokoroPhonemeChunks.split(phonemes).map { part ->
                                if (!active()) throw InterruptedException("Background narration yielded to higher priority work")
                                engine.synthesizePhonemes(part, item.voice, 1f) { !active() }
                            }
                            if (!active()) throw InterruptedException("Background narration yielded to higher priority work")
                            val temporary = File(app.cacheDir, "kokoro-offline-${System.nanoTime()}.wav")
                            try {
                                NarrationWavWriter.writeChunks(temporary, parts)
                                state("saving", item, scope)
                                if (!active()) throw InterruptedException("Course preparation paused before saving")
                                if (!NarrationStorageBudget.fitsNext(NarrationStorageBudget.snapshot(app), temporary.length())) {
                                    throw StorageBudgetReached()
                                }
                                checkNotNull(KokoroNarrationCache.store(app, item.language.tag, item.voice, item.text, temporary)) {
                                    "No se pudo guardar el audio generado"
                                }
                                generated++
                                KokoroNarrationSessionMonitor.onAudioGenerated()
                            } finally { temporary.delete() }
                        }
                        if (scope == "page" && pageRevision != null) KokoroPagePreparationQueue.markCompleted(app, pageRevision, hash)
                        state("sentence-complete", item, scope)
                        KokoroNarrationDiagnostics.event(app, TAG, "Cached lesson speech", fields = mapOf(
                            "hash" to hash, "scope" to scope, "location" to item.location, "generated" to generated, "reused" to reused))
                        if (sentenceIndex < pageSentences.lastIndex && SystemClock.elapsedRealtime() - started >= SLICE_MS)
                            return Outcome.MORE
                    }
                    if (planned.page.sentences.all {
                            KokoroNarrationCache.lookup(app, it.language.tag, it.voice, it.text) != null
                        }) plan.completed(planned, generated > generatedBefore)
                    else { deferredThisPass = true; plan.defer(planned) }
                } catch (error: Exception) {
                    if (error is InterruptedException || error is StorageBudgetReached || error is NarrationCpuLimitException) throw error
                    // A broken sentence remains pending; it must not starve the other area.
                    deferredThisPass = true
                    plan.defer(planned)
                    state("retry-page")
                    KokoroNarrationDiagnostics.event(app, TAG, "Narration page deferred without discarding valid audio", error,
                        fields = mapOf("page" to planned.page.identity, "scope" to scope))
                }
                if (SystemClock.elapsedRealtime() - started >= SLICE_MS) return Outcome.MORE
            }
            // A newly opened page may arrive while the final optional courses
            // are being scanned, after the sequence's last yielded sentence.
            if (pageChanged()) return Outcome.MORE
            if (deferredThisPass || plan.hasDeferredWork()) return if (skippedUninstalledVoices) Outcome.UNAVAILABLE else Outcome.RETRY
            if (skippedUninstalledVoices) {
                state("voice-unavailable")
                KokoroNarrationDiagnostics.event(app, TAG, "Offline narration pending: some voices are not installed")
                return Outcome.UNAVAILABLE
            }
            state("complete")
            KokoroNarrationDiagnostics.event(app, TAG, "All available lesson sentences are cached", fields = mapOf("scanned" to scanned))
            return Outcome.COMPLETE
        } catch (error: NarrationCpuPairBusyException) {
            state("cpu-pair-busy")
            KokoroNarrationDiagnostics.event(app, TAG,
                "Optional generation waiting for the previous CPU pair to be released", error)
            return Outcome.BUSY
        } catch (error: NarrationCpuLimitException) {
            state("cpu-limit-unavailable")
            KokoroNarrationDiagnostics.event(app, TAG, "Optional generation paused: Android CPU limit could not be guaranteed", error)
            return Outcome.PAUSED
        } catch (_: StorageBudgetReached) {
            state("storage-paused")
            KokoroNarrationDiagnostics.event(app, TAG, "Offline narration paused before committing WAV: storage budget reached")
            return Outcome.PAUSED
        } catch (_: InterruptedException) {
            state("preempted")
            return if (pageChanged() || !coursePermitted()) Outcome.MORE else Outcome.BUSY
        } catch (error: Exception) {
            state("retry")
            KokoroNarrationDiagnostics.event(app, TAG, "Offline narration interrupted or resources unavailable", error)
            return Outcome.RETRY
        } finally {
            cancelFrontend = null
            interruption.close()
            pageChange.close()
            contextChange.close()
            profileChange.close()
            runCatching { english?.close() }
            runCatching { (french as? AutoCloseable)?.close() }
            try { cpuBudget?.close() } finally { modelStore.close() }
        }
    }

    private class StorageBudgetReached : IllegalStateException()

    companion object {
        private const val TAG = "KokoroBackground"
        private const val SLICE_MS = 4L * 60 * 1000
        private const val MIN_SENTENCE_HEADROOM_BYTES = 4L * NarrationStorageBudget.MIB
        fun start(context: Context) { KokoroNarrationSessionMonitor.refresh(context) }
    }
}
