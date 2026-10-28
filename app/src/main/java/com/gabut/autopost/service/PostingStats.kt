package com.gabut.autopost.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory counters for the current process only (D20).
 *
 * Nothing here is persisted: "sent this session" must reset when the process dies, otherwise the
 * number becomes another silent lie after a crash.
 */
object PostingStats {
    private val sent = AtomicInteger(0)
    private val running = MutableStateFlow(0)
    private val channels = MutableStateFlow(0)

    val sentCount: Int get() = sent.get()
    val runningTokens: StateFlow<Int> = running
    val channelCount: StateFlow<Int> = channels

    fun recordSent() {
        sent.incrementAndGet()
    }

    fun updateSummary(runningTokens: Int, channelCount: Int) {
        running.value = runningTokens
        channels.value = channelCount
    }

    fun reset() {
        sent.set(0)
        running.value = 0
        channels.value = 0
    }
}