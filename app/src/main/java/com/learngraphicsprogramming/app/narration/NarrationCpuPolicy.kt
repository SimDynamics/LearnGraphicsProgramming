package com.learngraphicsprogramming.app.narration

/** CPU identifiers come from the calling thread's real affinity mask, never its array position. */
internal data class NarrationCpuPerformance(
    val cpu: Int,
    val capacity: Long? = null,
    val maxFrequencyKhz: Long? = null
)

internal data class NarrationCpuSelection(
    val allowed: List<Int>,
    val selected: List<Int>,
    val ranking: String
)

internal object NarrationCpuPolicy {
    fun select(allowed: List<Int>, performance: List<NarrationCpuPerformance>): NarrationCpuSelection {
        val candidates = allowed.distinct().sorted()
        require(candidates.isNotEmpty() && candidates.first() >= 0) { "No usable CPU mask" }
        val scores = performance.associateBy { it.cpu }
        val capacityKnown = candidates.all { (scores[it]?.capacity ?: 0) > 0 }
        val frequencyKnown = candidates.all { (scores[it]?.maxFrequencyKhz ?: 0) > 0 }
        val ranking = when {
            capacityKnown -> "cpu_capacity"
            frequencyKnown -> "cpuinfo_max_freq"
            else -> "stable_cpu_id_fallback_performance_unknown"
        }
        val ordered = when {
            capacityKnown -> candidates.sortedWith(compareByDescending<Int> { scores.getValue(it).capacity }
                .thenByDescending { scores[it]?.maxFrequencyKhz ?: 0 }.thenBy { it })
            frequencyKnown -> candidates.sortedWith(compareByDescending<Int> { scores.getValue(it).maxFrequencyKhz }
                .thenBy { it })
            else -> candidates
        }
        return NarrationCpuSelection(candidates, ordered.take(2).sorted(), ranking)
    }
}

/** Testable system boundary: each operation addresses only the caller's native thread. */
internal interface NarrationCpuAffinity {
    fun currentTid(): Long
    fun allowedCpus(): List<Int>
    fun setAllowedCpus(cpus: List<Int>)

    /**
     * Restore the caller, not a saved TID. Android can move the thread to another cpuset or
     * offline a CPU while a scope runs. Only the outermost scope may recover that new cpuset;
     * an inner lease must remain capped until its enclosing optional work has also ended.
     * The platform boundary verifies the effective kernel mask before returning it.
     */
    fun restoreAllowedCpus(cpus: List<Int>, recoverCurrentCpuset: Boolean): List<Int> {
        setAllowedCpus(cpus)
        return allowedCpus().distinct().sorted().also {
            if (it != cpus) throw NarrationCpuLimitException("Android did not restore the original narration thread CPU mask")
        }
    }
}

internal open class NarrationCpuLimitException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Another live owner still holds the old pair; wait for its release instead of widening it. */
internal class NarrationCpuPairBusyException(message: String) : NarrationCpuLimitException(message)

/**
 * All optional narration jobs share one pair. An overlapping scope cannot silently select a
 * second, disjoint pair when Android changes its allowed mask. There is no process-wide affinity.
 */
