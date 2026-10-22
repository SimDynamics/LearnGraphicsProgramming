package com.learngraphicsprogramming.app.narration

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real shipped recordings: no regenerated audio, approximate duration or alternate player. */
@RunWith(AndroidJUnit4::class)
class OpusWordTimingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val assets: List<String> get() {
        val requested = InstrumentationRegistry.getArguments().getString("opusAssets")
            ?: return listOf(
                "09fcddf2081b4fd20060bd189cf9ef7d54ba6f68ba75962d54fa5b6d9f64d6cd.ogg",
                "0a65bcfe2a4051acc78a463dab7e557aeda94d8a84c7e76a3ef9ca6a8fce1b41.ogg")
        val names = requested.split(',')
        require(names.isNotEmpty() && names.all { it.matches(Regex("[0-9a-f]{64}\\.ogg")) }) {
            "opusAssets must contain only comma-separated SHA-256 Opus filenames"
        }
        require(names.distinct().size == names.size) { "opusAssets must not contain duplicate recordings" }
        return names
    }

    @Test fun realOpusDecoderAndMediaPlayerShareThePostSkipPresentationOrigin() {
        val sandbox = File(app.cacheDir, "opus-word-clock-${System.nanoTime()}").apply { check(mkdirs()) }
        val evidence = JSONArray()
        try {
            for (name in assets) {
                val file = File(sandbox, name)
                app.assets.open("narration/prerendered/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                val timeline = requireNotNull(KokoroOpusTimeline.read(file))
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    assertEquals(1, extractor.trackCount)
                    val format = extractor.getTrackFormat(0)
                    assertEquals("audio/opus", format.getString(MediaFormat.KEY_MIME))
                    assertEquals(48_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    val expectedUs = timeline.presentationSamples24k * 1_000_000L / 24_000L
                    assertEquals("Android's Ogg extractor subtracts the actual pre-skip", expectedUs,
                        format.getLong(MediaFormat.KEY_DURATION))
                    val delayNs = requireNotNull(format.getByteBuffer("csd-1")).duplicate().order(ByteOrder.nativeOrder()).long
                    assertEquals(timeline.preSkip48k * 1_000_000_000L / 48_000L, delayNs)
                    extractor.selectTrack(0)
                    assertEquals("Packet zero is stamped at the audible origin", 0L, extractor.sampleTime)
                    val decoded = decode(extractor, format)
                    assertEquals("The native decoder discards the exact encoder delay once",
                        timeline.decodedSamples48k - timeline.preSkip48k, decoded.getLong("samples48k"))
                    assertTrue("Real packaged speech is not silence", decoded.getInt("peak16") > 32)
                    val player = playAndSeek(file, expectedUs / 1000L)
                    evidence.put(JSONObject().put("asset", name).put("sha256", sha256(file))
                        .put("preSkip48k", timeline.preSkip48k).put("finalGranule48k", timeline.finalGranule48k)
                        .put("samples24k", timeline.presentationSamples24k)
                        // Java MediaCodec exposes packet padding, whereas MediaPlayer uses the
                        // extractor's exact final duration. Neither padding nor pre-skip is
                        // added to a word timing derived from the original 24 kHz waveform.
                        .put("rawDecoderEndPadding48k", timeline.decodedSamples48k - timeline.finalGranule48k)
                        .put("decoder", decoded).put("player", player))
                } finally { extractor.release() }
            }
            saveEvidence("opus-presentation-clock.json", evidence)
        } finally { sandbox.deleteRecursively() }
    }

    @Test fun exactModelWordDurationsReusePackagedOpusWithoutCreatingAnotherRecording() {
        val bundled = app.assets.list("narration/prerendered").orEmpty().filter { it.endsWith(".ogg") }.toSet()
        val candidate = firstUnitSentences().entries.first { (hash, text) ->
            "$hash.ogg" in bundled && NarrationSpokenWords.lexemes(text).size >= 7
        }
        val sandbox = File(app.cacheDir, "opus-model-words-${System.nanoTime()}").apply { check(mkdirs()) }
        val file = File(sandbox, "${candidate.key}.ogg")
        app.assets.open("narration/prerendered/${file.name}").use { input -> file.outputStream().use { input.copyTo(it) } }
        val original = file.readBytes()
        val modified = file.lastModified()
        val aligner = KokoroWordAlignment(app)
        try {
            val ready = CountDownLatch(1)
            var alignment: NarrationWordAlignment? = null
            aligner.prepare(candidate.value, NarrationLanguage.SPANISH, "ef_dora", file, { false }) {
                alignment = it
                ready.countDown()
            }
            assertTrue("Exact immutable Opus receives real Kokoro token-duration metadata", ready.await(180, TimeUnit.SECONDS))
            val actual = requireNotNull(alignment) { "The existing recording must match its original frontend and duration model" }
            assertEquals(sha256(file), actual.audioSha256)
            assertEquals(requireNotNull(KokoroOpusTimeline.read(file)).presentationSamples24k, actual.sampleCount)
            assertTrue(NarrationWordTimingBuilder.valid(candidate.value, actual.sampleCount, actual.words))
            assertTrue("Different words have separate observed boundaries", actual.words.map { it.startSample }.distinct().size >= 7)
            assertArrayEquals("Alignment never changes, replaces or regenerates the recording", original, file.readBytes())
            assertEquals(modified, file.lastModified())
            assertEquals("No per-word recordings or WAV conversion", listOf(file.name), sandbox.list().orEmpty().toList())
            val words = JSONArray()
            actual.words.forEach { word -> words.put(JSONArray().put(word.start).put(word.endExclusive)
                .put(word.startSample).put(word.endSample)) }
            saveEvidence("opus-model-words.json", JSONObject().put("source", candidate.value).put("asset", file.name)
                .put("audioSha256", actual.audioSha256).put("sampleCount", actual.sampleCount).put("words", words))
        } finally {
            aligner.close()
            sandbox.deleteRecursively()
        }
    }

    private fun decode(extractor: MediaExtractor, format: MediaFormat): JSONObject {
        val codec = MediaCodec.createDecoderByType("audio/opus")
        var started = false
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            var inputEnded = false
            var outputEnded = false
            var samples = 0L
            var peak = 0
            var outputs = 0
            var lastInputTime = 0L
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, lastInputTime + 20_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            lastInputTime = extractor.sampleTime
                            codec.queueInputBuffer(index, 0, size, lastInputTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 5_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    assertEquals(48_000, codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    assertEquals(1, codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    if (codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        assertEquals(AudioFormat.ENCODING_PCM_16BIT, codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING))
                    }
                } else if (index >= 0) {
                    if (info.size > 0) {
                        assertEquals("Native PCM timestamps use the post-skip sample clock",
                            samples * 1_000_000L / 48_000L, info.presentationTimeUs)
                        assertEquals(0, info.size % 2)
                        val buffer = requireNotNull(codec.getOutputBuffer(index)).duplicate().order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        while (buffer.remaining() >= 2) peak = maxOf(peak, abs(buffer.short.toInt()))
                        samples += info.size / 2
                        outputs++
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("Real Android Opus decoding reaches EOS", outputEnded)
            return JSONObject().put("name", codec.name).put("samples48k", samples).put("peak16", peak).put("buffers", outputs)
        } finally {
            if (started) codec.stop()
            codec.release()
        }
    }

    private fun playAndSeek(file: File, expectedMs: Long): JSONObject {
        lateinit var player: MediaPlayer
        var created = false
        val complete = CountDownLatch(1)
        val seeked = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                player = MediaPlayer()
                created = true
                player.setVolume(0f, 0f)
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener { complete.countDown() }
                player.setOnSeekCompleteListener { seeked.countDown() }
                player.prepare()
                assertEquals("The actual production player exposes the same trimmed duration", expectedMs, player.duration.toLong())
                assertEquals("MediaPlayer starts at the audible presentation origin", 0, player.currentPosition)
                player.start()
            }
            var played = 0
            var isPlaying = false
            var duration = 0
            val startedAt = SystemClock.elapsedRealtime()
            val advancementDeadline = startedAt + 10_000L
            do {
                instrumentation.runOnMainSync {
                    played = player.currentPosition
                    isPlaying = player.isPlaying
                    duration = player.duration
                }
                if (isPlaying && played in 100 until expectedMs.toInt()) break
                Thread.sleep(25)
            } while (SystemClock.elapsedRealtime() < advancementDeadline)
            assertTrue("Real playback advances the presentation clock: position=$played, isPlaying=$isPlaying, " +
                "duration=$duration, expectedDuration=$expectedMs, waitedMs=${SystemClock.elapsedRealtime() - startedAt}",
                isPlaying && played in 100 until expectedMs.toInt())
            instrumentation.runOnMainSync {
                player.timestamp?.let { clock ->
                    val clockNowUs = clock.anchorMediaTimeUs + ((System.nanoTime() - clock.anchorSystemNanoTime) *
                        clock.mediaClockRate / 1_000.0).toLong()
                    assertTrue("Player position and native audio timestamp share the presentation origin",
                        abs(clockNowUs / 1000L - player.currentPosition) < 100L)
                }
                player.seekTo(1_000L, MediaPlayer.SEEK_CLOSEST)
            }
            assertTrue("The normal player completes a real seek", seeked.await(10, TimeUnit.SECONDS))
            var resumed = 0
            instrumentation.runOnMainSync {
                resumed = player.currentPosition
                assertTrue("A seek uses milliseconds after pre-skip rather than encoded packet origin", abs(resumed - 1_000) < 100)
            }
            assertTrue("Actual native playback reaches the exact trimmed end", complete.await(expectedMs / 1000L + 10L, TimeUnit.SECONDS))
            return JSONObject().put("durationMs", expectedMs).put("playedPositionMs", played).put("seekPositionMs", resumed)
        } finally {
            instrumentation.runOnMainSync { if (created) player.release() }
        }
    }

    private fun firstUnitSentences(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (name in listOf("what_is_programming", "first_contact_cpp", "program_structure")) {
            val path = "content/es/programming/cpp_fundamentals/intro_programming/lesson_$name.json"
            val lesson = JSONObject(app.assets.open(path).bufferedReader().use { it.readText() })
            for (page in 0 until lesson.getJSONArray("pages").length()) {
                val texts = LessonNarrationText.page(lesson, page, NarrationLanguage.SPANISH) { NarrationUiInstructions.forBlock(app, it) }
                NarrationSegments.sentences(texts).forEach { text ->
                    result[KokoroNarrationCache.key("es", "ef_dora", text)] = text
                }
            }
        }
        return result
    }

    private fun saveEvidence(name: String, data: Any) {
        val output = File(app.getExternalFilesDir(null), "word-reading-qa").apply { mkdirs() }
        File(output, name).writeText(data.toString())
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
