package com.learngraphicsprogramming.app.narration

import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Scoped affinity for optional narration workers. Neither the UI nor playback threads are
 * modified. Native inference pools must be constructed under this scope to inherit its mask.
 */
internal object NarrationCpuBudget {
    private val loaded by lazy { System.loadLibrary("lgp_narration_cpu"); true }
    private val affinity = object : NarrationCpuAffinity {
        override fun currentTid(): Long { ensureLoaded(); return nativeCurrentTid() }
        override fun allowedCpus(): List<Int> { ensureLoaded(); return nativeAllowedCpus().toList() }
        override fun setAllowedCpus(cpus: List<Int>) { ensureLoaded(); nativeSetAllowedCpus(cpus.toIntArray()) }
        override fun restoreAllowedCpus(cpus: List<Int>, recoverCurrentCpuset: Boolean): List<Int> {
            ensureLoaded()
            return nativeRestoreAllowedCpus(cpus.toIntArray(), recoverCurrentCpuset).toList()
        }
    }
    @Volatile var lastSelection: NarrationCpuSelection? = null
        private set
    private val scopes = NarrationCpuScopes(affinity, ::performance, report = { selection ->
        lastSelection = selection
        Log.i("NarrationCpuBudget", "Background affinity verified: allowed=${selection.allowed}, " +
            "selected=${selection.selected}, ranking=${selection.ranking}")
    }, restored = { original, actual ->
        Log.i("NarrationCpuBudget", "Narration CPU restoration followed Android's changed cpuset: " +
            "previous=$original, restored=$actual")
    })

    fun allowedCpus(): List<Int> = try {
        affinity.allowedCpus().distinct().sorted().also {
            if (it.isEmpty() || it.any { cpu -> cpu < 0 }) throw NarrationCpuLimitException("Empty CPU affinity mask")
        }
    } catch (error: Throwable) {
        throw if (error is NarrationCpuLimitException) error else
            NarrationCpuLimitException("Cannot read Android's actual thread CPU mask", error)
    }

    /** A count fallback is acceptable for foreground performance; it is never a hard cap. */
    fun foregroundThreadCount(): Int = try { allowedCpus().size } catch (error: NarrationCpuLimitException) {
        val count = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        Log.w("NarrationCpuBudget", "Actual foreground CPU mask unavailable; using Java's processor count ($count)", error)
        count
    }

    fun capBackground(): AutoCloseable {
        if (Looper.myLooper() == Looper.getMainLooper()) throw NarrationCpuLimitException(
            "The UI thread cannot receive a background narration CPU cap")
        return try { scopes.capBackground() } catch (error: Throwable) {
            throw if (error is NarrationCpuLimitException) error else
                NarrationCpuLimitException("Cannot guarantee the background narration CPU limit", error)
        }
    }

    private fun performance(cpus: List<Int>): List<NarrationCpuPerformance> = cpus.map { cpu ->
        fun value(relative: String): Long? = runCatching {
            val file = File("/sys/devices/system/cpu/cpu$cpu/$relative")
            if (!file.isFile) null
            else file.readText(Charsets.US_ASCII).takeIf { it.length <= 128 }
                ?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        }.getOrNull()
        NarrationCpuPerformance(cpu, value("cpu_capacity"),
            value("cpufreq/cpuinfo_max_freq") ?: value("cpufreq/scaling_max_freq"))
    }

    private fun ensureLoaded() { check(loaded) }
    private external fun nativeCurrentTid(): Long
    private external fun nativeAllowedCpus(): IntArray
    private external fun nativeSetAllowedCpus(cpus: IntArray)
    private external fun nativeRestoreAllowedCpus(cpus: IntArray, recoverCurrentCpuset: Boolean): IntArray
}
