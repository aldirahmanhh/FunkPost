package com.gabut.autopost.crypto

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the secret box actually protects what it claims to (todo 4).
 *
 * These run on a real device because the Android Keystore has no meaningful JVM stand-in.
 */
@RunWith(AndroidJUnit4::class)
class SecretCipherTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * A throwaway keyset under cacheDir. Instrumented tests run against the real installed app,
     * so deleting the production keyset here would corrupt every token the user has stored.
     */
    private fun testKeysetFile(): java.io.File =
        java.io.File(context.cacheDir, "test-keysets/secret-cipher-keyset")

    private fun cipher(): TinkSecretCipher {
        // A fresh instance so the test does not depend on the process-wide singleton's lifetime.
        testKeysetFile().delete()
        return TinkSecretCipher.build(context, testKeysetFile())
    }

    @Test
    fun roundTripsA72CharToken() {
        val cipher = cipher()
        val token = "M" + "x".repeat(70) + "="
        assertEquals(72, token.length)

        val ciphertext = cipher.encrypt(token)
        assertEquals(token, cipher.decrypt(ciphertext))
        println("round-trip ok for a 72-char token; ciphertext length=${ciphertext.length}")
    }

    @Test
    fun ciphertextIsNotThePlaintext() {
        val cipher = cipher()
        val token = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAuAbCdEf.GhIjKlMnOpQrStUvWxYz123456"

        val ciphertext = cipher.encrypt(token)
        assertNotEquals(token, ciphertext)
        assertFalse("ciphertext must not contain the plaintext", ciphertext.contains(token))
        println("ciphertext differs from plaintext and does not embed it")
    }

    @Test
    fun corruptedBase64ReturnsNullInsteadOfThrowing() {
        val cipher = cipher()
        val corrupted = "not-valid-base64-@@@"
        assertNull(cipher.decrypt(corrupted))
        println("corrupted blob decrypted to null (no throw)")
    }

    @Test
    fun samePlaintextTwiceYieldsDifferentCiphertexts() {
        val cipher = cipher()
        val token = "same-token-value"

        val first = cipher.encrypt(token)
        val second = cipher.encrypt(token)
        assertNotEquals("a random IV must make each ciphertext unique", first, second)
        assertEquals(token, cipher.decrypt(first))
        assertEquals(token, cipher.decrypt(second))
        println("random IV verified: two encryptions differ, both decrypt")
    }

    @Test
    fun productionKeysetPathStaysUnderFilesDir() {
        // Path check only: this test must never delete or rewrite the real keyset.
        val keysetFile = TinkSecretCipher.keysetFile(context)
        assertTrue(
            "keyset path must stay under filesDir",
            keysetFile.absolutePath.startsWith(context.filesDir.absolutePath)
        )
        assertEquals("keysets/autopost_keyset", keysetFile.relativeTo(context.filesDir).invariantSeparatorsPath)
        println("production keyset path = ${keysetFile.relativeTo(context.filesDir)}")
    }

    @Test
    fun maskedPreviewNeverShowsTheWholeSecret() {
        val token = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA"
        val masked = MaskedPreview.of(token)

        assertFalse(masked.contains(token))
        assertTrue(masked.startsWith(token.take(4)))
        assertTrue(masked.endsWith(token.takeLast(4)))
        println("masked preview = $masked")
    }
}