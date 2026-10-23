package com.learngraphicsprogramming.app.narration

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import com.learngraphicsprogramming.app.R
import com.learngraphicsprogramming.app.UiLanguageSettings
import androidx.work.WorkManager

/**
 * A screen turning off does not mean the user closed the app.
 *
 * While any activity is visible, keep offline narration in a session-scoped
 * foreground service. If the phone locks with the app open, that service
 * remains active and owns a bounded PARTIAL_WAKE_LOCK while synthesizing.
 * Going Home or switching apps with the screen on ends that session.
 */
internal object KokoroNarrationSessionMonitor {
    private const val TAG = "KokoroSession"
    private const val OLD_WORK_NAME = "kokoro-offline-curriculum-v1"
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    private var startedActivities = 0
    private lateinit var application: Application
    private var waitingForStorage = false
    private var resumedGenerationNoticePending = false
    private var resumedToastWaitingForVisibleActivity = false
    private const val STORAGE_RECHECK_MS = 60_000L

    private fun showResumedNoticeWhenVisible() {
        if (!resumedToastWaitingForVisibleActivity || startedActivities <= 0) return
        resumedToastWaitingForVisibleActivity = false
        val localized = UiLanguageSettings.localizedContext(application)
        Toast.makeText(localized, localized.getString(R.string.narration_storage_resumed),
            Toast.LENGTH_SHORT).show()
    }

    /** Show the notice only after a real new Kokoro recording has been saved. */
    fun onAudioGenerated() {
        val notify = {
            if (initialized && resumedGenerationNoticePending) {
                resumedGenerationNoticePending = false
                resumedToastWaitingForVisibleActivity = true
                showResumedNoticeWhenVisible()
                KokoroNarrationDiagnostics.event(application, TAG, "Kokoro resumed generating audio after storage pause")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) notify() else main.post { notify() }
    }

    // Restart offline synthesis when the user makes room. This polls only
    // while paused for storage; no wake lock or foreground service is held.
    private val storageRecheck = object : Runnable {
        override fun run() {
            if (!initialized || !waitingForStorage) return
            val settings = NarrationSettingsRepository.get(application)
            if (!settings.enabled || settings.engine == NarrationEngine.ANDROID ||
                !NarrationSettingsRepository.advanceGenerationEnabled(application) ||
                NarrationStorageBudget.savedLimit(application) == 0L) {
                waitingForStorage = false
                return
            }
            val room = runCatching {
                NarrationStorageBudget.fitsNext(NarrationStorageBudget.snapshot(application),
                    4L * NarrationStorageBudget.MIB)
            }.getOrDefault(false)
            if (room && startedActivities > 0 && startService()) {
                waitingForStorage = false
                resumedGenerationNoticePending = true
            } else {
                // Keep the intent to resume until the user returns to the app.
                // Do not relaunch a foreground service from a hidden activity.
                main.postDelayed(this, STORAGE_RECHECK_MS)
            }
        }
    }

    /** Background generation was paused specifically because space ran out. */
    fun resumeWhenStorageAvailable(context: Context) {
        val update = {
            if (!initialized) install(context.applicationContext as Application)
            if (NarrationStorageBudget.savedLimit(application) != 0L) {
                waitingForStorage = true
                main.removeCallbacks(storageRecheck)
                main.postDelayed(storageRecheck, STORAGE_RECHECK_MS)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else main.post { update() }
    }
    // SharedPreferences keeps listeners weakly; the application monitor owns this
    // one for its lifetime, including sessions with no visible activity.
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled" || key == "engine" || NarrationGenerationProfile.isPreference(key) ||
            NarrationSettingsRepository.isCourseGenerationPreference(key) ||
            key == NarrationSettingsRepository.KEY_ADVANCE_GENERATION) {
            val update = {
                if (!NarrationSettingsRepository.advanceGenerationEnabled(application)) {
                    waitingForStorage = false
                    resumedGenerationNoticePending = false
                    resumedToastWaitingForVisibleActivity = false
                    main.removeCallbacks(storageRecheck)
                }
                startService()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) update()
            else main.post { update() }
        }
    }

    fun refresh(context: Context) {
        val app = context.applicationContext as Application
        val update = {
            if (!initialized) install(app)
            if (waitingForStorage) {
                main.removeCallbacks(storageRecheck)
                main.post(storageRecheck)
            } else startService()
        }
        // MainActivity calls this inside onCreate. Registration must happen
        // NOW, before Android dispatches the first onActivityStarted callback.
        if (Looper.myLooper() == Looper.getMainLooper()) update()
        else main.post { update() }
    }

    private fun install(app: Application) {
        initialized = true
        application = app
        app.getSharedPreferences("lesson_narration_settings", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(settingsListener)

        // Jobs created by earlier APK versions must not run a second copy.
        WorkManager.getInstance(app).cancelUniqueWork(OLD_WORK_NAME)

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                KokoroNarrationDiagnostics.update(application, mapOf("startedActivities" to startedActivities))
                showResumedNoticeWhenVisible()
                if (startedActivities == 1) {
                    if (waitingForStorage) {
                        main.removeCallbacks(storageRecheck)
                        main.post(storageRecheck)
                    } else startService()
                }
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                KokoroNarrationDiagnostics.update(application, mapOf("startedActivities" to startedActivities))
                if (startedActivities == 0) scheduleStop(2_500L)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // A screen-on event ends an unattended screen-off session if the user
        // woke into another app rather than returning to LearnGraphics.
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action in setOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT) &&
                    startedActivities == 0) {
                    scheduleStop(10_000L)
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    private fun powerInteractive(): Boolean =
        (application.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun scheduleStop(delayMillis: Long) {
        main.postDelayed({
            val keyguard = application.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            // A lock screen can be ON while the app remains open behind it.
            // End only when the device is unlocked and our app is no longer visible.
            if (startedActivities == 0 && powerInteractive() && !keyguard.isKeyguardLocked) {
                KokoroNarrationDiagnostics.event(application, TAG, "Ending unattended unlocked narration session")
                application.stopService(Intent(application, KokoroContinuousNarrationService::class.java))
            }
        }, delayMillis)
    }

    private fun startService(): Boolean {
        val settings = NarrationSettingsRepository.get(application)
        if (!settings.enabled || settings.engine == NarrationEngine.ANDROID ||
            !NarrationSettingsRepository.advanceGenerationEnabled(application)) {
            KokoroNarrationDiagnostics.update(application, mapOf("sessionState" to "disabled-or-android-tts"))
            // Do not cancel a startForegroundService request before Android has
            // delivered onCreate/onStartCommand: that violates its promotion
            // deadline. A live service observes these settings itself; a queued
            // instance promotes first, then exits without launching synthesis.
            return false
        }
        if (startedActivities <= 0) return false
        val intent = Intent(application, KokoroContinuousNarrationService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) application.startForegroundService(intent)
            else application.startService(intent)
            return true
        } catch (error: RuntimeException) {
            // Foreground launch restrictions can still be imposed by Android
            // or the phone manufacturer; report them, never silently swallow.
            KokoroNarrationDiagnostics.event(application, TAG, "Could not start offline narration foreground service", error)
            return false
        }
    }
}
