package com.gabut.autopost.service

import android.content.Context
import androidx.core.content.ContextCompat
import com.gabut.autopost.AutoPostApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The one place the UI may ask for posting to start or stop (todo 8).
 *
 * Nothing else is allowed to start the service: not `Application.onCreate`, not a broadcast
 * receiver, not a "restore" path. That keeps the D17 rule ("no token ever resumes by itself")
 * structurally true instead of relying on discipline.
 */
class PostingController(private val context: Context) {

    private val app: AutoPostApplication
        get() = context.applicationContext as AutoPostApplication

    /** Token ids whose `running` flag is set, straight from Room, so UI and service agree. */
    val runningTokens: Flow<Set<Long>> =
        app.repository.observeTokens().map { tokens ->
            tokens.asSequence().filter { it.running }.map { it.id }.toSet()
        }

    /**
     * Starts [tokenId]. Safe to call twice: the repository flag is idempotent and
     * [ChannelScheduler.start] refuses to launch a second loop for a channel that is already up.
     */
    suspend fun start(tokenId: Long) {
        val token = app.repository.getToken(tokenId) ?: return
        if (app.cipher.decrypt(token.encryptedToken) == null) {
            app.repository.updateRunning(tokenId, false)
            app.notifier.notifyTokenUnreadable(tokenId)
            return
        }

        app.repository.updateRunning(tokenId, true)
        ContextCompat.startForegroundService(context, PostingService.startIntent(context, tokenId))
    }

    suspend fun stop(tokenId: Long) {
        app.repository.updateRunning(tokenId, false)
        ContextCompat.startForegroundService(context, PostingService.stopIntent(context, tokenId))
    }

    suspend fun stopAll() {
        app.repository.resetAllRunning()
        ContextCompat.startForegroundService(context, PostingService.stopAllIntent(context))
    }
}