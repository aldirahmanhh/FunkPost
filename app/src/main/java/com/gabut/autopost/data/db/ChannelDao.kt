package com.gabut.autopost.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels WHERE tokenId = :tokenId ORDER BY channelId ASC")
    fun observeChannels(tokenId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE tokenId = :tokenId ORDER BY channelId ASC")
    suspend fun getChannelsOnce(tokenId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE tokenId = :tokenId AND channelId = :channelId LIMIT 1")
    suspend fun getChannel(tokenId: Long, channelId: String): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channels WHERE tokenId = :tokenId")
    suspend fun countForToken(tokenId: Long): Int

    /** Live per-token channel counts, so the status bar never shows a stale number. */
    @Query("SELECT tokenId, COUNT(*) AS count FROM channels GROUP BY tokenId")
    fun observeChannelCounts(): Flow<List<ChannelCount>>

    /**
     * Upsert semantics: the unique index on `(tokenId, channelId)` makes REPLACE delete the
     * conflicting row and write the new values, so the latest edit always wins and a duplicate
     * `(tokenId, channelId)` never throws.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channel: ChannelEntity): Long

    @Query(
        "UPDATE channels SET lastStatus = :lastStatus, lastPostedAt = :lastPostedAt, " +
            "nextTickAt = :nextTickAt WHERE tokenId = :tokenId AND channelId = :channelId"
    )
    suspend fun updateChannelStatus(
        tokenId: Long,
        channelId: String,
        lastStatus: Int?,
        lastPostedAt: Long?,
        nextTickAt: Long?
    )

    @Query("DELETE FROM channels WHERE tokenId = :tokenId AND channelId = :channelId")
    suspend fun delete(tokenId: Long, channelId: String)

    @Query("DELETE FROM channels WHERE tokenId = :tokenId")
    suspend fun deleteAllForToken(tokenId: Long)
}
