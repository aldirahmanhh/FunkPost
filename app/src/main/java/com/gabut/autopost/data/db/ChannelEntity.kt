package com.gabut.autopost.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One target Discord channel of a bot token.
 *
 * Mirrors the reference channel dict `{id, message, interval}` (autopost.py:306) plus the
 * observability columns added by D20 (last status / last post time / next tick).
 *
 * A channel id is unique per token (unique index on `(tokenId, channelId)`), but the same
 * channel id may exist on a different token - exactly like the reference's per-token list.
 */
@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = BotTokenEntity::class,
            parentColumns = ["id"],
            childColumns = ["tokenId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["tokenId", "channelId"], unique = true),
        Index(value = ["tokenId"])
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val tokenId: Long,
    val channelId: String,
    val message: String,
    val intervalSeconds: Int,
    val lastStatus: Int? = null,
    val lastPostedAt: Long? = null,
    val nextTickAt: Long? = null
)
