package com.gabut.autopost.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VerifierDao {

    @Query("SELECT * FROM verifiers ORDER BY createdAt ASC, id ASC")
    fun observeVerifiers(): Flow<List<VerifierEntity>>

    @Query("SELECT * FROM verifiers ORDER BY createdAt ASC, id ASC")
    suspend fun getAllOnce(): List<VerifierEntity>

    @Query("SELECT * FROM verifiers WHERE id = :id")
    suspend fun getVerifier(id: Long): VerifierEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(verifier: VerifierEntity): Long

    @Update
    suspend fun update(verifier: VerifierEntity)

    @Query("UPDATE verifiers SET running = :running WHERE id = :id")
    suspend fun updateRunning(id: Long, running: Boolean)

    @Query(
        "UPDATE verifiers SET lastStatus = :status, lastEventAt = :at, lastEventText = :text, " +
            "clickedCount = clickedCount + :clickDelta WHERE id = :id"
    )
    suspend fun updateStatus(
        id: Long,
        status: Int?,
        at: Long?,
        text: String?,
        clickDelta: Int
    )

    @Query("DELETE FROM verifiers WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** D17 for verifiers: a cold start must never claim a verifier is still connected. */
    @Query("UPDATE verifiers SET running = 0")
    suspend fun setRunningFalseForAll()
}
