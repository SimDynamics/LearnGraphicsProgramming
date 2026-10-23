package com.learngraphicsprogramming.app.narration

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import com.learngraphicsprogramming.app.narration.phonemes.SpanishFluidG2P
import com.learngraphicsprogramming.app.narration.phonemes.EnglishMisakiG2P
import com.learngraphicsprogramming.app.narration.phonemes.FrenchFluidG2P
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional metadata work only. It neither owns a player nor generates, replaces or deletes audio.
 * Unsupported representations retain the controller's conservative visual presentation.
 */
internal class KokoroWordAlignment(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val thread = HandlerThread("NarrationWordAlignment", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val metadataDirectory = File(app.filesDir, "narration-word-alignment")
    private val derivedDirectory = File(app.cacheDir, "runtime-word-alignment")
    @Volatile private var closed = false
    @Volatile private var durationModel: KokoroDurationModel? = null
    private var durationCpuMask: List<Int>? = null
    private var modelStore: KokoroModelStore? = null
    private var lexicon: Map<String, String>? = null
    @Volatile private var english: EnglishMisakiG2P? = null
    private var french: FrenchFluidG2P? = null

    fun prepare(
        text: String,
        language: NarrationLanguage,
        voice: String,
        audio: File,
        isCancelled: () -> Boolean,
        onReady: (NarrationWordAlignment?) -> Unit
    ) {
        if (closed) return
        worker.post {
            if (closed || isCancelled()) return@post
            val alignment = runCatching {
                // Optional metadata and automatic speech share the same two permitted CPUs.
                // A session inherits its pool's mask at construction, not at run().
                NarrationCpuBudget.capBackground().use {
                    val mask = NarrationCpuBudget.allowedCpus()
                    if (durationCpuMask != mask) {
                        durationModel?.close()
                        durationModel = null
                        // Auxiliary G2P pools inherit their creating thread's affinity too.
                        // Reuse a frontend only while it belongs to this effective CPU pair.
                        english?.close()
                        english = null
                        (french as? AutoCloseable)?.close()
                        french = null
                        durationCpuMask = mask
                    }
                    prepareInternal(text, language, voice, audio, isCancelled)
                }
            }
                .onFailure { if (it !is InterruptedException && !closed) Log.w("NarrationWordAlignment", "Optional alignment unavailable", it) }
                .getOrNull()
            main.post { if (!closed && !isCancelled()) runCatching { onReady(alignment) } }
        }
    }

    private fun prepareInternal(
        text: String,
        language: NarrationLanguage,
        voice: String,
        audio: File,
        isCancelled: () -> Boolean
    ): NarrationWordAlignment? {
        if (text.length !in 1..32_768 ||
            KokoroVoiceCatalog.find(voice) == null) return null
        checkActive(isCancelled)
        // Both formats have an exact audible origin. For the shipped mono Opus pack,
        // subtract the container's actual pre-skip and end trimming, never a rounded duration.
        val sampleCount = audioSampleCount(audio, isCancelled) ?: return null
        val audioHash = digest(audio, isCancelled)
        val sourceHash = digest(text.toByteArray(Charsets.UTF_8))
        val key = digest("$VERSION\n${KokoroModelManifest.revision}\n$voice\n$audioHash\n$sourceHash".toByteArray(Charsets.UTF_8))
        val sidecar = File(metadataDirectory, "$key.json")
        read(sidecar, text, sourceHash, voice, audioHash, sampleCount, language)?.let { return it }
        // Ordinary-prose timings from the previous format remain exact. Validate their
        // offsets against today's atoms and the same immutable audio before reusing them.
        val compatibleVersions = if (language == NarrationLanguage.SPANISH) listOf(3, 2, 1)
            else if (NarrationSpokenWords.technicalAtoms(text, language) == NarrationSpokenWords.lexemes(text)) listOf(3)
            else emptyList()
        for (version in compatibleVersions) {
            val compatibleKey = digest("$version\n${KokoroModelManifest.revision}\n$voice\n$audioHash\n$sourceHash".toByteArray(Charsets.UTF_8))
            read(File(metadataDirectory, "$compatibleKey.json"), text, sourceHash, voice, audioHash, sampleCount, language)?.let { return it }
        }
        checkActive(isCancelled)
        val store = modelStore ?: KokoroModelStore(app).also { modelStore = it }
        val root = store.directory()
        val phonemes: String
        val sources: List<NarrationSourcePhones>
        when (language) {
            NarrationLanguage.SPANISH -> {
                val dictionary = lexicon ?: run {
                    val artifact = KokoroModelManifest.resources(NarrationLanguage.SPANISH).single()
                    val file = File(root, artifact.fileName)
                    if (!KokoroModelManifest.verify(file, artifact)) return null
                    checkActive(isCancelled)
                    SpanishFluidG2P.loadLexicon(file).also { lexicon = it }
                }
                val trace = MixedSpanishG2P.traceWordsPrepared(text, dictionary) ?: return null
                if (trace.phonemes != MixedSpanishG2P.phonemizePrepared(text, dictionary)) return null
                val lexical = NarrationSpokenWords.lexemes(text)
                if (lexical.size != trace.words.size || lexical.indices.any { lexical[it].canonical != trace.words[it].word }) return null
                phonemes = trace.phonemes
                sources = lexical.zip(trace.words).map { (source, phone) ->
                    NarrationSourcePhones(source.start, source.endExclusive, phone.start, phone.endExclusive)
                }
            }
            NarrationLanguage.FRENCH -> {
                val frontend = french ?: FrenchFluidG2P(root, File(root, "tokenizer.json")).also { french = it }
                val trace = MixedFrenchG2P.traceWordsPrepared(text, frontend) ?: return null
                phonemes = trace.phonemes
                sources = trace.words.map { NarrationSourcePhones(it.sourceStart, it.sourceEndExclusive, it.start, it.endExclusive) }
            }
            NarrationLanguage.AMERICAN_ENGLISH, NarrationLanguage.BRITISH_ENGLISH -> {
                val frontend = english ?: EnglishMisakiG2P(root).also { english = it }
                val trace = frontend.traceWords(text, language == NarrationLanguage.BRITISH_ENGLISH) {
                    !closed && !isCancelled()
                } ?: return null
                phonemes = trace.phonemes
                sources = trace.words.map { NarrationSourcePhones(it.sourceStart, it.sourceEndExclusive, it.start, it.endExclusive) }
            }
        }
        val chunks = KokoroPhonemeChunks.split(phonemes)
        checkActive(isCancelled)
        val model = durationModel ?: KokoroDurationModel(root, derivedDirectory).also { durationModel = it }
        val durations = chunks.map { chunk ->
            checkActive(isCancelled)
            model.durations(chunk, voice) { closed || isCancelled() }
        }
        checkActive(isCancelled)
        val alignment = NarrationWordTimingBuilder.buildSourceChunked(text, audioHash, sampleCount, phonemes, sources, chunks, durations, language) ?: return null
        // Bind the sidecar to bytes that are still the file actually supplied for playback.
        if (audioSampleCount(audio, isCancelled) != sampleCount || digest(audio, isCancelled) != audioHash) return null
        checkActive(isCancelled)
        runCatching { write(sidecar, sourceHash, voice, alignment) }
        return alignment
    }

    private fun read(
        file: File, text: String, sourceHash: String, voice: String, audioHash: String, sampleCount: Long,
        language: NarrationLanguage
    ): NarrationWordAlignment? = runCatching {
        if (!file.isFile || file.length() !in 1L..524_288L) return@runCatching null
        val data = JSONObject(file.readText(Charsets.UTF_8))
        if (data.getInt("version") !in setOf(1, 2, 3, VERSION) || data.getString("model") != KokoroModelManifest.revision ||
            data.getString("sourceSha256") != sourceHash || data.getString("voice") != voice ||
            data.getString("audioSha256") != audioHash || data.getLong("sampleCount") != sampleCount) return@runCatching null
        val rows = data.getJSONArray("words")
        if (rows.length() !in 1..4096) return@runCatching null
        val words = (0 until rows.length()).map { index ->
            val row = rows.getJSONArray(index)
            if (row.length() != 4) return@runCatching null
            NarrationWordTiming(row.getInt(0), row.getInt(1), row.getLong(2), row.getLong(3))
        }
        if (!NarrationWordTimingBuilder.valid(text, sampleCount, words, language)) return@runCatching null
        NarrationWordAlignment(audioHash, sampleCount, words)
    }.getOrNull()

    private fun write(file: File, sourceHash: String, voice: String, alignment: NarrationWordAlignment) {
        if (closed || (!metadataDirectory.isDirectory && !metadataDirectory.mkdirs())) return
        val rows = JSONArray()
        alignment.words.forEach { word ->
            rows.put(JSONArray().put(word.start).put(word.endExclusive).put(word.startSample).put(word.endSample))
        }
        val data = JSONObject().put("version", VERSION).put("model", KokoroModelManifest.revision)
            .put("sourceSha256", sourceHash).put("voice", voice).put("audioSha256", alignment.audioSha256)
            .put("sampleCount", alignment.sampleCount).put("words", rows)
        val temporary = File(metadataDirectory, ".${file.name}-${System.nanoTime()}.tmp")
        try {
            temporary.writeText(data.toString(), Charsets.UTF_8)
            if (!closed) temporary.renameTo(file)
        } finally { temporary.delete() }
    }

    private fun audioSampleCount(file: File, isCancelled: () -> Boolean): Long? {
        if (file.extension.equals("ogg", ignoreCase = true)) {
            return KokoroOpusTimeline.read(file, isCancelled)?.presentationSamples24k
        }
        return pcmSampleCount(file)
    }

    private fun pcmSampleCount(file: File): Long? = runCatching {
        val length = file.length()
        if (!file.isFile || length !in 46L..(44L + NarrationWordTimingBuilder.MAX_SAMPLES * 2L)) return@runCatching null
        val header = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        fun label(offset: Int) = String(header, offset, 4, Charsets.US_ASCII)
        val dataBytes = buffer.getInt(40).toLong()
        if (label(0) != "RIFF" || label(8) != "WAVE" || label(12) != "fmt " || label(36) != "data" ||
            buffer.getInt(4).toLong() + 8L != length || buffer.getInt(16) != 16 ||
            buffer.getShort(20).toInt() != 1 || buffer.getShort(22).toInt() != 1 ||
            buffer.getInt(24) != 24_000 || buffer.getInt(28) != 48_000 ||
            buffer.getShort(32).toInt() != 2 || buffer.getShort(34).toInt() != 16 ||
            dataBytes <= 0L || dataBytes % 2L != 0L || dataBytes + 44L != length) return@runCatching null
        dataBytes / 2L
    }.getOrNull()

    private fun digest(file: File, isCancelled: () -> Boolean): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                checkActive(isCancelled)
                val count = input.read(bytes)
                if (count < 0) break
                hash.update(bytes, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun checkActive(isCancelled: () -> Boolean) {
        if (closed || isCancelled()) throw InterruptedException("Word alignment cancelled")
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        worker.removeCallbacksAndMessages(null)
        durationModel?.cancelCurrentRun()
        english?.cancelCurrentRun()
        worker.post {
            runCatching { durationModel?.close() }
            durationModel = null
            modelStore?.close()
            modelStore = null
            lexicon = null
            english?.close()
            english = null
            french = null
        }
        thread.quitSafely()
    }

    companion object { private const val VERSION = 4 }
}
