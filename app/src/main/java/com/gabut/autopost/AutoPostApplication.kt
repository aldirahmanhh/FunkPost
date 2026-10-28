package com.gabut.autopost

import android.app.Application
import com.gabut.autopost.crypto.SecretCipher
import com.gabut.autopost.crypto.TinkSecretCipher
import com.gabut.autopost.data.ColdStartReset
import com.gabut.autopost.data.PostRepository
import com.gabut.autopost.data.SettingsStore
import com.gabut.autopost.data.db.AutoPostDatabase
import com.gabut.autopost.network.DiscordClient
import com.gabut.autopost.notify.AlertNotifier
import com.gabut.autopost.notify.Notifications
import com.gabut.autopost.service.ChannelScheduler
import com.gabut.autopost.service.PostingController
import com.gabut.autopost.service.PostingStats
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
        PostRepository(database.botTokenDao(), database.channelDao())
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

    val controller: PostingController by lazy { PostingController(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // D17: reset running flags exactly once per process start. Never starts the service.
        applicationScope.launch { ColdStartReset.resetAll(repository) }
    }

    /** Used by the service to decide whether it still has work before calling `stopSelf()`. */
    suspend fun schedulerAnyRunning(): Boolean = scheduler.runningTokenIds().isNotEmpty()
}