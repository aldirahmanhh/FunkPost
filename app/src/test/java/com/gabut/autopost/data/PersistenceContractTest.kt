package com.gabut.autopost.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gabut.autopost.data.db.AutoPostDatabase
import com.gabut.autopost.data.db.BotTokenEntity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Data-durability contract for bot/channel configuration.
 *
 * These are the two scenarios users actually hit:
 *  1. installing a newer build over an older one (v1 schema -> v3 schema),
 *  2. the process dying and the database being reopened.
 *
 * Both must keep every bot and every channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PersistenceContractTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var executor: ExecutorService? = null
    private var db: AutoPostDatabase? = null

    @Before
    fun setUp() {
        executor = Executors.newSingleThreadExecutor()
    }

    @After
    fun tearDown() {
        db?.close()
        db = null
        context.deleteDatabase(UPGRADE_DB)
        context.deleteDatabase(REOPEN_DB)
        executor?.shutdown()
    }

    private fun openRoom(name: String): AutoPostDatabase = Room.databaseBuilder(
        context,
        AutoPostDatabase::class.java,
        name
    )
        .addMigrations(AutoPostDatabase.MIGRATION_1_2, AutoPostDatabase.MIGRATION_2_3)
        .allowMainThreadQueries()
        .setQueryExecutor(executor!!)
        .setTransactionExecutor(executor!!)
        .build()
        .also { db = it }

    @Test
    fun upgradingFromV1KeepsEveryBotAndChannel() = runTest {
        createV1DatabaseWithOneBotAndChannel()

        val upgraded = openRoom(UPGRADE_DB)
        val repo = repository(upgraded)

        val token = repo.getToken(1L)
        assertNotNull("the bot must survive the v1 -> v3 migration", token)
        assertEquals("Alaryz", token!!.name)
        assertEquals("cipher-token", token.encryptedToken)

        val channels = repo.getChannelsOnce(1L)
        assertEquals("the channel must survive the v1 -> v3 migration", 1, channels.size)
        assertEquals("1243177096948486186", channels.single().channelId)
        assertEquals("hello from v1", channels.single().message)
        assertEquals(60, channels.single().intervalSeconds)

        // The tables added by v2/v3 must exist and be usable after the migration.
        assertEquals(0, repo.logCount())
        assertEquals(0, repo.getAllVerifiersOnce().size)
        println("MIGRATION_RESULT: v1 bot + channel survived into v3")
    }

    @Test
    fun closingAndReopeningTheDatabaseKeepsEveryRow() = runTest {
        var opened = openRoom(REOPEN_DB)
        var repo = repository(opened)
        val tokenId = repo.addToken(BotTokenEntity(name = "Alaryz", encryptedToken = "cipher"))
        repo.upsertChannel(tokenId, "111", "first", intervalSeconds = 60)
        repo.upsertChannel(tokenId, "222", "second", intervalSeconds = 120)
        opened.close()
        db = null

        opened = openRoom(REOPEN_DB)
        repo = repository(opened)

        assertEquals(1, repo.getAllTokensOnce().size)
        assertEquals(2, repo.getChannelsOnce(tokenId).size)
        assertEquals("Alaryz", repo.getToken(tokenId)!!.name)
        println("REOPEN_RESULT: token + 2 channels survived a close/reopen")
    }

    @Test
    fun editingAChannelKeepsItsRowAndLastStatus() = runTest {
        val opened = openRoom(REOPEN_DB)
        val repo = repository(opened)
        val tokenId = repo.addToken(BotTokenEntity(name = "bot", encryptedToken = "cipher"))
        repo.upsertChannel(tokenId, "333", "before", intervalSeconds = 60)
        val before = repo.getChannel(tokenId, "333")!!
        repo.updateChannelStatus(tokenId, "333", lastStatus = 200, lastPostedAt = 42L, nextTickAt = 99L)

        repo.upsertChannel(tokenId, "333", "after", intervalSeconds = 30)

        val after = repo.getChannel(tokenId, "333")!!
        assertEquals("editing must not create a second row", 1, repo.countForToken(tokenId))
        assertEquals("editing must update the message", "after", after.message)
        assertEquals(30, after.intervalSeconds)
        assertEquals("the row id must stay stable", before.id, after.id)
        assertEquals("the last HTTP status must not be erased", 200, after.lastStatus)
        assertEquals(42L, after.lastPostedAt)
        assertEquals(99L, after.nextTickAt)
        println("EDIT_RESULT: message/interval changed, status kept, id stable")
    }

    @Test
    fun channelCountsFlowMatchesTheTable() = runTest {
        val opened = openRoom(REOPEN_DB)
        val repo = repository(opened)
        val a = repo.addToken(BotTokenEntity(name = "a", encryptedToken = "c1"))
        val b = repo.addToken(BotTokenEntity(name = "b", encryptedToken = "c2"))
        repo.upsertChannel(a, "1", "m", 10)
        repo.upsertChannel(a, "2", "m", 10)
        repo.upsertChannel(b, "1", "m", 10)

        val counts = repo.observeChannelCounts().first()
        assertEquals(2, counts[a])
        assertEquals(1, counts[b])
        assertTrue(counts.values.sum() == 3)
        println("COUNT_RESULT: live channel counts are 2 + 1")
    }

    private fun repository(db: AutoPostDatabase): PostRepository = PostRepository(
        db.botTokenDao(),
        db.channelDao(),
        db.postLogDao(),
        db.verifierDao()
    )

    /**
     * Builds a real v1 database file using the shipped v1 schema. Room will migrate it on open,
     * exactly like an app upgrade does on a device.
     */
    private fun createV1DatabaseWithOneBotAndChannel() {
        val file = context.getDatabasePath(UPGRADE_DB)
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `bot_tokens` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `encryptedToken` TEXT NOT NULL, " +
                "`useWebhook` INTEGER NOT NULL, `encryptedWebhookUrl` TEXT NOT NULL, " +
                "`running` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `channels` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`tokenId` INTEGER NOT NULL, `channelId` TEXT NOT NULL, " +
                "`message` TEXT NOT NULL, `intervalSeconds` INTEGER NOT NULL, " +
                "`lastStatus` INTEGER, `lastPostedAt` INTEGER, `nextTickAt` INTEGER, " +
                "FOREIGN KEY(`tokenId`) REFERENCES `bot_tokens`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        raw.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_channels_tokenId_channelId` " +
                "ON `channels` (`tokenId`, `channelId`)"
        )
        raw.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_channels_tokenId` ON `channels` (`tokenId`)"
        )
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS room_master_table " +
                "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
        )
        raw.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, ?)",
            arrayOf(V1_IDENTITY_HASH)
        )
        raw.execSQL(
            "INSERT INTO bot_tokens " +
                "(id, name, encryptedToken, useWebhook, encryptedWebhookUrl, running, createdAt) " +
                "VALUES (1, 'Alaryz', 'cipher-token', 0, '', 1, 1793167176114)"
        )
        raw.execSQL(
            "INSERT INTO channels " +
                "(id, tokenId, channelId, message, intervalSeconds, lastStatus, lastPostedAt, nextTickAt) " +
                "VALUES (1, 1, '1243177096948486186', 'hello from v1', 60, 200, 5, 65)"
        )
        raw.version = 1
        raw.close()
    }

    private companion object {
        const val UPGRADE_DB = "upgrade-persistence.db"
        const val REOPEN_DB = "reopen-persistence.db"

        /** identityHash of the shipped v1 schema (app/schemas/.../1.json). */
        const val V1_IDENTITY_HASH = "bc91efe26d8dce1f0582f5010b1690cf"
    }
}
