package com.gabut.autopost.network

import android.util.Log
import com.gabut.autopost.crypto.MaskedPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Discord REST calls used by the posting loop.
 *
 * The client owns no retry policy on purpose: the scheduler decides when to try again, because a
 * blind retry is what turns a 429 into a revoked token. Every request for a bot token first takes
 * a permit from [rateLimiter].
 */
class DiscordClient(
    private val http: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val rateLimiter: PerTokenRateLimiter = PerTokenRateLimiter(),
    private val now: () -> Long = System::currentTimeMillis
) {

    /** Posts [content] to a channel as the bot behind [token] (raw token, no `Bot ` prefix). */
    suspend fun sendMessage(token: String, channelId: String, content: String): SendResult {
        rateLimiter.acquire("token:$token")
        val body = JSONObject().put("content", content).toString()
        val request = Request.Builder()
            .url("$baseUrl/channels/$channelId/messages")
            .header("Authorization", token)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    /**
     * Sends the reference's logging embed to [webhookUrl] (shape copied from `autopost.py:70-89`).
     *
     * [success] only switches the colour and the status field; the rest of the embed is identical,
     * which is what makes the two messages comparable in Discord.
     */
    suspend fun sendWebhookLog(
        webhookUrl: String,
        channelId: String?,
        message: String,
        success: Boolean
    ): SendResult {
        val embed = buildEmbed(channelId, message, success)
        val payload = JSONObject().put("embeds", JSONArray().put(embed)).toString()
        val request = Request.Builder()
            .url(webhookUrl)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request)
    }

    internal fun buildEmbed(channelId: String?, message: String, success: Boolean): JSONObject {
        val statusText = if (success) "> Success" else "> Failed"
        val timestamp = SimpleDateFormat("dd MMMM yyyy  hh:mm:ss a", Locale.ENGLISH)
            .format(Date(now()))

        return JSONObject()
            .put("title", "\u003ca:ms_discord:1129069176917610619\u003e FunkPost Discord" +
                "\u003ca:ms_discord:1129069176917610619\u003e")
            .put("description", "> **Details Info**")
            .put("color", if (success) COLOUR_SUCCESS else COLOUR_FAILURE)
            .put("fields", JSONArray().apply {
                put(field("<a:live:1247888274161143878> Status Log", statusText))
                put(field("<:BS_Time:1182386606661972099> Date Time", "> $timestamp"))
                put(
                    field(
                        "<:discord:1263889532688797727> Channel Target",
                        if (channelId.isNullOrBlank()) "> Unknown" else "> <#$channelId>"
                    )
                )
                put(field("<a:ki_verify:1193420850511224913> Status Message", "> $message"))
            })
            .put("footer", JSONObject().put("text", FOOTER))
            .put("timestamp", isoNow())
    }

    private fun field(name: String, value: String): JSONObject =
        JSONObject().put("name", name).put("value", value).put("inline", false)

    private fun isoNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(Date(now()))
    }

    private suspend fun execute(request: Request): SendResult = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).execute()
        } catch (failure: IOException) {
            Log.w(TAG, "transport failure for ${MaskedPreview.of(request.header("Authorization") ?: request.url.toString())}")
            return@withContext SendResult.NetworkError
        }
        response.use { it.toResult() }
    }

    private fun Response.toResult(): SendResult = when {
        code in 200..299 -> SendResult.Success(code, nextAllowedAtMillis())
        code == 401 || code == 403 -> SendResult.AuthFailed(code)
        code == 429 -> parseRateLimited()
        else -> SendResult.Other(code)
    }

    /** Turns `X-RateLimit-Remaining: 0` + `X-RateLimit-Reset-After` into an absolute deadline. */
    private fun Response.nextAllowedAtMillis(): Long? {
        val remaining = header("X-RateLimit-Remaining")?.toIntOrNull() ?: return null
        if (remaining > 0) return null
        val resetAfter = header("X-RateLimit-Reset-After")?.toDoubleOrNull() ?: return null
        return now() + (resetAfter * 1000.0).toLong()
    }

    private fun Response.parseRateLimited(): SendResult {
        val rawBody = body?.string().orEmpty()
        var retryAfterMs = 1000L
        var global = false

        if (rawBody.isNotBlank()) {
            try {
                val json = JSONObject(rawBody)
                if (json.has("retry_after")) {
                    retryAfterMs = (json.getDouble("retry_after") * 1000.0).toLong()
                }
                global = json.optBoolean("global", false)
            } catch (ignored: org.json.JSONException) {
                // Keep the defaults; a malformed 429 is still a 429.
            }
        }

        header("Retry-After")?.toDoubleOrNull()?.let { retryAfterMs = (it * 1000.0).toLong() }
        return SendResult.RateLimited(retryAfterMs.coerceAtLeast(0L), global)
    }

    companion object {
        private const val TAG = "DiscordClient"
        private const val DEFAULT_BASE_URL = "https://discord.com/api/v10"
        private const val COLOUR_SUCCESS = 65280
        private const val COLOUR_FAILURE = 16711680
        private const val FOOTER = "FunkPost By Lantas Continental. design By Void_String"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}