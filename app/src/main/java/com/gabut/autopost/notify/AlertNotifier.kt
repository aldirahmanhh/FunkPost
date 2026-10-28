package com.gabut.autopost.notify

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import com.gabut.autopost.ui.i18n.t
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
            title = t("Bot token rejected", "Token bot ditolak"),
            body = t(
                "Discord rejected this bot token. Posting is paused until you fix it.",
                "Discord menolak token bot ini. Posting dijeda sampai kamu memperbaikinya."
            )
        )
    }

    fun notifyTokenUnreadable(tokenId: Long) {
        publish(
            key = "unreadable:$tokenId",
            id = ALERT_ID_UNREADABLE + tokenId.toInt(),
            title = t("Token needs re-entry", "Token perlu dimasukkan ulang"),
            body = t(
                "This token could not be decrypted. Enter it again in Settings.",
                "Token ini tidak bisa didekripsi. Masukkan lagi di Setelan."
            )
        )
    }

    fun notifyChannelFailing(tokenId: Long, channelId: String, consecutiveFailures: Int) {
        if (consecutiveFailures < FAILURE_THRESHOLD) return
        publish(
            key = "failing:$tokenId:$channelId",
            id = ALERT_ID_CHANNEL + channelId.hashCode(),
            title = t("Channel keeps failing", "Channel terus gagal"),
            body = t(
                "Channel $channelId has failed $consecutiveFailures times in a row.",
                "Channel $channelId gagal $consecutiveFailures kali berturut-turut."
            )
        )
    }

    fun notifyVerifierAuthFailed(verifierId: Long) {
        publish(
            key = "verifier_auth:$verifierId",
            id = ALERT_ID_VERIFIER_AUTH + verifierId.toInt(),
            title = t("Verifier account rejected", "Akun verifier ditolak"),
            body = t(
                "Discord rejected this account's token. Auto-verify is paused until you fix it.",
                "Discord menolak token akun ini. Verifikasi otomatis dijeda sampai kamu perbaiki."
            )
        )
    }

    fun notifyVerifierUnreadable(verifierId: Long, name: String) {
        publish(
            key = "verifier_unreadable:$verifierId",
            id = ALERT_ID_VERIFIER_UNREADABLE + verifierId.toInt(),
            title = t("$name needs its token again", "$name perlu token lagi"),
            body = t(
                "This verifier's token could not be decrypted. Enter it again in Settings.",
                "Token verifier ini tidak bisa didekripsi. Masukkan lagi di Setelan."
            )
        )
    }

    fun notifyLongRateLimit(tokenId: Long, channelId: String, retryAfterMs: Long) {
        if (retryAfterMs < LONG_RATE_LIMIT_MS) return
        val seconds = retryAfterMs / 1000
        val key = "ratelimit:$tokenId:$channelId"
        if (!shouldPublish(key)) return

        val notification = Notifications.eventNotification(
            context,
            t("Discord rate limit", "Rate limit Discord"),
            t(
                "Channel $channelId is waiting ${seconds}s before the next post.",
                "Channel $channelId menunggu ${seconds}dtk sebelum posting berikutnya."
            ),
            EVENT_ID_RATE_LIMIT + channelId.hashCode()
        )
        manager?.notify(EVENT_ID_RATE_LIMIT + channelId.hashCode(), notification)
    }

    private fun publish(key: String, id: Int, title: String, body: String) {
        if (!shouldPublish(key)) return

        if (!notificationsEnabled()) {
            _banner.value = body
            return
        }
        val notification = Notifications.alertsNotification(
            context = context,
            title = title,
            body = body,
            tokenId = null,
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
        private const val ALERT_ID_VERIFIER_AUTH = 3000
        private const val ALERT_ID_VERIFIER_UNREADABLE = 3100
        private const val EVENT_ID_RATE_LIMIT = 4000
    }
}