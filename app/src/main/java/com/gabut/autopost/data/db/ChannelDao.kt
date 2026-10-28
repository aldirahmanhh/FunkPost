package com.gabut.autopost.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Channel persistence.
 *
 * The upsert is deliberately an UPDATE-then-INSERT inside one transaction instead of
 * `INSERT OR REPLACE`. REPLACE deletes the conflicting row and writes a fresh one, which churns
 * the primary key and silently resets every observability column (`lastStatus`, `lastPostedAt`,
 * `nextTickAt`) every time the user edits a channel. A plain UPDATE keeps the row - and its
 * history - intact.
 */
@Dao
abstract class ChannelDao {

    @Query("SELECT * FROM channels WHERE tokenId = :tokenId ORDER BY channelId ASC")
    abstract fun observeChannels(tokenId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE tokenId = :tokenId ORDER BY channelId ASC")
    abstract suspend fun getChannelsOnce(tokenId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE tokenId = :tokenId AND channelId = :channelId LIMIT 1")
    abstract suspend fun getChannel(tokenId: Long, channelId: String): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channels WHERE tokenId = :tokenId")
    abstract suspend fun countForToken(tokenId: Long): Int

    /** Live per-token channel counts, so the status bar never shows a stale number. */
    @Query("SELECT tokenId, COUNT(*) AS count FROM channels GROUP BY tokenId")
    abstract fun observeChannelCounts(): Flow<List<ChannelCount>>

    /**
     * Inserts a brand-new row. ABORT (not REPLACE) means a duplicate `(tokenId, channelId)` is a
     * programming error rather than a silent delete.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRow(channel: ChannelEntity): Long

    @Query(
        "UPDATE channels SET message = :message, intervalSeconds = :intervalSeconds " +
            "WHERE tokenId = :tokenId AND channelId = :channelId"
    )
    abstract suspend fun updateConfig(
        tokenId: Long,
        channelId: String,
        message: String,
        intervalSeconds: Int
    ): Int

    /**
     * Creates the channel or updates its editable fields. Status columns are only written by the
     * scheduler, so an edit never erases the last result the user is looking at.
     */
    @Transaction
    open suspend fun upsert(channel: ChannelEntity): Long {
        val updated = updateConfig(
            tokenId = channel.tokenId,
            channelId = channel.channelId,
            message = channel.message,
            intervalSeconds = channel.intervalSeconds
        )
        if (updated > 0) {
            return getChannel(channel.tokenId, channel.channelId)?.id ?: 0L
        }
        return insertRow(channel)
    }

    @Query(
        "UPDATE channels SET lastStatus = :lastStatus, lastPostedAt = :lastPostedAt, " +
            "nextTickAt = :nextTickAt WHERE tokenId = :tokenId AND channelId = :channelId"
    )
    abstract suspend fun updateChannelStatus(
        tokenId: Long,
        channelId: String,
        lastStatus: Int?,
        lastPostedAt: Long?,
        nextTickAt: Long?
    )

    @Query("DELETE FROM channels WHERE tokenId = :tokenId AND channelId = :channelId")
    abstract suspend fun delete(tokenId: Long, channelId: String)

    @Query("DELETE FROM channels WHERE tokenId = :tokenId")
    abstract suspend fun deleteAllForToken(tokenId: Long)
}
