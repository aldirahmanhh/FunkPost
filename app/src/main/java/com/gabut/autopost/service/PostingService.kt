package com.gabut.autopost.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import com.gabut.autopost.AutoPostApplication
import com.gabut.autopost.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps posting alive while the app is backgrounded or swiped away.
 *
 * Android only permits continued background work behind a visible notification, which is exactly
 * what this service provides (D1). The work itself lives in [ChannelScheduler] so this class stays
 * about lifecycle and the notification.
 *
 * `START_NOT_STICKY` is deliberate (D17): after the process is killed, posting must NOT resurrect
 * itself. The user presses Start again, and until then no token may claim to be running.
 */
class PostingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val app: AutoPostApplication
        get() = application as AutoPostApplication

    private var eventJob: Job? = null
    private var lastNotificationText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        eventJob = scope.launch {
            app.scheduler.eventFlow.collect { event -> onEvent(event) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val tokenId = intent.getLongExtra(EXTRA_TOKEN_ID, -1L)
                if (tokenId <= 0L) return START_NOT_STICKY
                startToken(tokenId)
            }

            ACTION_STOP -> {
                val tokenId = intent.getLongExtra(EXTRA_TOKEN_ID, -1L)
                if (tokenId > 0L) stopToken(tokenId)
            }

            ACTION_STOP_ALL -> stopEverything(pausedByAndroid = false)

            else -> {
                // A null action can happen when the OS re-creates the service; it must never
                // resume posting on its own.
                scope.launch { if (!app.schedulerAnyRunning()) stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    private fun startToken(tokenId: Long) {
        ensureForeground()

        scope.launch {
            app.repository.updateRunning(tokenId, true)
            app.scheduler.start(tokenId)
            refreshNotification()
        }
    }

    private fun stopToken(tokenId: Long) {
        scope.launch {
            app.scheduler.stop(tokenId)
            app.repository.updateRunning(tokenId, false)
            if (!app.schedulerAnyRunning()) {
                app.repository.resetAllRunning()
                PostingStats.updateSummary(0, 0)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                refreshNotification()
            }
        }
    }

    private fun stopEverything(pausedByAndroid: Boolean) {
        scope.launch {
            app.scheduler.stopAll()
            app.repository.resetAllRunning()
            PostingStats.updateSummary(0, 0)

            if (pausedByAndroid) {
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(
                    Notifications.POSTING_NOTIFICATION_ID,
                    Notifications.postingNotification(
                        this@PostingService,
                        Notifications.pausedText(),
                        paused = true
                    )
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun ensureForeground() {
        val notification = Notifications.postingNotification(
            this,
            lastNotificationText ?: Notifications.postingText(0, 0, PostingStats.sentCount)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.POSTING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Notifications.POSTING_NOTIFICATION_ID, notification)
        }
    }

    private suspend fun refreshNotification() {
        val tokens = app.repository.getAllTokensOnce()
        val running = tokens.filter { it.running }
        var channels = 0
        running.forEach { token ->
            channels += app.repository.countForToken(token.id)
        }

        PostingStats.updateSummary(running.size, channels)
        val text = Notifications.postingText(running.size, channels, PostingStats.sentCount)
        if (text == lastNotificationText) return
        lastNotificationText = text

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            Notifications.POSTING_NOTIFICATION_ID,
            Notifications.postingNotification(this, text)
        )
    }

    private fun onEvent(event: PostingEvent) {
        when (event) {
            is PostingEvent.Posted -> {
                PostingStats.recordSent()
                scope.launch { refreshNotification() }
            }

            is PostingEvent.AuthFailed -> {
                // D18: a rejected token pauses every channel it owns, and nothing retries.
                scope.launch {
                    app.scheduler.stop(event.tokenId)
                    app.repository.updateRunning(event.tokenId, false)
                    app.notifier.notifyAuthFailed(event.tokenId)
                    refreshNotification()
                }
            }

            is PostingEvent.RateLimited -> scope.launch {
                // Only a *long* limit is worth a notification; a 5s wait is routine.
                app.notifier.notifyLongRateLimit(
                    event.tokenId,
                    event.channelId,
                    event.retryAfterMs
                )
                refreshNotification()
            }
            is PostingEvent.ChannelFailing -> scope.launch {
                app.notifier.notifyChannelFailing(event.tokenId, event.channelId, event.consecutiveFailures)
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Defensive: Android 15 imposes a dataSync time limit. Pausing explicitly is far better
        // than a crash or a silent stop the owner cannot see.
        stopEverything(pausedByAndroid = true)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally empty: swiping the task away must NOT stop posting (IS-2).
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        eventJob?.cancel()
        scope.cancel()
        app.applicationScope.launch { app.scheduler.stopAll() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gabut.autopost.action.START"
        const val ACTION_STOP = "com.gabut.autopost.action.STOP"
        const val ACTION_STOP_ALL = "com.gabut.autopost.action.STOP_ALL"
        const val EXTRA_TOKEN_ID = "extra_token_id"

        fun startIntent(context: Context, tokenId: Long): Intent =
            Intent(context, PostingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TOKEN_ID, tokenId)

        fun stopIntent(context: Context, tokenId: Long): Intent =
            Intent(context, PostingService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_TOKEN_ID, tokenId)

        fun stopAllIntent(context: Context): Intent =
            Intent(context, PostingService::class.java).setAction(ACTION_STOP_ALL)

        /**
         * Opens the system's battery-optimisation screen for this app. Never called by the app
         * itself: asking the user is fine, exempting yourself behind their back is not.
         */
        fun buildBatteryOptimizationIntent(context: Context): Intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val manager = context.getSystemService(android.os.PowerManager::class.java) ?: return false
            return manager.isIgnoringBatteryOptimizations(context.packageName)
        }
    }
}