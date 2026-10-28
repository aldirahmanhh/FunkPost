package com.gabut.autopost.service

/** Facts the service/UI react to. Emitted by the scheduler, never by the network client. */
sealed interface PostingEvent {

    data class Posted(
        val tokenId: Long,
        val channelId: String,
        val code: Int,
        val at: Long
    ) : PostingEvent

    data class RateLimited(
        val tokenId: Long,
        val channelId: String,
        val retryAfterMs: Long,
        val isGlobal: Boolean
    ) : PostingEvent

    /** The token is rejected; every channel of it has already stopped (D18). */
    data class AuthFailed(val tokenId: Long, val code: Int) : PostingEvent

    /** One channel keeps failing transport-wise; the owner may want an alert. */
    data class ChannelFailing(
        val tokenId: Long,
        val channelId: String,
        val consecutiveFailures: Int
    ) : PostingEvent
}