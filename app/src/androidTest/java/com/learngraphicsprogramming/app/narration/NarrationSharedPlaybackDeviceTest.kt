package com.learngraphicsprogramming.app.narration

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.app.KeyguardManager
import android.app.Activity
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.learngraphicsprogramming.app.CoursesActivity
import com.learngraphicsprogramming.app.AppearanceSettingsRepository
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual controller synthesis and playback; requires the approved Kokoro Spanish resources. */
@RunWith(AndroidJUnit4::class)
class NarrationSharedPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val deviceContext = instrumentation.targetContext
    private lateinit var context: IsolatedApplication
    private val preferences = linkedMapOf<String, Map<String, *>>()
    private var originalPreferenceFiles = emptySet<String>()
    private lateinit var sandbox: File
    private lateinit var modelRoot: File
    private var originalModels = emptyMap<String, Pair<Long, Long>>()
    private var originalVolume: Int? = null
    private var originalMuted = false
    private var scenario: ActivityScenario<CoursesActivity>? = null
    private var foreground: Activity? = null
    private var controller: LessonNarrationController? = null
    private val errors = CopyOnWriteArrayList<String>()
    private val states = CopyOnWriteArrayList<Boolean>()
    private val text = "Las fracciones equivalentes representan la misma cantidad, " +
        "tres cuartos es igual a seis octavos, " +
        "las fracciones equivalentes representan la misma cantidad, " +
        "tres cuartos es igual a seis octavos y tres cuartos es igual a seis octavos."

    @Before fun preserveNarrationPreferences() {
        originalPreferenceFiles = preferenceFiles()
        (originalPreferenceFiles + KNOWN_PREFERENCES).forEach { name ->
            preferences[name] = deviceContext.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
                if (value is Set<*>) value.toSet() else value
            }
        }
        deviceShell("input keyevent KEYCODE_WAKEUP")
        val keyguard = deviceContext.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardSecure) deviceShell("wm dismiss-keyguard")
        deviceShell("cmd statusbar collapse")
        assertFalse("Unlock the target device before testing real playback", keyguard.isDeviceLocked)
        // The actual application's service must not generate user audio for a test.
        assertTrue(deviceContext.getSharedPreferences("lesson_narration_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", false).putString("engine", NarrationEngine.ANDROID.name).commit())
        sandbox = File(deviceContext.cacheDir, "shared-playback-acceptance-${UUID.randomUUID()}").canonicalFile
        assertEquals(deviceContext.cacheDir.canonicalFile, sandbox.parentFile)
        assertTrue(sandbox.mkdirs())
        context = IsolatedApplication(deviceContext.applicationContext as Application, sandbox)
        NarrationSettingsRepository.saveEnabled(context, true)
        NarrationSettingsRepository.saveEngine(context, NarrationEngine.KOKORO)
        NarrationSettingsRepository.saveLanguage(context, NarrationLanguage.SPANISH)
        NarrationSettingsRepository.saveKokoroVoice(context,
            InstrumentationRegistry.getArguments().getString("playbackVoice", "ef_dora").also {
                require(KokoroVoiceCatalog.find(it)?.language == NarrationLanguage.SPANISH)
            })
        NarrationSettingsRepository.saveAndroidVoice(context, null)
        NarrationSettingsRepository.saveRate(context, 1f)
        NarrationStorageBudget.saveLimit(context, 64L * NarrationStorageBudget.MIB)
        val store = KokoroModelStore(context)
        modelRoot = try { store.directory().canonicalFile } finally { store.close() }
        assertTrue("Use installed resources without importing or downloading fixtures", modelRoot.isDirectory)
        originalModels = modelMetadata()
        assertTrue("One long segment exercises seeking without regenerated audio", text.length in 180..250)
        scenario = ActivityScenario.launch<CoursesActivity>(Intent(deviceContext, CoursesActivity::class.java)
            .putExtra("routeId", AppearanceSettingsRepository.ROUTE_PROGRAMMING)).also { host ->
            host.onActivity { activity ->
                foreground = activity
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        deviceShell("cmd statusbar collapse")
        await("A foreground app window must retain audio focus during native synthesis", 15_000) {
            foreground?.hasWindowFocus() == true
        }
        val audio = deviceContext.getSystemService(AudioManager::class.java)
        originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        originalMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
            maxOf(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 3), 0)
    }

    @After fun closeAndRestoreNarrationPreferences() {
        try {
            controller?.let { current ->
                onMain { current.close() }
                await("The synthesis worker must close", 15_000, checkErrors = false) {
                    !field<Thread>(current, "workerThread").isAlive
                }
            }
        } finally {
            try {
                scenario?.close()
                foreground = null
                instrumentation.waitForIdleSync()
                // Restore the singleton's directory to the actual application's queue.
                KokoroPagePreparationQueue.snapshot(deviceContext)
                if (::modelRoot.isInitialized) assertEquals("Installed resources must retain size and timestamps",
                    originalModels, modelMetadata())
            } finally {
                try {
                    if (::sandbox.isInitialized) {
                        controller?.let {
                            assertFalse("Never remove recordings while synthesis is still alive",
                                field<Thread>(it, "workerThread").isAlive)
                        }
                        assertEquals(deviceContext.cacheDir.canonicalFile, sandbox.canonicalFile.parentFile)
                        assertTrue("Remove only the sandbox owned by this test", sandbox.deleteRecursively())
                    }
                } finally {
                    try { restorePreferences() } finally {
                        originalVolume?.let { volume ->
                            val audio = deviceContext.getSystemService(AudioManager::class.java)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                if (originalMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                        }
                    }
                }
            }
        }
    }

    private fun restorePreferences() {
        (preferenceFiles() + preferences.keys + if (::context.isInitialized) context.preferenceNames else emptySet()).forEach { name ->
            if (name !in originalPreferenceFiles) {
                assertTrue("Remove only test-created preferences: $name", deviceContext.deleteSharedPreferences(name))
            } else {
                val editor = deviceContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                preferences.getValue(name).forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        else -> error("Unsupported preference: $name/$key")
                    }
                }
                assertTrue("Restore user preference values: $name", editor.commit())
            }
        }
    }

    @Test fun completedPageNeverReplaysOnPlayButRewindAndRestartRemainAvailable() {
        assumeAndroidTtsInstalled()
        exerciseCompletion(NarrationEngine.ANDROID)
    }

    @Test fun kokoroCompletedPageNeverReplaysOnPlayButRewindAndRestartRemainAvailable() =
        exerciseCompletion(NarrationEngine.KOKORO)

    private fun exerciseCompletion(engine: NarrationEngine) {
        NarrationSettingsRepository.saveEngine(context, engine)
        val page = "qa/completed-narration"
        val current = onMain {
            LessonNarrationController(context, { states.add(it) }, { errors.add(it) }).also {
                controller = it
                it.toggle(listOf(text), NarrationLanguage.SPANISH, pageIdentity = page)
            }
        }
        await("The native narrator starts its audio", 60_000) {
            ready(current) && current.isPlaying && player(current).duration > 5_000
        }
        val duration = onMain { player(current).duration }
        val sourceEndMs = onMain {
            val segment = field<List<*>>(current, "segments").last()!!
            field<Int>(segment, "duration").toLong()
        }
        onMain {
            current.pauseKeepingPosition()
            val point = field<Long>(current, "offset")
            val advanceSeconds = ((duration.toLong() - point - 1_300L).coerceAtLeast(0L) / 1_000L).toInt()
            assertTrue("Enough duration remains for a near-end seek", advanceSeconds > 0)
            current.seekBy(advanceSeconds)
            current.toggle(listOf(text), NarrationLanguage.SPANISH, pageIdentity = page)
        }
        await("The real MediaPlayer finishes and releases audio focus", 12_000) {
            field<Boolean>(current, "finished") && !current.isPlaying &&
                field<MediaPlayer?>(current, "player") == null
        }
        val finishedPosition = onMain { field<Long>(current, "offset") }
        assertEquals(sourceEndMs, finishedPosition)
        val resumeKey = onMain { field<String>(current, "resumeKey") }
        val store = NarrationResumeStore(context)
        assertTrue("The bounded bookmark remembers completion",
            requireNotNull(store.restore(resumeKey, 1)).finished)

        repeat(3) {
            onMain {
                current.toggle(listOf(text), NarrationLanguage.SPANISH, pageIdentity = page)
                assertTrue("Play must not reset a completed page", field<Boolean>(current, "finished"))
                assertFalse(current.isPlaying)
                assertEquals(sourceEndMs, field<Long>(current, "offset"))
                assertNull(field<MediaPlayer?>(current, "player"))
                current.seekBy(3)
                assertTrue("Forward after completion must not restart", field<Boolean>(current, "finished"))
            }
        }

        onMain { current.close() }
        await("The completed controller must close", 15_000, checkErrors = false) {
            !field<Thread>(current, "workerThread").isAlive
        }
        val reopened = onMain {
            LessonNarrationController(context, { states.add(it) }, { errors.add(it) }).also {
                controller = it
                it.toggle(listOf(text), NarrationLanguage.SPANISH, pageIdentity = page)
            }
        }
        onMain {
            assertTrue("Returning to the page preserves its completed state",
                field<Boolean>(reopened, "finished"))
            assertEquals(sourceEndMs, field<Long>(reopened, "offset"))
            assertFalse(reopened.isPlaying)
            reopened.toggle(listOf(text), NarrationLanguage.SPANISH, pageIdentity = page)
            assertTrue(field<Boolean>(reopened, "finished"))
            reopened.seekBy(-3)
            assertFalse("Back from completion returns to a paused position",
                field<Boolean>(reopened, "finished"))
            assertFalse(reopened.isPlaying)
            assertEquals(sourceEndMs - 3_000L, field<Long>(reopened, "offset"))
        }
        assertFalse("Rewinding clears the completed bookmark",
            requireNotNull(store.restore(resumeKey, 1)).finished)
        onMain { reopened.toggle(listOf(text), NarrationLanguage.SPANISH, pageIdentity = page) }
        await("Rewind followed by Play resumes near the end", 45_000) {
            reopened.isPlaying && ready(reopened) &&
                player(reopened).currentPosition >= sourceEndMs - 3_500
        }
        onMain { reopened.restart() }
        await("Restart remains the explicit way to begin the page again", 12_000) {
            reopened.isPlaying && ready(reopened) && player(reopened).currentPosition < 2_000
        }
    }

    @Test fun androidTtsUsesSharedPauseSeekRateResumeAndCleanup() {
        assumeAndroidTtsInstalled()
        exercise(NarrationEngine.ANDROID)
    }

    @Test fun kokoroSpanishUsesSharedPauseSeekRateResumeAndCleanup() = exercise(NarrationEngine.KOKORO)

    @Test fun stoppingActiveEnglishBartInferenceDoesNotFallBackOrReviveThePage() {
        NarrationSettingsRepository.saveLanguage(context, NarrationLanguage.AMERICAN_ENGLISH)
        NarrationSettingsRepository.saveKokoroVoice(context, "af_heart")
        NarrationSettingsRepository.saveEngine(context, NarrationEngine.KOKORO)
        val unfamiliarWords = List(8) { "glorbaxylophonically" }.joinToString(" ")
        assertTrue(unfamiliarWords.length < 250)
        val current = onMain {
            LessonNarrationController(context, { states.add(it) }, { errors.add(it) }).also {
                controller = it
                it.toggle(listOf(unfamiliarWords), NarrationLanguage.AMERICAN_ENGLISH)
            }
        }
        val directory = onMain { field<File>(current, "directory") }
        val activeBeforeStop = AtomicReference<Triple<Any, Any, Any>>()
        await("Real Misaki BART must enter an active native run before cancellation", 120_000) {
            val english = field<Any?>(current, "english") ?: return@await false
            val lazyBart = field<Lazy<Any?>>(english, "americanBart")
            if (!lazyBart.isInitialized()) return@await false
            val bart = lazyBart.value ?: return@await false
            val run = field<Any?>(bart, "activeRun") ?: return@await false
            activeBeforeStop.set(Triple(english, bart, run))
            // Observe the active run and stop in the same main-thread turn.
            assertFalse("The active frontend must precede this controller's Kokoro audio inference", ownsNeuralRequest(current))
            current.stop()
            assertFalse(current.isPlaying)
            assertFalse(current.hasNarration)
            true
        }
        val observed = requireNotNull(activeBeforeStop.get())
        val frontend = observed.first
        val bart = observed.second
        val generation = onMain { field<Long>(current, "generation") }
        await("Cancellation must end the observed BART run and remove the temporary page", 5_000) {
            field<Any?>(bart, "activeRun") == null && !directory.exists()
        }
        SystemClock.sleep(350)
        onMain {
            assertSame("Stopping retains the English frontend for the same lesson", frontend, field<Any?>(current, "english"))
            assertNull("The cancelled native BART run cannot restart", field<Any?>(bart, "activeRun"))
            assertEquals(generation, field<Long>(current, "generation"))
            assertFalse("A stale phonemizer callback cannot resume narration", current.isPlaying)
            assertFalse(current.hasNarration)
            assertNull("Cancellation must not initialize Android fallback", field<Any?>(current, "tts"))
            assertNull(field<Any?>(current, "player"))
            assertTrue("Intentional cancellation must not report an unavailable engine", errors.isEmpty())
            assertEquals(false, states.last())
            current.close()
        }
        await("Closing must release the retained English frontend and synthesis worker", 15_000) {
            field<Any?>(current, "english") == null && !field<Thread>(current, "workerThread").isAlive
        }
        onMain {
            assertTrue("Closing must release the warmed English sessions", field<Boolean>(frontend, "closed"))
            assertTrue("Closing must release the warmed BART sessions", field<Boolean>(bart, "closed"))
            assertNull(field<Any?>(bart, "activeRun"))
            assertNull(field<Any?>(current, "tts"))
            assertNull(field<Any?>(current, "player"))
            assertFalse("Closing must release this controller's shared neural request", ownsNeuralRequest(current))
            current.toggle(listOf(unfamiliarWords), NarrationLanguage.AMERICAN_ENGLISH)
            assertFalse("A closed controller cannot revive the cancelled page", current.hasNarration)
            assertFalse(current.isPlaying)
            assertTrue("Cancellation and close must not report an unavailable engine", errors.isEmpty())
        }
    }

    /** Real curricular text and production cache/controller; all generated bytes belong to this test. */
    @Test fun measureRealCurricularFirstAudioContinuousPreparationAndCachedReplay() {
        val lesson = org.json.JSONObject(deviceContext.assets.open(
            "content/es/programming/cpp_fundamentals/programas_y_ejecucion/lesson_primer_programa_y_salida.json")
            .bufferedReader().use { it.readText() })
        val script = LessonNarrationScript.page(lesson, 0, NarrationLanguage.SPANISH, forKokoro = true)
        val parts = NarrationSegments.sentences(script.texts)
        assertTrue("The real page contains several continuous sentences", parts.size > 3)
        val events = CopyOnWriteArrayList<Long>()
        val started = SystemClock.elapsedRealtime()
        val current = onMain {
            LessonNarrationController(context, { states.add(it) }, { errors.add(it) }, { event ->
                if (event.phase == NarrationPlaybackPhase.PLAYING) events.add(SystemClock.elapsedRealtime())
            }).also {
                controller = it
                it.toggle(script.texts, NarrationLanguage.SPANISH, "qa/real-curricular-timing", script.gates)
            }
        }
        await("Santa prepares the first real curricular audio without Android TTS", 180_000) {
            ready(current) && current.isPlaying && player(current).currentPosition >= 100 && events.isNotEmpty()
        }
        val firstMs = events.first() - started
        val preparedAt = linkedMapOf<Int, Long>()
        await("Both existing preparation chains produce the first three real page segments", 240_000) {
            field<List<*>>(current, "segments").take(3).forEachIndexed { index, segment ->
                if (field<File?>(segment!!, "file")?.isFile == true)
                    preparedAt.putIfAbsent(index, SystemClock.elapsedRealtime() - started)
            }
            preparedAt.size == 3
        }
        val files = onMain {
            current.pauseKeepingPosition()
            assertNull("Pause releases MediaPlayer during timing too", field<MediaPlayer?>(current, "player"))
            assertNull("No Android TTS fallback", field<Any?>(current, "tts"))
            field<List<*>>(current, "segments").take(3).map { field<File>(it!!, "file") }
        }
        val fingerprints = files.map { Triple(it.length(), android.system.Os.stat(it.absolutePath).st_ino, sha256(it)) }
        onMain { current.close() }
        await("The previous page worker stops before the cached sample", 15_000, checkErrors = false) {
            !field<Thread>(current, "workerThread").isAlive
        }
        KokoroEngineCoordinator.releaseIfIdle()
        val cachedEvents = CopyOnWriteArrayList<Long>()
        val cacheStarted = SystemClock.elapsedRealtime()
        val cached = onMain {
            LessonNarrationController(context, { states.add(it) }, { errors.add(it) }, { event ->
                if (event.phase == NarrationPlaybackPhase.PLAYING) cachedEvents.add(SystemClock.elapsedRealtime())
            }).also {
                controller = it
                it.toggle(parts.take(3), NarrationLanguage.SPANISH, "qa/real-curricular-cached")
            }
        }
        await("Verified cached real sentences replay directly", 15_000) {
            ready(cached) && cached.isPlaying && player(cached).currentPosition >= 100 && cachedEvents.isNotEmpty()
        }
        val cachedMs = cachedEvents.first() - cacheStarted
        onMain { cached.pauseKeepingPosition() }
        assertNull("The cache hit does not initialize shared ONNX", sharedNeuralEngine())
        files.forEachIndexed { index, file ->
            assertEquals("Cache lookup may refresh recency but must retain the same inode and bytes", fingerprints[index],
                Triple(file.length(), android.system.Os.stat(file.absolutePath).st_ino, sha256(file)))
        }
        println("KOKORO_HONOR_PAGE " + org.json.JSONObject()
            .put("voice", NarrationSettingsRepository.get(context).kokoroVoice)
            .put("lesson", "primer_programa_y_salida").put("page", 1)
            .put("firstDecodedPlaybackMs", firstMs).put("firstThreePreparedMs", org.json.JSONObject(preparedAt.mapKeys { it.key.toString() }))
            .put("cachedDecodedPlaybackMs", cachedMs).put("cachedBytesAndInodesUnchanged", true)
            .put("cacheHitOpenedOnnx", false))
    }

    private fun exercise(engine: NarrationEngine) {
        NarrationSettingsRepository.saveEngine(context, engine)
        val playbackStarts = CopyOnWriteArrayList<NarrationPlaybackProgress>()
        val current = onMain {
            LessonNarrationController(context, { states.add(it) }, { errors.add(it) }, { event ->
                if (event.phase == NarrationPlaybackPhase.PLAYING) playbackStarts.add(event)
            }).also {
                controller = it
                it.toggle(listOf(text), NarrationLanguage.SPANISH)
            }
        }
        await("The selected real engine must prepare and advance its shared MediaPlayer", 180_000) {
            ready(current) && current.isPlaying && player(current).currentPosition >= 700
        }
        val directory = onMain { field<File>(current, "directory") }
        val audio = onMain {
            val first = field<List<*>>(current, "segments").first()!!
            field<File>(first, "file")
        }
        val fileLength = audio.length()
        val fileHash = sha256(audio)
        val fileTimestamp = audio.lastModified()
        val generation = onMain { field<Long>(current, "generation") }
        val loadedSpanishFrontend = onMain { field<Any?>(current, "spanishLexicon") != null }
        assertTrue("Synthesis must produce actual audio", fileLength > 4_096L)
        assertTrue("The real sample must provide room for a five-second jump", onMain { player(current).duration } > 8_000)
        if (engine == NarrationEngine.KOKORO) {
            // A persistent cache hit intentionally bypasses ONNX entirely.
            if (audio.parentFile?.name != "kokoro-narration-cache") {
                assertNotNull("Uncached Kokoro audio must use its real shared ONNX engine", sharedNeuralEngine())
            }
            assertNull("Kokoro success must not initialize Android as a hidden fallback", field<Any?>(current, "tts"))
        } else {
            assertNotNull("Android must synthesize through the actual system TTS engine", field<Any?>(current, "tts"))
            assertFalse("Android selection must not own a Kokoro request", ownsNeuralRequest(current))
        }
        assertTrue("No unavailable-engine warning or fallback is permitted", errors.isEmpty())

        onMain { current.toggle(listOf(text), NarrationLanguage.SPANISH) }
        assertFalse(onMain { current.isPlaying })
        assertPausedWithoutAdvance(current)

        var position = onMain { field<Long>(current, "offset") }
        seekPaused(current, 5, position + 5_000)
        position = onMain { field(current, "offset") }
        seekPaused(current, -3, position - 3_000)
        position = onMain { field(current, "offset") }
        seekPaused(current, -5, (position - 5_000).coerceAtLeast(0))
        seekPaused(current, 5, 5_000)

        for (rate in listOf(0.5f, 1f, 1.5f, 2f)) {
            // Keep every measurement away from completion, including short system voices.
            seekPaused(current, -3_600, 0)
            val before = onMain { field<Long>(current, "offset") }
            onMain {
                NarrationSettingsRepository.saveRate(context, rate)
                current.settingsChanged()
                assertNull("Changing speed must keep the paused decoder released", field<MediaPlayer?>(current, "player"))
                assertSame("Changing speed retains the prepared audio", audio,
                    field<File>(field<List<*>>(current, "segments").first()!!, "file"))
                assertEquals("The requested speed must remain stored while paused", rate,
                    NarrationSettingsRepository.get(context).rate, 0.001f)
                assertFalse("Speed must preserve the requested paused state", current.isPlaying)
            }
            assertPausedWithoutAdvance(current)
            assertEquals("Speed changes preserve the exact source timestamp", before, onMain { field<Long>(current, "offset") })
            assertActualPlaybackRate(current, playbackStarts, rate)
            assertPausedWithoutAdvance(current)
        }
        assertEquals("Rate changes and seeks must keep the same source file", audio, onMain {
            field<File>(field<List<*>>(current, "segments").first()!!, "file")
        })
        assertEquals("Cached audio bytes must be reused", fileLength, audio.length())
        assertEquals("Pause, seeks and rates must never synthesize different audio", fileHash, sha256(audio))
        assertEquals("Cached audio must not be rewritten", fileTimestamp, audio.lastModified())
        assertEquals("Changing playback position/rate must retain the page generation", generation, onMain { field<Long>(current, "generation") })

        val beforeResume = onMain { field<Long>(current, "offset") }
        resumeAtSavedPosition(current, playbackStarts)
        await("Resume must advance the same cached audio", 5_000) {
            current.isPlaying && ready(current) && player(current).isPlaying && player(current).currentPosition > beforeResume + 200
        }
        onMain { current.restart() }
        await("Restart must return to the beginning and continue playback", 5_000) {
            ready(current) && current.isPlaying && player(current).isPlaying && player(current).currentPosition < 1_000
        }
        assertTrue("Restart must retain the audio cache", audio.isFile)
        onMain { current.toggle(listOf(text), NarrationLanguage.SPANISH) }
        onMain { current.restart() }
        await("Long-press restart also starts a paused page", 5_000) {
            ready(current) && current.isPlaying && player(current).isPlaying && player(current).currentPosition < 1_000
        }
        assertTrue("Callbacks must report both playback and pause", true in states && false in states)

        onMain {
            current.stop()
            assertFalse(current.isPlaying)
            assertFalse(current.hasNarration)
            assertNull(field<Any?>(current, "player"))
        }
        await("Stopping a page must delete temporary audio files", 10_000) {
            !directory.exists()
        }
        if (engine == NarrationEngine.KOKORO) {
            assertTrue("Generated Kokoro narration persists across pages", audio.isFile)
            // Reusing persisted audio may avoid loading either ONNX or G2P.
            if (loadedSpanishFrontend) {
                assertNotNull("The Spanish lexicon is reusable while ONNX stays open",
                    field<Any?>(current, "spanishLexicon"))
            }
        }
        onMain { current.close() }
        await("Closing must release the engine and worker", 10_000) { !field<Thread>(current, "workerThread").isAlive }
        onMain {
            assertNull(field<Any?>(current, "tts"))
            assertFalse("Closing must release this controller's shared neural request", ownsNeuralRequest(current))
            assertNull(field<Any?>(current, "english"))
            assertNull(field<Any?>(current, "french"))
            assertNull(field<Any?>(current, "spanishLexicon"))
            current.toggle(listOf(text), NarrationLanguage.SPANISH)
            assertFalse("A closed controller cannot revive a page", current.hasNarration)
            assertFalse(current.isPlaying)
        }
    }

    private fun seekPaused(current: LessonNarrationController, seconds: Int, expected: Long) {
        onMain {
            val segment = field<Int>(current, "index")
            current.seekBy(seconds)
            assertEquals("The one-segment fixture retains its source segment", segment, field<Int>(current, "index"))
            assertEquals("A paused seek uses the exact source offset", expected, field<Long>(current, "offset"))
        }
        assertPausedWithoutAdvance(current)
    }

    private fun assertPausedWithoutAdvance(current: LessonNarrationController) {
        val before = onMain {
            assertFalse(current.isPlaying)
            assertNull("Pausing releases the native player", field<MediaPlayer?>(current, "player"))
            assertFalse("No paused decoder remains ready", ready(current))
            assertFalse("Pausing releases audio focus", field(current, "focusHeld"))
            field<Int>(current, "index") to field<Long>(current, "offset")
        }
        SystemClock.sleep(350)
        onMain {
            assertFalse(current.isPlaying)
            assertNull("A paused position must not allocate a player", field<MediaPlayer?>(current, "player"))
            assertFalse(ready(current))
            assertEquals("The paused source segment remains exact", before.first, field<Int>(current, "index"))
            assertEquals("The paused source offset cannot advance", before.second, field<Long>(current, "offset"))
            val saved = requireNotNull(NarrationResumeStore(context).restore(
                field(current, "resumeKey"), field<List<*>>(current, "segments").size))
            assertEquals("The existing bookmark owns the exact paused segment", before.first, saved.segment)
            assertEquals("The existing bookmark owns the exact paused offset", before.second, saved.positionMs)
        }
    }

    private fun resumeAtSavedPosition(current: LessonNarrationController,
                                      starts: List<NarrationPlaybackProgress>): MediaPlayer {
        val point = onMain { field<Int>(current, "index") to field<Long>(current, "offset") }
        val previousStarts = starts.size
        onMain {
            assertNull("Resume begins with a released decoder", field<MediaPlayer?>(current, "player"))
            current.toggle(listOf(text), NarrationLanguage.SPANISH)
            assertEquals(point.first, field<Int>(current, "index"))
            assertEquals("Resume retains the exact requested source position", point.second, field<Long>(current, "offset"))
        }
        await("Resume reconstructs a real player from the retained audio", 5_000) {
            current.isPlaying && ready(current) && player(current).isPlaying && starts.size > previousStarts
        }
        return onMain {
            val started = starts[previousStarts]
            assertEquals("Actual playback starts in the saved segment", point.first, started.segmentIndex)
            assertEquals("Actual playback starts at the exact saved offset", point.second, started.positionMs)
            assertTrue("The newly prepared player seeks before playing",
                abs(player(current).currentPosition.toLong() - point.second) <= 250L)
            player(current)
        }
    }

    private fun assertActualPlaybackRate(current: LessonNarrationController,
                                         starts: List<NarrationPlaybackProgress>, rate: Float) {
        val cachedPlayer = resumeAtSavedPosition(current, starts)
        val before = onMain { cachedPlayer.currentPosition }
        await("Resuming at $rate must advance the prepared audio", 5_000) {
            current.isPlaying && cachedPlayer.isPlaying && cachedPlayer.currentPosition > before + 120
        }
        val start = onMain {
            assertSame("Resume after a speed change must reuse the player", cachedPlayer, player(current))
            assertEquals("The running player must use the requested speed", rate, cachedPlayer.playbackParams.speed, 0.001f)
            assertEquals("Running pitch remains neutral", 1f, cachedPlayer.playbackParams.pitch, 0.001f)
            SystemClock.uptimeMillis() to cachedPlayer.currentPosition
        }
        SystemClock.sleep(2_000)
        val end = onMain {
            assertSame("Rate measurement must retain the cached player", cachedPlayer, player(current))
            if (!current.isPlaying || !cachedPlayer.isPlaying) {
                fail("Real playback must continue during the measurement; requestedRate=$rate, " +
                    "startPosition=${start.second}, elapsed=${SystemClock.uptimeMillis() - start.first}; ${diagnostics()}")
            }
            assertEquals(rate, cachedPlayer.playbackParams.speed, 0.001f)
            assertEquals(1f, cachedPlayer.playbackParams.pitch, 0.001f)
            val measured = SystemClock.uptimeMillis() to cachedPlayer.currentPosition
            current.toggle(listOf(text), NarrationLanguage.SPANISH)
            measured
        }
        val elapsed = end.first - start.first
        val advanced = end.second - start.second
        val expected = elapsed * rate
        // Allow timestamp/buffer jitter while still rejecting every other supported speed.
        val tolerance = maxOf(200f, expected * 0.15f)
        assertTrue("Speed $rate: advanced $advanced ms in $elapsed ms; expected $expected +/- $tolerance",
            abs(advanced - expected) <= tolerance)
        onMain {
            assertFalse("Measurement must return to pause", current.isPlaying)
            assertNull("Pausing after the measurement releases its decoder", field<MediaPlayer?>(current, "player"))
        }
    }

    @Suppress("DEPRECATION")
    private fun assumeAndroidTtsInstalled() {
        // Only a missing installed service is a prerequisite omission. Initialization,
        // voice selection, synthesis and playback errors must still fail the test.
        val services = deviceContext.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        assumeTrue("Android TTS omitted: no installed TTS service on the target device; real Kokoro is tested separately",
            services.isNotEmpty())
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun ready(current: LessonNarrationController): Boolean = field(current, "playerReady")
    private fun player(current: LessonNarrationController): MediaPlayer = field(current, "player")
    private fun sharedNeuralEngine(): Any? = synchronized(field<Any>(KokoroEngineCoordinator, "state")) {
        field(KokoroEngineCoordinator, "engine")
    }
    private fun ownsNeuralRequest(current: LessonNarrationController): Boolean =
        synchronized(field<Any>(KokoroEngineCoordinator, "state")) {
            field<Any?>(KokoroEngineCoordinator, "active")?.let { field<Any>(it, "owner") === current } == true
        }
    @Suppress("UNCHECKED_CAST") private fun <T> field(instance: Any, name: String): T =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
    private fun <T> onMain(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }
    private fun await(message: String, timeout: Long, checkErrors: Boolean = true, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (checkErrors && errors.isNotEmpty()) fail("$message; unexpected errors: $errors; ${diagnostics()}")
            if (onMain(condition)) return
            SystemClock.sleep(40)
        }
        fail("$message; errors=$errors; ${diagnostics()}")
    }

    private fun diagnostics(): String = if (Looper.myLooper() == Looper.getMainLooper()) {
        diagnosticsOnMain()
    } else onMain(::diagnosticsOnMain)

    private fun diagnosticsOnMain(): String = runCatching {
            val current = controller ?: return@runCatching "controller=null, focus=${foreground?.hasWindowFocus()}"
            val media = field<MediaPlayer?>(current, "player")
            val mediaState = if (media == null) "null" else runCatching {
                "running=${media.isPlaying}, position=${media.currentPosition}, duration=${media.duration}, " +
                    "effectiveRate=${media.playbackParams.speed}, pitch=${media.playbackParams.pitch}"
            }.getOrElse { "unprepared: ${it.javaClass.simpleName}" }
            val files = field<File?>(current, "directory")?.listFiles()?.joinToString { "${it.name}:${it.length()}" }
            val worker = field<Thread>(current, "workerThread")
            "states=$states, errors=$errors, playing=${current.isPlaying}, hasNarration=${current.hasNarration}, " +
                "finished=${field<Boolean>(current, "finished")}, requestedRate=${NarrationSettingsRepository.get(context).rate}, " +
                "ready=${field<Boolean>(current, "playerReady")}, focusHeld=${field<Boolean>(current, "focusHeld")}, " +
                "windowFocus=${foreground?.hasWindowFocus()}, player=[$mediaState], " +
                "index=${field<Int>(current, "index")}, offset=${field<Long>(current, "offset")}, " +
                "pending=${field<Set<Int>>(current, "pending")}, files=[$files], " +
                "worker=${worker.state} ${worker.stackTrace.take(6).joinToString(" <- ")}, " +
                "sharedKokoro=${sharedNeuralEngine() != null}, ownsKokoro=${ownsNeuralRequest(current)}, " +
                "tts=${field<Any?>(current, "tts") != null}"
        }.getOrElse { "diagnostics unavailable: $it" }

    private fun modelMetadata() = modelRoot.listFiles().orEmpty().associate { it.name to (it.length() to it.lastModified()) }
    private fun preferenceFiles() = File(deviceContext.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
        .filter { it.name.endsWith(".xml") }.map { it.name.removeSuffix(".xml") }.toSet()

    private fun deviceShell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).use { it.readBytes() }
    }

    private class IsolatedApplication(private val actual: Application, root: File) : Application() {
        private val files = File(root, "files").apply { check(mkdirs()) }
        private val cache = File(root, "cache").apply { check(mkdirs()) }
        private val prefix = "shared_playback_test_${UUID.randomUUID()}_"
        val preferenceNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        init { attachBaseContext(actual) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = prefix + name
            preferenceNames.add(scoped)
            return actual.getSharedPreferences(scoped, mode)
        }
    }

    companion object {
        private val KNOWN_PREFERENCES = setOf("learning_progress", "navigation_state", "lesson_narration_settings",
            "app_settings", "ui_language_settings", "kokoro_model_downloads")
    }
}
