package com.learngraphicsprogramming.app.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class NarrationCpuBudgetTest {
    @Test fun oneOrTwoAllowedProcessorsKeepTheirRealNoncontiguousIdentifiers() {
        assertEquals(listOf(7), NarrationCpuPolicy.select(listOf(7), emptyList()).selected)
        assertEquals(listOf(3, 9), NarrationCpuPolicy.select(listOf(9, 3), emptyList()).selected)
    }

    @Test fun fourAllowedProcessorsPreferTheFastestCapacitiesRatherThanLittleCores() {
        val allowed = listOf(0, 2, 5, 9)
        val result = NarrationCpuPolicy.select(allowed, listOf(
            NarrationCpuPerformance(0, 100, 1_000), NarrationCpuPerformance(2, 100, 1_100),
            NarrationCpuPerformance(5, 800, 2_400), NarrationCpuPerformance(9, 1_000, 2_800)))
        assertEquals(listOf(5, 9), result.selected)
        assertEquals("cpu_capacity", result.ranking)
    }

    @Test fun eightAllowedProcessorsCanUseKnownFrequenciesWhenCapacityIsUnavailable() {
        val allowed = listOf(1, 3, 5, 7, 9, 11, 13, 15)
        val result = NarrationCpuPolicy.select(allowed, allowed.mapIndexed { index, cpu ->
            NarrationCpuPerformance(cpu, maxFrequencyKhz = 1_000L + index * 100)
        })
        assertEquals(listOf(13, 15), result.selected)
        assertEquals("cpuinfo_max_freq", result.ranking)
    }

    @Test fun unknownOrPartialScoresUseAnExplicitStableFallbackWithoutInventingPerformance() {
        val result = NarrationCpuPolicy.select(listOf(9, 2, 6, 4, 2), listOf(
            NarrationCpuPerformance(2, capacity = 100)))
        assertEquals(listOf(2, 4, 6, 9), result.allowed)
        assertEquals(listOf(2, 4), result.selected)
        assertTrue(result.ranking.contains("performance_unknown"))
        assertFalse(result.ranking.contains("cpu_capacity"))
    }

    @Test fun policyRejectsEmptyOrInvalidMasks() {
        assertThrows(IllegalArgumentException::class.java) { NarrationCpuPolicy.select(emptyList(), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { NarrationCpuPolicy.select(listOf(-1, 3), emptyList()) }
    }

    @Test fun aScopedCapRestoresTheOriginalMaskEvenWhenGenerationThrows() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        val scopes = NarrationCpuScopes(fake, ::fastest)
        assertThrows(IllegalStateException::class.java) {
            scopes.capBackground().use {
                assertEquals(listOf(4, 6), fake.allowedCpus())
                error("an unrelated synthesis failure")
            }
        }
        assertEquals(listOf(0, 2, 4, 6), fake.allowedCpus())
    }

    @Test fun failedReadbackRejectsTheCapAndRestoresTheOriginalMask() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        fake.ignoreCap = true
        val scopes = NarrationCpuScopes(fake, ::fastest)
        assertThrows(NarrationCpuLimitException::class.java) { scopes.capBackground() }
        assertEquals(listOf(0, 2, 4, 6), fake.allowedCpus())
        assertEquals(listOf(0, 2, 4, 6), fake.sets.last())
    }

    @Test fun nativeSetFailureCannotLeaveTheWorkerWithAnUnrestoredPartialMask() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        fake.failFirstSetAfterMutation = true
        val scopes = NarrationCpuScopes(fake, ::fastest)
        assertThrows(NarrationCpuLimitException::class.java) { scopes.capBackground() }
        assertEquals(listOf(0, 2, 4, 6), fake.allowedCpus())
    }

    @Test fun overlappingWorkersShareOnePairAndRestoreTheirOwnOriginalMasks() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        fake.masks[2] = listOf(0, 2, 4, 6, 8)
        val scopes = NarrationCpuScopes(fake, ::fastest)
        val first = scopes.capBackground()
        assertEquals(listOf(4, 6), fake.allowedCpus())
        fake.tid = 2
        val second = scopes.capBackground()
        assertEquals(listOf(4, 6), fake.allowedCpus()) // CPU 8 cannot create a separate pair.
        second.close()
        assertEquals(listOf(0, 2, 4, 6, 8), fake.allowedCpus())
        fake.tid = 1
        first.close()
        first.close() // Idempotent; no extra restoration or lease decrement.
        assertEquals(listOf(0, 2, 4, 6), fake.allowedCpus())
    }

    @Test fun anOverlappingRestrictedWorkerCanUseOneCpuFromTheSharedPair() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        fake.masks[2] = listOf(0, 4)
        val scopes = NarrationCpuScopes(fake, ::fastest)
        val first = scopes.capBackground()
        fake.tid = 2
        scopes.capBackground().use { assertEquals(listOf(4), fake.allowedCpus()) }
        assertEquals(listOf(0, 4), fake.allowedCpus())
        fake.tid = 1
        first.close()
    }

    @Test fun disjointSimultaneousMasksFailInsteadOfSpreadingBackgroundWorkOverFourCores() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        fake.masks[2] = listOf(8, 10)
        val scopes = NarrationCpuScopes(fake, ::fastest)
        val first = scopes.capBackground()
        fake.tid = 2
        assertThrows(NarrationCpuPairBusyException::class.java) { scopes.capBackground() }
        assertEquals(listOf(8, 10), fake.allowedCpus())
        fake.tid = 1
        first.close()
        fake.tid = 2
        scopes.capBackground().use { assertEquals(listOf(8, 10), fake.allowedCpus()) }
        assertEquals(listOf(8, 10), fake.allowedCpus())
    }

    @Test fun aClosedGroupAdaptsToANewActualMaskAndRecoversWithoutRememberingUnavailableCpus() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        val scopes = NarrationCpuScopes(fake, ::fastest)
        scopes.capBackground().use { assertEquals(listOf(4, 6), fake.allowedCpus()) }
        fake.masks[1] = listOf(1, 3, 9)
        scopes.capBackground().use { assertEquals(listOf(3, 9), fake.allowedCpus()) }
        assertEquals(listOf(1, 3, 9), fake.allowedCpus())
    }

    @Test fun aClosedGroupReevaluatesPerformanceInsteadOfKeepingASlowerHistoricalPair() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        var report = fastest(fake.allowedCpus())
        val scopes = NarrationCpuScopes(fake, { report })
        scopes.capBackground().use { assertEquals(listOf(4, 6), fake.allowedCpus()) }
        report = report.map { it.copy(capacity = if (it.cpu <= 2) 9_999 else 1) }
        scopes.capBackground().use { assertEquals(listOf(0, 2), fake.allowedCpus()) }
    }

    @Test fun aOneCpuScopeCannotMakeLaterEightCpuWorkKeepTheHistoricalOneCpuMask() {
        val fake = FakeAffinity(listOf(0))
        val scopes = NarrationCpuScopes(fake, ::fastest)
        scopes.capBackground().use { assertEquals(listOf(0), fake.allowedCpus()) }
        val expanded = (0..7).toList()
        fake.masks[1] = expanded
        scopes.capBackground().use { assertEquals(listOf(6, 7), fake.allowedCpus()) }
        assertEquals(expanded, fake.allowedCpus())
    }

    @Test fun aScopeCannotRestoreAnotherThreadsMask() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        fake.masks[2] = listOf(8, 10)
        val scopes = NarrationCpuScopes(fake, ::fastest)
        val scope = scopes.capBackground()
        fake.tid = 2
        assertThrows(NarrationCpuLimitException::class.java) { scope.close() }
        assertEquals(listOf(8, 10), fake.allowedCpus())
        fake.tid = 1
        scope.close()
        assertEquals(listOf(0, 2, 4, 6), fake.allowedCpus())
    }

    @Test fun failureToRestoreIsReportedInsteadOfPretendingTheOriginalMaskWasRecovered() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        val scope = NarrationCpuScopes(fake, ::fastest).capBackground()
        fake.ignoreRestore = true
        assertThrows(NarrationCpuLimitException::class.java) { scope.close() }
    }

    @Test fun aCpusetOrHotplugSubsetIsAValidRestorationRatherThanAnExactHistoricalMaskFailure() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        val restored = mutableListOf<Pair<List<Int>, List<Int>>>()
        val scopes = NarrationCpuScopes(fake, ::fastest, restored = { before, after -> restored += before to after })
        val scope = scopes.capBackground()
        fake.moveCpuset(listOf(0, 2))
        scope.close()
        assertEquals(listOf(0, 2), fake.allowedCpus())
        assertEquals(listOf(listOf(0, 2, 4, 6) to listOf(0, 2)), restored)
        scopes.capBackground().use { assertEquals(listOf(0, 2), fake.allowedCpus()) }
    }

    @Test fun aDisjointCpusetRecoversOnlyAfterOptionalWorkEndsAndTheNextLeaseUsesTheNewPair() {
        val fake = FakeAffinity(listOf(4, 6, 8, 10))
        val scopes = NarrationCpuScopes(fake, ::fastest)
        val scope = scopes.capBackground()
        assertEquals(listOf(8, 10), fake.allowedCpus())
        fake.moveCpuset(listOf(0, 1, 2, 3))
        scope.close()
        assertEquals(listOf(0, 1, 2, 3), fake.allowedCpus())
        assertEquals(listOf(true), fake.recoveries)
        scopes.capBackground().use { assertEquals(listOf(2, 3), fake.allowedCpus()) }
        assertEquals(listOf(0, 1, 2, 3), fake.allowedCpus())
    }

    @Test fun nestedScopesDoNotLiftTheOuterCpuLimitWhenAndroidReplacesItsCpuset() {
        val fake = FakeAffinity(listOf(4, 6, 8, 10))
        val scopes = NarrationCpuScopes(fake, ::fastest)
        val outer = scopes.capBackground()
        val inner = scopes.capBackground()
        fake.moveCpuset(listOf(0, 1, 2, 3))
        inner.close()
        assertEquals("The still-active enclosing inference remains capped", listOf(2, 3), fake.allowedCpus())
        assertTrue("Only the outermost release can recover an unrestricted current cpuset", fake.recoveries.isEmpty())
        outer.close()
        assertEquals(listOf(0, 1, 2, 3), fake.allowedCpus())
        assertEquals(listOf(true), fake.recoveries)
    }

    @Test fun nestedScopesRestoreInReverseOrderWithoutCorruptingTheLeaseCount() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        val scopes = NarrationCpuScopes(fake, ::fastest)
        val outer = scopes.capBackground()
        val inner = scopes.capBackground()
        assertThrows(NarrationCpuLimitException::class.java) { outer.close() }
        assertEquals(listOf(4, 6), fake.allowedCpus())
        inner.close()
        assertEquals(listOf(4, 6), fake.allowedCpus())
        outer.close()
        assertEquals(listOf(0, 2, 4, 6), fake.allowedCpus())
        scopes.capBackground().close()
        assertEquals(listOf(0, 2, 4, 6), fake.allowedCpus())
    }

    @Test fun anExitedThreadOrRecycledNumericTidCannotRestoreAnotherJavaThreadsLease() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        val scopes = NarrationCpuScopes(fake, ::fastest)
        val scope = scopes.capBackground()
        val failure = AtomicReference<Throwable>()
        val other = Thread {
            // The numeric native identifier is deliberately the same, as after TID reuse.
            try { scope.close() } catch (error: Throwable) { failure.set(error) }
        }
        other.start()
        other.join(5_000)
        assertFalse(other.isAlive)
        assertTrue(failure.get() is NarrationCpuLimitException)
        assertEquals(listOf(4, 6), fake.allowedCpus())
        scope.close()
        assertEquals(listOf(0, 2, 4, 6), fake.allowedCpus())
    }

    @Test fun restoringTheParentDoesNotMutateAnInheritedChildOrAnUnrelatedRetiredTid() {
        val fake = FakeAffinity(listOf(0, 2, 4, 6))
        val scopes = NarrationCpuScopes(fake, ::fastest)
        val scope = scopes.capBackground()
        fake.masks[9] = fake.allowedCpus().toList() // Native pool child inherits the caller.
        fake.masks[12] = listOf(11, 13)
        fake.masks.remove(12) // A retired TID must never be a restoration target.
        scope.close()
        assertEquals(listOf(0, 2, 4, 6), fake.allowedCpus())
        assertEquals(listOf(4, 6), fake.masks.getValue(9))
        assertFalse(fake.masks.containsKey(12))
        assertEquals(setOf(1L), fake.setTids.toSet())
    }

    private fun fastest(cpus: List<Int>) = cpus.map { NarrationCpuPerformance(it, capacity = it.toLong() + 1) }

    private class FakeAffinity(original: List<Int>) : NarrationCpuAffinity {
        var tid = 1L
        val masks = mutableMapOf(1L to original)
        val sets = mutableListOf<List<Int>>()
        val setTids = mutableListOf<Long>()
        val recoveries = mutableListOf<Boolean>()
        private val cpusets = mutableMapOf<Long, List<Int>>()
        var ignoreCap = false
        var ignoreRestore = false
        var failFirstSetAfterMutation = false
        override fun currentTid() = tid
        override fun allowedCpus() = masks.getValue(tid)
        fun moveCpuset(cpus: List<Int>) {
            cpusets[tid] = cpus
            masks[tid] = cpus // Linux updates the thread's allowed mask when its cpuset changes.
        }
        override fun restoreAllowedCpus(cpus: List<Int>, recoverCurrentCpuset: Boolean): List<Int> {
            if (cpusets[tid] == null) return super.restoreAllowedCpus(cpus, recoverCurrentCpuset)
            try {
                setAllowedCpus(cpus)
            } catch (unavailable: CpuSetUnavailable) {
                if (!recoverCurrentCpuset) throw unavailable
                recoveries += true
                setAllowedCpus(cpusets.getValue(tid))
            }
            return allowedCpus()
        }
        override fun setAllowedCpus(cpus: List<Int>) {
            sets += cpus.toList()
            setTids += tid
            if ((ignoreCap && cpus.size <= 2) || (ignoreRestore && cpus.size > 2)) return
            val effective = cpusets[tid]?.let { allowed -> cpus.filter { it in allowed } } ?: cpus.toList()
            if (effective.isEmpty()) throw CpuSetUnavailable()
            masks[tid] = effective
            if (failFirstSetAfterMutation) {
                failFirstSetAfterMutation = false
                error("native affinity set failed after changing the mask")
            }
        }
        private class CpuSetUnavailable : IllegalStateException("EINVAL: no currently permitted CPU")
    }
}
