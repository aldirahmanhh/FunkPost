package com.gabut.autopost.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.gabut.autopost.MainActivity
import com.gabut.autopost.R
import com.gabut.autopost.service.PostingService
import com.gabut.autopost.ui.i18n.appLocale
import com.gabut.autopost.ui.i18n.localizedContext
import com.gabut.autopost.ui.i18n.t

/**
 * Every notification this app can post, in one place, so channel ids and intents cannot drift
 * between the service and the alert layer.
 */
object Notifications {

    const val CHANNEL_POSTING = "posting"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_EVENTS = "events"

    const val POSTING_NOTIFICATION_ID = 1001

    private const val PREFS = "notification_channels"
    private const val KEY_CHANNEL_LANGUAGE = "channel_language"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Channel names are localised resources. Android keeps the first name it was given, so the
        // channels are deleted and recreated whenever the app language changed since last run.
        val wantedLanguage = appLocale().language
        val storedLanguage = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CHANNEL_LANGUAGE, null)
        if (storedLanguage != null && storedLanguage != wantedLanguage) {
            manager.deleteNotificationChannel(CHANNEL_POSTING)
            manager.deleteNotificationChannel(CHANNEL_ALERTS)
            manager.deleteNotificationChannel(CHANNEL_EVENTS)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHANNEL_LANGUAGE, wantedLanguage)
            .apply()

        val localized = localizedContext(context)

        // The persistent service notification (D1). Low importance: visible, never noisy.
        val posting = NotificationChannel(
            CHANNEL_POSTING,
            localized.getString(R.string.channel_posting_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = localized.getString(R.string.channel_posting_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

        // Problems that need the owner's hands (D18): high importance, alert once.
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            localized.getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = localized.getString(R.string.channel_alerts_description)
            enableVibration(true)
        }

        // Routine events (a long rate limit, a recovered channel): quiet.
        val events = NotificationChannel(
            CHANNEL_EVENTS,
            localized.getString(R.string.channel_events_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = localized.getString(R.string.channel_events_description)
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(posting, alerts, events))
    }

    fun postingNotification(context: Context, text: String, paused: Boolean = false): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopAll = PendingIntent.getService(
            context,
            1,
            Intent(context, PostingService::class.java).setAction(PostingService.ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_POSTING)
            .setSmallIcon(R.drawable.ic_stat_posting)
            .setContentTitle(localizedContext(context).getString(R.string.notification_posting_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(!paused)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (!paused) {
            builder.addAction(
                0,
                localizedContext(context).getString(R.string.notification_stop_all),
                stopAll
            )
        }
        return builder.build()
    }

    /** `Posting - 2 bots - 7 channels - 134 sent` (D20). */
    fun postingText(
        runningTokens: Int,
        channels: Int,
        verifiers: Int = 0,
        sent: Int
    ): String =
        buildString {
            append(
                t(
                    "Posting - $runningTokens bots - $channels channels",
                    "Memposting - $runningTokens bot - $channels channel"
                )
            )
            if (verifiers > 0) {
                append(t(" - $verifiers verifying", " - $verifiers memverifikasi"))
            }
            append(t(" - $sent sent", " - $sent terkirim"))
        }

    /** Same counts, without the word "Posting", so the top bar and the notification agree. */
    fun summaryText(runningTokens: Int, channels: Int): String =
        t(
            "$runningTokens bots running \u00b7 $channels channels",
            "$runningTokens bot jalan \u00b7 $channels channel"
        )

    fun pausedText(): String = t(
        "Paused by Android - posting stopped",
        "Dijeda oleh Android - posting berhenti"
    )

    fun alertsNotification(
        context: Context,
        title: String,
        body: String,
        tokenId: Long?,
        requestCode: Int
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_SETTINGS_TOKEN_ID, tokenId ?: -1L)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
    }

    fun eventNotification(context: Context, title: String, body: String, id: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_posting)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}