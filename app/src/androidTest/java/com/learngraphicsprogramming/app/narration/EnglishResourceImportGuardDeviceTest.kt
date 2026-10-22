package com.learngraphicsprogramming.app.narration

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.learngraphicsprogramming.app.R
import com.learngraphicsprogramming.app.UiLanguageSettings
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests failed imports only: no transfers, replacements, model copies or backups. */
@RunWith(AndroidJUnit4::class)
class EnglishResourceImportGuardDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun requireIsolatedQaApplication() {
        assertEquals("com.learngraphicsprogramming.app.realdownloadsqa", context.packageName)
    }

    @Test fun everyEnglishNeuralArtifactIsRejectedBeforeSizeOrDigestChecking() {
        val store = KokoroModelStore(context)
        try {
            val root = store.directory()
            val before = stamps(root)
            val preferences = context.getSharedPreferences("kokoro_model_downloads", 0).all.toMap()
            for (artifact in EnglishAuxResources.artifacts) {
                val archive = archive(artifact.fileName)
                try {
                    assertEquals(UiLanguageSettings.localizedContext(context).getString(
                        R.string.narration_resource_unknown_entry, artifact.fileName), importResult(store, archive))
                    assertFalse(File(root, "${artifact.fileName}.import-part").exists())
                    assertEquals(before, stamps(root))
                    assertEquals(preferences, context.getSharedPreferences("kokoro_model_downloads", 0).all)
                } finally { assertTrue(archive.delete()) }
            }
        } finally { store.close() }
    }

    @Test fun traversalUsesTheLocalizedErrorOfTheGuardThatActuallyRejectedIt() {
        val store = KokoroModelStore(context)
        val root = store.directory()
        val probe = File(root.parentFile, "misaki-import-escape-${UUID.randomUUID()}")
        val name = "../${probe.name}"
        val archive = archive(name)
        try {
            assertFalse(probe.exists())
            val before = stamps(root)
            var platformRejectedPath = false
            try {
                ZipInputStream(archive.inputStream().buffered()).use { it.nextEntry }
            } catch (error: ZipException) {
                assertTrue(error.message.orEmpty().contains("Invalid zip entry path", ignoreCase = true))
                platformRejectedPath = true
            }
            val localized = UiLanguageSettings.localizedContext(context)
            val expected = if (platformRejectedPath) localized.getString(R.string.narration_resource_import_failed)
                else localized.getString(R.string.narration_resource_unknown_entry, name)
            assertEquals(expected, importResult(store, archive))
            assertFalse(probe.exists())
            assertEquals(before, stamps(root))
        } finally { store.close(); assertTrue(archive.delete()) }
    }

    private fun archive(name: String): File = File.createTempFile("english-import-guard-", ".zip", context.cacheDir).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
    }

    private fun importResult(store: KokoroModelStore, archive: File): String? {
        val result = AtomicReference<String?>()
        val complete = CountDownLatch(1)
        store.importResources(Uri.fromFile(archive)) { result.set(it); complete.countDown() }
        assertTrue("The real importer must return its rejection", complete.await(15, TimeUnit.SECONDS))
        return result.get()
    }

    private fun stamps(root: File): Map<String, Pair<Long, Long>> = root.listFiles().orEmpty()
        .filter { it.isFile }.associate { it.name to (it.length() to it.lastModified()) }
}
