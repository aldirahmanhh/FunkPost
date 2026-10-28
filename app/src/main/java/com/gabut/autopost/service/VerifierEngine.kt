package com.gabut.autopost.service

import android.util.Log
import com.gabut.autopost.network.ClickResult
import com.gabut.autopost.network.DiscordGateway
import com.gabut.autopost.network.GatewayEvent
import com.gabut.autopost.network.GatewayMessage
import com.gabut.autopost.network.MessageTargeting
import com.gabut.autopost.network.VerificationButtons
import com.gabut.autopost.network.VerifierClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** What the UI and the log need to know about one verifier. */
sealed interface VerifierEvent {
    /** Every event belongs to exactly one verifier row. */
    val verifierId: Long

    data class Clicked(override val verifierId: Long, val label: String) : VerifierEvent

    data class SkippedForeign(override val verifierId: Long, val messageId: String) : VerifierEvent

    data class Unverifiable(override val verifierId: Long, val messageId: String) : VerifierEvent

    data class RateLimited(override val verifierId: Long, val retryAfterMs: Long) : VerifierEvent

    data class AuthFailed(override val verifierId: Long, val code: Int) : VerifierEvent

    /**
     * Discord refused the interaction. Kept separate from [AuthFailed] because the token is fine -
     * only this one button could not be pressed - and the owner needs to see the reason.
     */
    data class ClickFailed(
        override val verifierId: Long,
        val code: Int?,
        val detail: String?
    ) : VerifierEvent

    data class Disconnected(override val verifierId: Long, val reason: String) : VerifierEvent

    data class WaitingConfirm(
        override val verifierId: Long,
        val timeoutMs: Long
    ) : VerifierEvent
}

/**
 * The per-verifier state machine: connect, watch, click, reconnect.
 *
 * Ported from `auto-click.py:AutoLoginWorker`, keeping the parts that carry safety and dropping the
 * parts that only existed to fight a desktop event loop:
 *
 *  - **Ownership before action.** A button is only pressed when [MessageTargeting] proves the
 *    message belongs to this account. Skipped-foreign and skipped-unverifiable are logged
 *    separately, because only the second one means a configuration problem.
 *  - **History before waiting.** A panel that was already on screen when Start was pressed never
 *    produces a MESSAGE_CREATE, so every connect also reads the last few channel messages. The
 *    confirm watchdog does the same when "Yes, Log Me In" does not appear after Authenticate.
 *  - **Dedupe with a TTL.** The same button can arrive via MESSAGE_CREATE and MESSAGE_UPDATE, from
 *    the history fetch, or again from the watchdog, so `(messageId, customId)` is recorded once.
 *  - **Adaptive delay.** Grows on a 429 and decays on success, clamped to the configured bounds.
 *  - **Bounded reconnects.** Every attempt has an increasing backoff; a rejected token stops.
 */
