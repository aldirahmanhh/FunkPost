package com.gabut.autopost.data

import com.gabut.autopost.data.db.BotTokenDao
import com.gabut.autopost.data.db.BotTokenEntity
import com.gabut.autopost.data.db.ChannelDao
import com.gabut.autopost.data.db.ChannelEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single data entry point for tokens and channels.
 *
 * Plain constructor injection (no DI framework): `AutoPostApplication` builds one instance
 * and every consumer receives it.
 */
class PostRepository(
    private val botTokenDao: BotTokenDao,
    private val channelDao: ChannelDao
) {

    // ---- tokens --------------------------------------------------------

    fun observeTokens(): Flow<List<BotTokenEntity>> = botTokenDao.observeTokens()

    suspend fun getToken(id: Long): BotTokenEntity? = botTokenDao.getToken(id)

    suspend fun getAllTokensOnce(): List<BotTokenEntity> = botTokenDao.getAllTokensOnce()

    suspend fun addToken(token: BotTokenEntity): Long = botTokenDao.insert(token)

    suspend fun updateToken(token: BotTokenEntity) = botTokenDao.update(token)

    suspend fun updateRunning(tokenId: Long, running: Boolean) =
        botTokenDao.updateRunning(tokenId, running)

    suspend fun deleteToken(tokenId: Long) = botTokenDao.deleteTokenById(tokenId)

    suspend fun findByEncryptedToken(cipher: String): BotTokenEntity? =
        botTokenDao.findByEncryptedToken(cipher)

    /** D17: no token may claim to be running after a cold start. */
    suspend fun resetAllRunning() = botTokenDao.setRunningFalseForAll()

    // ---- channels ------------------------------------------------------

    fun observeChannels(tokenId: Long): Flow<List<ChannelEntity>> =
        channelDao.observeChannels(tokenId)

    suspend fun getChannelsOnce(tokenId: Long): List<ChannelEntity> =
        channelDao.getChannelsOnce(tokenId)

    suspend fun getChannel(tokenId: Long, channelId: String): ChannelEntity? =
        channelDao.getChannel(tokenId, channelId)

    suspend fun countForToken(tokenId: Long): Int = channelDao.countForToken(tokenId)

    fun observeChannelCounts(): Flow<Map<Long, Int>> =
        channelDao.observeChannelCounts().map { rows ->
            rows.associate { it.tokenId to it.count }
        }

    /**
     * Validates BEFORE touching the database so a rejected channel never changes the row count.
     * A duplicate `(tokenId, channelId)` replaces the previous row (latest values win).
     */
    suspend fun upsertChannel(
        tokenId: Long,
        channelId: String,
        message: String,
        intervalSeconds: Int
    ): Long {
        requireChannelInputs(channelId, message, intervalSeconds)
        return channelDao.upsert(
            ChannelEntity(
                tokenId = tokenId,
                channelId = channelId,
                message = message,
                intervalSeconds = intervalSeconds
            )
        )
    }

    suspend fun upsertChannel(channel: ChannelEntity): Long {
        requireChannelInputs(channel.channelId, channel.message, channel.intervalSeconds)
        return channelDao.upsert(channel)
    }

    suspend fun updateChannelStatus(
        tokenId: Long,
        channelId: String,
        lastStatus: Int?,
        lastPostedAt: Long?,
        nextTickAt: Long?
    ) = channelDao.updateChannelStatus(tokenId, channelId, lastStatus, lastPostedAt, nextTickAt)

    suspend fun deleteChannel(tokenId: Long, channelId: String) =
        channelDao.delete(tokenId, channelId)

    suspend fun deleteAllChannelsForToken(tokenId: Long) = channelDao.deleteAllForToken(tokenId)

    private fun requireChannelInputs(channelId: String, message: String, intervalSeconds: Int) {
        if (channelId.isBlank()) {
            throw IllegalArgumentException("channel id must not be blank")
        }
        if (message.isBlank()) {
            throw IllegalArgumentException("message must not be blank")
        }
        if (intervalSeconds < MIN_INTERVAL_SECONDS) {
            throw IllegalArgumentException("interval must be at least 1 second")
        }
    }

    companion object {
        const val MIN_INTERVAL_SECONDS = 1
    }
}
