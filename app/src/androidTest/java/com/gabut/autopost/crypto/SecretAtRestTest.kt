package com.gabut.autopost.crypto

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gabut.autopost.data.PostRepository
import com.gabut.autopost.data.db.AutoPostDatabase
import com.gabut.autopost.data.db.BotTokenEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the "never plaintext on disk" promise end to end (todo 4, IS-5).
 *
 * The happy path writes a real token through the repository and then scans the raw database files
 * for it; the failure path removes the keyset and asserts the app degrades to the "re-enter token"
 * state instead of crashing.
 */
@RunWith(AndroidJUnit4::class)
class SecretAtRestTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AutoPostDatabase
    private lateinit var repo: PostRepository
    private lateinit var dbPath: String

    /** Deliberately recognisable so scanning the bytes cannot produce a false negative. */
    private val plaintextToken = "PLAINTEXT-LEAK-CANARY-0123456789.abcdefghijklmnopqrstuvwxyz"

    @Before
    fun setUp() {
        context.deleteDatabase("atrest.db")
        dbPath = context.getDatabasePath("atrest.db").absolutePath
        db = Room.databaseBuilder(context, AutoPostDatabase::class.java, "atrest.db").build()
        repo = PostRepository(db.botTokenDao(), db.channelDao())
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("atrest.db")
    }

    /** The main datastore plus its write-ahead log, decoded 1:1 so `contains` is byte-exact. */
    private fun rawDatabaseBytes(): String {
        val parts = listOf(dbPath, "$dbPath-wal", "$dbPath-shm")
            .map { java.io.File(it) }
            .filter { it.isFile }
            .map { it.readBytes().toString(Charsets.ISO_8859_1) }
        return parts.joinToString(separator = "")
    }

    @Test
    fun plaintextTokenNeverLandsInTheDatabaseFile() = runBlocking {
        val cipher = TinkSecretCipher.build(context)
        val ciphertext = cipher.encrypt(plaintextToken)

        val id = repo.addToken(
            BotTokenEntity(name = "canary", encryptedToken = ciphertext)
        )
        assertTrue(repo.getToken(id)!!.encryptedToken == ciphertext)

        // Closing checkpoints the WAL into the main file, so the on-disk bytes are final.
        db.close()

        val bytes = rawDatabaseBytes()
        assertTrue("precondition: the database must actually hold the row", bytes.isNotEmpty())
        assertTrue("sanity: the ciphertext itself IS expected on disk", bytes.contains(ciphertext))
        assertFalse("database file must not contain the plaintext token", bytes.contains(plaintextToken))

        println("CANARY_RESULT: ciphertext present, plaintext absent across db+wal+shm")
    }

    @Test
    fun deletingTheKeysetTurnsDecryptionIntoAReEnterState() {
        val cipher = TinkSecretCipher.build(context)
        val ciphertext = cipher.encrypt("some-bot-token-value")

        // Simulates clearing app data, restoring onto another device, or a keystore invalidation:
        // the ciphertext survives, the master key does not.
        val keyset = TinkSecretCipher.keysetFile(context)
        assertTrue("keyset must exist before the deletion", keyset.isFile)
        assertTrue(keyset.delete())

        val afterLoss = TinkSecretCipher.build(context)
        assertNull("an unreadable ciphertext must return null, not throw", afterLoss.decrypt(ciphertext))

        println("KEY_LOSS_RESULT: decrypt returned null after the keyset was removed")
    }
}