package com.gabut.autopost.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gabut.autopost.AutoPostApplication
import com.gabut.autopost.crypto.MaskedPreview
import com.gabut.autopost.data.db.BotTokenEntity
import com.gabut.autopost.data.db.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the token list, with everything the UI needs and nothing secret in plaintext. */
data class TokenRow(
    val id: Long,
    val name: String,
    val maskedToken: String,
    val channelCount: Int,
    val running: Boolean,
    val selected: Boolean,
    val needsReentry: Boolean
)

/** A channel plus the human-readable status line derived from its last attempt (D20). */
data class ChannelRow(
    val id: Long,
    val channelId: String,
    val message: String,
    val intervalSeconds: Int,
    val statusLine: String,
    val statusKind: ChannelStatusKind
)

enum class ChannelStatusKind { RUNNING, RATE_LIMITED, AUTH_FAILED, NEVER_POSTED, FAILING }

/**
 * The single ViewModel behind every tab.
 *
 * It exists so the screens stay declarative: nothing in the Compose tree decrypts a token, counts
 * channels, or talks to the service directly.
 */
class AutoPostViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AutoPostApplication

    val darkMode: StateFlow<Boolean> = app.settingsStore.darkModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val activeTokenId: StateFlow<Long?> = app.settingsStore.activeTokenIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Live from Room, so the status bar and the notification share one source of truth (D20). */
    private val channelCounts: StateFlow<Map<Long, Int>> = app.repository.observeChannelCounts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    val tokens: StateFlow<List<TokenRow>> = combine(
        app.repository.observeTokens(),
        channelCounts,
        activeTokenId
    ) { tokens, counts, selectedId ->
        tokens.map { token ->
            // One decrypt per row: the keystore round-trip is the expensive part here.
            val plaintext = app.cipher.decrypt(token.encryptedToken)
            TokenRow(
                id = token.id,
                name = token.name,
                maskedToken = MaskedPreview.of(plaintext ?: token.encryptedToken),
                channelCount = counts[token.id] ?: 0,
                running = token.running,
                selected = token.id == selectedId,
                needsReentry = plaintext == null
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Set when an alert could not be shown as a notification (D-permission denied). */
    val alertBanner: StateFlow<String?> = app.notifier.banner

    fun clearAlertBanner() = app.notifier.clearBanner()

    val summary: StateFlow<Pair<Int, Int>> = combine(
        app.repository.observeTokens(),
        channelCounts
    ) { tokens, counts ->
        val runningIds = tokens.filter { it.running }.map { it.id }
        runningIds.size to runningIds.sumOf { counts[it] ?: 0 }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0 to 0)

    fun observeChannels(tokenId: Long): StateFlow<List<ChannelRow>> = app.repository
        .observeChannels(tokenId)
        .map { channels -> channels.map { it.toRow() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun ChannelEntity.toRow(): ChannelRow {
        val now = System.currentTimeMillis()
        val status = lastStatus
        val kind = when {
            status == null && lastPostedAt == null -> ChannelStatusKind.NEVER_POSTED
            status == 401 || status == 403 -> ChannelStatusKind.AUTH_FAILED
            status == 429 -> ChannelStatusKind.RATE_LIMITED
            status == null -> ChannelStatusKind.FAILING
            else -> ChannelStatusKind.RUNNING
        }
        val line = when (kind) {
            ChannelStatusKind.RUNNING -> {
                val remaining = ((nextTickAt ?: now) - now).coerceAtLeast(0) / 1000
                "Running (next in %02d:%02d)".format(remaining / 60, remaining % 60)
            }
            ChannelStatusKind.RATE_LIMITED -> {
                val remaining = ((nextTickAt ?: now) - now).coerceAtLeast(0) / 1000
                "Rate limited (retry in ${remaining}s)"
            }
            ChannelStatusKind.AUTH_FAILED -> "Auth failed - fix token"
            ChannelStatusKind.FAILING -> "Failing - retrying with backoff"
            ChannelStatusKind.NEVER_POSTED -> "Never posted yet"
        }
        return ChannelRow(
            id = id,
            channelId = channelId,
            message = message,
            intervalSeconds = intervalSeconds,
            statusLine = line,
            statusKind = kind
        )
    }

    // ---- actions -------------------------------------------------------

    fun selectToken(tokenId: Long) {
        // Switching the active bot must never stop another bot's posting (D15).
        viewModelScope.launch { app.settingsStore.setActiveTokenId(tokenId) }
    }

    fun start(tokenId: Long) = viewModelScope.launch {
        app.controller.start(tokenId)
    }

    fun stop(tokenId: Long) = viewModelScope.launch {
        app.controller.stop(tokenId)
    }

    fun addToken(name: String, token: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        val trimmedName = name.trim()
        val trimmedToken = token.trim()
        when {
            trimmedName.isEmpty() -> _error.value = "Bot name is required"
            trimmedToken.isEmpty() -> _error.value = "Bot token is required"
            else -> {
                val existing = app.repository.getAllTokensOnce()
                if (existing.any { app.cipher.decrypt(it.encryptedToken) == trimmedToken }) {
                    _error.value = "This token is already used by another bot"
                    return@launch
                }
                val uniqueName = uniqueName(trimmedName, existing.map { it.name })
                app.repository.addToken(
                    BotTokenEntity(
                        name = uniqueName,
                        encryptedToken = app.cipher.encrypt(trimmedToken)
                    )
                )
                _error.value = null
                onDone()
            }
        }
    }

    private fun uniqueName(desired: String, taken: List<String>): String {
        if (desired !in taken) return desired
        var suffix = 2
        while ("$desired ($suffix)" in taken) suffix++
        return "$desired ($suffix)"
    }

    fun deleteToken(tokenId: Long) = viewModelScope.launch {
        app.controller.stop(tokenId)
        app.repository.deleteToken(tokenId)
        if (activeTokenId.value == tokenId) app.settingsStore.setActiveTokenId(null)
    }

    fun saveChannel(
        tokenId: Long,
        channelId: String,
        message: String,
        intervalSeconds: Int,
        onDone: () -> Unit = {}
    ) = viewModelScope.launch {
        val trimmedChannel = channelId.trim()
        when {
            trimmedChannel.isEmpty() -> _error.value = "Channel id is required"
            message.isBlank() -> _error.value = "Message is required"
            intervalSeconds < 1 -> _error.value = "Interval must be at least 1 second"
            else -> {
                app.repository.upsertChannel(tokenId, trimmedChannel, message, intervalSeconds)
                _error.value = null
                val running = app.repository.getToken(tokenId)?.running == true
                if (running) app.scheduler.rearm(tokenId, trimmedChannel)
                onDone()
            }
        }
    }

    fun deleteChannel(tokenId: Long, channelId: String) = viewModelScope.launch {
        app.repository.deleteChannel(tokenId, channelId)
    }

    fun saveTokenSettings(tokenId: Long, name: String, token: String?, onDone: () -> Unit = {}) =
        viewModelScope.launch {
            val current = app.repository.getToken(tokenId) ?: return@launch
            val existing = app.repository.getAllTokensOnce().filter { it.id != tokenId }

            if (name.isNotBlank() && name.trim() != current.name) {
                if (existing.any { it.name == name.trim() }) {
                    _error.value = "Another bot already uses that name"
                    return@launch
                }
            }
            if (token != null && token.isNotBlank()) {
                if (existing.any { app.cipher.decrypt(it.encryptedToken) == token.trim() }) {
                    _error.value = "This token is already used by another bot"
                    return@launch
                }
            }

            val updated = current.copy(
                name = name.ifBlank { current.name }.trim(),
                encryptedToken = if (token.isNullOrBlank()) {
                    current.encryptedToken
                } else {
                    app.cipher.encrypt(token.trim())
                }
            )
            app.repository.updateToken(updated)
            _error.value = null
            onDone()
        }

    fun saveWebhook(tokenId: Long, enabled: Boolean, url: String, onDone: () -> Unit = {}) =
        viewModelScope.launch {
            val current = app.repository.getToken(tokenId) ?: return@launch
            val encryptedUrl = if (url.isBlank() || url.trim() == currentWebhookPlain(tokenId)) {
                current.encryptedWebhookUrl
            } else {
                app.cipher.encrypt(url.trim())
            }
            app.repository.updateToken(
                current.copy(useWebhook = enabled, encryptedWebhookUrl = encryptedUrl)
            )
            _info.value = "Webhook settings saved"
            onDone()
        }

    private suspend fun currentWebhookPlain(tokenId: Long): String? =
        app.repository.getToken(tokenId)?.let { app.cipher.decrypt(it.encryptedWebhookUrl) }

    fun testWebhook(tokenId: Long, onResult: (Boolean, String) -> Unit) = viewModelScope.launch {
        val token = app.repository.getToken(tokenId) ?: return@launch
        val url = app.cipher.decrypt(token.encryptedWebhookUrl)
        if (!token.useWebhook || url.isNullOrBlank()) {
            onResult(false, "Webhook is not enabled or the URL is missing")
            return@launch
        }
        val result = withContext(Dispatchers.IO) {
            app.client.sendWebhookLog(url, null, "Test message from Auto Post", success = true)
        }
        val ok = result is com.gabut.autopost.network.SendResult.Success
        onResult(ok, if (ok) "Test webhook sent" else "Test webhook failed")
    }

    suspend fun batteryCardDismissed(): Boolean =
        app.settingsStore.batteryCardDismissedFlow.first()

    fun dismissBatteryCard() = viewModelScope.launch {
        app.settingsStore.setBatteryCardDismissed(true)
    }

    fun setDarkMode(enabled: Boolean) = viewModelScope.launch {
        app.settingsStore.setDarkMode(enabled)
    }

    fun clearMessages() {
        _error.value = null
        _info.value = null
    }

    /** First token id, used when nothing is selected yet. */
    suspend fun firstTokenId(): Long? = app.repository.getAllTokensOnce().firstOrNull()?.id

    fun revealToken(tokenId: Long, onRevealed: (String?) -> Unit) = viewModelScope.launch {
        val token = app.repository.getToken(tokenId) ?: return@launch
        onRevealed(app.cipher.decrypt(token.encryptedToken))
    }

    fun tokenById(tokenId: Long): StateFlow<BotTokenEntity?> = app.repository.observeTokens()
        .map { tokens -> tokens.firstOrNull { it.id == tokenId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}