class VerifierEngine(
    private val gateway: DiscordGateway,
    private val client: VerifierClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default
) {

    /** Everything the engine needs for one verifier, decrypted by the caller. */
    data class Config(
        val verifierId: Long,
        val token: String,
        val watchChannelId: String,
        val humanize: Boolean,
        val humanDelayMinMs: Long,
        val humanDelayMaxMs: Long,
        val baseDelayMs: Long,
        val minDelayMs: Long,
        val maxDelayMs: Long,
        val maxClickRetries: Int
    ) {
        companion object {
            /** Builds one Config from a stored verifier row and its plaintext user token. */
            fun from(entity: com.gabut.autopost.data.db.VerifierEntity, token: String): Config =
                Config(
                    verifierId = entity.id,
                    token = token,
                    watchChannelId = entity.watchChannelId,
                    humanize = entity.humanize,
                    humanDelayMinMs = entity.humanDelayMinMs,
                    humanDelayMaxMs = entity.humanDelayMaxMs,
                    baseDelayMs = entity.baseDelayMs,
                    minDelayMs = entity.minDelayMs,
                    maxDelayMs = entity.maxDelayMs,
                    maxClickRetries = entity.maxClickRetries
                )
        }
    }

    /** The live session facts a click needs; replaced on every Ready. */
    private data class LiveSession(val sessionId: String, val userId: String)

    /** A running verifier: its gateway loop plus the confirm watchdog. */
    private data class Running(val loop: Job, val watchdog: Job)

    private val events = MutableSharedFlow<VerifierEvent>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<VerifierEvent> = events

    private val mutex = Mutex()
    private val jobs = mutableMapOf<Long, Running>()

    /** Session facts by verifier id, so the watchdog can fetch without reaching into the loop. */
    private val live = ConcurrentHashMap<Long, LiveSession>()

    /** verifier id -> time an Authenticate click succeeded and the confirm button is awaited. */
    private val awaitingConfirm = ConcurrentHashMap<Long, Long>()

    /** Adaptive delay per verifier, so a 429 on one account does not slow another down. */
    private val delays = ConcurrentHashMap<Long, Long>()

    /**
     * Channel owning guild per verifier. Gateway frames carry it, REST history does not, and the
     * click payload needs it - without it Discord answers 400 "Unknown Integration".
     */
    private val guildIds = ConcurrentHashMap<Long, String>()

    /**
     * `"$messageId:$customId"` -> enqueue time. Prevents a double click when the same button
     * arrives twice; entries expire so a reusable panel can be clicked in a later round.
     */
    private val queued = ConcurrentHashMap<String, Long>()

    suspend fun isRunning(verifierId: Long): Boolean = mutex.withLock {
        jobs[verifierId]?.loop?.isActive == true
    }

    suspend fun runningIds(): Set<Long> = mutex.withLock {
        jobs.filterValues { it.loop.isActive }.keys.toSet()
    }

    /** Starts [config]'s loop unless one is already live. Idempotent. */
    suspend fun start(config: Config) {
        mutex.withLock {
            if (jobs[config.verifierId]?.loop?.isActive == true) return
            delays[config.verifierId] = config.baseDelayMs
            awaitingConfirm.remove(config.verifierId)
            // The watched channel may have been edited; resolve its guild again on this start.
            guildIds.remove(config.verifierId)
            jobs[config.verifierId] = Running(
                loop = launchLoop(config),
                watchdog = launchConfirmWatchdog(config)
            )
        }
    }

    suspend fun stop(verifierId: Long) {
        val running = mutex.withLock {
            jobs.remove(verifierId)?.also {
                delays.remove(verifierId)
                awaitingConfirm.remove(verifierId)
                live.remove(verifierId)
            }
        }
        running?.watchdog?.cancel()
        running?.loop?.cancel()
        running?.loop?.let { runCatching { it.join() } }
    }

    suspend fun stopAll() {
        val all = mutex.withLock {
            jobs.values.toList().also {
                jobs.clear()
                delays.clear()
                awaitingConfirm.clear()
                live.clear()
                queued.clear()
            }
        }
        all.forEach { it.watchdog.cancel() }
        all.forEach { it.loop.cancel() }
        all.forEach { runCatching { it.loop.join() } }
    }

    private fun launchLoop(config: Config): Job = scope.launch {
        var sessionId: String? = null
        var myUserId: String? = null
        // Messages that arrive between the socket opening and READY are replayed, not dropped.
        val pending = ArrayDeque<GatewayMessage>()
        var attempt = 0

        while (isActive) {
            var closeCode = 0
            var failureReason: String? = null

            try {
                gateway.connect(config.token).collect { event ->
                    when (event) {
                        is GatewayEvent.SocketOpen -> Unit

                        is GatewayEvent.Ready -> {
                            sessionId = event.sessionId
                            myUserId = event.userId
                            val session = LiveSession(event.sessionId, event.userId)
                            live[config.verifierId] = session
                            // A successful connect resets the backoff and clears stale dedupe state.
                            attempt = 0
                            prunedQueue()
                            Log.i(TAG, "verifier ${config.verifierId} ready as ${event.userId}")
                            events.emit(VerifierEvent.Disconnected(config.verifierId, "connected"))
                                .let { }

                            while (pending.isNotEmpty()) {
                                handleMessage(config, pending.removeFirst(), session)
                            }
                            // A panel already on screen produces no MESSAGE_CREATE; read history
                            // once per connect so pressing Start is enough to catch it.
                            launch { refreshRecent(config, session) }
                        }

                        is GatewayEvent.Message -> {
                            val session = live[config.verifierId]
                            if (session == null) {
                                if (pending.size >= PENDING_LIMIT) pending.removeFirst()
                                pending.addLast(event.message)
                            } else {
                                handleMessage(config, event.message, session)
                            }
                        }

                        is GatewayEvent.Closed -> closeCode = event.code

                        is GatewayEvent.Failed -> failureReason = event.reason
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: Exception) {
                Log.w(TAG, "verifier ${config.verifierId} loop error: ${unexpected.message}")
                failureReason = unexpected.message
            }

            live.remove(config.verifierId)

            if (!isActive) break

            // 4004: the token itself is gone. Reconnecting only adds to a ban budget.
            if (closeCode == CLOSE_AUTH_FAILED) {
                events.emit(VerifierEvent.AuthFailed(config.verifierId, closeCode))
                return@launch
            }

            attempt += 1
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                events.emit(
                    VerifierEvent.Disconnected(
                        config.verifierId,
                        failureReason ?: "gave up after $MAX_RECONNECT_ATTEMPTS attempts"
                    )
                )
                return@launch
            }

            val backoff = backoffFor(attempt)
            events.emit(VerifierEvent.Disconnected(config.verifierId, "reconnecting"))
            sleep(backoff)
        }
    }

    /**
     * Warns once when "Authenticate" was accepted but no "Yes, Log Me In" showed up, then reads
     * the channel history again - the confirm button is sometimes revealed by an edit.
     */
    private fun launchConfirmWatchdog(config: Config): Job = scope.launch {
        while (isActive) {
            delay(WATCHDOG_TICK_MS)
            val since = awaitingConfirm[config.verifierId] ?: continue
            if (clock() - since < CONFIRM_TIMEOUT_MS) continue
            awaitingConfirm.remove(config.verifierId)
            events.emit(VerifierEvent.WaitingConfirm(config.verifierId, CONFIRM_TIMEOUT_MS))
            live[config.verifierId]?.let { refreshRecent(config, it) }
        }
    }

    /** Reads the last few channel messages and hands them to the same path as gateway events. */
    private suspend fun refreshRecent(config: Config, session: LiveSession) {
        // Resolve the guild before processing history: those messages do not carry guild_id.
        if (guildIds[config.verifierId] == null) {
            client.fetchChannelGuild(config.token, config.watchChannelId)
                ?.let { guildIds[config.verifierId] = it }
        }
        val messages = client.fetchRecentMessages(config.token, config.watchChannelId)
        if (messages.isEmpty()) return
        Log.i(TAG, "verifier ${config.verifierId} checking ${messages.size} recent message(s)")
        for (index in messages.indices.reversed()) {
            handleMessage(config, messages[index], session)
        }
    }

    private suspend fun handleMessage(
        config: Config,
        message: GatewayMessage,
        session: LiveSession
    ) {
        if (message.channelId != config.watchChannelId) return
        message.guildId?.let { guildIds[config.verifierId] = it }

        val decision = MessageTargeting.decide(message, session.userId)
        val buttons = MessageTargeting.actionableButtons(message)
        Log.i(
            TAG,
            "verifier ${config.verifierId} msg ${message.id} flags=${message.flags} " +
                "decision=$decision buttons=${buttons.map { it.label }} " +
                "app=${message.applicationId} author=${message.authorId} " +
                "guild=${message.guildId != null} interactionUser=${message.interactionUserId} " +
                "mentions=${message.mentionedUserIds}"
        )

        when (decision) {
            MessageTargeting.Decision.NOT_MINE -> return

            MessageTargeting.Decision.UNVERIFIABLE -> {
                events.emit(VerifierEvent.Unverifiable(config.verifierId, message.id))
                return
            }

            MessageTargeting.Decision.MINE -> Unit
        }

        if (buttons.isEmpty()) return

        for (button in buttons) {
            if (!markQueued(message.id, button.customId)) continue

            // The success event is emitted by click(), and only the confirm press counts as a
            // completed verification, so a two-button flow adds one to the counter, not two.
            click(config, message, button, session.sessionId)
        }
    }

    /**
     * Presses one button, honouring the configured delays and retrying only what can succeed.
     *
     * Returns true when Discord accepted the interaction.
     */
    private suspend fun click(
        config: Config,
        message: GatewayMessage,
        button: com.gabut.autopost.network.GatewayButton,
        sessionId: String
    ): Boolean {
        val pacing = delayFor(config)
        sleep(pacing)

        if (config.humanize) {
            sleep(randomHumanDelay(config))
        }

        var attempt = 1
        val attempts = config.maxClickRetries.coerceAtLeast(1)

        while (attempt <= attempts) {
            if (!isActive()) return false

            val result = try {
                client.clickButton(
                    token = config.token,
                    sessionId = sessionId,
                    // History-sourced messages have no guild_id; the cached one is required or
                    // Discord rejects the interaction as an unknown integration.
                    guildId = message.guildId ?: guildIds[config.verifierId],
                    channelId = message.channelId,
                    messageId = message.id,
                    // The reference falls back to the author id, which is the application for
                    // most verify bots; Discord rejects the interaction without it.
                    applicationId = message.applicationId ?: message.authorId,
                    customId = button.customId,
                    messageFlags = message.flags
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: Exception) {
                ClickResult.NetworkError
            }

            when (result) {
                is ClickResult.Success -> {
                    relaxDelay(config)
                    if (button.label == VerificationButtons.AUTHENTICATE) {
                        awaitingConfirm[config.verifierId] = clock()
                    } else if (button.label == VerificationButtons.CONFIRM) {
                        awaitingConfirm.remove(config.verifierId)
                        // Only the confirm press means the account really logged in.
                        events.emit(VerifierEvent.Clicked(config.verifierId, button.label))
                    }
                    Log.i(TAG, "verifier ${config.verifierId} clicked '${button.label}'")
                    return true
                }

                is ClickResult.RateLimited -> {
                    // Grow the steady-state delay past the limit so the next click is late, not early.
                    tightenDelay(config, result.retryAfterMs)
                    events.emit(VerifierEvent.RateLimited(config.verifierId, result.retryAfterMs))
                    if (attempt == attempts) return false
                    sleep(result.retryAfterMs + randomJitterMs())
                    attempt += 1
                }

                // Expired/foreign button: the retry would fail identically.
                is ClickResult.Permanent -> {
                    Log.w(
                        TAG,
                        "verifier ${config.verifierId} click refused ${result.code}: ${result.message}"
                    )
                    events.emit(
                        VerifierEvent.ClickFailed(
                            config.verifierId,
                            result.code,
                            result.message ?: "Discord refused the interaction"
                        )
                    )
                    return false
                }

                is ClickResult.NetworkError, is ClickResult.Other -> {
                    tightenDelay(config, null)
                    if (attempt == attempts) {
                        val code = (result as? ClickResult.Other)?.code
                        val detail = (result as? ClickResult.Other)?.message
                            ?: "No response from Discord"
                        Log.w(TAG, "verifier ${config.verifierId} click failed after $attempts try: $code $detail")
                        events.emit(
                            VerifierEvent.ClickFailed(config.verifierId, code, detail)
                        )
                        return false
                    }
                    sleep(NETWORK_RETRY_MS * attempt)
                    attempt += 1
                }
            }
        }
        return false
    }

    private fun isActive(): Boolean = scope.coroutineContext[Job]?.isActive != false

    // ---- pacing ---------------------------------------------------------

    private fun delayFor(config: Config): Long = delays[config.verifierId]
        ?.coerceIn(config.minDelayMs, config.maxDelayMs)
        ?: config.baseDelayMs

    /** Grows the adaptive delay: past the requested wait when known, otherwise by 1.5x. */
    private fun tightenDelay(config: Config, retryAfterMs: Long?) {
        val current = delayFor(config)
        val target = retryAfterMs?.let { it * 3 / 2 } ?: (current * 3 / 2)
        delays[config.verifierId] =
            target.coerceIn(config.minDelayMs, config.maxDelayMs)
    }

    /** Decays the adaptive delay after a success, never below the configured base. */
    private fun relaxDelay(config: Config) {
        val current = delayFor(config)
        delays[config.verifierId] =
            (current * 9 / 10).coerceIn(config.minDelayMs, config.maxDelayMs)
    }

    private fun randomHumanDelay(config: Config): Long {
        val min = config.humanDelayMinMs.coerceAtLeast(0L)
        val max = config.humanDelayMaxMs.coerceAtLeast(min)
        return if (max == min) min else min + random.nextLong(max - min)
    }

    private fun randomJitterMs(): Long = random.nextLong(200L, 800L)

    private fun backoffFor(attempt: Int): Long {
        val exponential = RECONNECT_BASE_MS shl (attempt - 1).coerceAtMost(5)
        return exponential.coerceAtMost(MAX_RECONNECT_MS)
    }

    // ---- dedupe ---------------------------------------------------------

    /**
     * Records `(messageId, customId)` unless the same pair was seen within [DEDUPE_TTL_MS].
     *
     * Returns true when the caller may proceed, false when this is a duplicate.
     */
    private fun markQueued(messageId: String, customId: String): Boolean {
        val now = clock()
        val key = "$messageId:$customId"
        val seen = queued[key]
        if (seen != null && now - seen < DEDUPE_TTL_MS) return false
        queued[key] = now
        return true
    }

    private fun prunedQueue() {
        val now = clock()
        queued.entries.removeAll { now - it.value >= DEDUPE_TTL_MS }
    }

    private companion object {
        const val TAG = "VerifierEngine"
        const val CLOSE_AUTH_FAILED = 4004
        const val MAX_RECONNECT_ATTEMPTS = 10
        const val RECONNECT_BASE_MS = 5_000L
        const val MAX_RECONNECT_MS = 120_000L
        const val NETWORK_RETRY_MS = 1_500L
        /** Same window as the reference: long enough to cover a CREATE+UPDATE pair of one panel. */
        const val DEDUPE_TTL_MS = 120_000L
        /** How long "Yes, Log Me In" may take before the watchdog re-reads the channel. */
        const val CONFIRM_TIMEOUT_MS = 30_000L
        const val WATCHDOG_TICK_MS = 5_000L
        const val PENDING_LIMIT = 50
    }
}
