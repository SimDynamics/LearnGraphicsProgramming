package com.learngraphicsprogramming.app.narration

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.system.Os
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.learngraphicsprogramming.app.AppearanceSettingsRepository
import com.learngraphicsprogramming.app.BuildConfig
import com.learngraphicsprogramming.app.CourseActivity
import com.learngraphicsprogramming.app.CoursesActivity
import com.learngraphicsprogramming.app.LessonActivity
import com.learngraphicsprogramming.app.NavigationStateRepository
import com.learngraphicsprogramming.app.PremiumRepository
import com.learngraphicsprogramming.app.UnitActivity
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Test-owned page fixtures use real installed Kokoro; they are never curricular or performance claims. */
@RunWith(AndroidJUnit4::class)
class NarrationAudioPlanningDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val actual = instrumentation.targetContext
    private val preferenceBackups = linkedMapOf<String, Map<String, *>>()
    private var originalPreferenceFiles = emptySet<String>()
    private val fileBackups = linkedMapOf<File, ByteArray?>()
    private lateinit var sandbox: File
    private lateinit var isolated: IsolatedApplication
    private var captured = false
    private var worker: KokoroBackgroundNarrationWorker? = null
    private var workerThread: Thread? = null
    private var activityController: LessonNarrationController? = null
    private val active = AtomicBoolean(true)
    private val programming = AppearanceSettingsRepository.ROUTE_PROGRAMMING
    private val math = AppearanceSettingsRepository.ROUTE_MATH_PHYSICS

    @Before fun isolateQaStateAndUseInstalledResourcesOnly() {
        assertEquals("Never run audio-planning acceptance against the normal application", QA_PACKAGE, actual.packageName)
        assertEquals(QA_PACKAGE, BuildConfig.APPLICATION_ID)
        val keyguard = actual.getSystemService(KeyguardManager::class.java)
        assertFalse("Unlock LDPlayer before Android navigation checks", keyguard.isDeviceLocked)
        originalPreferenceFiles = preferenceFiles()
        (originalPreferenceFiles + setOf("lesson_narration_settings", "learning_progress", "navigation_state", "premium"))
            .forEach { name -> preferenceBackups[name] = actual.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues {
                (_, value) -> if (value is Set<*>) value.toSet() else value
            } }
        listOf("", ".bak", ".new").forEach { suffix ->
            val file = File(actual.filesDir, NarrationAudioPlanner.FILE_NAME + suffix)
            fileBackups[file] = file.takeIf { it.isFile }?.readBytes()
        }
        captured = true
        NarrationSettingsRepository.saveEnabled(actual, false)
        actual.stopService(Intent(actual, KokoroContinuousNarrationService::class.java))
        sandbox = File(actual.cacheDir, "audio-planning-${UUID.randomUUID()}").canonicalFile
        assertEquals(actual.cacheDir.canonicalFile, sandbox.parentFile)
        assertTrue(sandbox.mkdirs())
        isolated = IsolatedApplication(actual.applicationContext as Application, sandbox)
        NarrationSettingsRepository.saveEnabled(isolated, true)
        NarrationSettingsRepository.saveEngine(isolated, NarrationEngine.KOKORO)
        NarrationSettingsRepository.saveLanguage(isolated, NarrationLanguage.SPANISH)
        NarrationSettingsRepository.saveKokoroVoice(isolated, VOICE)
        NarrationSettingsRepository.saveRate(isolated, 1f)
        NarrationStorageBudget.saveLimit(isolated, 64L * NarrationStorageBudget.MIB)
        assertFalse("No other interactive narration operation may interfere with this isolated worker", NarrationWorkPriority.isBusy)
    }

    @After fun closeOwnedInferenceBeforeRestoringQaState() {
        if (!captured) return
        try {
            active.set(false)
            worker?.cancel()
            workerThread?.interrupt()
            workerThread?.join(15_000L)
            assertFalse("Close our native worker before removing its private audio", workerThread?.isAlive == true)
            activityController?.let { controller ->
                instrumentation.runOnMainSync { controller.close() }
                await("Close the actual activity's narration worker before restoring preferences", 15_000L) {
                    !field<Thread>(controller, "workerThread").isAlive
                }
            }
            instrumentation.waitForIdleSync()
            KokoroEngineCoordinator.releaseIfIdle()
        } finally {
            if (::isolated.isInitialized) forgetStore(isolated)
            forgetStore(actual)
            fileBackups.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
            (preferenceFiles() + preferenceBackups.keys + if (::isolated.isInitialized) isolated.preferenceNames else emptySet())
                .forEach { name ->
                    if (name !in originalPreferenceFiles) actual.deleteSharedPreferences(name)
                    else {
                        val edit = actual.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                        preferenceBackups.getValue(name).forEach { (key, value) -> when (value) {
                            is String -> edit.putString(key, value)
                            is Int -> edit.putInt(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                            else -> error("Unsupported saved preference $name/$key")
                        } }
                        assertTrue(edit.commit())
                    }
                }
            if (::sandbox.isInitialized && workerThread?.isAlive != true) {
                assertEquals(actual.cacheDir.canonicalFile, sandbox.parentFile)
                assertTrue(sandbox.deleteRecursively())
            }
            // Restore the durable queue singleton's ordinary QA directory after the isolated calls.
            KokoroPagePreparationQueue.snapshot(actual)
        }
    }

    @Test fun realWorkerAlternatesWholePendingPagesReusesAudioAndRepairsOnlyItsInvalidFixture() {
        requireInstalledResources()
        val pages = mutableListOf(
            page(programming, 0, "El cuaderno violeta sigue abierto."),
            page(programming, 1, "El sendero turquesa avanza tranquilo."),
            page(math, 0, "La ventana naranja guarda este turno."),
            page(math, 1, "La carpeta verde conserva otro lugar.")
        )
        val currentWorker = newWorker(pages)
        assertEquals(KokoroBackgroundNarrationWorker.Outcome.COMPLETE, currentWorker.runSlice { active.get() })
        val expected = listOf(pages[0], pages[2], pages[1], pages[3]).map { NarrationPageQueue.hash(it.sentences.single()) }
        assertEquals(expected, generationEvents().map { it.getString("hash") })
        val recordings = pages.associate { page ->
            page.identity to requireNotNull(KokoroNarrationCache.lookup(isolated, "es", VOICE, page.sentences.single().text))
        }
        val original = recordings.mapValues { (_, file) -> audioIdentity(file) }
        recordings.values.forEach { assertTrue("A fixture was synthesized through the real installed model", it.length() > 44L) }
        val saved = File(isolated.filesDir, NarrationAudioPlanner.FILE_NAME).readText()
        val restarted = NarrationAudioPlan(saved)
        assertEquals(NarrationAudioPlanner.store(isolated).snapshot(), restarted.snapshot())
        assertEquals(2, restarted.snapshot().cursors.size)
        assertEquals(KokoroBackgroundNarrationWorker.Outcome.COMPLETE, currentWorker.runSlice { active.get() })
        assertEquals("Cached pages must not be synthesized a second time", expected, generationEvents().map { it.getString("hash") })
        assertEquals(original, recordings.mapValues { (_, file) -> audioIdentity(file) })

        // This intentionally incomplete file belongs only to the test. No preexisting recording is changed.
        val recovery = page(programming, 2, "La libreta celeste conserva una prueba adicional.")
        pages += recovery
        val recoveryHash = NarrationPageQueue.hash(recovery.sentences.single())
        val incomplete = File(recordings.values.first().parentFile, "$recoveryHash.wav")
        assertFalse(incomplete.exists())
        incomplete.writeBytes(byteArrayOf(82, 73, 70, 70, 0, 0))
        assertNull(KokoroNarrationCache.lookup(isolated, "es", VOICE, recovery.sentences.single().text))
        NarrationAudioPlanner.store(isolated).enter(programming, recovery.courseId)
        assertEquals(KokoroBackgroundNarrationWorker.Outcome.COMPLETE, currentWorker.runSlice { active.get() })
        assertNotNull(KokoroNarrationCache.lookup(isolated, "es", VOICE, recovery.sentences.single().text))
        assertEquals(expected + recoveryHash, generationEvents().map { it.getString("hash") })
        assertEquals(original, recordings.mapValues { (_, file) -> audioIdentity(file) })
        evidence("native-home-cache-and-recovery", JSONObject().put("nativeGeneratedHashes", JSONArray(expected + recoveryHash))
            .put("cachedRerunGenerated", 0).put("originalAudioUnchanged", true)
            .put("independentCursors", JSONObject(restarted.snapshot().cursors)))
    }

    @Test fun changingCourseDuringRealNativeInferenceKeepsCompletedAudioAndUsesLatestContext() {
        requireInstalledResources()
        val retained = "El cuaderno violeta conserva su primer avance."
        val long = "La narración mantiene cada palabra y conserva el orden de lectura. ".repeat(5).trim()
        val first = page(programming, 0, retained).let { base -> base.copy(sentences = base.sentences +
            NarrationCurriculum.Sentence(long, NarrationLanguage.SPANISH, VOICE, base.location)) }
        val target = page(math, 0, "La página matemática recibe ahora la prioridad.", course = "course2")
        val pages = mutableListOf(first, target)
        val currentWorker = newWorker(pages)
        val outcome = AtomicReference<KokoroBackgroundNarrationWorker.Outcome>()
        val failure = AtomicReference<Throwable>()
        workerThread = Thread({
            try { outcome.set(currentWorker.runSlice { active.get() }) }
            catch (error: Throwable) { failure.set(error) }
        }, "QaAudioPlannerNativeSwitch").apply { start() }
        await("The first sentence must be durably cached before the native interruption", 180_000L) {
            KokoroNarrationCache.lookup(isolated, "es", VOICE, retained) != null
        }
        await("The next automatic sentence must enter a real ONNX run", 180_000L) { nativeRunOwnedBy(currentWorker) }
        val kept = requireNotNull(KokoroNarrationCache.lookup(isolated, "es", VOICE, retained))
        val before = audioIdentity(kept)
        val selected = NarrationAudioPlanner.store(isolated).enter(math, target.courseId, target.lessonId, 0)
        workerThread!!.join(15_000L)
        assertFalse("Context changes must safely terminate the background owner", workerThread!!.isAlive)
        failure.get()?.let { throw AssertionError("Native context cancellation must be controlled", it) }
        assertEquals(KokoroBackgroundNarrationWorker.Outcome.MORE, outcome.get())
        assertEquals(before, audioIdentity(kept))
        assertEquals(selected, NarrationAudioPlanner.store(isolated).snapshot())
        val eventsBefore = generationEvents().size
        assertEquals(KokoroBackgroundNarrationWorker.Outcome.COMPLETE, currentWorker.runSlice { active.get() })
        val after = generationEvents().drop(eventsBefore)
        assertTrue(after.isNotEmpty())
        assertEquals("The latest page wins before returning to the interrupted route",
            NarrationPageQueue.hash(target.sentences.single()), after.first().getString("hash"))
        assertEquals(before, audioIdentity(kept))
        assertEquals(1, generationEvents().count { it.getString("hash") == KokoroNarrationCache.key("es", VOICE, retained) })
        evidence("native-latest-context", JSONObject().put("oldOutcome", outcome.get().name)
            .put("latestPageFirstHash", after.first().getString("hash")).put("previousAudioUnchanged", true))
    }

    @Test fun realNavigationPublishesCourseLessonPageAndRouteExitWithoutStartingAudio() {
        PremiumRepository.setVerifiedPremium(actual)
        val target = NarrationCurriculum.pages(actual, math).first { it.sentences.isNotEmpty() }
        ActivityScenario.launch<CourseActivity>(Intent(actual, CourseActivity::class.java)
            .putExtra("routeId", math).putExtra("courseId", target.courseId)).use { host ->
            host.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val selected = NarrationAudioPlanner.store(actual).snapshot().context
                assertEquals(math, selected.routeId)
                assertEquals(target.courseId, selected.courseId)
                assertNull(selected.lessonId)
            }
        }
        ActivityScenario.launch<UnitActivity>(Intent(actual, UnitActivity::class.java)
            .putExtra("routeId", math).putExtra("courseId", target.courseId)
            .putExtra("unitId", target.unitId)).use { host ->
            host.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val selected = NarrationAudioPlanner.store(actual).snapshot().context
                assertEquals(math, selected.routeId)
                assertEquals(target.courseId, selected.courseId)
                assertEquals(target.unitId, selected.unitId)
                assertNull(selected.lessonId)
            }
        }
        var closing: LessonNarrationController? = null
        ActivityScenario.launch<LessonActivity>(Intent(actual, LessonActivity::class.java)
            .putExtra("routeId", math).putExtra("courseId", target.courseId)
            .putExtra("unitId", target.unitId).putExtra("lessonId", target.lessonId)).use { host ->
            host.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activityController = field(activity, "narrationController")
                val selected = NarrationAudioPlanner.store(actual).snapshot().context
                assertEquals(math, selected.routeId)
                assertEquals(target.courseId, selected.courseId)
                assertEquals(target.lessonId, selected.lessonId)
                assertEquals(target.unitId, selected.unitId)
                assertEquals(field<Int>(activity, "currentPage"), selected.pageIndex)
                closing = activityController
                assertFalse(requireNotNull(closing).isPlaying)
            }
        }
        closing?.let { controller -> await("Navigation must release its actual narration worker", 15_000L) {
            !field<Thread>(controller, "workerThread").isAlive
        } }
        ActivityScenario.launch<CoursesActivity>(Intent(actual, CoursesActivity::class.java).putExtra("routeId", programming)).use { host ->
            host.onActivity {
                val selected = NarrationAudioPlanner.store(actual).snapshot().context
                assertEquals(programming, selected.routeId)
                assertNull(selected.courseId)
            }
        }
        instrumentation.runOnMainSync { NavigationStateRepository.saveRoutes(actual) }
        assertNull(NarrationAudioPlanner.store(actual).snapshot().context.routeId)
        assertFalse(NarrationSettingsRepository.get(actual).enabled)
    }

    @Test fun changingVoiceCancelsRealNativeInferenceWithoutDiscardingCompletedAudio() =
        exerciseProfileChange(NarrationLanguage.SPANISH)

    @Test fun changingLanguageCancelsRealNativeInferenceWithoutDiscardingCompletedAudio() =
        exerciseProfileChange(NarrationLanguage.FRENCH)

    private fun exerciseProfileChange(nextLanguage: NarrationLanguage) {
        requireInstalledResources()
        val store = KokoroModelStore(isolated)
        val modelRoot = try { store.directory() } finally { store.close() }
        val requestedVoice = if (nextLanguage == NarrationLanguage.FRENCH) "ff_siwis"
            else InstrumentationRegistry.getArguments().getString("nextVoice")
        val nextVoice = if (requestedVoice != null) requireNotNull(KokoroVoiceCatalog.find(requestedVoice)).also {
            require(it.language == nextLanguage && it.id != VOICE && File(modelRoot, it.artifact.fileName).isFile)
        } else KokoroVoiceCatalog.voices.firstOrNull {
            it.language == nextLanguage && it.id != VOICE && File(modelRoot, it.artifact.fileName).isFile
        } ?: error("Install a second original voice before this real profile-switch acceptance")
        KokoroModelManifest.resources(nextLanguage).forEach {
            assertTrue("Use only existing verified language resources", KokoroModelManifest.verify(File(modelRoot, it.fileName), it))
        }
        val retained = "La grabación terminada conserva la voz seleccionada."
        val long = "La narración mantiene cada palabra y conserva el orden de lectura. ".repeat(5).trim()
        val first = page(programming, 0, retained).let { base -> base.copy(sentences = base.sentences +
            NarrationCurriculum.Sentence(long, NarrationLanguage.SPANISH, VOICE, base.location)) }
        val pages = mutableListOf(first)
        val currentWorker = newWorker(pages)
        val outcome = AtomicReference<KokoroBackgroundNarrationWorker.Outcome>()
        val failure = AtomicReference<Throwable>()
        workerThread = Thread({
            try { outcome.set(currentWorker.runSlice { active.get() }) }
            catch (error: Throwable) { failure.set(error) }
        }, "QaAudioPlannerNativeVoiceSwitch").apply { start() }
        await("Cache a real completed sentence before changing voice", 180_000L) {
            KokoroNarrationCache.lookup(isolated, "es", VOICE, retained) != null
        }
        await("The old voice must own a real native inference before cancellation", 180_000L) {
            nativeRunOwnedBy(currentWorker)
        }
        val kept = requireNotNull(KokoroNarrationCache.lookup(isolated, "es", VOICE, retained))
        val original = audioIdentity(kept)
        if (nextLanguage != NarrationLanguage.SPANISH) NarrationSettingsRepository.saveLanguage(isolated, nextLanguage)
        NarrationSettingsRepository.saveKokoroVoice(isolated, nextVoice.id)
        // This worker is intentionally test-owned, not service-owned. The hot-path
        // profile guard must cancel even without the main-thread preference observer.
        workerThread!!.join(15_000L)
        assertFalse("A changed saved voice must end the old native pass", workerThread!!.isAlive)
        failure.get()?.let { throw AssertionError("Voice cancellation must remain a controlled interruption", it) }
        assertEquals(KokoroBackgroundNarrationWorker.Outcome.MORE, outcome.get())
        assertEquals(original, audioIdentity(kept))
        assertNull(KokoroNarrationCache.lookup(isolated, "es", VOICE, long))
        val latestText = if (nextLanguage == NarrationLanguage.FRENCH)
            "La langue actuelle conserve la narration." else "La voz actual conserva la anterior."
        val latest = page(programming, 0, latestText).let { base ->
            base.copy(sentences = base.sentences.map { it.copy(voice = nextVoice.id, language = nextLanguage) })
        }
        pages.clear(); pages += latest
        val before = generationEvents().size
        val latestOutcome = currentWorker.runSlice { active.get() }
        if (latestOutcome != KokoroBackgroundNarrationWorker.Outcome.COMPLETE) {
            fail("New language=$nextLanguage voice=${nextVoice.id}; outcome=$latestOutcome; " +
                "state=${KokoroNarrationDiagnostics.snapshot(isolated)}; " +
                "events=${File(isolated.filesDir, KokoroNarrationDiagnostics.EVENTS_FILE).readText()}")
        }
        assertNotNull(KokoroNarrationCache.lookup(isolated, nextLanguage.tag, nextVoice.id, latest.sentences.single().text))
        assertEquals(listOf(NarrationPageQueue.hash(latest.sentences.single())),
            generationEvents().drop(before).map { it.getString("hash") })
        assertEquals(original, audioIdentity(kept))
    }

    private fun newWorker(pages: List<NarrationCurriculum.Page>) = KokoroBackgroundNarrationWorker(isolated) { route, course ->
        pages.asSequence().filter { it.routeId == route && (course == null || it.courseId == course) }
    }.also { worker = it }
    private fun page(route: String, index: Int, text: String, course: String = "course1") =
        NarrationCurriculum.Page(route, course, "${course}__unit1", "${course}__unit1__lesson1", index,
            listOf(NarrationCurriculum.Sentence(text, NarrationLanguage.SPANISH, VOICE,
                "$route/$course/${course}__unit1/${course}__unit1__lesson1/${index + 1}")))
    private fun generationEvents(): List<JSONObject> = File(isolated.filesDir, KokoroNarrationDiagnostics.EVENTS_FILE)
        .takeIf { it.isFile }?.readLines()?.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        ?.filter { it.optString("event") == "Cached lesson speech" }.orEmpty()
    private fun requireInstalledResources() {
        val store = KokoroModelStore(isolated)
        val root = try { store.directory() } finally { store.close() }
        assertTrue("Use the existing installation without downloads", File(root, "model.onnx").isFile)
        assertTrue(File(root, "$VOICE.bin").isFile)
    }
    private fun nativeRunOwnedBy(owner: Any): Boolean {
        val coordinator = KokoroEngineCoordinator
        return synchronized(field<Any>(coordinator, "state")) {
            val lease = field<Any?>(coordinator, "active") ?: return@synchronized false
            if (field<Any>(lease, "owner") !== owner) return@synchronized false
            val engine = field<KokoroOnnxEngine?>(coordinator, "engine") ?: return@synchronized false
            synchronized(field<Any>(engine, "runLock")) { field<Any?>(engine, "activeRun") != null }
        }
    }
    private fun await(label: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(10L) }
        fail("$label; diagnostics=${if (::isolated.isInitialized) KokoroNarrationDiagnostics.snapshot(isolated) else "uninitialized"}")
    }
    private fun evidence(name: String, data: JSONObject) {
        val root = requireNotNull(actual.getExternalFilesDir("qa-audio-planning")).apply { mkdirs() }
        File(root, "$name-${UUID.randomUUID()}.json").writeText(data.put("testOwnedPageFixtures", true)
            .put("realInstalledKokoro", true).put("package", actual.packageName).toString(2))
    }
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private data class AudioIdentity(val sha256: String, val length: Long, val device: Long, val inode: Long)
    private fun audioIdentity(file: File): AudioIdentity {
        val stat = Os.stat(file.absolutePath)
        // lookup() intentionally refreshes its access timestamp; bytes and inode must stay intact.
        return AudioIdentity(sha256(file), file.length(), stat.st_dev, stat.st_ino)
    }
    private fun preferenceFiles() = File(actual.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
        .filter { it.name.endsWith(".xml") }.map { it.name.removeSuffix(".xml") }.toSet()
    @Suppress("UNCHECKED_CAST") private fun <T> field(instance: Any, name: String): T =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
    @Suppress("UNCHECKED_CAST") private fun forgetStore(context: Context) {
        val stores = field<MutableMap<File, NarrationAudioPlan>>(NarrationAudioPlanner, "stores")
        synchronized(NarrationAudioPlanner) { stores.remove(File(context.filesDir, NarrationAudioPlanner.FILE_NAME).canonicalFile) }
    }
    private class IsolatedApplication(private val actual: Application, root: File) : Application() {
        private val files = File(root, "files").apply { check(mkdirs()) }
        private val cache = File(root, "cache").apply { check(mkdirs()) }
        private val prefix = "audio_planning_${UUID.randomUUID()}_"
        val preferenceNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        init { attachBaseContext(actual) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val key = prefix + name
            preferenceNames += key
            return actual.getSharedPreferences(key, mode)
        }
    }
    companion object {
        private const val QA_PACKAGE = "com.learngraphicsprogramming.app.curricularqa"
        private const val VOICE = "ef_dora"
    }
}
