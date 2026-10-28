package com.gabut.autopost

import android.app.Application
import com.gabut.autopost.crypto.SecretCipher
import com.gabut.autopost.crypto.TinkSecretCipher
import com.gabut.autopost.data.ColdStartReset
import com.gabut.autopost.data.PostLogRecorder
import com.gabut.autopost.data.PostRepository
import com.gabut.autopost.data.SettingsStore
import com.gabut.autopost.data.db.AutoPostDatabase
import com.gabut.autopost.data.db.VerifierDao
import com.gabut.autopost.network.DiscordClient
import com.gabut.autopost.network.DiscordGateway
import com.gabut.autopost.network.VerifierClient
import com.gabut.autopost.notify.AlertNotifier
import com.gabut.autopost.notify.Notifications
import com.gabut.autopost.service.ChannelScheduler
import com.gabut.autopost.service.PostingController
import com.gabut.autopost.service.PostingStats
import com.gabut.autopost.service.VerifierEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Composition root. Everything is created here and handed out by property, so no screen or service
 * has to know how its collaborators are built (plain constructor injection - no DI framework).
 */
class AutoPostApplication : Application() {

    /** Outlives any Activity; the scheduler's jobs live here so they survive UI churn. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AutoPostDatabase by lazy { AutoPostDatabase.get(this) }

    val repository: PostRepository by lazy {
        PostRepository(
            botTokenDao = database.botTokenDao(),
            channelDao = database.channelDao(),
            postLogDao = database.postLogDao(),
            verifierDao = database.verifierDao()
        )
    }

    val settingsStore: SettingsStore by lazy { SettingsStore(this) }

    val cipher: SecretCipher by lazy { TinkSecretCipher.build(this) }

    val client: DiscordClient by lazy { DiscordClient() }

    val notifier: AlertNotifier by lazy { AlertNotifier(this) }

    val scheduler: ChannelScheduler by lazy {
        ChannelScheduler(
            repository = repository,
            cipher = cipher,
            client = client,
            scope = applicationScope,
            onPosted = { PostingStats.recordSent() }
        )
    }

    val verifierDao: VerifierDao by lazy { database.verifierDao() }

    /** Outlives any Activity; each verifier keeps its own gateway socket alive here. */
    val gateway: DiscordGateway by lazy { DiscordGateway() }

    val verifierClient: VerifierClient by lazy { VerifierClient() }

    val verifierEngine: VerifierEngine by lazy {
        VerifierEngine(gateway, verifierClient, applicationScope)
    }

    val controller: PostingController by lazy { PostingController(this) }

    val logRecorder: PostLogRecorder by lazy {
        PostLogRecorder(
            repository = repository,
            events = scheduler.eventFlow,
            verifierEvents = verifierEngine.eventFlow
        )
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // D17: reset running flags exactly once per process start. Never starts the service.
        applicationScope.launch { ColdStartReset.resetAll(repository) }
    }

    /** Used by the service to decide whether it still has work before calling `stopSelf()`. */
    suspend fun schedulerAnyRunning(): Boolean = scheduler.runningTokenIds().isNotEmpty()

    /** Same question for auto-verifiers: the service must outlive whichever work is still live. */
    suspend fun verifierAnyRunning(): Boolean = verifierEngine.runningIds().isNotEmpty()

    /**
     * True when at least one posting loop or verifier is alive in this process.
     *
     * Stopping work is only worth an Intent when there is something to stop: starting the service
     * just to hand it a STOP action misses Android's `startForeground()` deadline and kills the app
     * (the delete-a-bot crash on Android 15).
     */
    suspend fun hasLiveWork(): Boolean = schedulerAnyRunning() || verifierAnyRunning()
}