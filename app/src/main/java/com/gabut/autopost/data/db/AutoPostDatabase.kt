package com.gabut.autopost.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BotTokenEntity::class,
        ChannelEntity::class,
        PostLogEntity::class,
        VerifierEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AutoPostDatabase : RoomDatabase() {

    abstract fun botTokenDao(): BotTokenDao

    abstract fun channelDao(): ChannelDao

    abstract fun postLogDao(): PostLogDao

    abstract fun verifierDao(): VerifierDao

    companion object {
        private const val DB_NAME = "autopost.db"

        /**
         * v1 -> v2 only adds the append-only activity log. Existing `bot_tokens` / `channels`
         * rows are never rewritten, dropped, or recreated, so all history survives the upgrade.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `post_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tokenId` INTEGER NOT NULL, " +
                        "`botName` TEXT NOT NULL, " +
                        "`channelId` TEXT, " +
                        "`eventType` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`statusCode` INTEGER, " +
                        "`occurredAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_post_logs_occurredAt_id` " +
                        "ON `post_logs` (`occurredAt`, `id`)"
                )
            }
        }

        /**
         * v2 -> v3 adds the auto-verifier table. Same rule as 1_2: nothing existing is touched, so
         * an install with bots and channels keeps every row.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `verifiers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`encryptedToken` TEXT NOT NULL, " +
                        "`watchChannelId` TEXT NOT NULL, " +
                        "`humanize` INTEGER NOT NULL, " +
                        "`humanDelayMinMs` INTEGER NOT NULL, " +
                        "`humanDelayMaxMs` INTEGER NOT NULL, " +
                        "`baseDelayMs` INTEGER NOT NULL, " +
                        "`minDelayMs` INTEGER NOT NULL, " +
                        "`maxDelayMs` INTEGER NOT NULL, " +
                        "`maxClickRetries` INTEGER NOT NULL, " +
                        "`running` INTEGER NOT NULL, " +
                        "`lastStatus` INTEGER, " +
                        "`lastEventAt` INTEGER, " +
                        "`lastEventText` TEXT, " +
                        "`clickedCount` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        @Volatile
        private var INSTANCE: AutoPostDatabase? = null

        fun get(context: Context): AutoPostDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutoPostDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
