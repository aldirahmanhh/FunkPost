package com.gabut.autopost.notify

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Turns scheduler facts into notifications the owner can act on (todo 17).
 *
 * Two rules keep this from becoming noise:
 * - each alert identity is de-duplicated for [DEDUPE_WINDOW_MS], so a token that fails every second
 *   does not produce a notification every second;
 * - when notifications are denied, the same message lands in [banner] instead, because a silent
 *   failure is the one thing this app must never do.
 */
class AlertNotifier(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val lastAlertAt = HashMap<String, Long>()
    private val _banner = MutableStateFlow<String?>(null)
    val banner: StateFlow<String?> = _banner

    fun notifyAuthFailed(tokenId: Long) {
        publish(
            key = "auth:$tokenId",
            id = ALERT_ID_AUTH + tokenId.toInt(),
            title = "Bot token rejected",
            body = "Discord rejected this bot token. Posting is paused until you fix it.",
            tokenId = tokenId
        )
    }

    fun notifyTokenUnreadable(tokenId: Long) {
        publish(
            key = "unreadable:$tokenId",
            id = ALERT_ID_UNREADABLE + tokenId.toInt(),
            title = "Token needs re-entry",
            body = "This token could not be decrypted. Enter it again in Settings.",
            tokenId = tokenId
        )
    }

    fun notifyChannelFailing(tokenId: Long, channelId: String, consecutiveFailures: Int) {
        if (consecutiveFailures < FAILURE_THRESHOLD) return
        publish(
            key = "failing:$tokenId:$channelId",
            id = ALERT_ID_CHANNEL + channelId.hashCode(),
            title = "Channel keeps failing",
            body = "Channel $channelId has failed $consecutiveFailures times in a row.",
            tokenId = tokenId
        )
    }

    fun notifyLongRateLimit(tokenId: Long, channelId: String, retryAfterMs: Long) {
        if (retryAfterMs < LONG_RATE_LIMIT_MS) return
        val seconds = retryAfterMs / 1000
        val key = "ratelimit:$tokenId:$channelId"
        if (!shouldPublish(key)) return

        val notification = Notifications.eventNotification(
            context,
            "Discord rate limit",
            "Channel $channelId is waiting ${seconds}s before the next post.",
            EVENT_ID_RATE_LIMIT + channelId.hashCode()
        )
        manager?.notify(EVENT_ID_RATE_LIMIT + channelId.hashCode(), notification)
    }

    private fun publish(key: String, id: Int, title: String, body: String, tokenId: Long) {
        if (!shouldPublish(key)) return

        if (!notificationsEnabled()) {
            _banner.value = body
            return
        }
        val notification = Notifications.alertsNotification(
            context = context,
            title = title,
            body = body,
            tokenId = tokenId,
            requestCode = id
        )
        manager?.notify(id, notification)
    }

    private fun shouldPublish(key: String): Boolean = synchronized(lastAlertAt) {
        val previous = lastAlertAt[key]
        val current = now()
        if (previous != null && current - previous < DEDUPE_WINDOW_MS) return false
        lastAlertAt[key] = current
        true
    }

    private fun notificationsEnabled(): Boolean {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return enabled && hasPostPermission()
    }

    private fun hasPostPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun clearBanner() {
        _banner.value = null
    }

    companion object {
        const val DEDUPE_WINDOW_MS = 10 * 60 * 1000L
        const val FAILURE_THRESHOLD = 5
        const val LONG_RATE_LIMIT_MS = 30_000L
        private const val ALERT_ID_AUTH = 2000
        private const val ALERT_ID_UNREADABLE = 2100
        private const val ALERT_ID_CHANNEL = 2200
        private const val EVENT_ID_RATE_LIMIT = 3000
    }
}