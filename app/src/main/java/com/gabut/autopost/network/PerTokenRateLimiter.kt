package com.gabut.autopost.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client-side token bucket per bot token (D13).
 *
 * Discord allows roughly 50 requests/second per bot, and every request a bot sends is charged to
 * both the per-route and the global bucket. Firing 50 channels once a second would sit exactly on
 * that ceiling, so this limiter caps each token at [capacity] requests per second and makes the
 * caller wait instead of letting Discord answer 429.
 *
 * One instance is shared by every channel of every token; the map key keeps the buckets separate.
 */
class PerTokenRateLimiter(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val refillPerSecond: Double = DEFAULT_REFILL_PER_SECOND,
    private val now: () -> Long = System::currentTimeMillis
) {

    private class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val mutex = Mutex()
    private val buckets = HashMap<String, Bucket>()

    /**
     * Suspends until [key] may send one request.
     *
     * The wait is computed from the real clock, so a permit can never be granted twice for the
     * same slot no matter how many coroutines race for it.
     */
    suspend fun acquire(key: String) {
        while (true) {
            val waitMs = mutex.withLock {
                val bucket = buckets.getOrPut(key) { Bucket(capacity.toDouble(), now()) }
                refill(bucket)
                if (bucket.tokens >= 1.0) {
                    bucket.tokens -= 1.0
                    0L
                } else {
                    val missing = 1.0 - bucket.tokens
                    val millis = (missing / refillPerSecond * 1000.0).toLong()
                    millis.coerceAtLeast(1L)
                }
            }
            if (waitMs == 0L) return
            delay(waitMs)
        }
    }

    private fun refill(bucket: Bucket) {
        val current = now()
        val elapsedMs = current - bucket.lastRefillMs
        if (elapsedMs <= 0L) return
        bucket.tokens = (bucket.tokens + elapsedMs / 1000.0 * refillPerSecond)
            .coerceAtMost(capacity.toDouble())
        bucket.lastRefillMs = current
    }

    companion object {
        const val DEFAULT_CAPACITY = 5
        const val DEFAULT_REFILL_PER_SECOND = 5.0
    }
}