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
import com.gabut.autopost.ui.i18n.t
import com.gabut.autopost.ui.i18n.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps posting and verifying alive while the app is backgrounded or
 * swiped away.
 *
 * Android only permits continued background work behind a visible notification, which is exactly
 * what this service provides (D1). The work itself lives in [ChannelScheduler] and [VerifierEngine]
 * so this class stays about lifecycle and the notification.
 *
 * `START_NOT_STICKY` is deliberate (D17): after the process is killed, nothing must resurrect
 * itself. The user presses Start again, and until then no row may claim to be running.
 */
class PostingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val app: AutoPostApplication
        get() = application as AutoPostApplication

    private var eventJob: Job? = null
    private var verifierEventJob: Job? = null
    private var lastNotificationText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app.logRecorder.start(scope)
        eventJob = scope.launch {
            app.scheduler.eventFlow.collect { event -> onEvent(event) }
        }
        verifierEventJob = scope.launch {
            app.verifierEngine.eventFlow.collect { event -> onVerifierEvent(event) }
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

            ACTION_START_VERIFY -> {
                val verifierId = intent.getLongExtra(EXTRA_TOKEN_ID, -1L)
                if (verifierId <= 0L) return START_NOT_STICKY
                startVerifier(verifierId)
            }

            ACTION_STOP_VERIFY -> {
                val verifierId = intent.getLongExtra(EXTRA_TOKEN_ID, -1L)
                if (verifierId > 0L) stopVerifier(verifierId)
            }

            ACTION_STOP_ALL -> stopEverything(pausedByAndroid = false)

            else -> {
                // A null action can happen when the OS re-creates the service; it must never
                // resume work on its own.
                scope.launch {
                    if (!app.schedulerAnyRunning() && !app.verifierAnyRunning()) stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    // ---- posting ---------------------------------------------------------

    private fun startToken(tokenId: Long) {
        ensureForeground()

        scope.launch {
            app.repository.updateRunning(tokenId, true)
            app.scheduler.start(tokenId)
            // A token with no channels (or one deleted while this coroutine was queued) leaves
            // nothing to run, and the service must not linger behind a notification.
            stopIfIdle()
        }
    }

    private fun stopToken(tokenId: Long) {
        scope.launch {
            app.scheduler.stop(tokenId)
            app.repository.updateRunning(tokenId, false)
            stopIfIdle()
        }
    }

    // ---- verifying -------------------------------------------------------

    private fun startVerifier(verifierId: Long) {
        ensureForeground()

        scope.launch {
            val entity = app.repository.getVerifier(verifierId) ?: return@launch
            val token = app.cipher.decrypt(entity.encryptedToken)
            if (token == null) {
                app.repository.updateVerifierRunning(verifierId, false)
                app.repository.updateVerifierStatus(
                    id = verifierId,
                    status = null,
                    at = System.currentTimeMillis(),
                    text = t(
                        "Token unreadable - enter it again",
                        "Token tidak terbaca - masukkan lagi"
                    )
                )
                app.notifier.notifyVerifierUnreadable(verifierId, entity.name)
                stopIfIdle()
                return@launch
            }
            app.repository.updateVerifierRunning(verifierId, true)
            app.verifierEngine.start(VerifierEngine.Config.from(entity, token))
            stopIfIdle()
        }
    }

    private fun stopVerifier(verifierId: Long) {
        scope.launch {
            app.verifierEngine.stop(verifierId)
            app.repository.updateVerifierRunning(verifierId, false)
            app.repository.updateVerifierStatus(
                id = verifierId,
                status = null,
                at = System.currentTimeMillis(),
                text = t("Stopped", "Berhenti")
            )
            stopIfIdle()
        }
    }

    // ---- lifecycle -------------------------------------------------------

    private fun stopIfIdle() {
        scope.launch {
            if (!app.schedulerAnyRunning() && !app.verifierAnyRunning()) {
                app.repository.resetAllRunning()
                app.repository.resetAllVerifierRunning()
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
            app.verifierEngine.stopAll()
            app.repository.resetAllRunning()
            app.repository.resetAllVerifierRunning()
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
            lastNotificationText ?: Notifications.postingText(
                runningTokens = 0,
                channels = 0,
                verifiers = 0,
                sent = PostingStats.sentCount
            )
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
        val verifiers = app.repository.getAllVerifiersOnce().count { it.running }

        PostingStats.updateSummary(running.size, channels, verifiers)
        val text = Notifications.postingText(running.size, channels, verifiers, PostingStats.sentCount)
        if (text == lastNotificationText) return
        lastNotificationText = text

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            Notifications.POSTING_NOTIFICATION_ID,
            Notifications.postingNotification(this, text)
        )
    }

    // ---- events ----------------------------------------------------------

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

    /**
     * A verifier has no channel row to update, so its outcome is written onto the verifier itself.
     * That is what the Verifiers tab reads back after a restart.
     */
    private fun onVerifierEvent(event: VerifierEvent) {
        scope.launch {
            when (event) {
                is VerifierEvent.Clicked -> {
                    app.repository.updateVerifierStatus(
                        id = event.verifierId,
                        status = 204,
                        at = System.currentTimeMillis(),
                        text = t("Verified (${event.label})", "Terverifikasi (${event.label})"),
                        clickDelta = 1
                    )
                }

                is VerifierEvent.AuthFailed -> {
                    app.verifierEngine.stop(event.verifierId)
                    app.repository.updateVerifierRunning(event.verifierId, false)
                    app.repository.updateVerifierStatus(
                        id = event.verifierId,
                        status = 401,
                        at = System.currentTimeMillis(),
                        text = t(
                            "Token rejected - enter it again",
                            "Token ditolak - masukkan lagi"
                        )
                    )
                    app.notifier.notifyVerifierAuthFailed(event.verifierId)
                    refreshNotification()
                }

                is VerifierEvent.RateLimited -> {
                    app.repository.updateVerifierStatus(
                        id = event.verifierId,
                        status = 429,
                        at = System.currentTimeMillis(),
                        text = t(
                            "Rate limited - waiting ${event.retryAfterMs / 1000}s",
                            "Kena rate limit - menunggu ${event.retryAfterMs / 1000}dtk"
                        )
                    )
                }

                is VerifierEvent.Disconnected -> {
                    // "connected" is the engine's way of saying the socket is healthy again.
                    if (event.reason == "connected") {
                        app.repository.updateVerifierStatus(
                            id = event.verifierId,
                            status = 200,
                            at = System.currentTimeMillis(),
                            text = t(
                                "Watching for verify buttons",
                                "Menunggu tombol verifikasi"
                            )
                        )
                    } else {
                        app.repository.updateVerifierStatus(
                            id = event.verifierId,
                            status = null,
                            at = System.currentTimeMillis(),
                            text = if (event.reason == "reconnecting") {
                                t("Reconnecting…", "Menyambung ulang…")
                            } else {
                                t("Stopped: ${event.reason}", "Berhenti: ${event.reason}")
                            }
                        )
                        refreshNotification()
                    }
                }

                is VerifierEvent.Unverifiable -> {
                    app.repository.updateVerifierStatus(
                        id = event.verifierId,
                        status = null,
                        at = System.currentTimeMillis(),
                        text = t(
                            "A verify message could not be matched to this account",
                            "Pesan verifikasi tidak cocok dengan akun ini"
                        )
                    )
                }

                is VerifierEvent.ClickFailed -> {
                    // Silent before: a refused click left the row saying "Watching" forever.
                    app.repository.updateVerifierStatus(
                        id = event.verifierId,
                        status = event.code,
                        at = System.currentTimeMillis(),
                        text = buildString {
                            append(t("Click failed", "Klik gagal"))
                            event.code?.let { append(" (HTTP $it)") }
                            event.detail?.let { append(": $it") }
                        }
                    )
                }

                is VerifierEvent.WaitingConfirm -> {
                    app.repository.updateVerifierStatus(
                        id = event.verifierId,
                        status = null,
                        at = System.currentTimeMillis(),
                        text = t(
                            "Authenticated, waiting for the confirm button",
                            "Terverifikasi, menunggu tombol konfirmasi"
                        )
                    )
                }

                // Not mine is the normal case on a busy channel; nothing to record.
                is VerifierEvent.SkippedForeign -> Unit
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
        verifierEventJob?.cancel()
        scope.cancel()
        app.applicationScope.launch {
            app.scheduler.stopAll()
            app.verifierEngine.stopAll()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gabut.autopost.action.START"
        const val ACTION_STOP = "com.gabut.autopost.action.STOP"
        const val ACTION_STOP_ALL = "com.gabut.autopost.action.STOP_ALL"
        const val ACTION_START_VERIFY = "com.gabut.autopost.action.START_VERIFY"
        const val ACTION_STOP_VERIFY = "com.gabut.autopost.action.STOP_VERIFY"
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

        fun startVerifyIntent(context: Context, verifierId: Long): Intent =
            Intent(context, PostingService::class.java)
                .setAction(ACTION_START_VERIFY)
                .putExtra(EXTRA_TOKEN_ID, verifierId)

        fun stopVerifyIntent(context: Context, verifierId: Long): Intent =
            Intent(context, PostingService::class.java)
                .setAction(ACTION_STOP_VERIFY)
                .putExtra(EXTRA_TOKEN_ID, verifierId)

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
