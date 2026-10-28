package com.gabut.autopost.service

import com.gabut.autopost.crypto.SecretCipher
import com.gabut.autopost.data.PostRepository
import com.gabut.autopost.network.DiscordClient
import com.gabut.autopost.network.SendResult
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

/**
 * One coroutine per (token, channel) pair, each an independent timer.
 *
 * Why not WorkManager: a repeating worker is scheduled by the OS with a 15-minute floor and no
 * sub-minute guarantee, which cannot express "every 1 second" (D2/D16). A foreground service that
 * holds plain coroutines is the only way to honour the reference's cadence.
 *
 * Cancellation contract (D19): cancelling a job prevents *new* requests; a request already in
 * flight may still complete and record its status. That is deliberate - aborting an HTTP call
 * mid-flight cannot un-send the message.
 */
class ChannelScheduler(
    private val repository: PostRepository,
    private val cipher: SecretCipher,
    private val client: DiscordClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPosted: () -> Unit = {}
) {

    private val events = MutableSharedFlow<PostingEvent>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<PostingEvent> = events

    private val mutex = Mutex()

    /** tokenId -> (channelId -> job) */
    private val jobs = mutableMapOf<Long, MutableMap<String, Job>>()

    suspend fun isRunning(tokenId: Long): Boolean = mutex.withLock {
        jobs[tokenId]?.values?.any { it.isActive } == true
    }

    suspend fun runningTokenIds(): Set<Long> = mutex.withLock {
        jobs.filterValues { perToken -> perToken.values.any { it.isActive } }.keys.toSet()
    }

    /** Starts every channel of [tokenId] that is not already running. Idempotent per channel. */
    suspend fun start(tokenId: Long) {
        val token = repository.getToken(tokenId) ?: return
        val plaintext = cipher.decrypt(token.encryptedToken)
        if (plaintext == null) {
            events.emit(PostingEvent.AuthFailed(tokenId, 0))
            return
        }
        val channels = repository.getChannelsOnce(tokenId)

        mutex.withLock {
            val perToken = jobs.getOrPut(tokenId) { mutableMapOf() }
            channels.forEach { channel ->
                val existing = perToken[channel.channelId]
                if (existing == null || !existing.isActive) {
                    perToken[channel.channelId] = launchLoop(tokenId, plaintext, channel.channelId)
                }
            }
        }
    }

    /** Cancels every channel of [tokenId] and waits for the cancellations to land. */
    suspend fun stop(tokenId: Long) {
        val cancelled = mutex.withLock { jobs.remove(tokenId)?.values?.toList().orEmpty() }
        cancelled.forEach { it.cancel() }
        cancelled.forEach { runCatching { it.join() } }
    }

    suspend fun stopAll() {
        val cancelled = mutex.withLock {
            jobs.values.flatMap { it.values }.toList().also { jobs.clear() }
        }
        cancelled.forEach { it.cancel() }
        cancelled.forEach { runCatching { it.join() } }
    }

    /**
     * D16: an interval edit takes effect immediately, without the user pressing Stop then Start.
     *
     * The old job is cancelled and a fresh loop is launched, so the next send happens one *new*
     * interval from now. Cancellation cannot abort an in-flight request (D19).
     */
    suspend fun rearm(tokenId: Long, channelId: String) {
        val cancelled = mutex.withLock {
            jobs[tokenId]?.remove(channelId)
        }
        cancelled?.cancel()
        cancelled?.let { runCatching { it.join() } }

        val token = repository.getToken(tokenId) ?: return
        val plaintext = cipher.decrypt(token.encryptedToken) ?: return
        mutex.withLock {
            jobs.getOrPut(tokenId) { mutableMapOf() }[channelId] =
                launchLoop(tokenId, plaintext, channelId)
        }
    }

    private fun launchLoop(
        tokenId: Long,
        plaintextToken: String,
        channelId: String
    ): Job = scope.launch {
        var backoffMs = INITIAL_BACKOFF_MS
        var consecutiveFailures = 0

        while (isActive) {
            // Re-read every iteration on purpose: the reference re-reads `ch["interval"]` each
            // pass (`autopost.py:99-128`), and this is also what makes live edits work even if a
            // rearm is missed.
            val channel = repository.getChannel(tokenId, channelId) ?: break
            val intervalMs = channel.intervalSeconds.coerceAtLeast(1) * 1_000L

            val result = try {
                client.sendMessage(plaintextToken, channel.channelId, channel.message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: Exception) {
                SendResult.NetworkError
            }

            val waitMs: Long = when (result) {
                is SendResult.Success -> {
                    consecutiveFailures = 0
                    backoffMs = INITIAL_BACKOFF_MS
                    onPosted()
                    val at = clock()
                    val next = maxOf(at + intervalMs, result.nextAllowedAtMs ?: 0L)
                    repository.updateChannelStatus(tokenId, channelId, result.code, at, next)
                    events.emit(PostingEvent.Posted(tokenId, channelId, result.code, at))
                    next - at
                }

                is SendResult.RateLimited -> {
                    val wait = result.retryAfterMs + intervalMs
                    repository.updateChannelStatus(
                        tokenId,
                        channelId,
                        429,
                        null,
                        clock() + wait
                    )
                    events.emit(
                        PostingEvent.RateLimited(
                            tokenId,
                            channelId,
                            result.retryAfterMs,
                            result.isGlobal
                        )
                    )
                    wait
                }

                is SendResult.AuthFailed -> {
                    repository.updateChannelStatus(tokenId, channelId, result.code, null, null)
                    events.emit(PostingEvent.AuthFailed(tokenId, result.code))
                    return@launch
                }

                SendResult.NetworkError -> {
                    consecutiveFailures += 1
                    val wait = backoffMs
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    repository.updateChannelStatus(tokenId, channelId, null, null, clock() + wait)
                    events.emit(
                        PostingEvent.ChannelFailing(tokenId, channelId, consecutiveFailures)
                    )
                    wait
                }

                is SendResult.Other -> {
                    consecutiveFailures += 1
                    val wait = backoffMs
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    repository.updateChannelStatus(
                        tokenId,
                        channelId,
                        result.code,
                        null,
                        clock() + wait
                    )
                    events.emit(
                        PostingEvent.ChannelFailing(tokenId, channelId, consecutiveFailures)
                    )
                    wait
                }
            }

            delay(waitMs.coerceAtLeast(1L))
        }
    }

    companion object {
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}