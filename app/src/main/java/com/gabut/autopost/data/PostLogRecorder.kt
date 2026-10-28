package com.gabut.autopost.data

import com.gabut.autopost.data.db.PostLogEntity
import com.gabut.autopost.service.PostingEvent
import com.gabut.autopost.service.VerifierEvent
import com.gabut.autopost.ui.i18n.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

object PostLogEventType {
    const val POSTED = "posted"
    const val RATE_LIMITED = "rate_limited"
    const val AUTH_FAILED = "auth_failed"
    const val CHANNEL_FAILING = "channel_failing"
    const val VERIFIED = "verified"
    const val VERIFY_SKIPPED = "verify_skipped"
    const val VERIFY_FAILED = "verify_failed"
}

/**
 * Appends scheduler and verifier outcomes to the activity log.
 *
 * Two event streams feed one table on purpose: the owner wants one chronological answer to "what
 * did the app do", not a per-feature history they have to cross-reference.
 */
class PostLogRecorder(
    private val repository: PostRepository,
    private val events: SharedFlow<PostingEvent>,
    private val verifierEvents: SharedFlow<VerifierEvent>? = null,
    private val now: () -> Long = System::currentTimeMillis
) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            events.collect { event -> repository.appendLog(event.toLog()) }
        }
        val verifiers = verifierEvents ?: return
        scope.launch {
            verifiers.collect { event ->
                event.toLog()?.let { repository.appendLog(it) }
            }
        }
    }

    private suspend fun PostingEvent.toLog(): PostLogEntity = when (this) {
        is PostingEvent.Posted -> entry(
            tokenId = tokenId,
            channelId = channelId,
            eventType = PostLogEventType.POSTED,
            summary = t("Message posted", "Pesan terkirim"),
            statusCode = code
        )

        is PostingEvent.RateLimited -> entry(
            tokenId = tokenId,
            channelId = channelId,
            eventType = PostLogEventType.RATE_LIMITED,
            summary = if (isGlobal) {
                t(
                    "Global rate limit - retrying in ${retryAfterMs / 1000}s",
                    "Rate limit global - mencoba lagi dalam ${retryAfterMs / 1000}dtk"
                )
            } else {
                t(
                    "Rate limited - retrying in ${retryAfterMs / 1000}s",
                    "Kena rate limit - mencoba lagi dalam ${retryAfterMs / 1000}dtk"
                )
            },
            statusCode = 429
        )

        is PostingEvent.AuthFailed -> entry(
            tokenId = tokenId,
            channelId = null,
            eventType = PostLogEventType.AUTH_FAILED,
            summary = if (code > 0) {
                t(
                    "Token rejected (HTTP $code) - posting stopped",
                    "Token ditolak (HTTP $code) - posting berhenti"
                )
            } else {
                t(
                    "Token could not be read - posting stopped",
                    "Token tidak bisa dibaca - posting berhenti"
                )
            },
            statusCode = code.takeIf { it > 0 }
        )

        is PostingEvent.ChannelFailing -> entry(
            tokenId = tokenId,
            channelId = channelId,
            eventType = PostLogEventType.CHANNEL_FAILING,
            summary = t(
                "Post failed $consecutiveFailures time" +
                    (if (consecutiveFailures == 1) "" else "s") + " in a row, retrying",
                "Posting gagal $consecutiveFailures kali berturut-turut, mencoba lagi"
            ),
            statusCode = null
        )
    }

    /**
     * Returns null for events that are not worth a log line. A foreign button on a busy channel is
     * the normal case, and reconnects are noise the verifier card already shows.
     */
    private suspend fun VerifierEvent.toLog(): PostLogEntity? {
        val name = repository.getVerifier(verifierId)?.name ?: DELETED_VERIFIER
        val channel = repository.getVerifier(verifierId)?.watchChannelId
        return when (this) {
            is VerifierEvent.Clicked -> PostLogEntity(
                tokenId = verifierId,
                botName = name,
                channelId = channel,
                eventType = PostLogEventType.VERIFIED,
                summary = t("Verify button pressed ($label)", "Tombol verifikasi ditekan ($label)"),
                statusCode = 204,
                occurredAt = now()
            )

            is VerifierEvent.AuthFailed -> PostLogEntity(
                tokenId = verifierId,
                botName = name,
                channelId = null,
                eventType = PostLogEventType.AUTH_FAILED,
                summary = t(
                    "Verifier token rejected - auto-verify stopped",
                    "Token verifier ditolak - verifikasi otomatis berhenti"
                ),
                statusCode = 401,
                occurredAt = now()
            )

            is VerifierEvent.RateLimited -> {
                // Sub-5s limits are routine pacing, not something the owner needs to read later.
                if (retryAfterMs < LOGGED_RATE_LIMIT_MS) return null
                PostLogEntity(
                    tokenId = verifierId,
                    botName = name,
                    channelId = channel,
                    eventType = PostLogEventType.RATE_LIMITED,
                    summary = t(
                        "Verify rate limited - waiting ${retryAfterMs / 1000}s",
                        "Verifikasi kena rate limit - menunggu ${retryAfterMs / 1000}dtk"
                    ),
                    statusCode = 429,
                    occurredAt = now()
                )
            }

            is VerifierEvent.Unverifiable -> PostLogEntity(
                tokenId = verifierId,
                botName = name,
                channelId = channel,
                eventType = PostLogEventType.VERIFY_SKIPPED,
                summary = t(
                    "A verify message could not be matched to this account",
                    "Pesan verifikasi tidak cocok dengan akun ini"
                ),
                statusCode = null,
                occurredAt = now()
            )

            is VerifierEvent.ClickFailed -> PostLogEntity(
                tokenId = verifierId,
                botName = name,
                channelId = channel,
                eventType = PostLogEventType.VERIFY_FAILED,
                summary = buildString {
                    append(t("Verify click failed", "Klik verifikasi gagal"))
                    code?.let { append(" (HTTP $it)") }
                    detail?.let { append(" - $it") }
                },
                statusCode = code,
                occurredAt = now()
            )

            is VerifierEvent.SkippedForeign,
            is VerifierEvent.Disconnected,
            is VerifierEvent.WaitingConfirm -> null
        }
    }

    private suspend fun entry(
        tokenId: Long,
        channelId: String?,
        eventType: String,
        summary: String,
        statusCode: Int?
    ): PostLogEntity {
        val botName = repository.getToken(tokenId)?.name ?: DELETED_BOT
        return PostLogEntity(
            tokenId = tokenId,
            botName = botName,
            channelId = channelId,
            eventType = eventType,
            summary = summary,
            statusCode = statusCode,
            occurredAt = now()
        )
    }

    private companion object {
        /** Stored in the log, so it is written in the language active when the row was created. */
        val DELETED_BOT: String get() = t("Deleted bot", "Bot terhapus")
        val DELETED_VERIFIER: String get() = t("Deleted verifier", "Verifier terhapus")
        const val LOGGED_RATE_LIMIT_MS = 5_000L
    }
}
