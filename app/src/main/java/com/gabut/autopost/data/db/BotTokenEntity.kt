package com.gabut.autopost.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One Discord bot configuration.
 *
 * Secrets are stored ONLY as ciphertext produced by the crypto layer (todo 4):
 * [encryptedToken] and [encryptedWebhookUrl] are never plaintext column names on purpose.
 */
@Entity(tableName = "bot_tokens")
data class BotTokenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val encryptedToken: String,
    val useWebhook: Boolean = false,
    val encryptedWebhookUrl: String = "",
    val running: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
