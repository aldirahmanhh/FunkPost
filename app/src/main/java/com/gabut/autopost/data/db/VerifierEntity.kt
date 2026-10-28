package com.gabut.autopost.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One auto-verifier: a **user** token that watches a channel and clicks the verify buttons for its
 * own account.
 *
 * This is deliberately a different table from [BotTokenEntity]. A bot token posts messages through
 * the REST API; a user token has to sit on the Discord gateway and press component interactions.
 * Sharing one table would mean a `kind` column that every existing query has to remember to filter
 * on - one forgotten `WHERE` and the poster would try to gateway a bot token.
 *
 * As with bots, the token is only ever stored as ciphertext ([encryptedToken]).
 */
@Entity(tableName = "verifiers")
data class VerifierEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val encryptedToken: String,
    /** The channel whose messages are inspected for buttons. */
    val watchChannelId: String,

    // ---- click pacing (mirrors auto-click.py's DEFAULT_CONFIG) -----------
    /** Adds a random human-like pause before each click. Off is faster, on is less robotic. */
    val humanize: Boolean = true,
    val humanDelayMinMs: Long = 300L,
    val humanDelayMaxMs: Long = 1_200L,
    /** Floor for the adaptive delay; the engine grows it after a 429 and shrinks it after a 200. */
    val baseDelayMs: Long = 1_000L,
    val minDelayMs: Long = 500L,
    val maxDelayMs: Long = 30_000L,
    val maxClickRetries: Int = 3,

    val running: Boolean = false,

    // ---- observability ---------------------------------------------------
    /** Last HTTP status seen (204 success, 429 rate limited, ...). */
    val lastStatus: Int? = null,
    val lastEventAt: Long? = null,
    /** Human sentence for the card, so a restart does not lose the last outcome. */
    val lastEventText: String? = null,
    val clickedCount: Int = 0,

    val createdAt: Long = System.currentTimeMillis()
)
