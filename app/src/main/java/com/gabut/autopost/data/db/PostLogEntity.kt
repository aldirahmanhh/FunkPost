package com.gabut.autopost.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One human-readable activity entry, rendered by the Logs tab.
 *
 * Deliberately has **no foreign key** to `bot_tokens`: a token can be deleted while its history
 * stays useful. [botName] is a snapshot taken at append time, so an old row still says which bot
 * produced it after the token itself is gone. [tokenId] is kept as a plain correlation value, not
 * as a referential constraint.
 *
 * The table is append-only from the repository's point of view. Only the retention policy
 * ([com.gabut.autopost.data.PostRepository.MAX_LOG_ENTRIES]) and an explicit clear remove rows.
 */
@Entity(
    tableName = "post_logs",
    indices = [
        // Newest-first reads are `ORDER BY occurredAt DESC, id DESC`; the composite index keeps
        // both the sort and the LIMIT cheap, and `id` breaks ties between equal timestamps.
        Index(value = ["occurredAt", "id"])
    ]
)
data class PostLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Correlation id only - never a foreign key. */
    val tokenId: Long,
    /** Snapshot of the bot's name at append time; survives token deletion. */
    val botName: String,
    /** Null for token-wide events (auth failure, global rate limit, ...). */
    val channelId: String? = null,
    /**
     * Stable machine identifier for the event kind (for example `posted`, `rate_limited`).
     * Kept as TEXT so a new scheduler event never needs a schema migration just to be recorded.
     */
    val eventType: String,
    /** Human-readable text shown to the user. */
    val summary: String,
    /** Optional HTTP status when the event came from a Discord response. */
    val statusCode: Int? = null,
    /** Wall-clock time of the event, used for newest-first ordering. */
    val occurredAt: Long = System.currentTimeMillis()
)
