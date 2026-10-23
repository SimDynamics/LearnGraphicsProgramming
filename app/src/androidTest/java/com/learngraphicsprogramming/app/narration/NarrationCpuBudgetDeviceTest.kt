package com.learngraphicsprogramming.app.narration

import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.learngraphicsprogramming.app.BuildConfig
import com.learngraphicsprogramming.app.narration.phonemes.SpanishFluidG2P
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Kernel-affinity acceptance on the actual Android device; no global affinity or settings change. */
@RunWith(AndroidJUnit4::class)
class NarrationCpuBudgetDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun requireTheIsolatedApplication() {
        assertEquals("Never test against Work or the ordinary application", QA_PACKAGE, context.packageName)
        assertEquals(QA_PACKAGE, BuildConfig.APPLICATION_ID)
    }

    @Test fun realAvailableMasksOfOneTwoFourAndEightCpusRestoreAfterFailureWithoutTouchingMain() {
        val original = NarrationCpuBudget.allowedCpus()
        val main = mainMask()
        val results = JSONArray()
        try {
            for (count in listOf(1, 2, 4, 8).filter { it <= original.size }) {
                val available = original.take(count)
                setCallingThreadMask(available)
                assertEquals(available, NarrationCpuBudget.allowedCpus())
                var selected = emptyList<Int>()
                var inherited = emptyList<Int>()
                try {
                    NarrationCpuBudget.capBackground().use {
                        selected = NarrationCpuBudget.allowedCpus()
                        assertEquals(minOf(2, count), selected.size)
                        assertTrue(available.containsAll(selected))
                        assertEquals("The kernel status verifies the effective worker mask", selected, procMask(Process.myTid()))
                        val child = AtomicReference<List<Int>>()
                        val failure = AtomicReference<Throwable>()
                        val done = CountDownLatch(1)
                        Thread({
                            try { child.set(NarrationCpuBudget.allowedCpus()) }
                            catch (error: Throwable) { failure.set(error) }
                            finally { done.countDown() }
                        }, "NarrationAffinityChild").start()
                        assertTrue("The child exits without retaining resources", done.await(10, TimeUnit.SECONDS))
                        failure.get()?.let { throw it }
                        inherited = requireNotNull(child.get())
                        assertEquals("Native children inherit exactly the selected CPU pair", selected, inherited)
                        assertEquals("The Android UI retains its complete original mask", main, mainMask())
                        throw ExpectedScopeFailure()
                    }
                } catch (_: ExpectedScopeFailure) { }
                assertEquals("An exception restores the caller's exact prior mask", available, NarrationCpuBudget.allowedCpus())
                results.put(JSONObject().put("restrictedAvailableMaskSize", count).put("available", JSONArray(available))
                    .put("selected", JSONArray(selected)).put("childInherited", JSONArray(inherited))
                    .put("selection", NarrationCpuBudget.lastSelection.toString())
                    .put("restored", JSONArray(NarrationCpuBudget.allowedCpus())))
            }
        } finally {
            setCallingThreadMask(original)
            assertEquals(original, NarrationCpuBudget.allowedCpus())
            assertEquals(main, mainMask())
        }
        evidence("kernel-mask-subsets.json", JSONObject().put("device", Build.MODEL)
            .put("deviceAllowedMask", JSONArray(original)).put("mainMaskUnchanged", JSONArray(main))
            .put("scope", "Functional affinity restrictions of this same device; not performance measurements on four independent hardware configurations")
            .put("cases", results))
    }

    @Test fun nestedWorkersShareTheSamePairAndTheUiCannotBeCapped() {
        val original = NarrationCpuBudget.allowedCpus()
        val main = mainMask()
        val inherited = AtomicReference<List<Int>>()
        val failure = AtomicReference<Throwable>()
        try {
            NarrationCpuBudget.capBackground().use {
                val selected = NarrationCpuBudget.allowedCpus()
                val done = CountDownLatch(1)
                Thread({
                    try {
                        val before = NarrationCpuBudget.allowedCpus()
                        NarrationCpuBudget.capBackground().use {
                            inherited.set(NarrationCpuBudget.allowedCpus())
                        }
                        assertEquals(before, NarrationCpuBudget.allowedCpus())
                    } catch (error: Throwable) { failure.set(error) }
                    finally { done.countDown() }
                }, "NarrationOverlappingScope").start()
                assertTrue(done.await(10, TimeUnit.SECONDS))
                failure.get()?.let { throw it }
                assertEquals("Overlapping optional workers cannot select disjoint CPU pairs", selected, inherited.get())
            }
            val rejected = AtomicReference<Throwable>()
            instrumentation.runOnMainSync {
                try { NarrationCpuBudget.capBackground().close() }
                catch (error: Throwable) { rejected.set(error) }
            }
            assertTrue("Applying the optional-work cap to the UI is explicitly rejected", rejected.get() is NarrationCpuLimitException)
        } finally {
            assertEquals(original, NarrationCpuBudget.allowedCpus())
            assertEquals(main, mainMask())
        }
    }

    @Test fun kernelRestorationAcceptsAnAvailableSubsetAndRecoversAnUnavailableSavedCpuOnlyAfterTheOuterLease() {
        val original = NarrationCpuBudget.allowedCpus()
        val main = mainMask()
        // This is a deterministic syscall regression for the same EINVAL returned when a
        // saved CPU goes offline or becomes disallowed. It does not change device cpusets,
        // online CPUs, UI policy, or claim to measure a real hotplug performance event.
        val unavailable = 1023
        assertFalse(File("/sys/devices/system/cpu/cpu$unavailable").exists())
        val selected = original.take(2)
        var recovered = emptyList<Int>()
        try {
            setCallingThreadMask(selected)
            val partial = restoreCallingThreadMask(listOf(selected.first(), unavailable), false)
            assertEquals(listOf(selected.first()), partial)
            assertEquals(partial, procMask(Process.myTid()))
            setCallingThreadMask(selected)
            try {
                restoreCallingThreadMask(listOf(unavailable), false)
                fail("An enclosing optional lease must never be expanded by EINVAL recovery")
            } catch (expected: java.lang.reflect.InvocationTargetException) {
                assertTrue(expected.cause is IllegalStateException)
                assertTrue(requireNotNull(expected.cause?.message).contains("Invalid argument", ignoreCase = true))
            }
            assertEquals(selected, NarrationCpuBudget.allowedCpus())
            recovered = restoreCallingThreadMask(listOf(unavailable), true)
            assertTrue(recovered.isNotEmpty())
            assertEquals(recovered, procMask(Process.myTid()))
            assertTrue("The current cpuset still permits the initial caller mask", recovered.containsAll(original))
            NarrationCpuBudget.capBackground().use {
                val next = NarrationCpuBudget.allowedCpus()
                assertTrue(next.size in 1..2)
                assertTrue(recovered.containsAll(next))
                assertEquals(next, procMask(Process.myTid()))
            }
            assertEquals(recovered, NarrationCpuBudget.allowedCpus())
        } finally {
            restoreCallingThreadMask(original, true)
            assertEquals(main, mainMask())
        }
        evidence("kernel-restoration-einval.json", JSONObject().put("device", Build.MODEL)
            .put("original", JSONArray(original)).put("capped", JSONArray(selected))
            .put("unavailableCpu", unavailable).put("recoveredCurrentCpuset", JSONArray(recovered))
            .put("mainMaskUnchanged", JSONArray(main))
            .put("scope", "Real kernel syscall recovery with an unavailable CPU; no cpuset or device configuration was changed"))
    }

    @Test fun sameThreadNestedScopesRemainCappedUntilTheirFinalRelease() {
        val original = NarrationCpuBudget.allowedCpus()
        val main = mainMask()
        NarrationCpuBudget.capBackground().use {
            val selected = NarrationCpuBudget.allowedCpus()
            NarrationCpuBudget.capBackground().use {
                assertEquals(selected, procMask(Process.myTid()))
            }
            assertEquals("Releasing the inner engine lease keeps the outer frontend cap", selected,
                NarrationCpuBudget.allowedCpus())
        }
        assertEquals(original, NarrationCpuBudget.allowedCpus())
        assertEquals(main, mainMask())
    }

    @Test fun aRealXnnpackSessionCreatesNativeWorkersInsideTheSelectedCpuPair() {
        val original = NarrationCpuBudget.allowedCpus()
        val main = mainMask()
        val root = InstrumentationRegistry.getArguments().getString("modelRoot")?.let(::File)
            ?: File(requireNotNull(context.getExternalFilesDir(null)), "kokoro/${KokoroModelManifest.revision}")
        val model = File(root, "model.onnx")
        val modelSize = model.length()
        val modelModified = model.lastModified()
        assertEquals(KokoroModelManifest.sharedArtifacts.first { it.fileName == "model.onnx" }.bytes, modelSize)
        val voice = InstrumentationRegistry.getArguments().getString("cpuVoice") ?: "ef_dora"
        val voiceArtifact = requireNotNull(KokoroVoiceCatalog.find(voice)).artifact
        assertTrue("The selected original voice must match its pinned SHA-256",
            KokoroModelManifest.verify(File(root, voiceArtifact.fileName), voiceArtifact))
        val lexicon = SpanishFluidG2P.loadLexicon(File(root, "es_lexicon_cache.json"))
        val phones = SpanishFluidG2P.phonemize("La voz conserva el audio de la página actual.", lexicon)
        val before = tasks()
        val record = JSONObject().put("device", Build.MODEL).put("originalAllowed", JSONArray(original))
            .put("mainAllowed", JSONArray(main)).put("voice", voice)
            .put("tasksBeforeSession", JSONArray(before.values.toList()))
        try {
            NarrationCpuBudget.capBackground().use {
                val selected = NarrationCpuBudget.allowedCpus()
                record.put("selected", JSONArray(selected)).put("selection", NarrationCpuBudget.lastSelection.toString())
                KokoroOnnxEngine(root, preferAccelerator = false, cpuThreads = minOf(2, selected.size), cpuFallbackThreads = 2).use { engine ->
                    val constructed = tasks()
                    val callerName = requireNotNull(constructed[Process.myTid()]).getString("name")
                    val newSessionThreads = constructed.filterKeys { it !in before }
                    val identified = newSessionThreads.filterValues { nativePoolName(it.getString("name"), callerName) }
                    val start = SystemClock.elapsedRealtime()
                    val pcm = engine.synthesizePhonemes(phones, voice, 1f)
                    assertTrue("Real native inference produces finite nonsilent audio", pcm.isNotEmpty() && pcm.all { it.isFinite() } && pcm.any { kotlin.math.abs(it) > .001 })
                    val during = tasks()
                    val consumedPools = identified.keys.mapNotNull { tid ->
                        val active = during[tid] ?: return@mapNotNull null
                        active.takeIf { it.getLong("cpuTicks") > requireNotNull(constructed[tid]).getLong("cpuTicks") }
                    }
                    record.put("usesXnnpack", engine.usesXnnpack).put("usesNnapi", engine.usesNnapi)
                        .put("samples", pcm.size).put("inferenceMillis", SystemClock.elapsedRealtime() - start)
                        .put("tasksAfterSessionConstruction", JSONArray(constructed.values.toList()))
                        .put("tasksDuringSession", JSONArray(during.values.toList()))
                        .put("newSessionThreads", JSONArray(newSessionThreads.values.toList()))
                        .put("identifiedNativePoolThreads", JSONArray(identified.values.toList()))
                        .put("identifiedNativeComputeThreads", JSONArray(consumedPools))
                        .put("identifiedNativeComputeThreadCount", consumedPools.size)
                        .put("parallelUseNote", "The limit permits at most two CPUs; the graph/provider can perform its actual compute on the caller alone")
                        .put("nativePoolIdentification", "New onnx/xnn/ort/pthreadpool TIDs or native TIDs inheriting the caller name; unrelated ART/GC/JIT threads remain unattributed")
                    assertTrue("The bounded caller performs real native synthesis work",
                        requireNotNull(during[Process.myTid()]).getLong("cpuTicks") >
                            requireNotNull(constructed[Process.myTid()]).getLong("cpuTicks"))
                    // An upper bound never requires two busy workers: some graph operators
                    // run on ORT's single caller while an XNNPACK pool remains inactive.
                    identified.values.forEach { worker ->
                        val mask = worker.getJSONArray("allowedCpus")
                        assertEquals("Every identified native pool worker inherits the selected pair even when inactive: ${worker.getInt("tid")}/${worker.getString("name")}",
                            selected, (0 until mask.length()).map { mask.getInt(it) })
                    }
                    assertEquals("The inference caller stays inside the real kernel cap", selected, procMask(Process.myTid()))
                    assertEquals("Inference does not change the Android main-thread mask", main, mainMask())
                }
            }
        } finally {
            assertEquals("The original caller CPU set is restored after closing native inference", original, NarrationCpuBudget.allowedCpus())
            assertEquals(main, mainMask())
            assertEquals("Installed model bytes are not rewritten", modelSize, model.length())
            assertEquals("Inference does not touch the installed model", modelModified, model.lastModified())
            record.put("restoredAllowed", JSONArray(NarrationCpuBudget.allowedCpus()))
                .put("tasksAfterSessionClosed", JSONArray(tasks().values.toList()))
            evidence("real-ort-pool-mask.json", record)
        }
    }

    private fun mainMask(): List<Int> {
        val result = AtomicReference<List<Int>>()
        instrumentation.runOnMainSync { result.set(NarrationCpuBudget.allowedCpus()) }
        return requireNotNull(result.get())
    }
    private fun procMask(tid: Int): List<Int> {
        val value = File("/proc/self/task/$tid/status").readLines()
            .first { it.startsWith("Cpus_allowed_list:") }.substringAfter(':').trim()
        return value.split(',').flatMap { item ->
            val bounds = item.trim().split('-').map(String::toInt)
            if (bounds.size == 1) listOf(bounds[0]) else (bounds[0]..bounds[1]).toList()
        }.distinct().sorted()
    }
    private fun setCallingThreadMask(cpus: List<Int>) {
        // The test restricts only its own thread. This private JNI method always uses pid zero.
        NarrationCpuBudget::class.java.getDeclaredMethod("nativeSetAllowedCpus", IntArray::class.java)
            .apply { isAccessible = true }.invoke(NarrationCpuBudget, cpus.toIntArray())
    }
    private fun restoreCallingThreadMask(cpus: List<Int>, recoverCurrentCpuset: Boolean): List<Int> =
        (NarrationCpuBudget::class.java.getDeclaredMethod("nativeRestoreAllowedCpus", IntArray::class.java,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(NarrationCpuBudget, cpus.toIntArray(), recoverCurrentCpuset) as IntArray).toList()
    private fun tasks(): Map<Int, JSONObject> = File("/proc/self/task").listFiles().orEmpty().mapNotNull { directory ->
        runCatching {
            val tid = directory.name.toInt()
            val stat = File(directory, "stat").readText()
            val columns = stat.substring(stat.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))
            tid to JSONObject().put("tid", tid).put("name", File(directory, "comm").readText().trim())
                .put("allowedCpus", JSONArray(procMask(tid))).put("cpuTicks", columns[11].toLong() + columns[12].toLong())
        }.getOrNull()
    }.toMap()
    private fun nativePoolName(name: String, inherited: String): Boolean {
        val lower = name.lowercase(java.util.Locale.ROOT)
        if (listOf("jit", "gc", "heap", "finalizer", "reference", "signal", "perfetto", "profile saver", "renderthread")
                .any { it in lower }) return false
        return name == inherited || "onnx" in lower || "xnn" in lower || "pthreadpool" in lower ||
            Regex("(^|[-_: ])ort($|[-_: ])").containsMatchIn(lower)
    }
    private fun evidence(name: String, data: JSONObject) {
        File(requireNotNull(context.getExternalFilesDir(null)), "cpu-planning-qa").apply { mkdirs() }
            .resolve(name).writeText(data.toString(2))
    }
    private class ExpectedScopeFailure : RuntimeException()

    companion object { private const val QA_PACKAGE = "com.learngraphicsprogramming.app.curricularqa" }
}
