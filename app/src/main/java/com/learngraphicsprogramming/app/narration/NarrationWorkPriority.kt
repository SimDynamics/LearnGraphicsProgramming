package com.learngraphicsprogramming.app.narration

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central arbitration for optional background narration.
 * Synthesis, compilation and graphics take precedence over optional generation.
 * Cached playback protects its files without needlessly stopping that generation.
 */
object NarrationWorkPriority {
    private val interactiveCount = AtomicInteger()
    private var playbackCount = 0
    private val interrupts = CopyOnWriteArrayList<() -> Unit>()
    private val leaseLock = Any()
    // Once a reader enters, this maintenance attempt may never resume deletion.
    private var maintenanceActive = false
    private var maintenancePreempted = false

    val isBusy: Boolean get() = interactiveCount.get() > 0

    /** A player does not require an inference lane. Keep cache deletion excluded,
     * while automatic synthesis remains restricted by its real two-CPU scope. */
    fun beginPlayback(): AutoCloseable {
        synchronized(leaseLock) {
            playbackCount++
            if (maintenanceActive) maintenancePreempted = true
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(leaseLock) { playbackCount-- }
        }
    }

    fun beginInteractive(): AutoCloseable {
        synchronized(leaseLock) {
            interactiveCount.incrementAndGet()
            if (maintenanceActive) maintenancePreempted = true
        }
        interrupts.forEach { listener -> runCatching { listener() } }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(leaseLock) { interactiveCount.decrementAndGet() }
            }
        }
    }

    fun beginMaintenanceIfIdle(): AutoCloseable? {
        synchronized(leaseLock) {
            if (interactiveCount.get() != 0 || playbackCount != 0 || maintenanceActive) return null
            maintenanceActive = true
            maintenancePreempted = false
            interactiveCount.incrementAndGet()
        }
        // Preempt background generation without keeping the bookkeeping lock.
        interrupts.forEach { listener -> runCatching { listener() } }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(leaseLock) {
                    maintenanceActive = false
                    interactiveCount.decrementAndGet()
                }
            }
        }
    }

    val isMaintenanceExclusive: Boolean get() = synchronized(leaseLock) {
        maintenanceActive && !maintenancePreempted && interactiveCount.get() == 1 && playbackCount == 0
    }

    /**
     * Hold the short critical section for one confirmed, verified deletion.
     * A reader cannot acquire a lease midway through removing that file.
     */
    internal fun runMaintenanceStep(action: () -> Unit): Boolean = synchronized(leaseLock) {
        if (!maintenanceActive || maintenancePreempted || interactiveCount.get() != 1 || playbackCount != 0) return false
        action()
        true
    }

    fun onPreempt(listener: () -> Unit): AutoCloseable {
        interrupts.add(listener)
        if (isBusy) listener()
        return AutoCloseable { interrupts.remove(listener) }
    }
}