internal class NarrationCpuScopes(
    private val affinity: NarrationCpuAffinity,
    private val performance: (List<Int>) -> List<NarrationCpuPerformance>,
    private val report: (NarrationCpuSelection) -> Unit = {},
    private val restored: (List<Int>, List<Int>) -> Unit = { _, _ -> }
) {
    private val lock = Any()
    private var shared: NarrationCpuSelection? = null
    private var active = 0
    private data class ThreadKey(val thread: Thread, val tid: Long)
    private val leases = mutableMapOf<ThreadKey, MutableList<Any>>()

    fun capBackground(): AutoCloseable = synchronized(lock) {
        val thread = Thread.currentThread()
        val tid = affinity.currentTid()
        val key = ThreadKey(thread, tid)
        val token = Any()
        val original = affinity.allowedCpus().distinct().sorted()
        if (original.isEmpty() || original.any { it < 0 }) {
            throw NarrationCpuLimitException("Android did not provide a usable thread CPU mask")
        }
        val previous = shared
        val selection = when {
            active == 0 -> NarrationCpuPolicy.select(original, performance(original))
            previous != null && original.containsAll(previous.selected) ->
                NarrationCpuSelection(original, previous.selected, previous.ranking)
            previous == null -> throw NarrationCpuLimitException("Missing active narration CPU pair")
            else -> {
                val intersection = previous.selected.filter { it in original }
                if (intersection.isEmpty()) throw NarrationCpuPairBusyException(
                    "The active narration CPU pair is unavailable to this thread")
                NarrationCpuSelection(original, intersection, "shared_${previous.ranking}")
            }
        }
        try {
            affinity.setAllowedCpus(selection.selected)
            val actual = affinity.allowedCpus().distinct().sorted()
            if (actual.isEmpty() || actual.size > 2 || !selection.selected.containsAll(actual)) {
                throw NarrationCpuLimitException("Android did not apply the requested two-CPU limit")
            }
        } catch (error: Throwable) {
            try { restore(original, recoverCurrentCpuset = leases[key].isNullOrEmpty()) }
            catch (restoreError: Throwable) { error.addSuppressed(restoreError) }
            throw if (error is NarrationCpuLimitException) error else
                NarrationCpuLimitException("Cannot guarantee the background narration CPU limit", error)
        }
        if (active == 0) shared = selection
        active++
        leases.getOrPut(key) { mutableListOf() }.add(token)
        runCatching { report(selection) }
        object : AutoCloseable {
            private var closed = false
            override fun close() = synchronized(lock) {
                if (closed) return@synchronized
                if (Thread.currentThread() !== thread || affinity.currentTid() != tid) throw NarrationCpuLimitException(
                    "Narration CPU affinity must be restored by its original thread")
                val stack = leases[key]
                if (stack?.lastOrNull() !== token) throw NarrationCpuLimitException(
                    "Nested narration CPU scopes must be restored in reverse order")
                try {
                    if (stack.size > 1) {
                        val current = affinity.allowedCpus().distinct().sorted()
                        if (original.none { it in current } && active == stack.size) {
                            // All active leases belong to this same thread. Android replaced its
                            // cpuset, so the old pair is unusable. Rebind the still-active outer
                            // lease to at most two of the newly permitted CPUs before returning.
                            val replacement = NarrationCpuPolicy.select(current, performance(current))
                            affinity.setAllowedCpus(replacement.selected)
                            val actual = affinity.allowedCpus().distinct().sorted()
                            if (actual.isEmpty() || actual.size > 2 || !replacement.selected.containsAll(actual)) {
                                throw NarrationCpuLimitException("Android did not preserve the enclosing narration CPU limit")
                            }
                            shared = replacement.copy(selected = actual)
                            runCatching { report(requireNotNull(shared)) }
                        } else {
                            restore(original, recoverCurrentCpuset = false)
                        }
                    } else {
                        restore(original, recoverCurrentCpuset = true)
                    }
                } catch (error: Throwable) {
                    throw if (error is NarrationCpuLimitException) error else
                        NarrationCpuLimitException("Cannot restore the narration thread CPU mask", error)
                } finally {
                    closed = true
                    active--
                    stack.removeAt(stack.lastIndex)
                    if (stack.isEmpty()) leases.remove(key)
                    if (active == 0) shared = null
                }
            }
        }
    }

    private fun restore(original: List<Int>, recoverCurrentCpuset: Boolean) {
        val actual = affinity.restoreAllowedCpus(original, recoverCurrentCpuset).distinct().sorted()
        if (actual.isEmpty() || actual.any { it < 0 } ||
            (!recoverCurrentCpuset && (actual.size > 2 || !original.containsAll(actual))) ||
            affinity.allowedCpus().distinct().sorted() != actual) {
            throw NarrationCpuLimitException("Android did not verify the restored narration thread CPU mask")
        }
        if (actual != original) runCatching { restored(original, actual) }
    }
}
