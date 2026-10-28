package com.gabut.autopost.network

import android.util.Log
import com.gabut.autopost.crypto.MaskedPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Who a user token belongs to, once it has been accepted by Discord. */
data class VerifiedUser(val id: String, val username: String)

/** Result of pressing one button. Mirrors [SendResult] so the engine's retry logic reads the same. */
sealed interface ClickResult {
    /** 204: Discord accepted the interaction. */
    data object Success : ClickResult

    /** 429 with the wait Discord asked for. */
    data class RateLimited(val retryAfterMs: Long) : ClickResult

    /**
     * The click will never succeed for this button (expired, already used, not for us).
     * Distinct from [Other] so the engine stops instead of spending its retry budget.
     * [message] carries Discord's own explanation so the UI can show why.
     */
    data class Permanent(val code: Int, val message: String? = null) : ClickResult

    /** 5xx and anything else: worth retrying with backoff. */
    data class Other(val code: Int, val message: String? = null) : ClickResult

    data object NetworkError : ClickResult
}

/**
 * REST calls an auto-verifier needs: identify the account and press a message component.
 *
 * Deliberately separate from [DiscordClient]: that one sends bot messages and must never be handed
 * a user token (a user token has no `Bot ` prefix and can act as the human who owns it), so the two
 * surfaces stay apart on purpose.
 */
