package com.gabut.autopost.crypto

/**
 * Encrypts the two secret fields (bot token, webhook URL) before they ever reach Room, and
 * decrypts them again for the network layer.
 *
 * Implementations are expected to be safe to call from any thread. Only [encrypt] may fail hard
 * (a broken keystore means the app cannot protect new secrets); [decrypt] returns `null` for any
 * failure so the UI can render the "re-enter this token" state instead of crashing.
 */
interface SecretCipher {

    /** Returns a Base64 (no wrap) ciphertext of [plaintext]; never returns [plaintext] itself. */
    fun encrypt(plaintext: String): String

    /** Returns the original plaintext, or `null` when the ciphertext cannot be read. */
    fun decrypt(ciphertext: String): String?
}

/**
 * Renders a secret for humans without exposing enough of it to be reused.
 *
 * Mirrors the reference UI, which shows four leading and four trailing characters
 * (`autopost.py:1342-1347`).
 */
object MaskedPreview {
    private const val EDGE = 4

    fun of(secret: String): String {
        if (secret.length <= EDGE * 2) return "\u2026"
        return secret.take(EDGE) + "\u2026" + secret.takeLast(EDGE)
    }
}