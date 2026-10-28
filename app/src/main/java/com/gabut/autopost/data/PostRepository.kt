package com.gabut.autopost.data

import com.gabut.autopost.data.db.BotTokenDao
import com.gabut.autopost.data.db.BotTokenEntity
import com.gabut.autopost.data.db.ChannelDao
import com.gabut.autopost.data.db.ChannelEntity
import com.gabut.autopost.data.db.PostLogDao
import com.gabut.autopost.data.db.PostLogEntity
import com.gabut.autopost.data.db.VerifierDao
import com.gabut.autopost.data.db.VerifierEntity
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
    private val channelDao: ChannelDao,
    private val postLogDao: PostLogDao,
    private val verifierDao: VerifierDao
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

    // ---- activity log --------------------------------------------------

    /** Newest-first stream for the Logs tab; never returns more than [MAX_LOG_ENTRIES] rows. */
    fun observeLogs(limit: Int = MAX_LOG_ENTRIES): Flow<List<PostLogEntity>> =
        postLogDao.observeNewest(limit.coerceIn(1, MAX_LOG_ENTRIES))

    /**
     * Appends one entry and prunes everything outside the newest [MAX_LOG_ENTRIES] in the same
     * transaction, so the table can never grow without bound.
     */
    suspend fun appendLog(log: PostLogEntity): Long =
        postLogDao.insertAndTrim(log, MAX_LOG_ENTRIES)

    /** One-shot read, used by tests and by any caller that is not observing. */
    suspend fun recentLogs(limit: Int = MAX_LOG_ENTRIES): List<PostLogEntity> =
        postLogDao.getNewestOnce(limit.coerceIn(1, MAX_LOG_ENTRIES))

    suspend fun logCount(): Int = postLogDao.count()

    suspend fun clearLogs() = postLogDao.deleteAll()

    // ---- auto-verifiers -------------------------------------------------

    fun observeVerifiers(): Flow<List<VerifierEntity>> = verifierDao.observeVerifiers()

    suspend fun getAllVerifiersOnce(): List<VerifierEntity> = verifierDao.getAllOnce()

    suspend fun getVerifier(id: Long): VerifierEntity? = verifierDao.getVerifier(id)

    suspend fun addVerifier(verifier: VerifierEntity): Long = verifierDao.insert(verifier)

    suspend fun updateVerifier(verifier: VerifierEntity) = verifierDao.update(verifier)

    suspend fun updateVerifierRunning(id: Long, running: Boolean) =
        verifierDao.updateRunning(id, running)

    suspend fun updateVerifierStatus(
        id: Long,
        status: Int?,
        at: Long?,
        text: String?,
        clickDelta: Int = 0
    ) = verifierDao.updateStatus(id, status, at, text, clickDelta)

    suspend fun deleteVerifier(id: Long) = verifierDao.deleteById(id)

    /** D17 for verifiers: nothing may claim to be connected after a cold start. */
    suspend fun resetAllVerifierRunning() = verifierDao.setRunningFalseForAll()

    private fun requireChannelInputs(channelId: String, message: String, intervalSeconds: Int) {
        if (channelId.isBlank()) {
            throw IllegalArgumentException("channel id must not be blank")
        }
        if (message.isBlank()) {
            throw IllegalArgumentException("message must not be blank")
        }
        if (message.length > MAX_MESSAGE_LENGTH) {
            throw IllegalArgumentException("message must be at most $MAX_MESSAGE_LENGTH characters")
        }
        if (intervalSeconds < MIN_INTERVAL_SECONDS) {
            throw IllegalArgumentException("interval must be at least 1 second")
        }
    }

    companion object {
        const val MIN_INTERVAL_SECONDS = 1
        /** Discord rejects messages longer than 2,000 characters; check before we ever post. */
        const val MAX_MESSAGE_LENGTH = 2_000
        /** Hard cap for the activity log; the oldest rows are pruned on every append. */
        const val MAX_LOG_ENTRIES = 500
    }
}
