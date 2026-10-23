package com.learngraphicsprogramming.app.narration

import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import com.learngraphicsprogramming.app.CoursesActivity
import com.learngraphicsprogramming.app.AppearanceSettingsRepository
import org.junit.Before
import org.junit.After
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.learngraphicsprogramming.app.BuildConfig
import com.learngraphicsprogramming.app.narration.phonemes.SpanishFluidG2P
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only native inference measurements: no downloads, cache audio, settings or device actions. */
@RunWith(AndroidJUnit4::class)
class KokoroCpuBenchmarkDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private var scenario: ActivityScenario<CoursesActivity>? = null

    @Before fun keepQaVisibleWithoutChangingDeviceSettings() {
        assertEquals(QA_PACKAGE, context.packageName)
        scenario = ActivityScenario.launch<CoursesActivity>(Intent(context, CoursesActivity::class.java)
            .putExtra("routeId", AppearanceSettingsRepository.ROUTE_PROGRAMMING)).also { host ->
            host.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    @After fun releaseQaWindow() { scenario?.close() }

    @Test fun benchmarkInstalledPinnedModelAndActualWorkerCpuMasks() {
        assertEquals("Never benchmark the ordinary or Work application", QA_PACKAGE, context.packageName)
        assertEquals(QA_PACKAGE, BuildConfig.APPLICATION_ID)
        assertNotEquals("Inference must remain off the Android main thread", Looper.getMainLooper(), Looper.myLooper())
        val phase = arguments.getString("benchmarkPhase", "baseline").also {
            require(it.matches(Regex("[a-zA-Z0-9_-]{1,40}")))
        }
        val root = arguments.getString("modelRoot")?.let(::File)
            ?: File(requireNotNull(context.getExternalFilesDir(null)), "kokoro/${KokoroModelManifest.revision}")
        val voice = benchmarkVoice()
        val source = benchmarkSource()
        val runCount = arguments.getString("benchmarkRuns", "4").toInt().also { require(it in 2..4) }
        val writeEvidence = arguments.getString("benchmarkWriteEvidence", "true").toBooleanStrict()
        val resources = KokoroModelManifest.sharedArtifacts + KokoroModelManifest.resources(NarrationLanguage.SPANISH) +
            requireNotNull(KokoroVoiceCatalog.find(voice)).artifact
        val beforeFiles = resources.associate { artifact ->
            val file = File(root, artifact.fileName)
            assertTrue("The pinned installed resource is required; nothing is downloaded: ${artifact.fileName}", file.isFile)
            val fingerprint = fingerprint(file)
            assertEquals("Installed bytes retain the pinned digest", artifact.sha256, fingerprint.getString("sha256"))
            assertEquals(artifact.bytes, fingerprint.getLong("bytes"))
            artifact.fileName to fingerprint
        }
        val lexicon = SpanishFluidG2P.loadLexicon(File(root, "es_lexicon_cache.json"))
        val phonemes = MixedSpanishG2P.phonemizePrepared(source, lexicon)
        assertTrue(phonemes.isNotBlank() && phonemes.length < KokoroModelManifest.maximumPhonemeTokens)
        val caller = requireNotNull(tasks()[Process.myTid()])
        val profiles = mutableListOf(Profile("current-page", true, null), Profile("automatic-background", false, 2))
        arguments.getString("cpuProfiles")?.split(',')?.map { it.trim().toInt() }?.distinct()?.forEach {
            require(it in setOf(1, 2, 4, 8))
            profiles += Profile("experimental-cpu-$it", false, it)
        }
        arguments.getString("onlyProfile")?.let { selected ->
            require(profiles.any { it.name == selected }) { "Unknown benchmark profile: $selected" }
            profiles.removeAll { it.name != selected }
        }
        val fallbackThreads = arguments.getString("cpuFallbackThreads", if (phase == "baseline") "1" else "2").toInt().also {
            require(it in setOf(1, 2, 4, 8)) { "The A/B fallback experiment permits one, two, four or eight threads" }
        }
        val records = JSONArray()
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "cpu-planning-qa")
        val result = JSONObject().put("phase", phase).put("package", context.packageName)
            .put("device", Build.MODEL).put("fingerprint", Build.FINGERPRINT)
            .put("abi", JSONArray(Build.SUPPORTED_ABIS.toList())).put("sdk", Build.VERSION.SDK_INT)
            .put("runtimeAvailableProcessors", Runtime.getRuntime().availableProcessors())
            .put("callerAllowedCpus", caller.allowed).put("voice", voice)
            .put("sampleRate", KokoroModelManifest.sampleRate).put("modelRevision", KokoroModelManifest.revision)
            .put("phonemeSha256", digest(phonemes.toByteArray(Charsets.UTF_8)))
            .put("phonemeCount", phonemes.length).put("source", source)
            .put("memoryBefore", memory()).put("filesBefore", JSONObject(beforeFiles))
            .put("profiles", records)
            .put("scope", "Actual Android process CPU masks and main-handler latency; heartbeat gaps are not rendered-frame measurements")
        fun persist() { if (writeEvidence) { output.mkdirs(); File(output, "benchmark-$phase.json").writeText(result.toString(2)) } }
        persist()
        var reference: FloatArray? = null
        try {
            profiles.forEach { profile ->
                val beforeEngine = tasks()
                val record = JSONObject().put("name", profile.name).put("preferAccelerator", profile.accelerator)
                    .put("requestedCpuThreads", profile.threads ?: JSONObject.NULL)
                    .put("cpuFallbackThreads", if (profile.name == "automatic-background" || profile.name.startsWith("experimental-cpu-")) fallbackThreads else JSONObject.NULL)
                    .put("memoryBeforeSession", memory()).put("tasksBeforeSession", snapshotJson(beforeEngine))
                records.put(record)
                persist()
                val setupStarted = SystemClock.elapsedRealtimeNanos()
                val samples = JSONArray()
                record.put("runs", samples)
                var backgroundScope: AutoCloseable? = null
                try {
                    if (phase != "baseline" && profile.name == "automatic-background") {
                        backgroundScope = NarrationCpuBudget.capBackground()
                        record.put("actualBackgroundAllowedCpus", JSONArray(NarrationCpuBudget.allowedCpus()))
                            .put("backgroundAffinitySelection", NarrationCpuBudget.lastSelection.toString())
                        assertTrue("The final automatic profile has an actual one/two-CPU mask",
                            NarrationCpuBudget.allowedCpus().size in 1..2)
                    }
                    val selectedEngine = if (profile.name == "automatic-background" || profile.name.startsWith("experimental-cpu-")) {
                        KokoroOnnxEngine(root, preferAccelerator = profile.accelerator,
                            cpuThreads = profile.threads, cpuFallbackThreads = fallbackThreads)
                    } else KokoroOnnxEngine(root, preferAccelerator = profile.accelerator, cpuThreads = profile.threads)
                    selectedEngine.use { engine ->
                        record.put("sessionCreationMillis", (SystemClock.elapsedRealtimeNanos() - setupStarted) / 1_000_000.0)
                            .put("usesNnapi", engine.usesNnapi).put("usesXnnpack", engine.usesXnnpack)
                        val afterConstruction = tasks()
                        val newSessionThreads = afterConstruction.filterKeys { it !in beforeEngine }
                        val nativePools = newSessionThreads.values.filter { nativePoolName(it.name, caller.name) }
                        val ownedInferenceTids = nativePools.map { it.tid }.toSet() + caller.tid
                        val finalBackgroundMask = if (backgroundScope != null) NarrationCpuBudget.allowedCpus() else null
                        record.put("tasksAfterSessionConstruction", snapshotJson(afterConstruction))
                            .put("newSessionThreads", snapshotJson(newSessionThreads))
                            .put("identifiedNativePoolThreads", JSONArray(nativePools.map { it.json() }))
                            .put("nativePoolIdentification", "New session TIDs named onnx/xnn/ort/pthreadpool or inheriting the inference caller name; unrelated ART/GC/JIT threads are not attributed to inference")
                        repeat(runCount) { run ->
                            val before = tasks()
                            val sampler = CpuSampler(before)
                            val heartbeat = MainHeartbeat()
                            lateinit var pcm: FloatArray
                            var elapsed = 0.0
                            try {
                                sampler.start()
                                heartbeat.start()
                                val start = SystemClock.elapsedRealtimeNanos()
                                pcm = engine.synthesizePhonemes(phonemes, voice, 1f)
                                elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                            } finally {
                                heartbeat.close()
                                sampler.close()
                            }
                            val after = tasks()
                            assertTrue("Real Kokoro produces nonempty finite 24 kHz PCM", pcm.isNotEmpty() && pcm.all { it.isFinite() })
                            val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
                            assertTrue("Synthesized speech is not silence", rms > .0001)
                            val comparison = reference?.let { compare(it, pcm) } ?: JSONObject().put("reference", true)
                            if (reference == null) reference = pcm
                            val consumed = after.values.mapNotNull { task ->
                                val old = before[task.tid]
                                val delta = task.ticks - (old?.ticks ?: 0L)
                                if (delta <= 0L) null else task.json().put("cpuTicksConsumed", delta)
                                    .put("createdDuringSession", task.tid !in beforeEngine)
                            }
                            assertTrue("At least one real process thread consumes CPU ticks", consumed.isNotEmpty())
                            assertTrue("Native inference exposes actual active-thread CPU masks", sampler.observedMasks().isNotEmpty())
                            assertTrue("Observed worker CPU masks are readable", sampler.observedMasks().all { it.optString("allowedCpus").isNotBlank() })
                            val consumedInference = after.values.filter { task ->
                                task.tid in ownedInferenceTids && task.ticks > (before[task.tid]?.ticks ?: 0L)
                            }
                            if (finalBackgroundMask != null) {
                                assertTrue("The actual caller consumes CPU while synthesizing", consumedInference.any { it.tid == caller.tid })
                                consumedInference.forEach { task ->
                                    assertEquals("Every identified native compute worker stays inside the selected background pair: ${task.tid}/${task.name}",
                                        finalBackgroundMask, parseCpuMask(task.allowed))
                                }
                                sampler.observedMasks().filter { it.getInt("tid") in ownedInferenceTids }.forEach { observed ->
                                    assertEquals("The runtime mask during active native computation matches the selected pair: ${observed.getInt("tid")}",
                                        finalBackgroundMask, parseCpuMask(observed.getString("allowedCpus")))
                                }
                            }
                            samples.put(JSONObject().put("index", run).put("warmup", run == 0)
                                .put("elapsedMillis", elapsed).put("samples", pcm.size)
                                .put("audioSeconds", pcm.size.toDouble() / KokoroModelManifest.sampleRate)
                                .put("realtimeFactor", elapsed * KokoroModelManifest.sampleRate / (pcm.size * 1_000.0))
                                .put("rms", rms).put("pcmFloatSha256", pcmDigest(pcm)).put("comparisonToFirstRun", comparison)
                                .put("mainHandler", heartbeat.result()).put("memory", memory())
                                .put("consumedThreads", JSONArray(consumed))
                                .put("identifiedInferenceComputeThreads", JSONArray(consumedInference.map { it.json() }))
                                .put("runtimeAllowedMasks", JSONArray(sampler.observedMasks()))
                                .put("tasksBefore", snapshotJson(before)).put("tasksAfter", snapshotJson(after)))
                            persist()
                        }
                    }
                } finally {
                    backgroundScope?.close()
                    record.put("memoryAfterSessionClosed", memory()).put("tasksAfterSessionClosed", snapshotJson(tasks()))
                    persist()
                }
                val measured = (1 until samples.length()).map { samples.getJSONObject(it).getDouble("elapsedMillis") }.sorted()
                record.put("measuredMedianMillis", measured[measured.size / 2])
                record.put("measuredMeanMillis", measured.average())
                measurement("KOKORO_HONOR_PROFILE " + JSONObject().put("phase", phase).put("profile", profile.name)
                    .put("voice", voice).put("fallbackThreads", if (profile.name == "current-page") 1 else fallbackThreads).put("phonemes", phonemes.length)
                    .put("setupMs", record.getDouble("sessionCreationMillis"))
                    .put("runsMs", JSONArray((0 until samples.length()).map { samples.getJSONObject(it).getDouble("elapsedMillis") }))
                    .put("audioSeconds", samples.getJSONObject(0).getDouble("audioSeconds"))
                    .put("callerMask", caller.allowed).put("xnnpack", record.getBoolean("usesXnnpack")))
                persist()
            }
        } finally {
            val afterFiles = resources.associate { it.fileName to fingerprint(File(root, it.fileName)) }
            result.put("filesAfter", JSONObject(afterFiles)).put("memoryAfter", memory())
                .put("resourcesUnchanged", beforeFiles.all { (name, before) -> before.toString() == afterFiles[name]?.toString() })
            persist()
            beforeFiles.forEach { (name, before) ->
                assertEquals("Inference must not modify installed model, voice or dictionaries: $name", before.toString(), afterFiles[name]?.toString())
            }
        }
    }

    /** Exactly the production foreground/background factory, with no generated cache files. */
    @Test fun benchmarkProductionCoordinatorProfilesAndSessionReuse() {
        assertEquals(QA_PACKAGE, context.packageName)
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        val root = File(requireNotNull(context.getExternalFilesDir(null)), "kokoro/${KokoroModelManifest.revision}")
        val voice = benchmarkVoice()
        val source = benchmarkSource()
        val lexicon = SpanishFluidG2P.loadLexicon(File(root, "es_lexicon_cache.json"))
        val phonemes = MixedSpanishG2P.phonemizePrepared(source, lexicon)
        require(phonemes.isNotBlank() && phonemes.length < KokoroModelManifest.maximumPhonemeTokens)
        val original = NarrationCpuBudget.allowedCpus()
        val owner = Any()
        KokoroEngineCoordinator.releaseIfIdle()
        try {
            val profiles = arguments.getString("coordinatorProfiles", "foreground,background,foreground")
                .split(',').map { when (it) { "foreground" -> true; "background" -> false; else -> error("Unknown production profile") } }
            profiles.forEachIndexed { pass, foreground ->
                var prior: KokoroOnnxEngine? = null
                repeat(2) { run ->
                    val before = tasks()
                    val acquire = SystemClock.elapsedRealtimeNanos()
                    KokoroEngineCoordinator.withEngine(owner, root, { false }, foreground = foreground) { engine ->
                        val setupMs = (SystemClock.elapsedRealtimeNanos() - acquire) / 1_000_000.0
                        if (run > 0) assertSame("Same profile reuses its production session", prior, engine)
                        prior = engine
                        val mask = NarrationCpuBudget.allowedCpus()
                        if (!foreground) assertTrue(mask.size in 1..2) else assertEquals(original, mask)
                        val sampler = CpuSampler(tasks())
                        lateinit var pcm: FloatArray
                        val start = SystemClock.elapsedRealtimeNanos()
                        try {
                            sampler.start()
                            pcm = engine.synthesizePhonemes(phonemes, voice, 1f)
                        } finally { sampler.close() }
                        val inferenceMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        assertTrue(pcm.isNotEmpty() && pcm.all { it.isFinite() })
                        assertTrue(sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size) > .0001)
                        val owned = tasks().values.filter { it.tid !in before && nativePoolName(it.name, before.getValue(Process.myTid()).name) }
                        if (!foreground) owned.forEach { assertEquals(mask, parseCpuMask(it.allowed)) }
                        measurement("KOKORO_HONOR_PRODUCTION " + JSONObject().put("pass", pass).put("run", run)
                            .put("foreground", foreground).put("voice", voice).put("phonemes", phonemes.length)
                            .put("sourceKind", arguments.getString("benchmarkSource", "short"))
                            .put("setupMs", setupMs).put("inferenceMs", inferenceMs)
                            .put("audioSeconds", pcm.size.toDouble() / KokoroModelManifest.sampleRate)
                            .put("xnnpack", engine.usesXnnpack).put("nnapi", engine.usesNnapi)
                            .put("callerMask", JSONArray(mask)).put("newNativePools", JSONArray(owned.map { it.json() })))
                    }
                    assertEquals("Foreground permissions restored after each production lease", original, NarrationCpuBudget.allowedCpus())
                }
            }
        } finally { KokoroEngineCoordinator.releaseIfIdle() }
    }

    private fun measurement(value: String) {
        println(value)
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", value + "\n") })
    }

    private fun benchmarkVoice(): String = arguments.getString("benchmarkVoice", VOICE).also {
        require(KokoroVoiceCatalog.find(it)?.language == NarrationLanguage.SPANISH) { "A pinned Spanish voice is required" }
    }

    private fun benchmarkSource(): String = when (arguments.getString("benchmarkSource", "short")) {
        "short" -> SOURCE
        "curricular" -> {
            val lesson = JSONObject(context.assets.open("content/es/programming/cpp_fundamentals/programas_y_ejecucion/lesson_primer_programa_y_salida.json")
                .bufferedReader().use { it.readText() })
            val script = LessonNarrationScript.page(lesson, 0, NarrationLanguage.SPANISH, forKokoro = true)
            NarrationSegments.sentences(script.texts).filter { it.length in 180..320 }.maxBy { it.length }
        }
        else -> error("Unknown benchmark source")
    }

    private data class Profile(val name: String, val accelerator: Boolean, val threads: Int?)
    private data class Task(val tid: Int, val name: String, val user: Long, val system: Long, val allowed: String, val cpu: Int) {
        val ticks get() = user + system
        fun json() = JSONObject().put("tid", tid).put("name", name).put("userTicks", user)
            .put("systemTicks", system).put("allowedCpus", allowed).put("lastProcessor", cpu)
    }
    private fun task(directory: File, includeMask: Boolean = true): Task? = runCatching {
        val stat = File(directory, "stat").readText()
        val right = stat.lastIndexOf(')')
        val columns = stat.substring(right + 2).trim().split(Regex("\\s+"))
        val allowed = if (includeMask) File(directory, "status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim().orEmpty()
        } else ""
        Task(directory.name.toInt(), stat.substring(stat.indexOf('(') + 1, right),
            columns[11].toLong(), columns[12].toLong(), allowed, columns[36].toInt())
    }.getOrNull()
    private fun tasks(includeMask: Boolean = true): Map<Int, Task> = File("/proc/self/task").listFiles().orEmpty()
        .mapNotNull { task(it, includeMask) }.associateBy { it.tid }
    private fun snapshotJson(tasks: Map<Int, Task>) = JSONArray(tasks.values.sortedBy { it.tid }.map { it.json() })
    private fun nativePoolName(name: String, inherited: String): Boolean {
        val lower = name.lowercase(java.util.Locale.ROOT)
        if (listOf("jit", "gc", "heap", "finalizer", "reference", "signal", "perfetto", "profile saver", "renderthread")
                .any { it in lower }) return false
        return name == inherited || "onnx" in lower || "xnn" in lower || "pthreadpool" in lower ||
            Regex("(^|[-_: ])ort($|[-_: ])").containsMatchIn(lower)
    }
    private fun parseCpuMask(mask: String): List<Int> = mask.split(',').flatMap { item ->
        val range = item.trim().split('-').map(String::toInt)
        if (range.size == 1) listOf(range[0]) else (range[0]..range[1]).toList()
    }.distinct().sorted()

    private inner class CpuSampler(before: Map<Int, Task>) : AutoCloseable {
        private val active = AtomicBoolean(true)
        private var previous = before
        private val masks = linkedMapOf<Pair<Int, String>, JSONObject>()
        private val thread = Thread({
            val ownTid = Process.myTid()
            while (active.get()) {
                val now = tasks(false)
                now.values.filter { it.tid != ownTid && it.ticks > (previous[it.tid]?.ticks ?: 0L) }.forEach { changed ->
                    task(File("/proc/self/task/${changed.tid}"))?.let { full ->
                        masks[full.tid to full.allowed] = full.json().put("observedUptimeMillis", SystemClock.uptimeMillis())
                    }
                }
                previous = now
                SystemClock.sleep(100)
            }
        }, "CpuBenchSampler").apply { isDaemon = true }
        fun start() = thread.start()
        override fun close() { active.set(false); thread.join(5_000); assertFalse("The test sampler releases its thread", thread.isAlive) }
        fun observedMasks() = masks.values.toList()
    }

    private class MainHeartbeat : AutoCloseable {
        private val handler = Handler(Looper.getMainLooper())
        private val active = AtomicBoolean(true)
        private val started = CountDownLatch(1)
        private val gaps = mutableListOf<Long>()
        private var last = 0L
        private val callback = object : Runnable {
            override fun run() {
                if (!active.get()) return
                val now = SystemClock.uptimeMillis()
                if (last != 0L) gaps += now - last
                last = now
                started.countDown()
                handler.postDelayed(this, 16)
            }
        }
        fun start() { handler.post(callback); assertTrue("Main handler remains responsive", started.await(10, TimeUnit.SECONDS)) }
        override fun close() {
            active.set(false)
            handler.removeCallbacks(callback)
            val barrier = CountDownLatch(1)
            handler.post { barrier.countDown() }
            assertTrue("The benchmark releases its main-thread callbacks", barrier.await(10, TimeUnit.SECONDS))
        }
        fun result(): JSONObject {
            val sorted = gaps.sorted()
            val p95 = if (sorted.isEmpty()) 0L else sorted[(ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)]
            return JSONObject().put("requestedIntervalMillis", 16).put("samples", sorted.size)
                .put("p95CallbackGapMillis", p95).put("maximumCallbackGapMillis", sorted.lastOrNull() ?: 0)
                .put("gapsOver32Millis", sorted.count { it > 32 }).put("rawGapsMillis", JSONArray(gaps))
        }
    }

    private fun memory(): JSONObject {
        val runtime = Runtime.getRuntime()
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val process = File("/proc/self/status").readLines().filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }
        return JSONObject().put("totalPssKiB", info.totalPss).put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory()).put("processResident", JSONArray(process))
    }
    private fun fingerprint(file: File): JSONObject = JSONObject().put("bytes", file.length())
        .put("lastModified", file.lastModified()).put("sha256", digest(file))
    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val size = input.read(bytes); if (size < 0) break; sha.update(bytes, 0, size) }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun pcmDigest(pcm: FloatArray): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(4_096).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { sample ->
            if (bytes.remaining() < 4) { sha.update(bytes.array(), 0, bytes.position()); bytes.clear() }
            bytes.putInt(sample.toRawBits())
        }
        sha.update(bytes.array(), 0, bytes.position())
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    private fun compare(first: FloatArray, next: FloatArray): JSONObject {
        val common = minOf(first.size, next.size)
        var maximum = 0.0
        var sum = 0.0
        for (index in 0 until common) {
            val difference = abs(first[index].toDouble() - next[index])
            maximum = maxOf(maximum, difference)
            sum += difference * difference
        }
        return JSONObject().put("sameSampleCount", first.size == next.size).put("sampleCountDifference", next.size - first.size)
            .put("maximumAbsoluteDifference", maximum).put("commonSampleRmse", sqrt(sum / common))
    }

    companion object {
        private const val QA_PACKAGE = "com.learngraphicsprogramming.app.curricularqa"
        private const val VOICE = "ef_dora"
        private const val SOURCE = "La página actual tiene prioridad y conserva la calidad de la voz mientras el resto del audio espera su turno."
    }
}
