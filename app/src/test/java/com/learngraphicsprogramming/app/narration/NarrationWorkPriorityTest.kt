package com.learngraphicsprogramming.app.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationWorkPriorityTest {
    @Test fun compilationOrGraphicsPreemptsBackgroundUntilLeaseCloses() {
        var interruptions = 0
        val listener = NarrationWorkPriority.onPreempt { interruptions++ }
        val compiler = NarrationWorkPriority.beginInteractive()
        val graphics = NarrationWorkPriority.beginInteractive()
        try {
            assertTrue(NarrationWorkPriority.isBusy)
            assertEquals(2, interruptions)
            compiler.close()
            assertTrue(NarrationWorkPriority.isBusy)
        } finally {
            graphics.close()
            listener.close()
        }
        assertFalse(NarrationWorkPriority.isBusy)
    }

    @Test fun aWorkerRegisteredDuringCurrentPagePreparationIsInterruptedImmediately() {
        val audio = NarrationWorkPriority.beginInteractive()
        var interrupted = false
        val listener = NarrationWorkPriority.onPreempt { interrupted = true }
        try {
            assertTrue(interrupted)
        } finally {
            listener.close()
            audio.close()
        }
        assertFalse(NarrationWorkPriority.isBusy)
    }

    @Test fun cacheMaintenanceRefusesToStartDuringReaderPlayback() {
        val reader = NarrationWorkPriority.beginPlayback()
        try {
            org.junit.Assert.assertNull(NarrationWorkPriority.beginMaintenanceIfIdle())
        } finally {
            reader.close()
        }
        assertFalse(NarrationWorkPriority.isBusy)
    }

    @Test fun explicitMaintenancePreemptsBackgroundAndReleasesExactlyOneLease() {
        var interrupted = 0
        val callback = NarrationWorkPriority.onPreempt { interrupted++ }
        val maintenance = NarrationWorkPriority.beginMaintenanceIfIdle()
        try {
            org.junit.Assert.assertNotNull(maintenance)
            assertTrue(NarrationWorkPriority.isBusy)
            assertTrue(NarrationWorkPriority.isMaintenanceExclusive)
            assertEquals(1, interrupted)
            org.junit.Assert.assertNull(NarrationWorkPriority.beginMaintenanceIfIdle())
            // A reader starting at this point makes further deletion ineligible.
            val reader = NarrationWorkPriority.beginPlayback()
            try {
                assertFalse(NarrationWorkPriority.isMaintenanceExclusive)
            } finally {
                reader.close()
            }
            assertFalse("A preempted deletion must never resume under its old lease",
                NarrationWorkPriority.isMaintenanceExclusive)
        } finally {
            maintenance?.close()
            maintenance?.close()
            callback.close()
        }
        assertFalse(NarrationWorkPriority.isBusy)
    }

    @Test fun maintenanceFilesRequireAnUninterruptedExclusiveLease() {
        var filesTouched = 0
        assertFalse(NarrationWorkPriority.runMaintenanceStep { filesTouched++ })
        val maintenance = requireNotNull(NarrationWorkPriority.beginMaintenanceIfIdle())
        try {
            assertTrue(NarrationWorkPriority.runMaintenanceStep { filesTouched++ })
            val reader = NarrationWorkPriority.beginPlayback()
            try {
                assertFalse(NarrationWorkPriority.runMaintenanceStep { filesTouched++ })
            } finally {
                reader.close()
            }
            assertFalse(NarrationWorkPriority.runMaintenanceStep { filesTouched++ })
            assertEquals(1, filesTouched)
        } finally {
            maintenance.close()
        }
        val newMaintenance = requireNotNull(NarrationWorkPriority.beginMaintenanceIfIdle())
        try {
            assertTrue(NarrationWorkPriority.runMaintenanceStep { filesTouched++ })
            assertEquals(2, filesTouched)
        } finally {
            newMaintenance.close()
        }
    }

    @Test fun closingOneLeaseTwiceDoesNotReleaseAnotherInteractiveOperation() {
        val audio = NarrationWorkPriority.beginInteractive()
        val compiler = NarrationWorkPriority.beginInteractive()
        try {
            audio.close()
            audio.close()
            assertTrue(NarrationWorkPriority.isBusy)
        } finally {
            compiler.close()
            audio.close()
        }
        assertFalse(NarrationWorkPriority.isBusy)
    }

    @Test fun cachedPlaybackAllowsLimitedGenerationButStillProtectsFilesAndHeavyWork() {
        var interruptions = 0
        val listener = NarrationWorkPriority.onPreempt { interruptions++ }
        val playback = NarrationWorkPriority.beginPlayback()
        val secondPlayback = NarrationWorkPriority.beginPlayback()
        try {
            assertFalse(NarrationWorkPriority.isBusy)
            assertEquals(0, interruptions)
            org.junit.Assert.assertNull(NarrationWorkPriority.beginMaintenanceIfIdle())
            val compiler = NarrationWorkPriority.beginInteractive()
            try {
                assertTrue(NarrationWorkPriority.isBusy)
                assertEquals(1, interruptions)
            } finally { compiler.close() }
            assertFalse(NarrationWorkPriority.isBusy)
            playback.close(); playback.close()
            org.junit.Assert.assertNull(NarrationWorkPriority.beginMaintenanceIfIdle())
        } finally {
            secondPlayback.close(); playback.close(); listener.close()
        }
        val maintenance = NarrationWorkPriority.beginMaintenanceIfIdle()
        org.junit.Assert.assertNotNull(maintenance)
        maintenance?.close()
    }
}
