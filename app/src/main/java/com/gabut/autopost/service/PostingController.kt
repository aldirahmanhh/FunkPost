package com.gabut.autopost.service

import android.content.Context
import androidx.core.content.ContextCompat
import com.gabut.autopost.AutoPostApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The one place the UI may ask for posting or verification to start or stop (todo 8).
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
        // A STOP intent is not a reason to (re)launch the service: with nothing live, the stop
        // would race Android's startForeground() deadline and crash the app. Plain startService
        // is enough, and it is safe because this only runs when a loop is actually alive.
        if (!app.hasLiveWork()) return
        context.startService(PostingService.stopIntent(context, tokenId))
    }

    suspend fun stopAll() {
        app.repository.resetAllRunning()
        if (!app.hasLiveWork()) return
        context.startService(PostingService.stopAllIntent(context))
    }

    /**
     * Starts verifying [verifierId]. Safe to call twice.
     *
     * The controller only flips the row and hands the intent to the service; the service owns the
     * engine call so the loop and the foreground notification can never disagree about whether
     * something is running.
     */
    suspend fun verifyStart(verifierId: Long) {
        val ent = app.repository.getVerifier(verifierId) ?: return
        if (app.cipher.decrypt(ent.encryptedToken) == null) {
            app.repository.updateVerifierRunning(verifierId, false)
            app.notifier.notifyVerifierUnreadable(verifierId, ent.name)
            return
        }
        app.repository.updateVerifierRunning(verifierId, true)
        ContextCompat.startForegroundService(
            context,
            PostingService.startVerifyIntent(context, verifierId)
        )
    }

    suspend fun verifyStop(verifierId: Long) {
        app.repository.updateVerifierRunning(verifierId, false)
        if (!app.hasLiveWork()) return
        context.startService(PostingService.stopVerifyIntent(context, verifierId))
    }
}