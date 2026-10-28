package com.gabut.autopost.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Append-only access to the activity log.
 *
 * [insertAndTrim] is an abstract-class DAO on purpose: a concrete `@Transaction` method is the
 * reliable way to make "insert then prune" atomic across both Room and KSP. Concurrent appends are
 * serialized by Room's transaction executor, so the table can never finish a write above the bound.
 */
@Dao
abstract class PostLogDao {

    /** Newest first; `id` breaks ties between entries written in the same millisecond. */
    @Query("SELECT * FROM post_logs ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    abstract fun observeNewest(limit: Int): Flow<List<PostLogEntity>>

    @Query("SELECT * FROM post_logs ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    abstract suspend fun getNewestOnce(limit: Int): List<PostLogEntity>

    @Query("SELECT COUNT(*) FROM post_logs")
    abstract suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(log: PostLogEntity): Long

    /**
     * Deletes every row outside the newest [keep] by `(occurredAt, id)`. A non-positive [keep]
     * clears the table, which is the documented edge of the retention bound.
     */
    @Query(
        "DELETE FROM post_logs WHERE id NOT IN (" +
            "SELECT id FROM post_logs ORDER BY occurredAt DESC, id DESC LIMIT :keep)"
    )
    abstract suspend fun pruneTo(keep: Int)

    @Query("DELETE FROM post_logs")
    abstract suspend fun deleteAll()

    /** Append and enforce the retention bound in one transaction. */
    @Transaction
    open suspend fun insertAndTrim(log: PostLogEntity, keep: Int): Long {
        val id = insert(log)
        pruneTo(keep)
        return id
    }
}
