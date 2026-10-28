package com.gabut.autopost.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON <-> model translation for the gateway, kept free of Android and OkHttp so the parsing
 * rules can be unit-tested directly (they carry the ownership check).
 */
object GatewayPayloads {

    /** Gateway opcodes used here. */
    const val OP_HELLO = 10
    const val OP_IDENTIFY = 2
    const val OP_HEARTBEAT = 1
    const val OP_HEARTBEAT_ACK = 11

    /** Reconnect-with-resume is not implemented; READY is the only "we are live" signal we need. */
    const val OP_INVALID_SESSION = 9

    const val EVENT_READY = "READY"
    const val EVENT_MESSAGE_CREATE = "MESSAGE_CREATE"
    const val EVENT_MESSAGE_UPDATE = "MESSAGE_UPDATE"

    /** A forced close the reference treats as "stop reconnecting". */
    const val CLOSE_AUTH_FAILED = 4004

    /** IDENTIFY payload. Kept close to the reference so the session looks like a normal client. */
    fun identify(token: String, buildNumber: Int): String {
        val properties = JSONObject()
            .put("os", "Windows")
            .put("browser", "Chrome")
            .put("device", "")
            .put("system_locale", "en-US")
            .put("browser_user_agent", USER_AGENT)
            .put("browser_version", "131.0.0.0")
            .put("os_version", "10")
            .put("referrer", "")
            .put("referring_domain", "")
            .put("referrer_current", "")
            .put("referring_domain_current", "")
            .put("client_build_number", buildNumber)
            .put("release_channel", "stable")
            .put("client_event_source", JSONObject.NULL)

        return JSONObject()
            .put("op", OP_IDENTIFY)
            .put(
                "d",
                JSONObject()
                    .put("token", token)
                    .put("capabilities", 16381)
                    .put("properties", properties)
                    .put("compress", false)
                    // An empty presence: advertising something custom in IDENTIFY is a free
                    // bot-detection signal.
                    .put(
                        "presence",
                        JSONObject()
                            .put("status", "online")
                            .put("since", 0)
                            .put("activities", JSONArray())
                            .put("afk", false)
                    )
                    // Real clients always send client_state; leaving it out is another tell.
                    .put(
                        "client_state",
                        JSONObject()
                            .put("guild_versions", JSONObject())
                            .put("highest_last_message_id", "0")
                            .put("read_state_version", 0)
                            .put("user_guild_settings_version", -1)
                            .put("user_settings_version", -1)
                            .put("private_channels_version", 0)
                            .put("api_code_version", 0)
                    )
            )
            .toString()
    }

    fun heartbeat(): String = JSONObject().put("op", OP_HEARTBEAT).put("d", JSONObject.NULL).toString()

    /** Decodes one gateway frame into a [GatewayEvent]; unknown frames return null. */
    fun parse(frame: String): GatewayEvent? {
        val root = try {
            JSONObject(frame)
        } catch (ignored: Exception) {
            return null
        }

        when (val op = root.optInt("op", -1)) {
            OP_HEARTBEAT_ACK -> return null
            OP_INVALID_SESSION -> return GatewayEvent.Failed("session invalidated")
            else -> if (op != 0) return null
        }

        val data = root.optJSONObject("d") ?: return null
        return when (root.optString("t")) {
            EVENT_READY -> {
                val sessionId = data.optString("session_id").takeIf { it.isNotEmpty() }
                val userId = data.optJSONObject("user")?.optString("id").orEmpty()
                if (sessionId == null || userId.isEmpty()) null
                else GatewayEvent.Ready(sessionId, userId)
            }

            EVENT_MESSAGE_CREATE, EVENT_MESSAGE_UPDATE -> GatewayEvent.Message(parseMessage(data))

            else -> null
        }
    }

    /**
     * Decodes one message object, shared by gateway frames and the REST history fetch so both
     * paths carry exactly the same ownership facts.
     */
    fun parseMessage(data: JSONObject): GatewayMessage {
        val flags = data.optInt("flags", 0)
        val mentions = mutableListOf<String>()
        data.optJSONArray("mentions")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.optString("id")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(mentions::add)
            }
        }

        return GatewayMessage(
            id = data.optString("id"),
            channelId = data.optString("channel_id"),
            guildId = data.optString("guild_id").takeIf { it.isNotEmpty() },
            applicationId = data.optString("application_id").takeIf { it.isNotEmpty() }
                ?: data.optString("webhook_id").takeIf { it.isNotEmpty() },
            authorId = data.optJSONObject("author")?.optString("id")?.takeIf { it.isNotEmpty() },
            mentionedUserIds = mentions,
            flags = flags,
            isEphemeral = flags and EPHEMERAL_FLAG != 0,
            interactionUserId = interactionUserId(data),
            referencedAuthorId = data.optJSONObject("referenced_message")
                ?.optJSONObject("author")
                ?.optString("id")
                ?.takeIf { it.isNotEmpty() },
            buttons = buttons(data.optJSONArray("components"))
        )
    }

    /**
     * The interaction owner, from the current API shape first and the deprecated one second.
     *
     * Discord moved `interaction.user` into `interaction_metadata.user` (with a bare `user_id`
     * variant); supporting both keeps older gateways working.
     */
    private fun interactionUserId(data: JSONObject): String? {
        data.optJSONObject("interaction_metadata")?.let { meta ->
            meta.optJSONObject("user")?.optString("id")
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            meta.optString("user_id").takeIf { it.isNotEmpty() }?.let { return it }
        }
        data.optJSONObject("interaction")
            ?.optJSONObject("user")
            ?.optString("id")
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return null
    }

    /** Flattens action rows into a button list, ignoring non-button components. */
    private fun buttons(rows: JSONArray?): List<GatewayButton> {
        if (rows == null) return emptyList()
        val found = mutableListOf<GatewayButton>()
        for (rowIndex in 0 until rows.length()) {
            val components = rows.optJSONObject(rowIndex)?.optJSONArray("components") ?: continue
            for (itemIndex in 0 until components.length()) {
                val component = components.optJSONObject(itemIndex) ?: continue
                if (component.optInt("type", -1) != COMPONENT_BUTTON) continue
                val label = component.optString("label").takeIf { it.isNotEmpty() } ?: continue
                val customId = component.optString("custom_id").takeIf { it.isNotEmpty() } ?: continue
                found += GatewayButton(label, customId)
            }
        }
        return found
    }

    private const val COMPONENT_BUTTON = 2
    private const val EPHEMERAL_FLAG = 64

    /**
     * The same desktop UA the reference sends. Discord's edge is noticeably harder on requests
     * with a bare library UA, and `/interactions` is the endpoint where that shows up first.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
}
