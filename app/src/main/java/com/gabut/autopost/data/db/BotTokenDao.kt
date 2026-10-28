package com.gabut.autopost.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BotTokenDao {

    @Query("SELECT * FROM bot_tokens ORDER BY createdAt ASC, id ASC")
    fun observeTokens(): Flow<List<BotTokenEntity>>

    @Query("SELECT * FROM bot_tokens WHERE id = :id")
    suspend fun getToken(id: Long): BotTokenEntity?

    @Query("SELECT * FROM bot_tokens ORDER BY createdAt ASC, id ASC")
    suspend fun getAllTokensOnce(): List<BotTokenEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(token: BotTokenEntity): Long

    @Update
    suspend fun update(token: BotTokenEntity)

    @Query("UPDATE bot_tokens SET running = :running WHERE id = :tokenId")
    suspend fun updateRunning(tokenId: Long, running: Boolean)

    @Delete
    suspend fun deleteToken(token: BotTokenEntity)

    @Query("DELETE FROM bot_tokens WHERE id = :tokenId")
    suspend fun deleteTokenById(tokenId: Long)

    @Query("SELECT * FROM bot_tokens WHERE encryptedToken = :cipher LIMIT 1")
    suspend fun findByEncryptedToken(cipher: String): BotTokenEntity?

    @Query("UPDATE bot_tokens SET running = 0")
    suspend fun setRunningFalseForAll()
}
