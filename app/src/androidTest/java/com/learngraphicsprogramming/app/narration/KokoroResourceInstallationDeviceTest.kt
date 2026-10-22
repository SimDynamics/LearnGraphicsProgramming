package com.learngraphicsprogramming.app.narration

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.learngraphicsprogramming.app.R
import com.learngraphicsprogramming.app.UiLanguageSettings
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** One real small voice download/import; never downloads or replaces the shared FP32 model. */
@RunWith(AndroidJUnit4::class)
class KokoroResourceInstallationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val voice = KokoroVoiceCatalog.find("em_santa")!!.artifact
    private val voiceArtifacts = listOf(voice)
    private val stores = mutableListOf<KokoroModelStore>()
    private lateinit var root: File
    private lateinit var work: File
    private lateinit var backup: File
    private var outsideProbe: File? = null
    private var preservedFiles: Map<String, Stamp> = emptyMap()
    private data class Stamp(val exists: Boolean, val bytes: Long, val modified: Long)
    private val preferences get() = context.getSharedPreferences("kokoro_model_downloads", Context.MODE_PRIVATE)
    private val downloadKey get() = "download:${KokoroModelManifest.revision}:${voice.fileName}"
    private val downloads get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    @Before fun backUpOnlyTheVerifiedSmallVoice() {
        val store = store()
        root = store.directory().canonicalFile
        val installed = File(root, voice.fileName)
        assertTrue("The approved real em_santa voice must already be installed", KokoroModelManifest.verify(installed, voice))
        assertEquals(KokoroModelStatus.INSTALLED, inspect(store).status)
        val cache = context.cacheDir.canonicalFile
        work = File(cache, "kokoro-installation-test-${UUID.randomUUID()}").canonicalFile
        require(work.parentFile == cache)
        assertTrue(work.mkdir())
        backup = File(work, voice.fileName)
        installed.copyTo(backup, overwrite = false)
        assertTrue("Recovery backup must have the real published digest", KokoroModelManifest.verify(backup, voice))
        preservedFiles = KokoroModelManifest.artifacts.filter { it != voice }.associate { artifact ->
            artifact.fileName to stamp(File(root, artifact.fileName))
        }
        val model = preservedFiles.getValue("model.onnx")
        assertTrue("The shared model is a prerequisite, never a download target", model.exists)
        assertEquals(KokoroModelManifest.sharedArtifacts.first().bytes, model.bytes)
    }

    @After fun cancelOnlyOurTransferAndRestoreTheVerifiedVoice() {
        var workersSettled = false
        try {
            // Let already submitted mutations finish before recovery starts on another store.
            stores.forEach { it.close() }
            workersSettled = stores.map { executor(it).awaitTermination(15, TimeUnit.SECONDS) }.all { it }
            if (::backup.isInitialized && backup.isFile) {
                val recovery = KokoroModelStore(context)
                try {
                    recovery.cancelDownload(voiceArtifacts)
                    try { inspect(recovery) } // Ordered after cancellation on this store's worker.
                    finally { restoreVerifiedBackupIfNeeded() }
                    assertEquals("Leave the original voice installed after success or failure", KokoroModelStatus.INSTALLED, inspect(recovery).status)
                    assertTrue(KokoroModelManifest.verify(File(root, voice.fileName), voice))
                    assertPreservedFiles()
                } finally { recovery.close(); executor(recovery).awaitTermination(15, TimeUnit.SECONDS) }
            }
            assertTrue("Store workers must finish before test cleanup", workersSettled)
        } finally {
            // Only these explicit test paths may be cleaned, including a failed traversal probe.
            outsideProbe?.let { probe ->
                require(::root.isInitialized && probe.canonicalFile.parentFile == root.parentFile)
                if (probe.exists()) probe.delete()
            }
            if (::work.isInitialized) {
                require(work.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                val recovered = !::backup.isInitialized || !backup.isFile ||
                    (::root.isInitialized && KokoroModelManifest.verify(File(root, voice.fileName), voice))
                // Retain the verified recovery copy if an unsettled worker could still mutate
                // this voice, or if restoration itself fails. Never discard the only backup.
                if (workersSettled && recovered) {
                    work.listFiles()?.forEach { file ->
                        require(file.canonicalFile.parentFile == work)
                        if (file.isFile) file.delete()
                    }
                    work.delete()
                }
            }
        }
    }

    private fun restoreVerifiedBackupIfNeeded() {
        assertTrue("Recovery backup must remain authentic: ${backup.absolutePath}", KokoroModelManifest.verify(backup, voice))
        val destination = File(root, voice.fileName).canonicalFile
        require(destination.parentFile == root)
        if (KokoroModelManifest.verify(destination, voice)) return
        val temporary = File(root, "${voice.fileName}.test-restore").canonicalFile
        require(temporary.parentFile == root)
        try {
            backup.copyTo(temporary, overwrite = true)
            assertTrue(KokoroModelManifest.verify(temporary, voice))
            if (destination.exists()) assertTrue(destination.delete())
            assertTrue(temporary.renameTo(destination))
        } finally { temporary.delete() }
        assertTrue(KokoroModelManifest.verify(destination, voice))
    }

    @Test fun explicitSmallDownloadSurvivesStoreRecreationAndVerifiesRealSha() {
        val initial = stores.first()
        initial.remove(voiceArtifacts)
        assertEquals(KokoroModelStatus.NOT_INSTALLED, inspect(initial).status)
        assertPreservedFiles()
        assertFalse(preferences.contains(downloadKey))
        assertTrue("Inspecting absent resources must not enqueue a download", voiceDownloadIds().isEmpty())
        assertEquals(KokoroModelStatus.NOT_INSTALLED, inspect(initial).status)
        try {
            initial.startDownload(false, voiceArtifacts)
            fail("Starting a network transfer requires explicit acceptance")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("aceptación"))
        }
        assertTrue("Rejected acceptance must not create a system transfer", voiceDownloadIds().isEmpty())

        initial.startDownload(true, voiceArtifacts)
        await("The real DownloadManager ID must be committed", 15_000) { preferences.contains(downloadKey) }
        val id = preferences.getLong(downloadKey, -1L)
        assertTrue(id >= 0L)
        downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            assertNotNull("The committed ID must refer to an actual system transfer", cursor)
            assertTrue(cursor!!.moveToFirst())
            assertEquals(voice.url, cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)))
        }
        assertFalse("Before recovery inspection the transferred file is not yet promoted", File(root, voice.fileName).exists())
        initial.close()
        assertTrue(executor(initial).awaitTermination(15, TimeUnit.SECONDS))
        val recovered = store()
        assertEquals("Recreation must retain the same system transfer", id, preferences.getLong(downloadKey, -1L))
        assertEquals(listOf(id), voiceDownloadIds())

        val deadline = SystemClock.uptimeMillis() + 90_000L
        var state = inspect(recovered)
        while (state.status != KokoroModelStatus.INSTALLED && SystemClock.uptimeMillis() < deadline) {
            assertEquals("The real transfer must remain recoverable: ${state.error}", KokoroModelStatus.DOWNLOADING, state.status)
            SystemClock.sleep(250)
            state = inspect(recovered)
        }
        assertEquals("The accepted small download must install within 90 seconds: ${state.error}", KokoroModelStatus.INSTALLED, state.status)
        assertEquals(100, state.percentage)
        assertEquals(voice.bytes, state.downloadedBytes)
        assertTrue("The downloaded voice must match the published SHA-256", KokoroModelManifest.verify(File(root, voice.fileName), voice))
        assertFalse("Successful promotion clears the persisted pending ID", preferences.contains(downloadKey))
        assertTrue("Successful promotion removes its completed system transfer", voiceDownloadIds().isEmpty())
        assertFalse(File(root, "${voice.fileName}.part").exists())
        assertPreservedFiles()
    }

    @Test fun realZipImportVerifiesVoiceAndRejectsCorruptionAndTraversal() {
        val current = stores.first()
        current.remove(voiceArtifacts)
        assertEquals(KokoroModelStatus.NOT_INSTALLED, inspect(current).status)
        val valid = zip("valid.zip", voice.fileName)
        assertNull("A legitimate pinned voice ZIP must import", importZip(current, valid))
        assertEquals(KokoroModelStatus.INSTALLED, inspect(current).status)
        assertTrue(KokoroModelManifest.verify(File(root, voice.fileName), voice))
        assertTrue("Local ZIP import must not create any download", voiceDownloadIds().isEmpty())
        assertPreservedFiles()

        val corrupt = zip("corrupt.zip", voice.fileName, corrupt = true)
        val corruptError = importZip(current, corrupt)
        assertNotNull("A same-size corrupt voice must fail integrity verification", corruptError)
        assertTrue(corruptError.orEmpty().contains("SHA-256"))
        assertTrue("Failed import must preserve the existing verified voice", KokoroModelManifest.verify(File(root, voice.fileName), voice))
        assertFalse("Temporary corrupt bytes must be removed", File(root, "${voice.fileName}.import-part").exists())

        val probe = File(root.parentFile, "kokoro-traversal-${UUID.randomUUID()}.bin").canonicalFile
        require(probe.parentFile == root.parentFile)
        outsideProbe = probe
        assertFalse(probe.exists())
        val traversal = zip("traversal.zip", "../${probe.name}")
        // Android 14+ may reject the path before the store sees the entry.
        var platformRejectedPath = false
        try {
            ZipInputStream(traversal.inputStream().buffered()).use { it.nextEntry }
        } catch (error: ZipException) {
            assertTrue("The platform rejection must be its traversal guard: $error",
                error.message.orEmpty().contains("Invalid zip entry path", ignoreCase = true))
            platformRejectedPath = true
        }
        val traversalError = importZip(current, traversal)
        assertNotNull("An undeclared traversal entry must be rejected", traversalError)
        val localized = UiLanguageSettings.localizedContext(context)
        val expectedError = if (platformRejectedPath)
            localized.getString(R.string.narration_resource_import_failed)
        else localized.getString(R.string.narration_resource_unknown_entry, "../${probe.name}")
        assertEquals("The actual guard must use the existing localized error contract", expectedError, traversalError)
        assertFalse("Import must not write outside the revision directory", probe.exists())
        assertTrue(KokoroModelManifest.verify(File(root, voice.fileName), voice))
        assertPreservedFiles()
    }

    @Test fun interruptedImportIsVisibleAndRemovableWithoutChangingOtherResources() {
        val current = stores.first()
        val partial = File(root, "${voice.fileName}.import-part").canonicalFile
        require(partial.parentFile == root)
        assertFalse(partial.exists())
        fun leaveInterruptedImport() {
            backup.inputStream().use { input ->
                partial.outputStream().use { output ->
                    val bytes = ByteArray(1_024)
                    val count = input.read(bytes)
                    assertEquals(1_024, count)
                    output.write(bytes, 0, count)
                }
            }
        }
        try {
            leaveInterruptedImport()
            val interrupted = inspect(current)
            assertEquals("A killed import must remain visible for removal/retry", KokoroModelStatus.ERROR, interrupted.status)
            assertTrue(interrupted.error.orEmpty().contains("importación") && interrupted.error.orEmpty().contains("interrumpida"))
            assertTrue("The interrupted import must preserve the existing good voice", KokoroModelManifest.verify(File(root, voice.fileName), voice))
            assertPreservedFiles()
            assertTrue(voiceDownloadIds().isEmpty())
            current.startDownload(true, voiceArtifacts)
            assertEquals("Retry must reuse a verified voice and clean the abandoned import", KokoroModelStatus.INSTALLED, inspect(current).status)
            assertFalse(partial.exists())
            assertTrue("Retry with authentic final bytes must not enqueue a redundant download", voiceDownloadIds().isEmpty())
            assertTrue(KokoroModelManifest.verify(File(root, voice.fileName), voice))
            assertPreservedFiles()

            leaveInterruptedImport()
            assertEquals(KokoroModelStatus.ERROR, inspect(current).status)
            current.remove(voiceArtifacts)
            assertEquals(KokoroModelStatus.NOT_INSTALLED, inspect(current).status)
            assertFalse("Removing these resources must remove the abandoned temporary file", partial.exists())
            assertPreservedFiles()
            assertNull(importZip(current, zip("recover-interrupted.zip", voice.fileName)))
            assertEquals(KokoroModelStatus.INSTALLED, inspect(current).status)
            assertTrue(KokoroModelManifest.verify(File(root, voice.fileName), voice))
        } finally { if (partial.exists()) partial.delete() }
    }

    private fun store() = KokoroModelStore(context).also { stores.add(it) }
    private fun stamp(file: File) = Stamp(file.isFile, file.length(), file.lastModified())
    private fun assertPreservedFiles() {
        preservedFiles.forEach { (name, original) ->
            assertEquals("Changing one voice must preserve $name", original, stamp(File(root, name)))
        }
    }
    private fun voiceDownloadIds(): List<Long> = downloads.query(DownloadManager.Query()).use { cursor ->
        val ids = mutableListOf<Long>()
        if (cursor != null) {
            val uri = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val id = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (cursor.moveToNext()) if (cursor.getString(uri) == voice.url) ids.add(cursor.getLong(id))
        }
        ids.sorted()
    }
    private fun inspect(store: KokoroModelStore): KokoroModelState {
        val done = CountDownLatch(1)
        val state = AtomicReference<KokoroModelState>()
        store.inspectArtifacts(voiceArtifacts) { state.set(it); done.countDown() }
        assertTrue("The small voice inspection must respond", done.await(15, TimeUnit.SECONDS))
        return state.get()
    }
    private fun importZip(store: KokoroModelStore, zip: File): String? {
        val done = CountDownLatch(1)
        val result = AtomicReference<String?>()
        store.importResources(Uri.fromFile(zip)) { result.set(it); done.countDown() }
        assertTrue("Actual ContentResolver ZIP import must respond", done.await(20, TimeUnit.SECONDS))
        return result.get()
    }
    private fun zip(name: String, entry: String, corrupt: Boolean = false): File {
        val file = File(work, name)
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            output.putNextEntry(ZipEntry(entry))
            backup.inputStream().buffered().use { input ->
                if (corrupt) output.write(input.read() xor 1)
                input.copyTo(output)
            }
            output.closeEntry()
        }
        return file
    }
    private fun await(message: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        fail(message)
    }
    private fun executor(store: KokoroModelStore) = store.javaClass.getDeclaredField("worker")
        .apply { isAccessible = true }.get(store) as ExecutorService
}
