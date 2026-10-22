package com.learngraphicsprogramming.app.narration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.learngraphicsprogramming.app.R
import com.learngraphicsprogramming.app.narration.phonemes.SpanishFluidG2P
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstUnitNarrationPackDeviceTest {
    @Test fun manifestsEveryAuthoredSentenceFromTheFirstThreeLessons() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                error("A fixed Spanish/Dora pack must not consult the student's preferences")
        }
        FirstUnitNarrationPack.writeManifest(context)
        val manifest = JSONObject(
            File(context.filesDir, FirstUnitNarrationPack.MANIFEST_FILE).readText()
        )
        assertEquals("cpp_fundamentals", manifest.getString("course"))
        assertEquals("intro_programming", manifest.getString("unit"))
        val entries = manifest.getJSONArray("entries")
        assertTrue("The three first lessons must contain spoken content", entries.length() > 0)
        val names = mutableSetOf<String>()
        val hashes = mutableSetOf<String>()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val lessonId = entry.getString("lesson")
            names += lessonId
            assertEquals("es", entry.getString("language"))
            assertEquals("ef_dora", entry.getString("voice"))
            val prefix = "programming/cpp_fundamentals/cpp_fundamentals__intro_programming/$lessonId/"
            val location = entry.getString("location")
            assertTrue("Manifest locations retain the historical pack identity", location.startsWith(prefix))
            val path = "content/es/programming/cpp_fundamentals/intro_programming/lesson_${lessonId.substringAfterLast("__")}.json"
            val pageCount = JSONObject(context.assets.open(path).bufferedReader().use { it.readText() })
                .getJSONArray("pages").length()
            assertTrue("Each manifest location identifies a real page in its owning lesson",
                (location.removePrefix(prefix).toIntOrNull() ?: 0) in 1..pageCount)
            val hash = entry.getString("hash")
            assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
            assertTrue("Duplicated sentence must only be packaged once", hashes.add(hash))
        }
        assertEquals(
            setOf(
                "cpp_fundamentals__intro_programming__what_is_programming",
                "cpp_fundamentals__intro_programming__first_contact_cpp",
                "cpp_fundamentals__intro_programming__program_structure"
            ),
            names
        )
        assertEquals("The manifest describes precisely the same Spanish/Dora recordings as the packaged unit",
            spanishSentences(context).keys, hashes)
    }

    /**
     * Verify the entire Spanish/Dora release pack independently of the user's
     * current UI/voice preferences. Only a private test sandbox is populated.
     */
    @Test fun packagedOpusResolvesFromFreshCacheWithoutGeneratingWav() {
        assumeTrue("Opus playback requires Android 10+", Build.VERSION.SDK_INT >= 29)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val bundled = app.assets.list("narration/prerendered").orEmpty()
            .filter { it.endsWith(".ogg") }.toSet()
        assertTrue("The current APK must include the verified first-unit Opus pack", bundled.isNotEmpty())
        val required = spanishSentences(app)
        assertTrue("Missing packaged first-unit sentences: ${required.keys.filter { "$it.ogg" !in bundled }}",
            required.keys.all { "$it.ogg" in bundled })

        val sandbox = File(app.cacheDir, "kokoro-pack-test-${System.nanoTime()}")
        assertTrue("Could not create isolated test directory", sandbox.mkdirs())
        val isolatedContext = object : ContextWrapper(app) {
            override fun getFilesDir(): File = sandbox
            override fun getApplicationContext(): Context = this
        }
        try {
            for ((hash, text) in required) {
                val audio = KokoroNarrationCache.lookup(isolatedContext, "es", "ef_dora", text)
                assertNotNull("Packaged Opus must be found without inference: $hash", audio)
                val file = requireNotNull(audio)
                assertEquals("ogg", file.extension)
                assertTrue(file.isFile)
                assertEquals(file, KokoroNarrationCache.lookup(isolatedContext, "es", "ef_dora", text))
                assertFalse("Packaged Opus must not trigger WAV synthesis",
                    File(file.parentFile, "$hash.wav").exists())
                val decoder = MediaMetadataRetriever()
                try {
                    decoder.setDataSource(file.absolutePath)
                    val duration = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    assertTrue("Android must decode nonempty packaged Opus: $hash",
                        (duration?.toLongOrNull() ?: 0) > 0)
                } finally { decoder.release() }
            }
        } finally {
            // Only our temporary isolated directory; never the user's actual cache.
            sandbox.deleteRecursively()
        }
    }

    /** Explicit, incremental recording tool; an ordinary complete-pack run does no inference. */
    @Test fun exportsOnlyMissingDoraRecordingsWhenExplicitlyRequested() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val bundled = app.assets.list("narration/prerendered").orEmpty().toSet()
        val missing = spanishSentences(app).filterKeys { "$it.ogg" !in bundled }
        args.getString("expectedMissing")?.let { expected ->
            assertEquals("The precise incremental work must match before any inference", expected.toInt(), missing.size)
        }
        fun report(line: String) = instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "$line\n")
        })
        if (missing.isEmpty()) {
            report("DORA_COUNTS|missing=0|reused=0|generated=0")
            return
        }
        assertEquals("Only explicit generation may create recordings", "generate", args.getString("doraPackAction"))
        assertTrue("Never generate recordings in the student's ordinary application",
            app.packageName.endsWith(".curricularqa") || app.packageName.endsWith(".realdownloadsqa"))
        val cache = File(app.filesDir, "kokoro-narration-cache")
        check(cache.isDirectory || cache.mkdirs())
        var reused = 0
        var generated = 0
        fun existing(hash: String): File? = listOf("wav", "ogg")
            .map { File(cache, "$hash.$it") }.firstOrNull(::validRecording)
        fun record(hash: String, text: String, file: File) {
            report("DORA_AUDIO|$hash|files/kokoro-narration-cache/${file.name}")
            report("DORA_TEXT|$hash|${ManualEnglishSpeech.strip(text).replace("\n", "\\n").replace("\r", "\\r")}")
        }
        val pending = missing.filter { (hash, text) ->
            val found = existing(hash)
            if (found != null) {
                reused++
                record(hash, text, found)
                false
            } else {
                check(!File(cache, "$hash.wav").exists()) { "An unverified existing recording must not be overwritten: $hash" }
                true
            }
        }
        if (pending.isNotEmpty()) {
            var store: KokoroModelStore? = null
            try {
                val root = args.getString("doraModelRoot")?.let(::File)
                    ?: KokoroModelStore(app).also { store = it }.directory()
                val voice = requireNotNull(KokoroVoiceCatalog.find("ef_dora"))
                val dictionary = KokoroModelManifest.resources(NarrationLanguage.SPANISH).single()
                for (artifact in KokoroModelManifest.sharedArtifacts + voice.artifact + dictionary) {
                    assertTrue("Resource must match pinned size and SHA-256 before inference: ${artifact.fileName}",
                        KokoroModelManifest.verify(File(root, artifact.fileName), artifact))
                }
                val lexicon = SpanishFluidG2P.loadLexicon(File(root, dictionary.fileName))
                KokoroEngineCoordinator.withEngine(this, root, { Thread.currentThread().isInterrupted }) { engine ->
                    for ((hash, text) in pending) {
                        val found = existing(hash)
                        if (found != null) {
                            reused++
                            record(hash, text, found)
                            continue
                        }
                        val target = File(cache, "$hash.wav")
                        check(!target.exists()) { "Never replace an existing recording: $hash" }
                        val temporary = File(cache, ".$hash-dora-pack-${System.nanoTime()}.wav")
                        try {
                            val phones = MixedSpanishG2P.phonemizePrepared(text, lexicon)
                            val chunks = KokoroPhonemeChunks.split(phones).map { part ->
                                engine.synthesizePhonemes(part, "ef_dora", 1f)
                            }
                            NarrationWavWriter.writeChunks(temporary, chunks)
                            check(validRecording(temporary)) { "Generated recording has an invalid PCM format: $hash" }
                            // link(2) atomically publishes complete bytes and refuses an existing destination.
                            try {
                                Os.link(temporary.absolutePath, target.absolutePath)
                                generated++
                            } catch (error: ErrnoException) {
                                if (error.errno != OsConstants.EEXIST || !validRecording(target)) throw error
                                reused++
                            }
                            record(hash, text, target)
                        } finally { check(!temporary.exists() || temporary.delete()) }
                    }
                }
            } finally {
                KokoroEngineCoordinator.releaseIfIdle()
                store?.close()
            }
        }
        assertEquals(missing.size, reused + generated)
        report("DORA_COUNTS|missing=${missing.size}|reused=$reused|generated=$generated")
    }

    private fun validRecording(file: File): Boolean = runCatching {
        if (!file.isFile) return@runCatching false
        if (file.extension == "ogg") return@runCatching KokoroOpusTimeline.read(file) != null
        if (file.extension != "wav" || file.length() !in 46L..(96L * 1024 * 1024)) return@runCatching false
        val header = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        val pcm = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        fun label(at: Int) = String(header, at, 4, Charsets.US_ASCII)
        label(0) == "RIFF" && label(8) == "WAVE" && label(12) == "fmt " && label(36) == "data" &&
            pcm.getInt(4).toLong() + 8 == file.length() && pcm.getInt(16) == 16 &&
            pcm.getShort(20).toInt() == 1 && pcm.getShort(22).toInt() == 1 &&
            pcm.getInt(24) == 24_000 && pcm.getInt(28) == 48_000 &&
            pcm.getShort(32).toInt() == 2 && pcm.getShort(34).toInt() == 16 &&
            pcm.getInt(40) > 0 && pcm.getInt(40) % 2 == 0 && pcm.getInt(40).toLong() + 44 == file.length()
    }.getOrDefault(false)

    private fun spanishSentences(app: Context): Map<String, String> {
        val spanish = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
        })
        val required = linkedMapOf<String, String>()
        for (name in listOf("what_is_programming", "first_contact_cpp", "program_structure")) {
            val path = "content/es/programming/cpp_fundamentals/intro_programming/lesson_$name.json"
            val lesson = JSONObject(app.assets.open(path).bufferedReader().use { it.readText() })
            for (page in 0 until lesson.getJSONArray("pages").length()) {
                val texts = LessonNarrationText.page(lesson, page, NarrationLanguage.SPANISH) { type ->
                    when (type) {
                        "order_code" -> spanish.getString(R.string.exercise_order_instruction)
                        "drag_fill_code" -> spanish.getString(R.string.exercise_fill_instruction)
                        else -> ""
                    }
                }
                NarrationSegments.sentences(texts).forEach { text ->
                    required[KokoroNarrationCache.key("es", "ef_dora", text)] = text
                }
            }
        }
        return required
    }

    @Test fun pagesSixAndSevenIncludeTheInstructionsActuallyDisplayedByTheUi() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val path = "content/es/programming/cpp_fundamentals/intro_programming/lesson_what_is_programming.json"
        val lesson = JSONObject(app.assets.open(path).bufferedReader().use { it.readText() })
        for ((page, type) in listOf(5 to "order_code", 6 to "drag_fill_code")) {
            val expected = NarrationUiInstructions.forBlock(app, type)
            val texts = LessonNarrationText.page(lesson, page, NarrationLanguage.SPANISH) {
                NarrationUiInstructions.forBlock(app, it)
            }
            assertTrue("Small visible instructions must be narrated on page ${page + 1}",
                NarrationSegments.sentences(texts).contains(expected))
            assertFalse("Hidden explanations and source code must remain private",
                texts.any { "#include" in it || "Primero incluimos la biblioteca" in it })
        }
    }

}
