package com.gabut.autopost.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BotTokenEntity::class, ChannelEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AutoPostDatabase : RoomDatabase() {

    abstract fun botTokenDao(): BotTokenDao

    abstract fun channelDao(): ChannelDao

    companion object {
        private const val DB_NAME = "autopost.db"

        @Volatile
        private var INSTANCE: AutoPostDatabase? = null

        fun get(context: Context): AutoPostDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutoPostDatabase::class.java,
                    DB_NAME
                ).build().also { INSTANCE = it }
            }
    }
}
