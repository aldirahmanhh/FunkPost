package com.gabut.autopost.crypto

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKeyManager
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import java.io.File

/**
 * AES-256-GCM secret box backed by the Android Keystore.
 *
 * The data-encryption keyset lives in app-private storage (`filesDir/keysets/autopost_keyset`) and
 * is itself encrypted with a hardware-protected master key held in the Android Keystore, so the
 * keyset file is useless when copied off the device (adb pull, root, or a stray backup).
 *
 * The two failure modes are deliberately different:
 * - [encrypt] lets a broken keystore surface as an exception, because silently writing a secret in
 *   the clear would defeat the whole point of this class.
 * - [decrypt] turns every failure into `null`, which is the signal the UI uses to say "re-enter
 *   this token" rather than crashing (a restored-but-unreadable ciphertext is a normal state after
 *   the keystore master key is invalidated).
 */
class TinkSecretCipher private constructor(private val aead: Aead) : SecretCipher {

    override fun encrypt(plaintext: String): String {
        val ciphertext = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), null)
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String? = try {
        val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        String(aead.decrypt(bytes, null), Charsets.UTF_8)
    } catch (failure: Exception) {
        // Corrupted blob, foreign keyset, or an invalidated master key - all mean "re-enter".
        null
    }

    companion object {
        private const val KEYSET_DIR = "keysets"
        private const val KEYSET_FILE = "autopost_keyset"

        /** Prefixed URI understood by [AndroidKeystoreKmsClient]; the key never leaves the TEE. */
        const val MASTER_KEY_URI = "android-keystore://autopost_master_key"

        @Volatile
        private var instance: TinkSecretCipher? = null

        fun get(context: Context): TinkSecretCipher =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /** Builds a fresh instance; tests use this to avoid the process-wide singleton. */
        fun build(context: Context): TinkSecretCipher {
            AeadConfig.register()
            val keysetFile = keysetFile(context)
            val masterKeyUri = MASTER_KEY_URI
            val handle = readOrCreateKeyset(keysetFile, masterKeyUri)
            return TinkSecretCipher(handle.getPrimitive(Aead::class.java))
        }

        internal fun keysetFile(context: Context): File =
            File(File(context.filesDir, KEYSET_DIR), KEYSET_FILE)

        private fun readOrCreateKeyset(keysetFile: File, masterKeyUri: String): KeysetHandle {
            val masterKey = AndroidKeystoreKmsClient.getOrGenerateNewAeadKey(masterKeyUri)
            if (keysetFile.isFile) {
                try {
                    return KeysetHandle.read(BinaryKeysetReader.withFile(keysetFile), masterKey)
                } catch (unreadable: Exception) {
                    // Master key rotated/invalidated or the file is damaged: start over with a new
                    // keyset. Existing ciphertexts stop decrypting, which surfaces as "re-enter".
                    keysetFile.delete()
                }
            }
            val template: KeyTemplate = AesGcmKeyManager.aes256GcmTemplate()
            val fresh = KeysetHandle.generateNew(template)
            keysetFile.parentFile?.mkdirs()
            fresh.write(BinaryKeysetWriter.withFile(keysetFile), masterKey)
            return fresh
        }
    }
}