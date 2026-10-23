package com.learngraphicsprogramming.app.narration

import android.content.Context
import android.content.SharedPreferences
import com.learngraphicsprogramming.app.R
import com.learngraphicsprogramming.app.ProgressRepository
import com.learngraphicsprogramming.app.StableLessonStateLayout
import com.learngraphicsprogramming.app.UiLanguageSettings
import com.learngraphicsprogramming.app.math.MathAnswer
import com.learngraphicsprogramming.app.math.MathAnswerJson
import com.learngraphicsprogramming.app.math.MathNarrationPresentation
import com.learngraphicsprogramming.app.math.MathPuzzleState
import com.learngraphicsprogramming.app.math.MathPuzzleTypes
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.learngraphicsprogramming.app.narration.phonemes.EnglishMisakiG2P
import com.learngraphicsprogramming.app.narration.phonemes.FrenchFluidG2P
import com.learngraphicsprogramming.app.narration.phonemes.SpanishFluidG2P
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** The Activity installs the new reading plan before rebinding or resuming playback. */
data class NarrationPresentationUpdate(
    val deferred: Boolean = false,
    val rebind: Boolean = false,
    val resume: Boolean = false
)

/** TTS and Kokoro synthesize temporary files; one player supplies pause, seek and speed. */
class LessonNarrationController(
    context: Context,
    private val onState: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onProgress: (NarrationPlaybackProgress) -> Unit = {},
    private val trackWords: Boolean = false,
    private val onNotice: (String) -> Unit = onError
) {
    private val uiContext: Context get() = UiLanguageSettings.localizedContext(appContext)
    private fun s(id: Int, vararg args: Any): String = uiContext.getString(id, *args)
    private class UiFailure(val messageRes: Int, vararg val arguments: Any) : IllegalStateException()
    private class PassiveStoragePause : IllegalStateException()
    private class StorageBudgetReached : IllegalStateException()
    private fun errorMessage(error: Exception, fallback: Int) =
        if (error is UiFailure) s(error.messageRes, *error.arguments) else s(fallback)
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread(
        "LessonNarration",
        Process.THREAD_PRIORITY_FOREGROUND
    ).apply { start() }
    private val worker = Handler(workerThread.looper)
    private val models = KokoroModelStore(appContext)

    init {
        // Prune once, before any cache file is opened for this lesson.
        worker.post {
            runCatching { KokoroNarrationCache.prune(appContext) }
                .onFailure { android.util.Log.w("KokoroInference", "Cache pruning failed", it) }
        }
    }
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val synthesisWakeLock = appContext.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LearnGraphicsProgramming:KokoroSynthesis")
        ?.apply { setReferenceCounted(false) }

    // MediaPlayer holds its own partial lock while playing. The synthesis lock
    // keeps the CPU awake only while a neural/audio file is being generated.
    private fun holdSynthesisCpu() {
        if (synthesisWakeLock?.isHeld != true) {
            synthesisWakeLock?.acquire(210_000L)
        }
    }
    private fun releaseSynthesisCpu() {
        if (synthesisWakeLock?.isHeld == true) synthesisWakeLock.release()
    }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0) onMain { pause() }
    }
    private val focusRequest = if (Build.VERSION.SDK_INT >= 26) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener, main)
            .setAcceptsDelayedFocusGain(false).build()
    } else null

    @Volatile private var closed = false
    @Volatile private var generation = 0L
    @Volatile private var configuration = 0L
    @Volatile private var playing = false
    private var language = NarrationSettingsRepository.get(appContext).language
    private var settings = NarrationSettingsRepository.forLanguage(appContext, language)
    private var generationProfile = NarrationSettingsRepository.generationProfile(appContext)
    private var directory: File? = null
    private var segments = mutableListOf<Segment>()
    private var index = 0
    private var offset = 0L
    private var finished = false
    private var player: MediaPlayer? = null
    private var playerReady = false
    private var focusHeld = false
    private var foregroundPlayback: AutoCloseable? = null
    private val pending = mutableSetOf<Int>()
    private val watchdogs = mutableMapOf<String, Runnable>()
    private var reportedFallback = false
    private var reportedStorageStatus = false
    private var warnedNetwork = false
    private var playbackGate: NarrationExerciseGate? = null
    private var correctExercises = emptySet<Int>()
    private var pendingPausedCorrectBlock: Int? = null
    private var activePageIdentity = ""
    private var waitingForExercise = false
    private var wordAlignmentService: KokoroWordAlignment? = null
    /** One deliberately requested guide; never a page, a bookmark, or a second player. */
    private class IncorrectFeedback(
        val token: Long, val blockIndex: Int, val parts: MutableList<Segment>,
        val wasWaiting: Boolean, val wasPlaying: Boolean,
        var index: Int = 0, var offset: Long = 0L,
        @Volatile var finished: Boolean = false,
        var pendingManualForward: NarrationGatePosition? = null,
        val pending: MutableSet<Int> = mutableSetOf(),
        val unscored: Boolean = false,
        val continueAfterCompletion: Boolean = false
    )
    @Volatile private var incorrectFeedback: IncorrectFeedback? = null
    private var incorrectFeedbackSequence = 0L
    // Transient provenance for one explicit seek, not another bookmark or playback owner.
    private var pendingManualForward: NarrationGatePosition? = null
    private val wordCheckpoint = object : Runnable {
        override fun run() {
            if (!trackWords || closed || !playing || !playerReady) return
            val guide = incorrectFeedback
            if (guide != null) publishGuideProgress(guide, NarrationPlaybackPhase.ADVANCED,
                feedbackPlaybackPosition(guide))
            else {
                if (finished) return
                publishProgress(progressSnapshot(NarrationPlaybackPhase.ADVANCED).copy(
                    positionMs = playbackPoint().positionMs))
            }
            main.postDelayed(this, 50L)
        }
    }

    private fun progressSnapshot(phase: NarrationPlaybackPhase,
                                 pageIdentity: String = activePageIdentity) = NarrationPlaybackProgress(
        pageIdentity, generation, index, segments.size, phase, resumeKey, offset.coerceAtLeast(0L),
        android.os.SystemClock.uptimeMillis(), NarrationSettingsRepository.get(appContext).rate
    )

    /** UI observation must never interrupt audio, synthesis or bookmark updates. */
    private fun publishProgress(progress: NarrationPlaybackProgress) = onMain {
        runCatching { onProgress(progress) }.onFailure {
            android.util.Log.w("LessonNarration", "Playback observer failed", it)
        }
    }

    private fun publishProgress(phase: NarrationPlaybackPhase) =
        publishProgress(progressSnapshot(phase))

    private fun publishPositionChanged() {
        if (offset < 0L) {
            // The unresolved offset is relative to a previous WAV's end. Never present
            // a fabricated zero while its measured duration is still being recovered.
            val projection = NarrationVisualSeek.resolveBackward(NarrationGatePosition(index, offset),
                segments.map { it.duration })
            if (projection.resolved) publishProgress(progressSnapshot(NarrationPlaybackPhase.POSITION_CHANGED).copy(
                segmentIndex = projection.destination.segment, positionMs = projection.destination.positionMs))
            return
        }
        val from = pendingManualForward
        if (from == null && offset == 0L) {
            publishProgress(NarrationPlaybackPhase.POSITION_CHANGED)
            return
        }
        // Project the same explicit seek through available WAV durations even while paused.
        // This observes the existing gate without changing the selected segment, player,
        // bookmark or synthesis queue. Unknown durations defer the remaining visual range.
        val projection = NarrationVisualSeek.resolve(NarrationGatePosition(index, offset),
            segments.map { it.duration }) { edge, proposed -> playbackGate?.seek(edge, proposed) ?: proposed }
        val destination = projection.destination
        if (from == null) {
            // MediaPlayer normalizes an oversized seek one segment at a time. Each
            // visual callback must retain its final measured destination, not regress
            // to an intermediate segment and erase the user's explicit forward skip.
            if (projection.resolved) publishProgress(progressSnapshot(NarrationPlaybackPhase.POSITION_CHANGED).copy(
                segmentIndex = destination.segment, positionMs = destination.positionMs))
            return
        }
        val forward = destination.segment > from.segment ||
            (destination.segment == from.segment && destination.positionMs > from.positionMs)
        if (forward) publishProgress(progressSnapshot(NarrationPlaybackPhase.POSITION_CHANGED).copy(
            segmentIndex = destination.segment,
            positionMs = destination.positionMs, manualForwardFrom = from,
            manualForwardToEnd = projection.resolved && destination.segment == segments.lastIndex &&
                destination.positionMs == segments[destination.segment].duration?.toLong()))
        else publishProgress(NarrationPlaybackPhase.POSITION_CHANGED)
        if (projection.resolved) pendingManualForward = null
    }

    private fun startWordObservation() {
        if (!trackWords) return
        main.removeCallbacks(wordCheckpoint)
        main.post(wordCheckpoint)
    }

    private fun prepareWordAlignment(request: Synthesis) {
        if (!trackWords || request.usedAndroidTts ||
            request.settings.engine == NarrationEngine.ANDROID || !isCurrent(request)) return
        val guide = requestFeedback(request)
        val identity = activePageIdentity
        val key = resumeKey
        val count = segments.size
        val service = wordAlignmentService ?: KokoroWordAlignment(appContext).also { wordAlignmentService = it }
        service.prepare(request.text, request.settings.language, request.settings.kokoroVoice, request.file,
            isCancelled = { !isCurrent(request) }) { alignment ->
            val parts = guide?.parts ?: segments
            if (alignment != null && isCurrent(request) && parts.getOrNull(request.index)?.file == request.file) {
                if (guide != null) publishProgress(guideProgressSnapshot(guide,
                    NarrationPlaybackPhase.ALIGNMENT_AVAILABLE).copy(segmentIndex = request.index, alignment = alignment))
                else publishProgress(NarrationPlaybackProgress(identity, request.generation, request.index, count,
                    NarrationPlaybackPhase.ALIGNMENT_AVAILABLE, key, alignment = alignment))
            }
        }
    }

    /** Answering makes earlier question/option text VISUALLY read. It never
     * completes previous audio or credits an earlier successful explanation.
     * Correct submissions own the main player's next position immediately;
     * incorrect submissions retain the same original-page bookmark.
     */
    private fun skipAnsweredQuestion(blockIndex: Int, moveSourceAudio: Boolean) {
        if (closed || finished || segments.isEmpty() || incorrectFeedback != null) return
        val point = playbackPoint()
        val gate = playbackGate ?: return
        val destination = if (moveSourceAudio) gate.prioritizeCorrect(blockIndex)
            else gate.feedbackStart(blockIndex)
        if (destination == null) return
        if (moveSourceAudio) {
            if (playerReady && playing) {
                publishProgress(progressSnapshot(NarrationPlaybackPhase.ADVANCED)
                    .copy(positionMs = point.positionMs))
            }
            pendingManualForward = null
            selectSegment(destination.segment, destination.positionMs)
        }
        // Native segment indices are known independently of synthesized WAV
        // durations. The omitted prefix becomes read ink, not listened audio.
        publishProgress(progressSnapshot(NarrationPlaybackPhase.POSITION_CHANGED).copy(
            segmentIndex = destination.segment,
            positionMs = destination.positionMs,
            manualForwardFrom = NarrationGatePosition(0)
        ))
    }

    /** Every newly correct answer has priority over older questions, options,
     * and even an unrelated incorrect guide still speaking. Only actual
     * correctness unlocks later page narration. A manually paused reader
     * remains paused, with its successful feedback queued for the next Play.
     */
    fun onCorrectSubmission(pageIdentity: String, blockIndex: Int, correctBlocks: Set<Int>) = onMain {
        val gate = playbackGate ?: return@onMain
        if (closed || pageIdentity != activePageIdentity ||
            !gate.hasExercise(blockIndex) || blockIndex !in correctBlocks) return@onMain
        val oldGuide = incorrectFeedback
        val shouldSpeakNow = playing || waitingForExercise || oldGuide?.finished == true ||
            (oldGuide == null && resumeOwner == null)
        // This native check has already saved the student's result. The
        // preference observer may be queued, but it cannot delay this action.
        correctExercises = correctBlocks.toSet()
        gate.updateCorrect(correctBlocks)
        if (oldGuide != null) finishIncorrectFeedback(restoreWaiting = false)
        if (!shouldSpeakNow) {
            pendingPausedCorrectBlock = blockIndex
            return@onMain
        }
        pendingPausedCorrectBlock = null
        if (finished) finished = false
        skipAnsweredQuestion(blockIndex, moveSourceAudio = true)
        if (!playing) {
            // A source selected after a transient guide must not be overwritten
            // by the last paused bookmark on resume.
            userPositionChosen = true
            resume()
        }
    }

    /** A changed answer only changes playback permission, never the pre-render queue. */
    fun updateSolvedExercises(pageIdentity: String, correctBlocks: Set<Int>) = onMain {
        if (closed || pageIdentity != activePageIdentity) return@onMain
        val gate = playbackGate ?: return@onMain
        val newlyCorrectGuide = incorrectFeedback?.blockIndex?.let {
            it in correctBlocks && it !in correctExercises
        } == true
        correctExercises = correctBlocks.toSet()
        gate.updateCorrect(correctBlocks)
        if (incorrectFeedback != null) {
            if (!newlyCorrectGuide) return@onMain
            // The original page gate receives the answer; a temporary guide cannot grant it.
            finishIncorrectFeedback(restoreWaiting = true)
        }
        if (finished) return@onMain
        val point = playbackPoint()
        val permitted = gate.clamp(NarrationGatePosition(point.segment,point.positionMs))
        if (permitted.segment != point.segment || permitted.positionMs != point.positionMs) {
            val shouldWait = playing || waitingForExercise
            pause()
            index = permitted.segment
            offset = permitted.positionMs
            waitingForExercise = shouldWait && gate.blockedByAnswer(index)
            pendingManualForward = null
            rememberPlaybackPosition()
            publishProgress(NarrationPlaybackPhase.POSITION_CHANGED)
        }
        if (waitingForExercise && gate.canPlay(index)) {
            val key = resumeKey
            // Another explicit playback request wins over an old answer callback.
            if (key != null && resumeStore.owns(key,resumeOwner) &&
                NarrationSettingsRepository.controlsVisible(appContext)) {
                waitingForExercise = false
                resume()
            } else waitingForExercise = false
        }
    }

    private fun waitForExercise() {
        val armed = playing || waitingForExercise
        pause()
        waitingForExercise = armed
    }

    /** End of the page is a stable position; only Back or Restart leaves it. */
    private fun finishPage() {
        // Keep the exact end even after releasing MediaPlayer. A later -3/-5 seek
        // must be relative to the final audio, not a stale segment offset.
        val finalPosition = segments.getOrNull(index)?.duration?.toLong()
            ?: (if (playerReady) runCatching { player?.currentPosition?.toLong() }.getOrNull() else null)
        offset = finalPosition ?: offset
        finished = true
        waitingForExercise = false
        playing = false
        main.removeCallbacks(playbackCheckpoint)
        releasePlayer()
        rememberPlaybackPosition()
        NarrationPlaybackOwner.release(this)
        foregroundPlayback?.close()
        foregroundPlayback = null
        releaseFocus()
        onState(false)
        publishProgress(NarrationPlaybackPhase.FINISHED)
    }

    // Bookmarks are bounded metadata. Only explicit playback owns and updates one.
    private val resumeStore = NarrationResumeStore(appContext)
    private var resumeKey: String? = null
    private var resumeOwner: String? = null
    private data class StableBinding(val page: NarrationStableLesson.Page,
                                     val legacy: Map<String, NarrationStablePage>)
    private var stableBinding: StableBinding? = null
    private var activeStablePage: NarrationStablePage? = null
    private var stableLessonJson: JSONObject? = null
    private var stableLessonLayout: StableLessonStateLayout? = null
    private var legacyStableConfiguration: String? = null
    private var legacyStablePages: Map<String, NarrationStablePage> = emptyMap()
    private var userPositionChosen = false
    private val playbackCheckpoint = object : Runnable {
        override fun run() {
            if (closed || !playing) return
            rememberPlaybackPosition()
            main.postDelayed(this, 5_000L)
        }
    }

    private fun playbackPoint() = NarrationResumePoint(index,
        (if (playerReady && incorrectFeedback == null) runCatching { player?.currentPosition?.toLong() ?: offset }
            .getOrDefault(offset) else offset).coerceAtLeast(0L), finished,
        playbackGate?.heardThrough ?: 0)

    private fun rememberPlaybackPosition() {
        val key = resumeKey ?: return
        val owner = resumeOwner ?: return
        // A negative unresolved seek is relative to the previous segment end, not zero.
        if (!playerReady && offset < 0L) return
        if (segments.isNotEmpty()) resumeStore.save(key, owner, playbackPoint())
    }

    fun checkpointPlayback() = onMain {
        if (!closed) rememberPlaybackPosition()
    }

    private fun playbackIdentity(page: String, selected: NarrationSettings,
                                 prepared: List<String>): String = NarrationResumePolicy.key(
        page, selected.language.tag, selected.engine.name,
        if (selected.engine == NarrationEngine.ANDROID) selected.androidVoice.orEmpty()
        else selected.kokoroVoice, prepared
    )

    /**
     * Supply the displayed script's stable block provenance before binding playback.
     * This does not play, create a bookmark, or promote an existing history slot.
     */
    internal fun bindStableLesson(lesson: JSONObject, routeId: String, lessonId: String,
                                  pageIndex: Int, script: LessonNarrationScript,
                                  language: NarrationLanguage, forKokoro: Boolean,
                                  instruction: (String) -> String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (lesson !== stableLessonJson) {
            stableLessonJson = lesson
            stableLessonLayout = NarrationStableLesson.layout(lesson)
            legacyStableConfiguration = null
            legacyStablePages = emptyMap()
        }
        val layout = stableLessonLayout
        if (layout == null) { stableBinding = null; return }
        val selected = NarrationSettingsRepository.forLanguage(appContext, language)
        val page = NarrationStableLesson.page(layout, routeId, lessonId, pageIndex, script, selected)
        if (page == null) { stableBinding = null; return }
        if (legacyStableConfiguration != page.stable.configuration) {
            legacyStablePages = NarrationStableLesson.legacy(lesson, layout, routeId, lessonId, selected,
                resumeStore.legacyKeys(), forKokoro, instruction) { p, b, block ->
                savedNarrationPresentation(lessonId, p, b, block)
            }
            legacyStableConfiguration = page.stable.configuration
        }
        stableBinding = StableBinding(page, legacyStablePages)
    }

    private fun savedNarrationPresentation(lessonId: String, page: Int, index: Int,
                                           block: JSONObject): JSONObject {
        if (!MathPuzzleTypes.isExercise(block.optString("type"))) return block
        val saved = ProgressRepository.getExerciseState(appContext, lessonId, page, index)
            ?.takeIf { it.optString("contentFingerprint") == MathPuzzleState.fingerprint(block) } ?: return block
        val responses = saved.optJSONObject("responses") ?: return block
        val orders = responses.keys().asSequence().mapNotNull { id ->
            responses.optJSONObject(id)?.let { MathAnswerJson.decode(it) as? MathAnswer.Order }?.let { id to it.ids }
        }.toMap()
        return MathNarrationPresentation.block(block, orders)
    }

    // Construction, inference and close belong to the worker; main may request cancellation.
    @Volatile private var english: EnglishMisakiG2P? = null
    private var french: FrenchFluidG2P? = null
    private var spanishLexicon: Map<String, String>? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitializing = false
    private var ttsGeneration = 0L
    private var systemEngine: String? = null
    private var voices: List<Voice> = emptyList()
    private val ttsPending = linkedMapOf<String, Synthesis>()
    // This is a notification-only probe, never a request to synthesize a page.
    // Kept on the worker thread and invalidated by the request generation.
    private var storageVoiceProbe: Synthesis? = null
    private val removeResourceListener = NarrationResourceEvents.addListener {
        if (!closed) stopInternal(releaseNeuralResources = true)
    }
    private val masterPreferences = appContext.getSharedPreferences("lesson_narration_settings", Context.MODE_PRIVATE)
    private val masterListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "enabled" || key == "engine" ||
            NarrationGenerationProfile.isPreference(key)) settingsChanged()
    }

    init {
        masterPreferences.registerOnSharedPreferenceChangeListener(masterListener)
    }

    private data class Segment(
        val text: String,
        var file: File? = null,
        var duration: Int? = null,
        var usedAndroidTts: Boolean = false
    )
    private data class Synthesis(
        val generation: Long, val configuration: Long, val index: Int, val text: String,
        val file: File, val settings: NarrationSettings, val profile: NarrationGenerationProfile,
        val feedbackToken: Long? = null,
        val usedAndroidTts: Boolean = false, val storageFallback: Boolean = false
    ) {
        val id = "narration_" + generation + "_" + configuration + "_" + index +
            (feedbackToken?.let { "_guide_$it" } ?: "")
    }

    val isPlaying: Boolean get() = playing
    val hasNarration: Boolean get() = segments.isNotEmpty()

    fun toggle(
        segments: List<String>,
        language: NarrationLanguage = NarrationSettingsRepository.get(appContext).language,
        pageIdentity: String = "",
        feedbackGates: List<NarrationTextGate> = emptyList(),
        correctBlocks: Set<Int> = emptySet(),
        restartFromBeginning: Boolean = false
    ) = onMain {
        if (closed) return@onMain
        val selected = NarrationSettingsRepository.forLanguage(appContext, language)
        if (!selected.enabled) {
            publishProgress(progressSnapshot(NarrationPlaybackPhase.UNAVAILABLE, pageIdentity))
            return@onMain
        }
        val prepared = narrationParts(segments, selected)
        val key = playbackIdentity(pageIdentity, selected, prepared)
        if (this.segments.isNotEmpty() && resumeKey == key && this.language == language &&
            this.segments.map { it.text } == prepared) {
            updateSolvedExercises(pageIdentity,correctBlocks)
            if (restartFromBeginning) restart()
            else if (playing) pause() else resume()
            return@onMain
        }
        val gates = NarrationScriptSegments.gates(segments,selected.engine,feedbackGates)
        if (!startPage(prepared,selected,pageIdentity,gates,correctBlocks)) return@onMain
        if (restartFromBeginning) restart() else resume()
    }

    private fun narrationParts(texts: List<String>, selected: NarrationSettings): List<String> =
        if (selected.engine == NarrationEngine.ANDROID) NarrationSegments.prepare(texts, 250)
        else NarrationSegments.sentences(texts)

    /**
     * Reorders runtime-only puzzle text within its existing bounded bookmark. This
     * never starts playback or promotes a page. An active page first uses the same
     * checkpoint/release path as every ordinary page change; its caller then binds
     * the new reading plan and resumes only when [NarrationPresentationUpdate.resume] is true.
     * An incorrect-answer guide retains ownership until it stops or is dismissed.
     */
    fun relocatePresentation(previous: LessonNarrationScript, current: LessonNarrationScript,
                             language: NarrationLanguage, pageIdentity: String): NarrationPresentationUpdate {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || pageIdentity.isBlank()) return NarrationPresentationUpdate()
        val selected = NarrationSettingsRepository.forLanguage(appContext, language)
        val oldParts = narrationParts(previous.texts, selected)
        val newParts = narrationParts(current.texts, selected)
        if (oldParts.isEmpty() || newParts.isEmpty()) return NarrationPresentationUpdate()
        val oldKey = playbackIdentity(pageIdentity, selected, oldParts)
        val newKey = playbackIdentity(pageIdentity, selected, newParts)
        if (oldKey == newKey) return NarrationPresentationUpdate()
        val active = activePageIdentity == pageIdentity && resumeKey == oldKey &&
            this.language == language && segments.map { it.text } == oldParts
        if (active && incorrectFeedback != null) return NarrationPresentationUpdate(deferred = true)
        val resume = active && playing
        if (active) stopInternal()
        resumeStore.relocate(oldKey, newKey, stableBinding?.page?.takeIf { it.key == newKey }?.stable) { point ->
            NarrationPresentationRemap.remap(point, previous, current, selected.engine)
        }
        return NarrationPresentationUpdate(rebind = active, resume = resume)
    }

    /**
     * Read only the guidance from one explicit failed submission, then stop. The original
     * page, permission gate, synthesis queue and bounded bookmark remain the playback source.
     * Texts are prepared by the same language frontend as pageTexts, exactly once. Supplying
     * the existing page script also supports Android TTS before the first page Play.
     */
    fun readIncorrectFeedback(
        texts: List<String>, language: NarrationLanguage, pageIdentity: String, blockIndex: Int,
        pageTexts: List<String> = emptyList(), feedbackGates: List<NarrationTextGate> = emptyList(),
        correctBlocks: Set<Int> = emptySet()
    ) = readVisibleFeedback(texts, language, pageIdentity, blockIndex, pageTexts, feedbackGates, correctBlocks, false)

    /** A laboratory verdict uses the existing transient review and the same decoder.
     * It cannot be an exercise gate, credit feedback, skip a question or create a bookmark. */
    fun readUnscoredFeedback(
        texts: List<String>, language: NarrationLanguage, pageIdentity: String, blockIndex: Int,
        pageTexts: List<String>, feedbackGates: List<NarrationTextGate>, correctBlocks: Set<Int>,
        continueAfterCompletion: Boolean = false
    ) = readVisibleFeedback(texts, language, pageIdentity, blockIndex, pageTexts, feedbackGates, correctBlocks, true,
        continueAfterCompletion)

    fun cancelUnscoredFeedback(pageIdentity: String, blockIndex: Int) = onMain {
        if (activePageIdentity == pageIdentity && incorrectFeedback?.let { it.unscored && it.blockIndex == blockIndex } == true) {
            finishIncorrectFeedback(restoreWaiting = true)
        }
    }

    private fun readVisibleFeedback(
        texts: List<String>, language: NarrationLanguage, pageIdentity: String, blockIndex: Int,
        pageTexts: List<String>, feedbackGates: List<NarrationTextGate>, correctBlocks: Set<Int>, unscored: Boolean,
        continueAfterCompletion: Boolean = false
    ) = onMain {
        if (closed || blockIndex < 0 || pageIdentity.isBlank()) return@onMain
        val selected = NarrationSettingsRepository.forLanguage(appContext, language)
        if (!selected.enabled || texts.all(String::isBlank)) return@onMain
        if (pageTexts.isNotEmpty()) {
            val prepared = narrationParts(pageTexts, selected)
            val key = playbackIdentity(pageIdentity, selected, prepared)
            if (resumeKey != key || this.language != language || segments.map { it.text } != prepared) {
                if (!startPage(prepared, selected, pageIdentity,
                    NarrationScriptSegments.gates(pageTexts, selected.engine, feedbackGates), correctBlocks)) return@onMain
            } else updateSolvedExercises(pageIdentity, correctBlocks)
        }
        if (activePageIdentity != pageIdentity || this.language != language || segments.isEmpty()) return@onMain
        // Do not speak unbound or validator-derived material: the UI supplies visible orientation,
        // and the exercise must be one of the original script's actual permission gates.
        if (unscored) {
            if (playbackGate?.hasExercise(blockIndex) == true) return@onMain
        } else if (blockIndex in correctExercises || playbackGate?.hasExercise(blockIndex) != true) return@onMain
        val prepared = narrationParts(texts, selected)
        if (prepared.isEmpty()) return@onMain
        pendingPausedCorrectBlock = null
        val previous = incorrectFeedback
        val wasWaiting = previous?.wasWaiting ?: waitingForExercise
        val wasPlaying = previous?.wasPlaying ?: playing
        if (previous != null) finishIncorrectFeedback(restoreWaiting = false, prepareOriginal = false)
        // The wrong-answer guide already interrupts page audio. If the page was
        // being read, reveal the skipped prefix without moving its bookmark.
        if (!unscored && (wasPlaying || wasWaiting)) skipAnsweredQuestion(blockIndex, moveSourceAudio = false)
        pause()
        val feedback = IncorrectFeedback(++incorrectFeedbackSequence, blockIndex,
            prepared.map { Segment(it) }.toMutableList(), wasWaiting, wasPlaying, unscored = unscored,
            continueAfterCompletion = unscored && continueAfterCompletion)
        incorrectFeedback = feedback
        publishGuideProgress(feedback, NarrationPlaybackPhase.PREPARED)
        resume()
    }

    private fun feedbackPlaybackKey(feedback: IncorrectFeedback): String = "$resumeKey:incorrect:${feedback.token}"

    private fun feedbackPlaybackPosition(feedback: IncorrectFeedback): Long =
        if (incorrectFeedback === feedback && playerReady)
            runCatching { player?.currentPosition?.toLong() ?: feedback.offset }.getOrDefault(feedback.offset)
        else feedback.offset

    private fun guideProgressSnapshot(feedback: IncorrectFeedback, phase: NarrationPlaybackPhase,
                                      positionMs: Long = feedback.offset): NarrationPlaybackProgress =
        NarrationPlaybackProgress(activePageIdentity, generation, feedback.index, feedback.parts.size,
            phase, feedbackPlaybackKey(feedback), positionMs.coerceAtLeast(0L),
            android.os.SystemClock.uptimeMillis(), NarrationSettingsRepository.get(appContext).rate,
            feedbackToken = feedback.token, feedbackBlockIndex = feedback.blockIndex)

    private fun publishGuideProgress(feedback: IncorrectFeedback, phase: NarrationPlaybackPhase,
                                     positionMs: Long = feedback.offset) {
        if (phase != NarrationPlaybackPhase.POSITION_CHANGED) {
            publishProgress(guideProgressSnapshot(feedback, phase, positionMs))
            return
        }
        val point = NarrationGatePosition(feedback.index, positionMs)
        val projection = if (positionMs < 0L) NarrationVisualSeek.resolveBackward(point, feedback.parts.map { it.duration })
            else NarrationVisualSeek.resolve(point, feedback.parts.map { it.duration }) { _, proposed -> proposed }
        if (!projection.resolved) return
        val from = feedback.pendingManualForward
        val destination = projection.destination
        val forward = from != null && (destination.segment > from.segment ||
            (destination.segment == from.segment && destination.positionMs > from.positionMs))
        publishProgress(guideProgressSnapshot(feedback, phase, destination.positionMs).copy(
            segmentIndex = destination.segment,
            manualForwardFrom = from.takeIf { forward },
            manualForwardToEnd = forward && destination.segment == feedback.parts.lastIndex &&
                destination.positionMs == feedback.parts.last().duration?.toLong()))
        feedback.pendingManualForward = null
    }

    /** A finished guide remains seekable metadata; the decoder and audio focus are released. */
    private fun completeIncorrectFeedback(feedback: IncorrectFeedback) {
        if (incorrectFeedback !== feedback) return
        val shouldWait = playing && (feedback.wasWaiting || feedback.wasPlaying)
        feedback.index = feedback.parts.lastIndex
        feedback.offset = feedback.parts.last().duration?.toLong() ?: feedback.offset
        feedback.finished = true
        feedback.pendingManualForward = null
        releasePlayer()
        main.removeCallbacks(playbackCheckpoint)
        clearGuideRequests(feedback)
        playing = false
        waitingForExercise = shouldWait
        NarrationPlaybackOwner.release(this)
        foregroundPlayback?.close()
        foregroundPlayback = null
        releaseFocus()
        rememberPlaybackPosition()
        onState(false)
        publishGuideProgress(feedback, NarrationPlaybackPhase.FINISHED)
        if (feedback.unscored && feedback.continueAfterCompletion &&
            (feedback.wasPlaying || feedback.wasWaiting) && !finished) {
            // A successful laboratory check resumes only its interrupted source.
            // Its final ink remains read, but it never completes a puzzle gate.
            finishIncorrectFeedback(restoreWaiting = false, prepareOriginal = false, publishStopped = false)
            userPositionChosen = true
            resume()
            return
        }
        if (settings.engine != NarrationEngine.ANDROID && !finished) {
            ensureSegment(index)
            ensureSegment(index + 1)
        }
    }

    /** Cancel only this transient guide; completed and pending page recordings remain reusable. */
    private fun finishIncorrectFeedback(restoreWaiting: Boolean, prepareOriginal: Boolean = true,
                                        publishStopped: Boolean = true) {
        val feedback = incorrectFeedback ?: return
        val shouldWait = restoreWaiting && (waitingForExercise || (playing && (feedback.wasWaiting || feedback.wasPlaying)))
        if (publishStopped) publishGuideProgress(feedback, NarrationPlaybackPhase.STOPPED, feedbackPlaybackPosition(feedback))
        releasePlayer()
        main.removeCallbacks(playbackCheckpoint)
        clearGuideRequests(feedback)
        incorrectFeedback = null
        playing = false
        waitingForExercise = shouldWait
        NarrationPlaybackOwner.release(this)
        foregroundPlayback?.close()
        foregroundPlayback = null
        releaseFocus()
        rememberPlaybackPosition()
        onState(false)
        publishProgress(if (finished) NarrationPlaybackPhase.FINISHED else NarrationPlaybackPhase.PAUSED)
        if (prepareOriginal && settings.engine != NarrationEngine.ANDROID && !finished) {
            ensureSegment(index)
            ensureSegment(index + 1)
        }
    }

    private fun clearGuideRequests(feedback: IncorrectFeedback) {
        val marker = "_guide_${feedback.token}"
        watchdogs.keys.filter { it.endsWith(marker) }.toList().forEach { id ->
            watchdogs.remove(id)?.let(main::removeCallbacks)
        }
        feedback.pending.clear()
        if (pending.isEmpty()) releaseSynthesisCpu()
    }

    private fun startPage(prepared: List<String>, selected: NarrationSettings,
                          pageIdentity: String, gates: List<NarrationFeedbackGate> = emptyList(),
                          correctBlocks: Set<Int> = emptySet()): Boolean {
        stopInternal()
        settings = selected
        generationProfile = NarrationSettingsRepository.generationProfile(appContext)
        if (!settings.enabled) {
            publishProgress(progressSnapshot(NarrationPlaybackPhase.UNAVAILABLE, pageIdentity))
            return false
        }
        if (prepared.isEmpty()) {
            publishProgress(progressSnapshot(NarrationPlaybackPhase.UNAVAILABLE, pageIdentity))
            onError(s(R.string.narration_no_text))
            return false
        }
        language = selected.language
        activePageIdentity = pageIdentity
        segments = prepared.map { Segment(it) }.toMutableList()
        resumeKey = playbackIdentity(pageIdentity,selected,prepared)
        resumeOwner = null
        val stable = stableBinding?.takeIf { it.page.key == resumeKey && it.page.texts == prepared }
        activeStablePage = stable?.page?.stable
        if (stable != null) resumeStore.prepareStable(requireNotNull(resumeKey), stable.page.stable, stable.legacy)
        userPositionChosen = false
        val saved = resumeStore.restore(requireNotNull(resumeKey),segments.size)
        playbackGate = NarrationExerciseGate(segments.size,gates,correctBlocks,saved?.feedbackHeardThrough ?: 0)
        correctExercises = correctBlocks.toSet()
        val restored = NarrationGatePosition(saved?.segment ?: 0, saved?.positionMs ?: 0L)
        playbackGate?.restoreAnsweredPosition(restored)
        val position = requireNotNull(playbackGate).clamp(restored)
        index = position.segment; offset = position.positionMs
        pendingPausedCorrectBlock = null
        // A completed bookmark is retained only while the page and its exercise
        // permissions still match. A newly blocked answer must not be skipped.
        finished = saved?.finished == true && position == restored && index == segments.lastIndex
        waitingForExercise = false
        directory = File(appContext.cacheDir,"lesson-narration-" + UUID.randomUUID())
        if (selected.engine != NarrationEngine.ANDROID) preservePagePriority(prepared,selected)
        publishProgress(if (finished) NarrationPlaybackPhase.FINISHED else NarrationPlaybackPhase.PREPARED)
        return true
    }

    /** Bind a relocated paused bookmark even when Android TTS has no passive synthesis. */
    fun bindPausedPresentation(texts: List<String>, pageLanguage: NarrationLanguage,
                               pageIdentity: String, feedbackGates: List<NarrationTextGate> = emptyList(),
                               correctBlocks: Set<Int> = emptySet()) = onMain {
        if (closed) return@onMain
        val selected = NarrationSettingsRepository.forLanguage(appContext, pageLanguage)
        if (selected.engine != NarrationEngine.ANDROID) {
            prefetchPage(texts, pageLanguage, pageIdentity, feedbackGates, correctBlocks)
            return@onMain
        }
        if (!selected.enabled) return@onMain
        val prepared = narrationParts(texts, selected)
        if (prepared.isEmpty()) return@onMain
        val key = playbackIdentity(pageIdentity, selected, prepared)
        if (resumeKey == key && language == pageLanguage && segments.map { it.text } == prepared) {
            updateSolvedExercises(pageIdentity, correctBlocks)
            return@onMain
        }
        // Restore the same bookmark and word progress without initializing TTS,
        // taking audio focus, synthesizing or promoting another history slot.
        startPage(prepared, selected, pageIdentity,
            NarrationScriptSegments.gates(texts, selected.engine, feedbackGates), correctBlocks)
    }

    /**
     * Start synthesis when a page is OPENED, without playing audio or taking
     * audio focus. Replay uses the same cached WAV files; no new inference.
     */
    fun prefetchPage(texts: List<String>, pageLanguage: NarrationLanguage,
                     pageIdentity: String = "", feedbackGates: List<NarrationTextGate> = emptyList(),
                     correctBlocks: Set<Int> = emptySet()) = onMain {
        if (closed) return@onMain
        val selected = NarrationSettingsRepository.forLanguage(appContext,pageLanguage)
        if (!selected.enabled || selected.engine == NarrationEngine.ANDROID) return@onMain
        val installed = runCatching {
            val root = models.directory()
            File(root,"model.onnx").isFile &&
                (KokoroVoiceCatalog.find(selected.kokoroVoice)?.let { File(root,it.artifact.fileName).isFile } == true)
        }.getOrDefault(false)
        if (!installed) return@onMain
        val prepared = narrationParts(texts,selected)
        if (prepared.isEmpty()) return@onMain
        val key = playbackIdentity(pageIdentity,selected,prepared)
        if (resumeKey == key && language == pageLanguage && segments.map { it.text } == prepared) {
            updateSolvedExercises(pageIdentity,correctBlocks)
            return@onMain
        }
        if (!startPage(prepared,selected,pageIdentity,
            NarrationScriptSegments.gates(texts,selected.engine,feedbackGates),correctBlocks)) return@onMain
        // A completed page must not start new synthesis until an explicit rewind.
        if (finished) return@onMain
        // The +2 completion chain prepares the COMPLETE page, including gated feedback.
        // It does not start a player or promote a resume bookmark.
        ensureSegment(0)
        ensureSegment(1)
    }

    /**
     * Opens an already-installed neural model in the background while the lesson
     * is visible. Never downloads files and never blocks the lesson UI thread.
     * Sessions are reused across page changes and released on activity destroy.
     */
    fun warmUp() {
        if (closed || !NarrationSettingsRepository.get(appContext).enabled) return
        worker.post {
            if (closed) return@post
            val preference = NarrationSettingsRepository.get(appContext)
            if (!preference.enabled || preference.engine == NarrationEngine.ANDROID) return@post
            runCatching {
                val root = models.directory()
                val voice = KokoroVoiceCatalog.find(preference.kokoroVoice) ?: return@post
                if (!File(root, "model.onnx").isFile ||
                    !File(root, voice.artifact.fileName).isFile
                ) return@post
                KokoroEngineCoordinator.withEngine(this, root,
                    { closed || !neuralGenerationAllowed() }, foreground = true) { }
            }
                .onFailure { error ->
                    if (error !is InterruptedException) {
                        KokoroNarrationDiagnostics.event(appContext, "KokoroInference", "Warm-up failed", error)
                    }
                }
        }
    }

    private fun pause() {
        val feedback = incorrectFeedback
        waitingForExercise = false
        val wasPlaying = playing
        playing = false
        main.removeCallbacks(playbackCheckpoint)
        main.removeCallbacks(wordCheckpoint)
        if (playerReady) runCatching { player?.pause() }
        if (playerReady) {
            if (feedback != null) feedback.offset = runCatching {
                player?.currentPosition?.toLong() ?: feedback.offset
            }.getOrDefault(feedback.offset)
            else offset = playbackPoint().positionMs
        }
        rememberPlaybackPosition()
        // A paused page keeps its position, not a decoder/file handle.
        releasePlayer()
        NarrationPlaybackOwner.release(this)
        foregroundPlayback?.close()
        foregroundPlayback = null
        releaseFocus()
        if (wasPlaying) onState(false)
        if (feedback != null) {
            publishGuideProgress(feedback, if (feedback.finished) NarrationPlaybackPhase.FINISHED else NarrationPlaybackPhase.PAUSED)
        } else if (segments.isNotEmpty()) {
            publishProgress(if (finished) NarrationPlaybackPhase.FINISHED else NarrationPlaybackPhase.PAUSED)
        }
    }

    private fun resume() {
        if (closed || segments.isEmpty() || !NarrationSettingsRepository.get(appContext).enabled) return
        if (incorrectFeedback?.let { it.finished && it.unscored && it.continueAfterCompletion } == true) {
            // A manually paused page stays paused after its verdict. The next Play
            // resumes that page, rather than getting trapped in a finished guide.
            finishIncorrectFeedback(restoreWaiting = false, prepareOriginal = false, publishStopped = false)
            userPositionChosen = true
        }
        if (incorrectFeedback == null) pendingPausedCorrectBlock?.let { blockIndex ->
            pendingPausedCorrectBlock = null
            skipAnsweredQuestion(blockIndex, moveSourceAudio = true)
            userPositionChosen = true
        }
        incorrectFeedback?.let { feedback ->
            if (feedback.finished) return
            NarrationPlaybackOwner.acquire(this) { pause() }
            if (!acquireFocus()) {
                NarrationPlaybackOwner.release(this)
                onState(false)
                onError(s(R.string.narration_focus_error))
                return
            }
            playing = true
            foregroundPlayback = foregroundPlayback ?: NarrationWorkPriority.beginPlayback()
            onState(true)
            publishGuideProgress(feedback, NarrationPlaybackPhase.BUFFERING)
            ensureSegment(feedback.index, feedback)
            ensureSegment(feedback.index + 1, feedback)
            return
        }
        // Play on a completed page is intentionally a no-op. The user can seek
        // backward to replay the last words, or explicitly restart the page.
        if (finished) return
        val key = resumeKey ?: return
        waitingForExercise = false
        NarrationPlaybackOwner.acquire(this) { pause() }
        if (!acquireFocus()) {
            NarrationPlaybackOwner.release(this)
            publishProgress(NarrationPlaybackPhase.UNAVAILABLE)
            onState(false); onError(s(R.string.narration_focus_error)); return
        }
        if (!resumeStore.owns(key,resumeOwner)) {
            val saved = if (userPositionChosen) playbackPoint()
                else resumeStore.restore(key,segments.size) ?: NarrationResumePoint()
            playbackGate?.restoreHeard(saved.feedbackHeardThrough)
            val target = playbackGate?.clamp(NarrationGatePosition(saved.segment,saved.positionMs))
                ?: NarrationGatePosition(saved.segment,saved.positionMs)
            val current = playbackPoint()
            if (current.segment != target.segment || current.positionMs != target.positionMs) {
                releasePlayer(); index = target.segment; offset = target.positionMs
            }
            resumeOwner = resumeStore.begin(key,playbackPoint(),activeStablePage)
        }
        userPositionChosen = false
        playing = true
        if (playbackGate?.canPlay(index) == false) {
            if (playbackGate?.blockedByAnswer(index) == true) waitForExercise()
            else { index = requireNotNull(playbackGate).clamp(NarrationGatePosition(index,offset)).segment; offset = 0 }
            if (!playing) { onState(false); return }
        }
        foregroundPlayback = foregroundPlayback ?: NarrationWorkPriority.beginPlayback()
        onState(true)
        publishProgress(NarrationPlaybackPhase.BUFFERING)
        if (playerReady) {
            applyRate()
            if (!finished && playing) runCatching {
                player?.start()
                if (player != null) {
                    publishProgress(NarrationPlaybackPhase.PLAYING)
                    startWordObservation()
                }
            }.onFailure { fail(s(R.string.narration_resume_error)) }
        } else ensureSegment(index)
        rememberPlaybackPosition()
        main.removeCallbacks(playbackCheckpoint)
        if (playing && !finished) main.postDelayed(playbackCheckpoint,5_000L)
    }

    /** Hide controls without resetting position; pause releases the native player. */
    fun pauseKeepingPosition() = onMain {
        if (!closed) pause()
    }

    fun stop() = onMain { stopInternal() }

    fun restart() = onMain {
        if (closed || segments.isEmpty() || !NarrationSettingsRepository.isEnabled(appContext)) return@onMain
        if (incorrectFeedback != null) finishIncorrectFeedback(restoreWaiting = false, prepareOriginal = false)
        pause()
        pendingManualForward = null
        userPositionChosen = true
        finished = false
        playbackGate?.restart()
        pendingPausedCorrectBlock = null
        index = 0; offset = 0
        rememberPlaybackPosition()
        publishProgress(NarrationPlaybackPhase.RESTARTED)
        resume()
    }

    /** Offsets are measured in source audio seconds, independent of playback speed.
     * Rewinding across the beginning of a temporary incorrect guide returns to
     * the original page, instead of trapping the reader at the guide's 0 ms.
     */
    fun seekBy(seconds: Int) = onMain {
        incorrectFeedback?.let { feedback ->
            if (!closed && NarrationSettingsRepository.isEnabled(appContext)) {
                val earlierPageMillis = seekIncorrectFeedback(feedback, seconds)
                if (earlierPageMillis != null) {
                    val wasPlaying = playing
                    finishIncorrectFeedback(restoreWaiting = false)
                    // Only the remaining backward time is applied to the actual
                    // source. The original page bookmark remains the same slot.
                    seekSourceByMillis(earlierPageMillis)
                    if (wasPlaying && !finished) resume()
                }
            }
            return@onMain
        }
        seekSourceByMillis(seconds.toLong() * 1_000L)
    }

    private fun seekSourceByMillis(deltaMs: Long) {
        if (closed || !NarrationSettingsRepository.isEnabled(appContext) || segments.isEmpty() ||
            (finished && deltaMs >= 0L)) return
        val point = playbackPoint()
        var target = point.positionMs + deltaMs
        var targetIndex = index
        var previousUnknown = false
        while (target < 0 && targetIndex > 0) {
            targetIndex--
            val duration = segments[targetIndex].duration
            if (duration == null) { previousUnknown = true; break }
            target += duration
        }
        val from = NarrationGatePosition(point.segment,point.positionMs)
        val requested = NarrationGatePosition(targetIndex,if(previousUnknown) target else target.coerceAtLeast(0))
        val bounded = if (previousUnknown) requested else playbackGate?.seek(from,requested) ?: requested
        if (bounded == from) return
        // Close the genuinely played interval at its observed decoder position before
        // reporting the separate, voluntary visual skip. Neither event completes feedback.
        if (playerReady && playing) publishProgress(progressSnapshot(NarrationPlaybackPhase.ADVANCED)
            .copy(positionMs = point.positionMs))
        pendingManualForward = if (deltaMs > 0L) pendingManualForward ?: from else null
        val wasFinished = finished
        if (wasFinished) finished = false
        userPositionChosen = true
        waitingForExercise = false
        selectSegment(bounded.segment,bounded.positionMs)
        if (wasFinished) {
            // Rewind is explicit user intent and creates a resumable bookmark.
            // The completed bookmark's former owner was released at finish.
            resumeKey?.let { resumeOwner = resumeStore.begin(it,playbackPoint(),activeStablePage) }
        }
        rememberPlaybackPosition()
    }

    fun settingsChanged() = onMain {
        if (closed) return@onMain
        val previous = settings
        val previousProfile = generationProfile
        generationProfile = NarrationSettingsRepository.generationProfile(appContext)
        settings = NarrationSettingsRepository.forLanguage(appContext, language)
        if (!settings.enabled) {
            stopInternal(releaseNeuralResources = true, phase = NarrationPlaybackPhase.UNAVAILABLE)
            return@onMain
        }
        if (settings.engine != previous.engine || settings.kokoroVoice != previous.kokoroVoice ||
            settings.androidVoice != previous.androidVoice ||
            (settings.engine != NarrationEngine.ANDROID && generationProfile != previousProfile)
        ) {
            if (incorrectFeedback != null) finishIncorrectFeedback(restoreWaiting = true, prepareOriginal = false)
            configuration++
            KokoroEngineCoordinator.cancel(this)
            english?.cancelCurrentRun()
            if (generationProfile != previousProfile) {
                // Stop the old voice's duration/G2P requests as well. The metadata
                // files remain valid and are reused if this profile is selected again.
                releaseWordAlignment()
            }
            pending.clear()
            releaseSynthesisCpu()
            watchdogs.values.forEach(main::removeCallbacks)
            watchdogs.clear()
            segments.forEachIndexed { current, segment ->
                if (current != index || player == null) {
                    val previousFile = segment.file
                    segment.file = null
                    segment.duration = null
                    segment.usedAndroidTts = false
                    if (previousFile != null) worker.post { deleteTemporaryAudio(previousFile) }
                }
            }
            if (!finished && segments.isNotEmpty() && player == null &&
                (playing || settings.engine != NarrationEngine.ANDROID)) ensureSegment(index)
            if (segments.isNotEmpty() && settings.engine != NarrationEngine.ANDROID) {
                preservePagePriority(segments.map { it.text }, settings)
                // Resume both preparation chains after an engine change, including
                // an idle page that has no player to request the next sentence.
                if (!finished && settings.engine != previous.engine) ensureSegment(index + 1)
            }
        }
        applyRate()
        if (settings.engine == NarrationEngine.ANDROID) releaseWordAlignment()
        worker.post {
            if (tts != null && systemEngine != defaultAndroidTtsEngine(appContext)) {
                ttsGeneration++
                runCatching { tts?.shutdown() }
                tts = null; ttsReady = false; ttsInitializing = false; voices = emptyList()
                failTtsPending(s(R.string.narration_provider_changed))
            }
            if (settings.engine == NarrationEngine.ANDROID) {
                releaseKokoro()
                releasePhonemizers()
            }
        }
    }

    private fun selectSegment(next: Int, position: Long) {
        if (closed || segments.isEmpty() || finished) return
        releasePlayer()
        val desired = NarrationGatePosition(next.coerceIn(0,segments.lastIndex),position)
        // Negative offsets are resolved against the previous audio duration, not lost.
        val target = if (position < 0) desired else playbackGate?.clamp(desired) ?: desired
        index = target.segment; offset = target.positionMs
        rememberPlaybackPosition()
        publishPositionChanged()
        if (playing && playbackGate?.blockedByAnswer(index) == true) waitForExercise()
        if (playing) publishProgress(NarrationPlaybackPhase.BUFFERING)
        ensureSegment(index)
    }

    private fun selectIncorrectFeedback(feedback: IncorrectFeedback, next: Int, position: Long) {
        if (incorrectFeedback !== feedback || closed) return
        releasePlayer()
        feedback.index = next.coerceIn(0, feedback.parts.lastIndex)
        feedback.offset = position
        feedback.finished = false
        waitingForExercise = false
        publishGuideProgress(feedback, NarrationPlaybackPhase.POSITION_CHANGED)
        if (playing) publishGuideProgress(feedback, NarrationPlaybackPhase.BUFFERING)
        ensureSegment(feedback.index, feedback)
    }

    /** Returns only the negative remainder when Back crosses the guide's
     * beginning. Unknown earlier WAV durations stay inside the guide until
     * they are resolved, preserving the existing negative-offset seek policy.
     */
    private fun seekIncorrectFeedback(feedback: IncorrectFeedback, seconds: Int): Long? {
        if (feedback.finished && seconds >= 0) return null
        val position = (if (playerReady) runCatching { player?.currentPosition?.toLong() }
            .getOrNull() else null) ?: feedback.offset
        val from = NarrationGatePosition(feedback.index, position)
        if (playerReady && playing) publishGuideProgress(feedback, NarrationPlaybackPhase.ADVANCED, position)
        val destination = IncorrectFeedbackRewind.resolve(
            feedback.index, position, seconds.toLong() * 1000L,
            feedback.parts.map { it.duration }
        )
        if (destination.pageRemainderMs != null) return destination.pageRemainderMs
        feedback.pendingManualForward = if (seconds > 0) feedback.pendingManualForward ?: from else null
        selectIncorrectFeedback(feedback, destination.segment, destination.positionMs)
        return null
    }

    private fun requestFeedback(request: Synthesis): IncorrectFeedback? = incorrectFeedback?.takeIf {
        request.feedbackToken == it.token
    }

    private fun requestPending(request: Synthesis): MutableSet<Int>? =
        if (request.feedbackToken == null) pending else requestFeedback(request)?.pending

    private fun ensureSegment(next: Int, feedback: IncorrectFeedback? = null) {
        if (feedback != null && (incorrectFeedback !== feedback || feedback.finished)) return
        val parts = feedback?.parts ?: segments
        if (closed || !NarrationSettingsRepository.isEnabled(appContext) || next !in parts.indices) return
        val segment = parts[next]
        if (segment.file?.isFile == true) {
            if (feedback != null && next == feedback.index && trackWords && !segment.usedAndroidTts) {
                prepareWordAlignment(Synthesis(generation, configuration, next, segment.text,
                    requireNotNull(segment.file), settings, generationProfile, feedback.token))
            }
            if (playing && next == (feedback?.index ?: index) && player == null &&
                ((feedback != null) == (incorrectFeedback != null))) preparePlayer(segment, feedback)
            return
        }
        val activePending = feedback?.pending ?: pending
        if (!activePending.add(next)) return
        holdSynthesisCpu()
        val session = directory ?: return
        val request = Synthesis(generation, configuration, next, segment.text,
            File(session, configuration.toString() + "_" + next +
                (feedback?.let { "_guide_${it.token}" } ?: "") + ".wav"),
            settings, generationProfile, feedback?.token)
        val timeout = Runnable {
            if (isCurrent(request) && requestPending(request)?.contains(next) == true)
                synthesisFailed(request, s(R.string.narration_engine_timeout))
        }
        watchdogs[request.id] = timeout
        main.postDelayed(timeout, 180_000L)
        worker.post { synthesize(request) }
    }

    private fun synthesize(request: Synthesis) {
        if (!isCurrent(request)) return
        var foregroundPreparation: AutoCloseable? = null
        try {
            if (request.settings.engine != NarrationEngine.ANDROID) {
                // Disk cache (or an optional WAV bundled in the APK) is
                // checked BEFORE G2P, ONNX initialization or inference.
                val ready = runCatching {
                    KokoroNarrationCache.lookup(
                        appContext, request.settings.language.tag,
                        request.settings.kokoroVoice, request.text
                    )
                }.getOrNull()
                if (ready != null) {
                    android.util.Log.i("KokoroInference", "Persistent narration cache hit: sentence ${request.index}")
                    generated(request.copy(file = ready))
                    return
                }
            }
            var storageFallback = false
            if (request.settings.engine != NarrationEngine.ANDROID) {
                // Persistent cache hits above always win, even when the user's
                // configured limit has been reached. Never delete existing audio.
                when (NarrationStorageBudget.onCacheMiss(
                    NarrationStorageBudget.snapshot(appContext), playing)) {
                    NarrationStorageBudget.CacheMissAction.KOKORO -> Unit
                    NarrationStorageBudget.CacheMissAction.WAIT_FOR_SPACE -> {
                        probeStorageTts(request)
                        deferPassiveRequest(request)
                        return
                    }
                    NarrationStorageBudget.CacheMissAction.ANDROID_TTS -> storageFallback = true
                }
                if (!storageFallback && !playing && NarrationWorkPriority.isBusy) {
                    // Passive page preparation yields to a compiler/graphics operation.
                    // Keep the request and durable page queue; retry without switching engines.
                    worker.postDelayed({ synthesize(request) }, 250L)
                    return
                }
                // Cache misses that belong to the visible lesson outrank
                // unrelated offline curriculum generation.
                if (!storageFallback) foregroundPreparation = NarrationWorkPriority.beginInteractive()
            }
            val parent = request.file.parentFile ?: error("Directorio temporal no disponible")
            if (!parent.isDirectory && !parent.mkdirs()) error("No se pudo crear el audio temporal")
            if (request.settings.engine != NarrationEngine.ANDROID && !storageFallback) {
                try {
                    val root = models.directory()
                    if (!File(root, "model.onnx").isFile) throw UiFailure(R.string.narration_model_missing)
                    val voice = KokoroVoiceCatalog.find(request.settings.kokoroVoice)
                        ?: throw UiFailure(R.string.narration_voice_invalid)
                    if (!File(root, voice.artifact.fileName).isFile) throw UiFailure(R.string.narration_voice_missing)
                    if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) throw UiFailure(R.string.narration_requires_arm64)
                    val synthesisStarted = android.os.SystemClock.elapsedRealtime()
                    val phonemes = phonemize(request.text, request.settings.language, root) { isCurrent(request) }
                    if (!isCurrent(request)) return
                    // All pieces become ONE seamless WAV for the current
                    // sentence. The next sentence is generated in advance.
                    val phonemeParts = KokoroPhonemeChunks.split(phonemes)
                    val ready = KokoroEngineCoordinator.withEngine(this, root, { !isCurrent(request) }, foreground = true,
                        onProviderFailure = { error -> KokoroNarrationDiagnostics.event(appContext, "KokoroInference",
                            "NNAPI request retried on CPU", error, mapOf("index" to request.index,
                                "language" to request.settings.language.tag, "voice" to request.settings.kokoroVoice)) }) { active ->
                        // Another owner may have completed the same sentence while
                        // this page waited for the shared session.
                        KokoroNarrationCache.lookup(appContext, request.settings.language.tag,
                            request.settings.kokoroVoice, request.text) ?: run {
                            val audioParts = phonemeParts.map { part ->
                                if (!isCurrent(request)) throw InterruptedException("Se canceló la narración")
                                active.synthesizePhonemes(part, request.settings.kokoroVoice, 1f) {
                                    !isCurrent(request)
                                }
                            }
                            if (!isCurrent(request)) throw InterruptedException("Se canceló la narración")
                            NarrationWavWriter.writeChunks(request.file, audioParts)
                            if (!NarrationStorageBudget.fitsNext(NarrationStorageBudget.snapshot(appContext),
                                    request.file.length())) {
                                // Only the uncommitted temporary audio is discarded.
                                // Completed recordings in the persistent cache are immutable.
                                deleteTemporaryAudio(request.file)
                                if (playing) throw StorageBudgetReached()
                                else throw PassiveStoragePause()
                            }
                            KokoroNarrationDiagnostics.event(appContext, "KokoroInference", "Page sentence generated",
                                fields = mapOf("index" to request.index, "language" to request.settings.language.tag,
                                    "voice" to request.settings.kokoroVoice,
                                    "hash" to KokoroNarrationCache.key(request.settings.language.tag,
                                        request.settings.kokoroVoice, request.text),
                                    "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - synthesisStarted)))
                            KokoroNarrationCache.store(appContext, request.settings.language.tag,
                                request.settings.kokoroVoice, request.text, request.file) ?: request.file
                        }
                    }
                    generated(request.copy(file = ready))
                    return
                } catch (_: PassiveStoragePause) {
                    probeStorageTts(request)
                    deferPassiveRequest(request)
                    return
                } catch (_: StorageBudgetReached) {
                    // Space may have disappeared after the initial budget check.
                    storageFallback = true
                } catch (_: InterruptedException) {
                    // Cancellation invalidated the page/configuration before aborting its G2P.
                    return
                } catch (error: Exception) {
                    if (!isCurrent(request)) return
                    // The UI message is intentionally friendly, but never hide
                    // the actual cause of an unexpected neural-to-TTS fallback.
                    KokoroNarrationDiagnostics.event(appContext, "KokoroInference", "Neural narration failed", error,
                        mapOf("index" to request.index, "language" to request.settings.language.tag,
                            "voice" to request.settings.kokoroVoice, "textLength" to request.text.length,
                            "hash" to KokoroNarrationCache.key(request.settings.language.tag,
                                request.settings.kokoroVoice, request.text)))
                    reportFallback(request, errorMessage(error, R.string.narration_neural_unavailable))
                } catch (error: UnsatisfiedLinkError) {
                    if (!isCurrent(request)) return
                    android.util.Log.e(
                        "KokoroInference",
                        "Native ONNX narration unavailable: segment=${request.index}, " +
                            "language=${request.settings.language.tag}, " +
                            "voice=${request.settings.kokoroVoice}",
                        error
                    )
                    reportFallback(request, s(R.string.narration_onnx_unavailable))
                }
            }
            if (!isCurrent(request)) return
            val ttsRequest = request.copy(usedAndroidTts = true, storageFallback = storageFallback)
            ttsPending[request.id] = ttsRequest
            if (ttsReady) synthesizeAndroid(ttsRequest) else initializeTts()
        } catch (error: Exception) {
            KokoroNarrationDiagnostics.event(appContext, "KokoroInference", "Temporary audio failed", error)
            synthesisFailed(request, s(R.string.narration_temporary_audio_error))
        } finally {
            foregroundPreparation?.close()
        }
    }

    private fun phonemize(text: String, language: NarrationLanguage, root: File, isCurrent: () -> Boolean): String = when (language) {
        NarrationLanguage.SPANISH -> {
            val lexicon = spanishLexicon ?: run {
                val artifact = KokoroModelManifest.resources(language).single()
                val file = File(root, artifact.fileName)
                require(KokoroModelManifest.verify(file, artifact)) { "Falta el diccionario español verificado" }
                SpanishFluidG2P.loadLexicon(file).also { spanishLexicon = it }
            }
            MixedSpanishG2P.phonemizePrepared(text, lexicon)
        }
        NarrationLanguage.AMERICAN_ENGLISH, NarrationLanguage.BRITISH_ENGLISH ->
            (english ?: EnglishMisakiG2P(root).also { english = it })
                .phonemize(text, language == NarrationLanguage.BRITISH_ENGLISH, isCurrent)
        NarrationLanguage.FRENCH ->
            MixedFrenchG2P.phonemizePrepared(text,
                french ?: FrenchFluidG2P(root, File(root, "tokenizer.json")).also { french = it })
    }

    private fun initializeTts() {
        if (ttsInitializing || closed || !NarrationSettingsRepository.isEnabled(appContext)) return
        ttsInitializing = true
        systemEngine = defaultAndroidTtsEngine(appContext)
        val initialization = ++ttsGeneration
        try {
            tts = TextToSpeech(appContext) { status ->
                worker.post {
                    if (closed || initialization != ttsGeneration || !NarrationSettingsRepository.isEnabled(appContext)) return@post
                    ttsInitializing = false
                    if (status != TextToSpeech.SUCCESS) {
                        reportStorageVoiceProbe()
                        failTtsPending(s(R.string.narration_android_configure))
                        runCatching { tts?.shutdown() }; tts = null
                        return@post
                    }
                    try {
                        val engine = tts ?: error("Motor de voz no disponible")
                        engine.setOnUtteranceProgressListener(ttsListener())
                        voices = engine.voices.orEmpty().toList()
                        ttsReady = true
                        reportStorageVoiceProbe()
                        ttsPending.values.toList().forEach(::synthesizeAndroid)
                    } catch (_: RuntimeException) {
                        ttsReady = false
                        reportStorageVoiceProbe()
                        failTtsPending(s(R.string.narration_android_configuration_error))
                    }
                }
            }
        } catch (_: RuntimeException) {
            ttsInitializing = false
            reportStorageVoiceProbe()
            failTtsPending(s(R.string.narration_android_open_error))
        }
    }

    /** Check whether Android speech can handle a storage-limited page on entry.
     * This probe never requests TTS synthesis or changes the saved engine choice.
     */
    private fun probeStorageTts(request: Synthesis) {
        if (!isCurrent(request)) return
        storageVoiceProbe = request
        if (ttsReady) reportStorageVoiceProbe() else initializeTts()
    }

    private fun reportStorageVoiceProbe() {
        val request = storageVoiceProbe ?: return
        storageVoiceProbe = null
        if (!isCurrent(request)) return
        val engine = tts
        val available = if (ttsReady && engine != null) runCatching {
            SpanishVoices.available(voices.toSet(), request.settings.language).any {
                engine.setVoice(it) == TextToSpeech.SUCCESS
            }
        }.getOrDefault(false) else false
        reportStorageStatus(request, available)
    }

    private fun reportStorageStatus(request: Synthesis, androidAvailable: Boolean) {
        main.post {
            if (isCurrent(request) && !reportedStorageStatus) {
                reportedStorageStatus = true
                onNotice(s(if (androidAvailable) R.string.narration_storage_android_fallback
                    else R.string.narration_storage_android_unavailable))
            }
        }
    }

    private fun synthesizeAndroid(request: Synthesis) {
        if (!isCurrent(request)) { ttsPending.remove(request.id); return }
        try {
            val engine = tts ?: error("Motor de voz no disponible")
            val available = SpanishVoices.available(voices.toSet(), request.settings.language)
            val preferred = available.firstOrNull { it.name == request.settings.androidVoice }
            val automatic = available.filter { it != preferred && !it.isNetworkConnectionRequired }
            val selected = (listOfNotNull(preferred) + automatic)
                .firstOrNull { engine.setVoice(it) == TextToSpeech.SUCCESS }
                ?: throw UiFailure(R.string.narration_offline_voice_missing, request.settings.language.label(uiContext))
            if (selected.isNetworkConnectionRequired) notice(request, s(R.string.narration_network_notice))
            require(engine.setSpeechRate(1f) == TextToSpeech.SUCCESS)
            val result = engine.synthesizeToFile(ManualEnglishSpeech.strip(request.text), Bundle(), request.file, request.id)
            if (result != TextToSpeech.SUCCESS) {
                ttsPending.remove(request.id)
                synthesisFailed(request, if (request.storageFallback)
                    s(R.string.narration_storage_android_unavailable)
                    else s(R.string.narration_android_segment_error))
            } else if (request.storageFallback) {
                reportStorageStatus(request, androidAvailable = true)
            }
        } catch (error: RuntimeException) {
            ttsPending.remove(request.id)
            synthesisFailed(request, if (request.storageFallback)
                s(R.string.narration_storage_android_unavailable)
                else errorMessage(error, R.string.narration_android_synthesis_error))
        }
    }

    private fun ttsListener() = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            val id = utteranceId ?: return
            worker.post { ttsPending.remove(id)?.let(::generated) }
        }
        @Deprecated("Required abstract compatibility callback")
        override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)
        override fun onError(utteranceId: String?, errorCode: Int) {
            val id = utteranceId ?: return
            worker.post {
                val request = ttsPending.remove(id) ?: return@post
                val message = if (request.storageFallback) s(R.string.narration_storage_android_unavailable)
                    else when (errorCode) {
                        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> s(R.string.narration_android_network_error)
                        TextToSpeech.ERROR_NOT_INSTALLED_YET -> s(R.string.narration_android_data_missing)
                        else -> s(R.string.narration_android_failed)
                    }
                synthesisFailed(request, message)
            }
        }
    }

    private fun generated(request: Synthesis) {
        if (!isCurrent(request)) { deleteTemporaryAudio(request.file); return }
        try {
            require(request.file.isFile && request.file.length() > 44) { "El motor produjo un archivo vacío" }
            val retriever = MediaMetadataRetriever()
            val duration = try {
                retriever.setDataSource(request.file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
            } finally { retriever.release() }
            require(duration != null && duration > 0) { "El audio no tiene una duración válida" }
            if (request.feedbackToken == null && request.settings.engine != NarrationEngine.ANDROID &&
                request.file.parentFile?.name == "kokoro-narration-cache") {
                runCatching {
                    KokoroPagePreparationQueue.snapshot(appContext)?.let { page ->
                        KokoroPagePreparationQueue.markCompleted(appContext, page.revision,
                            KokoroNarrationCache.key(request.settings.language.tag,
                                request.settings.kokoroVoice, request.text))
                    }
                }.onFailure { KokoroNarrationDiagnostics.event(appContext, "KokoroInference", "Page progress could not be preserved", it) }
            }
            // The persistent cache may hold hundreds of MB across lessons.
            // Only bound the active temporary page, never the global cache.
            val temporary = request.file.parentFile?.name?.startsWith("lesson-narration-") == true
            val bytes = if (temporary) request.file.parentFile?.listFiles()?.sumOf { it.length() } ?: 0L
                else request.file.length()
            require(bytes <= 96L * 1024L * 1024L) { "La página excede el límite de audio temporal" }
            main.post {
                if (!isCurrent(request)) {
                    worker.post { deleteTemporaryAudio(request.file) }
                    return@post
                }
                watchdogs.remove(request.id)?.let(main::removeCallbacks)
                requestPending(request)?.remove(request.index)
                if (pending.isEmpty() && incorrectFeedback?.pending.isNullOrEmpty()) releaseSynthesisCpu()
                val feedback = requestFeedback(request)
                val parts = feedback?.parts ?: segments
                val segment = parts.getOrNull(request.index) ?: return@post
                segment.file = request.file
                segment.duration = duration
                segment.usedAndroidTts = request.usedAndroidTts
                if (feedback != null) {
                    if (feedback.pendingManualForward != null || feedback.offset < 0L ||
                        (request.index == feedback.index && player == null))
                        publishGuideProgress(feedback, NarrationPlaybackPhase.POSITION_CHANGED)
                    if (playing && request.index == feedback.index && player == null) preparePlayer(segment, feedback)
                    if (!request.usedAndroidTts) prepareWordAlignment(request)
                    if (request.settings.engine != NarrationEngine.ANDROID) ensureSegment(request.index + 2, feedback)
                    return@post
                }
                if (incorrectFeedback == null &&
                    (pendingManualForward != null || offset < 0L || (request.index == index && player == null)))
                    publishPositionChanged()
                if (incorrectFeedback == null && playing && request.index == index && player == null) preparePlayer(segment)
                if (!request.usedAndroidTts) prepareWordAlignment(request)
                if (request.settings.engine != NarrationEngine.ANDROID) {
                    // Continue rendering the whole page in the background,
                    // even before Play is pressed. A two-sentence window
                    // bounds queued inference and keeps the first words ready.
                    ensureSegment(request.index + 2)
                }
            }
        } catch (error: Exception) {
            KokoroNarrationDiagnostics.event(appContext, "KokoroInference", "Generated audio validation failed", error)
            synthesisFailed(request, s(R.string.narration_invalid_audio))
        }
    }

    private fun preparePlayer(segment: Segment, feedback: IncorrectFeedback? = null) {
        fun belongs(): Boolean = if (feedback == null) incorrectFeedback == null else incorrectFeedback === feedback
        fun selectedIndex(): Int = feedback?.index ?: index
        fun selectedOffset(): Long = feedback?.offset ?: offset
        fun setOffset(value: Long) { if (feedback == null) offset = value else feedback.offset = value }
        fun select(next: Int, position: Long) {
            if (feedback == null) selectSegment(next, position) else selectIncorrectFeedback(feedback, next, position)
        }
        fun failPlayback(message: String) {
            if (feedback == null) fail(message) else if (incorrectFeedback === feedback) {
                finishIncorrectFeedback(restoreWaiting = true)
                if (!closed) onError(message)
            }
        }
        if (!belongs() || closed || (finished && feedback == null) || !playing ||
            !NarrationSettingsRepository.isEnabled(appContext)) return
        if (feedback == null && playbackGate?.canPlay(index) == false) { waitForExercise(); return }
        val file = segment.file ?: return
        val duration = segment.duration ?: return
        val parts = feedback?.parts ?: segments
        if (selectedOffset() < 0) {
            setOffset(selectedOffset() + duration)
            if (selectedOffset() < 0 && selectedIndex() > 0) { select(selectedIndex()-1,selectedOffset()); return }
        }
        if (selectedOffset() >= duration) {
            if (selectedIndex() == parts.lastIndex) {
                if (feedback == null) finishPage() else completeIncorrectFeedback(feedback)
                return
            }
            // Do not carry the unused part of a forward seek across a feedback boundary.
            val proposed = NarrationGatePosition(selectedIndex()+1,selectedOffset()-duration)
            val safe = if (feedback == null) playbackGate?.seek(NarrationGatePosition(index,duration.toLong()),proposed)
                ?: proposed else proposed
            select(safe.segment,safe.positionMs)
            return
        }
        setOffset(selectedOffset().coerceIn(0,(duration-1).coerceAtLeast(0).toLong()))
        if (feedback != null) publishGuideProgress(feedback, NarrationPlaybackPhase.POSITION_CHANGED)
        val currentGeneration = generation
        val currentIndex = selectedIndex()
        try {
            val media = MediaPlayer()
            fun current(): Boolean = belongs() && !closed && (feedback != null || !finished) && playing &&
                NarrationSettingsRepository.isEnabled(appContext) && generation == currentGeneration &&
                selectedIndex() == currentIndex && player === media
            media.setWakeMode(appContext,PowerManager.PARTIAL_WAKE_LOCK)
            player = media; playerReady = false
            media.setAudioAttributes(attributes)
            media.setDataSource(file.absolutePath)
            media.isLooping = false
            media.setOnPreparedListener {
                if (!current()) return@setOnPreparedListener
                fun finishPreparation() {
                    if (!current()) return
                    if (feedback == null && playbackGate?.canPlay(index) == false) { waitForExercise(); return }
                    try {
                        playerReady = true
                        applyRate()
                        if (current()) {
                            media.start()
                            if (feedback == null) {
                                publishProgress(NarrationPlaybackPhase.PLAYING)
                            } else publishGuideProgress(feedback, NarrationPlaybackPhase.PLAYING)
                            startWordObservation()
                        }
                        rememberPlaybackPosition()
                        if (selectedIndex()+1 < parts.size) ensureSegment(selectedIndex()+1, feedback)
                        if (selectedIndex()+2 < parts.size) ensureSegment(selectedIndex()+2, feedback)
                    } catch (_: RuntimeException) { failPlayback(s(R.string.narration_audio_start_error)) }
                }
                try {
                    if (selectedOffset() > 0) {
                        media.setOnSeekCompleteListener {
                            if (!current()) return@setOnSeekCompleteListener
                            media.setOnSeekCompleteListener(null)
                            finishPreparation()
                        }
                        if (Build.VERSION.SDK_INT >= 26) media.seekTo(selectedOffset(),MediaPlayer.SEEK_CLOSEST)
                        else media.seekTo(selectedOffset().toInt())
                    } else finishPreparation()
                } catch (_: RuntimeException) { failPlayback(s(R.string.narration_audio_start_error)) }
            }
            media.setOnCompletionListener {
                if (!current()) return@setOnCompletionListener
                if (feedback != null) {
                    feedback.offset = duration.toLong()
                    publishGuideProgress(feedback, NarrationPlaybackPhase.SEGMENT_COMPLETED)
                    if (feedback.index < feedback.parts.lastIndex) selectIncorrectFeedback(feedback, feedback.index+1, 0)
                    else completeIncorrectFeedback(feedback)
                    return@setOnCompletionListener
                }
                playbackGate?.completedNaturally(currentIndex)
                publishProgress(NarrationPlaybackPhase.SEGMENT_COMPLETED)
                rememberPlaybackPosition()
                if (index < segments.lastIndex) selectSegment(index+1,0) else finishPage()
            }
            media.setOnErrorListener { _, _, _ ->
                if (belongs() && (feedback != null || !finished) && generation == currentGeneration && player === media)
                    failPlayback(s(R.string.narration_audio_play_error))
                true
            }
            media.prepareAsync()
        } catch (_: Exception) { failPlayback(s(R.string.narration_audio_open_error)) }
    }

    private fun applyRate() {
        val media = player ?: return
        // A nonzero PlaybackParams speed starts a prepared MediaPlayer. Keep the
        // preference while paused and apply it on resume, before normal playback.
        if (!playerReady || !playing || (finished && incorrectFeedback == null)) return
        try {
            media.playbackParams = PlaybackParams().setSpeed(NarrationSettingsRepository.get(appContext).rate).setPitch(1f)
        } catch (_: RuntimeException) {
            if (incorrectFeedback != null) {
                finishIncorrectFeedback(restoreWaiting = true)
                if (!closed) onError(s(R.string.narration_rate_error))
            } else fail(s(R.string.narration_rate_error))
        }
    }

    private fun reportFallback(request: Synthesis, reason: String) {
        val installed = runCatching { File(models.directory(), "model.onnx").isFile }.getOrDefault(false)
        main.post {
            if (isCurrent(request) && !reportedFallback) {
                reportedFallback = true
                if (request.settings.engine == NarrationEngine.KOKORO || installed) onError(s(R.string.narration_fallback, reason))
            }
        }
    }
    private fun notice(request: Synthesis, message: String) {
        main.post {
            if (isCurrent(request) && request.index == index && !warnedNetwork) {
                warnedNetwork = true
                onError(message)
            }
        }
    }
    private fun synthesisFailed(request: Synthesis, message: String) {
        main.post {
            if (!isCurrent(request)) return@post
            if (request.feedbackToken != null) {
                finishIncorrectFeedback(restoreWaiting = true)
                if (!closed) onError(message)
            } else fail(message)
        }
    }
    private fun failTtsPending(message: String) {
        ttsReady = false
        ttsPending.values.toList().forEach {
            synthesisFailed(it, if (it.storageFallback) s(R.string.narration_storage_android_unavailable)
                else message)
        }
        ttsPending.clear()
    }
    private fun neuralGenerationAllowed(): Boolean =
        NarrationSettingsRepository.isEngineAllowed(appContext, NarrationEngine.KOKORO)

    private fun isCurrent(request: Synthesis): Boolean = !closed &&
        // The saved selection wins immediately, even before its main-thread observer
        // invalidates queued requests. AUTO and Kokoro both use the neural pipeline.
        NarrationSettingsRepository.isEngineAllowed(appContext, request.settings.engine) &&
        (request.settings.engine == NarrationEngine.ANDROID ||
            NarrationSettingsRepository.isGenerationProfileCurrent(appContext, request.profile)) &&
        request.generation == generation && request.configuration == configuration &&
        (request.feedbackToken == null || incorrectFeedback?.let { it.token == request.feedbackToken && !it.finished } == true)
    private fun fail(message: String) {
        stopInternal(phase = NarrationPlaybackPhase.UNAVAILABLE)
        if (!closed) onError(message)
    }

    private fun releasePlayer() {
        main.removeCallbacks(wordCheckpoint)
        playerReady = false
        runCatching { player?.release() }
        player = null
    }

    private fun stopInternal(releaseNeuralResources: Boolean = false,
                             phase: NarrationPlaybackPhase = NarrationPlaybackPhase.STOPPED) {
        if (incorrectFeedback != null) finishIncorrectFeedback(restoreWaiting = false, prepareOriginal = false)
        val stoppedProgress = progressSnapshot(phase)
        rememberPlaybackPosition()
        main.removeCallbacks(playbackCheckpoint)
        NarrationPlaybackOwner.release(this)
        resumeKey = null
        resumeOwner = null
        activeStablePage = null
        userPositionChosen = false
        pendingManualForward = null
        playbackGate = null
        correctExercises = emptySet()
        pendingPausedCorrectBlock = null
        activePageIdentity = ""
        waitingForExercise = false
        val previous = directory
        generation++
        KokoroEngineCoordinator.cancel(this)
        english?.cancelCurrentRun()
        val wasPlaying = playing
        playing = false
        foregroundPlayback?.close()
        foregroundPlayback = null
        directory = null; segments.clear(); pending.clear(); releaseSynthesisCpu()
        index = 0; offset = 0; finished = false
        reportedFallback = false; reportedStorageStatus = false; warnedNetwork = false
        watchdogs.values.forEach(main::removeCallbacks); watchdogs.clear()
        releasePlayer(); releaseFocus()
        if (releaseNeuralResources) releaseWordAlignment()
        if (!closed) worker.post {
            runCatching { tts?.stop() }
            ttsPending.clear()
            storageVoiceProbe = null
            // Keep the verified ONNX session and language frontends warm between
            // pages of the SAME lesson. The audio files are still discarded.
            // Remove them if narration is disabled, assets change or we close.
            if (releaseNeuralResources) {
                ttsGeneration++
                runCatching { tts?.shutdown() }
                tts = null; ttsReady = false; ttsInitializing = false; voices = emptyList()
                releaseKokoro()
                releasePhonemizers()
            }
            if (previous != null) cleanup(previous)
        }
        if (wasPlaying) onState(false)
        if (stoppedProgress.pageIdentity.isNotEmpty() || stoppedProgress.segmentCount > 0) {
            publishProgress(stoppedProgress.copy(generation = generation))
        }
    }

    /** Never remove a persistent audio file on stop, voice change or cancellation. */
    private fun deleteTemporaryAudio(file: File) {
        runCatching {
            val parent = file.canonicalFile.parentFile ?: return
            if (parent.parentFile == appContext.cacheDir.canonicalFile &&
                parent.name.startsWith("lesson-narration-")
            ) file.delete()
        }
    }

    private fun cleanup(root: File) {
        runCatching {
            val directory = root.canonicalFile
            if (directory.parentFile != appContext.cacheDir.canonicalFile || !directory.name.startsWith("lesson-narration-")) return
            directory.listFiles()?.forEach { file -> if (file.canonicalFile.parentFile == directory && file.isFile) file.delete() }
            directory.delete()
        }
    }

    @Suppress("DEPRECATION") private fun acquireFocus(): Boolean {
        if (focusHeld) return true
        val manager = audioManager ?: return false
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) manager.requestAudioFocus(focusRequest)
            else manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }
    @Suppress("DEPRECATION") private fun releaseFocus() {
        if (!focusHeld) return
        focusHeld = false
        val manager = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) manager.abandonAudioFocusRequest(focusRequest)
            else manager.abandonAudioFocus(focusListener)
        }
    }

    private fun releaseKokoro() { runCatching { KokoroEngineCoordinator.releaseIfIdle() } }
    private fun deferPassiveRequest(request: Synthesis) {
        main.post {
            if (!isCurrent(request)) return@post
            watchdogs.remove(request.id)?.let(main::removeCallbacks)
            requestPending(request)?.remove(request.index)
            if (pending.isEmpty() && incorrectFeedback?.pending.isNullOrEmpty()) releaseSynthesisCpu()
            // A Play pressed during the budget check is an explicit request.
            if (playing) ensureSegment(request.index, requestFeedback(request))
        }
    }
    private fun preservePagePriority(texts: List<String>, selected: NarrationSettings) {
        if (!NarrationSettingsRepository.isEnabled(appContext)) return
        runCatching { KokoroPagePreparationQueue.submit(appContext, texts, selected.language, selected.kokoroVoice) }
            .onFailure { KokoroNarrationDiagnostics.event(appContext, "KokoroInference", "Page priority could not be preserved", it) }
    }
    private fun releasePhonemizers() {
        fun release(resource: Any?) { if (resource is AutoCloseable) runCatching { resource.close() } }
        release(english); release(french)
        english = null; french = null; spanishLexicon = null
    }
    private fun releaseWordAlignment() {
        wordAlignmentService?.close()
        wordAlignmentService = null
    }
    fun close() = onMain {
        if (closed) return@onMain
        stopInternal(releaseNeuralResources = true); closed = true; removeResourceListener()
        masterPreferences.unregisterOnSharedPreferenceChangeListener(masterListener)
        main.removeCallbacksAndMessages(null); models.close()
        worker.post {
            runCatching { tts?.shutdown() }; tts = null; releaseKokoro()
            releasePhonemizers()
            workerThread.quitSafely()
        }
    }
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
}