class VerifierClient(
    private val http: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    /**
     * `GET /users/@me` with the raw user token.
     *
     * Returns null on 401/403 or a transport failure; the caller decides what to tell the user.
     * Both the raw token and the `Bot ` form are tried, because Discord rejects the wrong shape
     * with an unhelpful 401 and the reference did the same fallback (`auto-click.py:_verify_token`).
     */
    suspend fun verifyToken(token: String): VerifiedUser? = withContext(Dispatchers.IO) {
        val candidates = listOf(token, "Bot $token")
        for (candidate in candidates) {
            val request = Request.Builder()
                .url("$baseUrl/users/@me")
                .header("Authorization", candidate)
                .discordHeaders()
                .get()
                .build()
            try {
                val user = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "verify token rejected: ${response.code}")
                        return@use null
                    }
                    val body = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(body) }.getOrNull() ?: return@use null
                    val id = json.optString("id")
                    if (id.isEmpty()) return@use null
                    VerifiedUser(id = id, username = json.optString("username").ifEmpty { id })
                }
                if (user != null) return@withContext user
            } catch (failure: IOException) {
                Log.w(TAG, "verify failed for ${MaskedPreview.of(token)}")
                return@withContext null
            }
        }
        null
    }

    /**
     * `GET /channels/{id}/messages` - the last few messages in the watched channel.
     *
     * The engine uses this once per connect and from the confirm watchdog: a panel that was
     * already on screen before the verifier started never produces a MESSAGE_CREATE, so without
     * this the button would simply never be pressed. Newest-first, as Discord returns it.
     */
    suspend fun fetchRecentMessages(
        token: String,
        channelId: String,
        limit: Int = 10
    ): List<GatewayMessage> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/channels/$channelId/messages?limit=$limit")
            .header("Authorization", token)
            .discordHeaders()
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "history fetch rejected: ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string().orEmpty()
                val array = runCatching { JSONArray(body) }.getOrNull()
                    ?: return@withContext emptyList()
                val messages = ArrayList<GatewayMessage>(array.length())
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { messages += GatewayPayloads.parseMessage(it) }
                }
                messages
            }
        } catch (failure: IOException) {
            Log.w(TAG, "history fetch failed: ${failure.message}")
            emptyList()
        }
    }

    /**
     * `GET /channels/{id}` - only the owning guild id.
     *
     * The REST message history omits `guild_id` (only gateway frames carry it), but an interaction
     * click must include it, or Discord answers 400 "Unknown Integration". One lookup per verifier
     * start fills that hole; the reference kept the same cache.
     */
    suspend fun fetchChannelGuild(token: String, channelId: String): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/channels/$channelId")
                .header("Authorization", token)
                .discordHeaders()
                .get()
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "channel lookup rejected: ${response.code}")
                        return@withContext null
                    }
                    val body = response.body?.string().orEmpty()
                    runCatching { JSONObject(body).optString("guild_id") }
                        .getOrNull()
                        ?.takeIf(String::isNotEmpty)
                }
            } catch (failure: IOException) {
                Log.w(TAG, "channel lookup failed: ${failure.message}")
                null
            }
        }

    /**
     * `POST /interactions` - the same call a real client makes when a button is pressed.
     *
     * [messageFlags] must carry the ephemeral bit for ephemeral messages; Discord rejects the
     * interaction without it.
     */
    suspend fun clickButton(
        token: String,
        sessionId: String,
        guildId: String?,
        channelId: String,
        messageId: String,
        applicationId: String?,
        customId: String,
        messageFlags: Int
    ): ClickResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("type", INTERACTION_MESSAGE_COMPONENT)
            // Same shape the reference sends: microseconds, not a synthetic snowflake.
            .put("nonce", (System.currentTimeMillis() * 1_000L).toString())
            .put("channel_id", channelId)
            .put("message_id", messageId)
            .put("data", JSONObject().put("component_type", COMPONENT_BUTTON).put("custom_id", customId))
            .put("session_id", sessionId)

        guildId?.let { payload.put("guild_id", it) }
        applicationId?.let { payload.put("application_id", it) }
        if (messageFlags and EPHEMERAL_FLAG != 0) payload.put("message_flags", EPHEMERAL_FLAG)

        val request = Request.Builder()
            .url("$baseUrl/interactions")
            .header("Authorization", token)
            .discordHeaders()
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (failure: IOException) {
            Log.w(TAG, "click transport failure on $customId")
            return@withContext ClickResult.NetworkError
        }

        response.use {
            val body = if (it.code == 204) "" else it.body?.string().orEmpty()
            val detail = runCatching { JSONObject(body).optString("message") }
                .getOrNull()
                ?.takeIf(String::isNotEmpty)
            when (it.code) {
                204 -> ClickResult.Success
                429 -> {
                    var retryAfterMs = it.header("Retry-After")?.toDoubleOrNull()
                        ?.times(1_000.0)?.toLong() ?: DEFAULT_RETRY_AFTER_MS
                    runCatching { JSONObject(body).optDouble("retry_after") }
                        .getOrNull()
                        ?.takeIf { value -> value > 0.0 }
                        ?.let { seconds -> retryAfterMs = (seconds * 1_000.0).toLong() }
                    ClickResult.RateLimited(retryAfterMs.coerceAtLeast(0L))
                }

                // Same permanent set as the reference: an expired or foreign button only burns
                // the rate-limit budget on a retry, while 401/5xx may clear on its own.
                in PERMANENT_CODES -> {
                    Log.w(
                        TAG,
                        "click rejected ${it.code} on $customId: ${detail ?: body.take(200)} " +
                            "(msg=$messageId app=$applicationId guild=$guildId flags=$messageFlags)"
                    )
                    ClickResult.Permanent(it.code, detail)
                }

                else -> {
                    Log.w(TAG, "click failed ${it.code} on $customId: ${detail ?: body.take(200)}")
                    ClickResult.Other(it.code, detail)
                }
            }
        }
    }

    private fun Request.Builder.discordHeaders(): Request.Builder = this
        .header("User-Agent", GatewayPayloads.USER_AGENT)
        .header("X-Discord-Locale", "en-US")
        .header("Content-Type", JSON_MEDIA_TYPE.toString())

    companion object {
        private const val TAG = "VerifierClient"
        private const val DEFAULT_BASE_URL = "https://discord.com/api/v10"
        private const val INTERACTION_MESSAGE_COMPONENT = 3
        private const val COMPONENT_BUTTON = 2
        private const val EPHEMERAL_FLAG = 64
        private const val DEFAULT_RETRY_AFTER_MS = 1_000L

        /** Expired, foreign or malformed button: a retry fails the same way. */
        private val PERMANENT_CODES = setOf(400, 403, 404, 410)

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
