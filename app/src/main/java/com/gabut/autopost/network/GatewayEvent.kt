package com.gabut.autopost.network

/** One button found on a message the verifier is allowed to act on. */
data class GatewayButton(val label: String, val customId: String)

/**
 * Facts the verifier reacts to, parsed out of the gateway payload.
 *
 * The socket layer never decides *what to click*: it only reports what arrived. The decision lives
 * in [MessageTargeting] and the engine, which keeps the security-critical rule ("only click buttons
 * that belong to this account") testable without a network.
 */
sealed interface GatewayEvent {

    /** The socket is open, but the session is not identified yet. */
    data object SocketOpen : GatewayEvent

    /** IDENTIFY was accepted: a session id now exists and interactions can be sent. */
    data class Ready(val sessionId: String, val userId: String) : GatewayEvent

    /** A message that may or may not be ours; [GatewayMessage] already carries the ownership facts. */
    data class Message(val message: GatewayMessage) : GatewayEvent

    /** The socket closed. [code] 4004 means the token was rejected. */
    data class Closed(val code: Int, val reason: String?) : GatewayEvent

    /** A transport-level failure (offline, reset). Retryable. */
    data class Failed(val reason: String) : GatewayEvent
}

/**
 * The subset of MESSAGE_CREATE / MESSAGE_UPDATE this feature needs.
 *
 * Ownership facts are kept as raw ids rather than a pre-computed boolean so the rule can be applied
 * (and tested) against the verifier's *own* user id, which is only known after READY.
 */
data class GatewayMessage(
    val id: String,
    val channelId: String,
    val guildId: String?,
    val applicationId: String?,
    val authorId: String?,
    val mentionedUserIds: List<String>,
    val flags: Int,
    val isEphemeral: Boolean,
    /** User id that triggered the interaction, from `interaction_metadata` / legacy `interaction`. */
    val interactionUserId: String?,
    /** Author of the message being replied to; the fallback for message-command flows. */
    val referencedAuthorId: String?,
    val buttons: List<GatewayButton>
)

/** Which labels this feature is willing to press. */
object VerificationButtons {
    const val AUTHENTICATE = "Authenticate"
    const val CONFIRM = "Yes, Log Me In"

    val ALL: Set<String> = setOf(AUTHENTICATE, CONFIRM)
}

/**
 * The ownership rule, lifted verbatim from `auto-click.py:_message_targets_me`.
 *
 * An ephemeral flag alone is **not** proof of ownership: the gateway can dispatch ephemeral
 * MESSAGE_CREATE events to other members of the channel (official clients filter them client-side).
 * Trusting the flag alone is what made one account click another account's verify button, so the
 * interaction's owner is checked explicitly and an unverifiable message is skipped rather than
 * guessed at.
 */
object MessageTargeting {

    enum class Decision { MINE, NOT_MINE, UNVERIFIABLE }

    fun decide(message: GatewayMessage, myUserId: String?): Decision {
        if (myUserId.isNullOrBlank()) return Decision.UNVERIFIABLE

        // 1) An explicit mention of us is unambiguous.
        if (message.mentionedUserIds.any { it == myUserId }) return Decision.MINE

        // A public message that does not mention us is simply not ours.
        if (!message.isEphemeral) return Decision.NOT_MINE

        // 2) Ephemeral: verify the interaction actually belongs to this account.
        message.interactionUserId?.let { owner ->
            return if (owner == myUserId) Decision.MINE else Decision.NOT_MINE
        }

        // Current API carries it in `interaction_metadata`; older payloads used `interaction`.
        // Both are folded into `interactionUserId` at parse time, so reaching here means the
        // gateway sent neither.

        // 3) Message-command flows (`.verify`) reply to our own message.
        message.referencedAuthorId?.let { author ->
            return if (author == myUserId) Decision.MINE else Decision.NOT_MINE
        }

        return Decision.UNVERIFIABLE
    }

    /** Buttons on [message] this feature is willing to press, in the order they appear. */
    fun actionableButtons(message: GatewayMessage): List<GatewayButton> =
        message.buttons.filter { it.label in VerificationButtons.ALL }
}
