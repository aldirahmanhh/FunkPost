package com.gabut.autopost

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gabut.autopost.data.ColdStartReset
import com.gabut.autopost.data.PostRepository
import com.gabut.autopost.data.PostLogEventType
import com.gabut.autopost.data.db.AutoPostDatabase
import com.gabut.autopost.data.db.BotTokenEntity
import com.gabut.autopost.data.db.PostLogEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomRepositoryTest {

    private lateinit var db: AutoPostDatabase
    private lateinit var repo: PostRepository
    private lateinit var dbExecutor: ExecutorService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Robolectric's legacy SQLite shadow binds each connection to the thread that opened
        // it, which breaks as soon as Room dispatches on its own executors (Flow queries run on
        // the ArchTaskExecutor IO pool and ignore setQueryExecutor). NATIVE SQLite mode keeps
        // connections usable across threads; the single-threaded executor below stays only to
        // serialize writes so assertions are deterministic.
        dbExecutor = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(context, AutoPostDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(dbExecutor)
            .setTransactionExecutor(dbExecutor)
            .build()
        repo = PostRepository(
            db.botTokenDao(),
            db.channelDao(),
            db.postLogDao(),
            db.verifierDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
        dbExecutor.shutdown()
    }

    private suspend fun seedToken(name: String = "bot"): Long =
        repo.addToken(
            BotTokenEntity(
                name = name,
                encryptedToken = "cipher-$name",
                createdAt = System.currentTimeMillis()
            )
        )

    @Test
    fun newTokenHasNoChannels() = runTest {
        val tokenId = seedToken()
        val channels = repo.observeChannels(tokenId).first()
        assertEquals(0, channels.size)
        println("new token $tokenId observed 0 channels")
    }

    @Test
    fun zeroIntervalIsRejectedWithoutTouchingTheDatabase() = runTest {
        val tokenId = seedToken()
        try {
            repo.upsertChannel(tokenId, "123", "hello", intervalSeconds = 0)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "message should mention interval but was: ${expected.message}",
                expected.message.orEmpty().contains("interval")
            )
        }
        assertEquals(0, repo.countForToken(tokenId))
        println("interval=0 rejected; row count still 0")
    }

    @Test
    fun duplicateChannelReplacesInsteadOfDuplicating() = runTest {
        val tokenId = seedToken()
        repo.upsertChannel(tokenId, "555", "first", intervalSeconds = 60)
        repo.upsertChannel(tokenId, "555", "second", intervalSeconds = 10)
        val channels = repo.getChannelsOnce(tokenId)
        assertEquals(1, channels.size)
        assertEquals("second", channels[0].message)
        assertEquals(10, channels[0].intervalSeconds)
        println("duplicate (tokenId,channelId) replaced: 1 row, latest values win")
    }

    @Test
    fun sameChannelIdOnDifferentTokensIsAllowed() = runTest {
        val a = seedToken("a")
        val b = seedToken("b")
        repo.upsertChannel(a, "777", "from a", intervalSeconds = 5)
        repo.upsertChannel(b, "777", "from b", intervalSeconds = 5)
        assertEquals(1, repo.countForToken(a))
        assertEquals(1, repo.countForToken(b))
        println("same channel id on two tokens allowed: 1 row each")
    }

    @Test
    fun deletingTokenCascadesToChannels() = runTest {
        val tokenId = seedToken()
        repo.upsertChannel(tokenId, "111", "m1", intervalSeconds = 30)
        repo.upsertChannel(tokenId, "222", "m2", intervalSeconds = 30)
        assertEquals(2, repo.countForToken(tokenId))
        repo.deleteToken(tokenId)
        assertNull(repo.getToken(tokenId))
        assertEquals(0, repo.countForToken(tokenId))
        println("inserted 1 token, 2 channels; cascade delete removed 2")
    }

    @Test
    fun coldStartResetClearsRunningFlags() = runTest {
        val a = seedToken("a")
        val b = seedToken("b")
        repo.updateRunning(a, true)
        repo.updateRunning(b, true)
        ColdStartReset.resetAll(repo)
        val tokens = repo.getAllTokensOnce()
        assertEquals(2, tokens.size)
        tokens.forEach { assertTrue("token ${it.id} still running", !it.running) }
        assertNotNull(tokens)
        println("cold-start reset cleared running=true on 2 tokens")
    }
}
