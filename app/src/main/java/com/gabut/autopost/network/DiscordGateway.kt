package com.gabut.autopost.network

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A Discord gateway connection that reports frames as a [Flow].
 *
 * Owns no reconnect policy: exactly as with [DiscordClient], the caller decides when to try again,
 * because a tight reconnect loop is what turns a 4004 into a banned account.
 *
 * The flow ends when the socket closes; the collector re-subscribes to reconnect.
 */
class DiscordGateway(
    private val http: OkHttpClient = defaultClient(),
    private val url: String = DEFAULT_URL
) {

    /**
     * Connects as [token] and emits every frame until the socket closes.
     *
     * [token] is a **user** token (no `Bot ` prefix) - that is what lets this session press buttons
     * as the human who owns the account.
     */
    fun connect(token: String): Flow<GatewayEvent> = callbackFlow {
        var identified = false

        val listener = object : WebSocketListener() {

            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = runCatching { JSONObject(text) }.getOrNull() ?: return

                // HELLO carries the heartbeat interval. This is the only place it can be read, so
                // IDENTIFY and the ping loop are armed here rather than inside the parser.
                if (root.optInt("op", -1) == GatewayPayloads.OP_HELLO) {
                    val intervalMs = root.optJSONObject("d")
                        ?.optLong("heartbeat_interval", 0L)
                        ?: 0L
                    if (!identified) {
                        identified = true
                        launch { runSession(webSocket, token, intervalMs) }
                    }
                    return
                }

                GatewayPayloads.parse(text)?.let { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // 4004 is terminal; report it and let the collector decide to stop.
                trySend(GatewayEvent.Closed(code, reason))
                webSocket.close(NORMAL_CLOSE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(GatewayEvent.Closed(code, reason))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "gateway failure: ${t.message}")
                trySend(GatewayEvent.Failed(t.message ?: "socket failure"))
                close()
            }
        }

        val socket = http.newWebSocket(Request.Builder().url(url).build(), listener)

        awaitClose { runCatching { socket.cancel() } }
    }

    /**
     * IDENTIFY, then the heartbeat loop, for as long as the socket lives.
     *
     * Cancelled automatically when the flow's collector goes away.
     */
    private suspend fun runSession(socket: WebSocket, token: String, intervalMs: Long) {
        // A short jitter before IDENTIFY avoids a thundering herd when several verifiers start
        // at the same moment; Discord expects IDENTIFY promptly, not instantly.
        delay(IDENTIFY_JITTER_MS)
        socket.send(GatewayPayloads.identify(token, DEFAULT_BUILD_NUMBER))

        if (intervalMs <= 0L) return
        while (true) {
            delay(intervalMs)
            if (!socket.send(GatewayPayloads.heartbeat())) return
        }
    }

    private companion object {
        const val TAG = "DiscordGateway"
        const val DEFAULT_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        const val DEFAULT_BUILD_NUMBER = 544136
        const val NORMAL_CLOSE = 1000
        const val IDENTIFY_JITTER_MS = 500L
    }
}

private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    // 0 = no read timeout: a gateway connection is idle by design between events.
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(25, TimeUnit.SECONDS)
    .build()
