package com.gabut.autopost.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gabut.autopost.AutoPostApplication
import com.gabut.autopost.crypto.MaskedPreview
import com.gabut.autopost.data.PostLogEventType
import com.gabut.autopost.data.db.BotTokenEntity
import com.gabut.autopost.data.db.ChannelEntity
import com.gabut.autopost.data.db.PostLogEntity
import com.gabut.autopost.data.db.VerifierEntity
import com.gabut.autopost.ui.i18n.AppLanguage
import com.gabut.autopost.ui.i18n.LanguageState
import com.gabut.autopost.ui.i18n.t
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

/**
 * One row of the channel list.
 *
 * The countdown is deliberately NOT a pre-rendered string: a string cached here only changes when
 * the database changes, so "next in 04:12" would freeze until the next post. The row carries the
 * raw [nextTickAt] and the screen formats it against a ticking clock.
 */
data class ChannelRow(
    val id: Long,
    val channelId: String,
    val message: String,
    val intervalSeconds: Int,
    val nextTickAt: Long?,
    val statusKind: ChannelStatusKind
)

/** One row of the Verifiers tab: a user token that clicks verify buttons. */
data class VerifierRow(
    val id: Long,
    val name: String,
    val maskedToken: String,
    val watchChannelId: String,
    val running: Boolean,
    val clickedCount: Int,
    val lastEventText: String?,
    val statusKind: VerifierStatusKind
)

enum class VerifierStatusKind { WATCHING, STOPPED, AUTH_FAILED, UNREADABLE }

enum class ChannelStatusKind { RUNNING, RATE_LIMITED, AUTH_FAILED, NEVER_POSTED, FAILING }

/** One row of the Logs tab, without leaking the Room entity into the Compose layer. */
data class LogRow(
    val id: Long,
    val botName: String,
    val channelId: String?,
    val summary: String,
    val statusCode: Int?,
    val occurredAt: Long,
    val kind: LogKind
)

enum class LogKind { POSTED, RATE_LIMITED, AUTH_FAILED, FAILING, OTHER }

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

    /**
     * The chosen UI language. An unset preference follows the phone, so an Indonesian device opens
     * in Indonesian without the owner touching Settings.
     */
    val language: StateFlow<AppLanguage> = app.settingsStore.languageFlow
        .map { code -> AppLanguage.fromCode(code) ?: AppLanguage.systemDefault() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppLanguage.systemDefault())

    init {
        // Mirror the stored choice into the global that `t()` reads. This runs in the ViewModel,
        // not in composition, so the new language is in place before the recomposition that the
        // StateFlow triggers - no restart and no one-frame flash of the old language.
        viewModelScope.launch {
            app.settingsStore.languageFlow.collect { code ->
                LanguageState.set(AppLanguage.fromCode(code) ?: AppLanguage.systemDefault())
            }
        }
    }

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

    val logs: StateFlow<List<LogRow>> = app.repository.observeLogs()
        .map { entries -> entries.map { it.toRow() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- auto-verifiers ------------------------------------------------

    val verifiers: StateFlow<List<VerifierRow>> = app.repository.observeVerifiers()
        .map { entities ->
            entities.map { entity ->
                val plaintext = app.cipher.decrypt(entity.encryptedToken)
                VerifierRow(
                    id = entity.id,
                    name = entity.name,
                    maskedToken = MaskedPreview.of(plaintext ?: entity.encryptedToken),
                    watchChannelId = entity.watchChannelId,
                    running = entity.running,
                    clickedCount = entity.clickedCount,
                    lastEventText = entity.lastEventText,
                    statusKind = when {
                        plaintext == null -> VerifierStatusKind.UNREADABLE
                        entity.lastStatus == 401 -> VerifierStatusKind.AUTH_FAILED
                        entity.running -> VerifierStatusKind.WATCHING
                        else -> VerifierStatusKind.STOPPED
                    }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Verifier accounts currently watching, for the top bar summary. */
    val runningVerifiers: StateFlow<Int> = app.repository.observeVerifiers()
        .map { entities -> entities.count { it.running } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun startVerifier(id: Long) = viewModelScope.launch { app.controller.verifyStart(id) }

    fun stopVerifier(id: Long) = viewModelScope.launch { app.controller.verifyStop(id) }

    /**
     * Adds a verifier. When [botTokenId] is given, that bot's ciphertext is reused verbatim - the
     * same token then powers both autopost and auto-verify without the user re-typing it.
     */
    fun addVerifier(
        name: String,
        token: String = "",
        watchChannelId: String,
        humanize: Boolean = true,
        baseDelaySeconds: Int = 1,
        maxClickRetries: Int = 3,
        botTokenId: Long? = null,
        onDone: () -> Unit = {}
    ) = viewModelScope.launch {
        val trimmedName = name.trim()
        val trimmedChannel = watchChannelId.trim()
        when {
            trimmedName.isEmpty() -> _error.value = t(
                "Verifier name is required",
                "Nama verifier wajib diisi"
            )
            trimmedChannel.isEmpty() -> _error.value = t(
                "Watch channel id is required",
                "ID channel pantauan wajib diisi"
            )
            else -> {
                val existing = app.repository.getAllVerifiersOnce()
                val bot = botTokenId?.let { app.repository.getToken(it) }
                val plaintext = if (bot != null) {
                    app.cipher.decrypt(bot.encryptedToken) ?: run {
                        _error.value = t("Bot token cannot be read", "Token bot tidak bisa dibaca")
                        return@launch
                    }
                } else {
                    val trimmedToken = token.trim()
                    if (trimmedToken.isEmpty()) {
                        _error.value = t("User token is required", "Token user wajib diisi")
                        return@launch
                    }
                    trimmedToken
                }
                if (existing.any { app.cipher.decrypt(it.encryptedToken) == plaintext }) {
                    _error.value = t(
                        "This token is already used by another verifier",
                        "Token ini sudah dipakai verifier lain"
                    )
                    return@launch
                }
                val finalName = if (trimmedName.isNotBlank()) trimmedName else (bot?.name ?: "")
                val baseMs = baseDelaySeconds.coerceAtLeast(1) * 1_000L
                app.repository.addVerifier(
                    VerifierEntity(
                        name = uniqueName(finalName, existing.map { it.name }),
                        // Reuse the bot's ciphertext verbatim: same cipher and key, so it is valid
                        // here too and the token never has to leave ciphertext form.
                        encryptedToken = if (bot != null) bot.encryptedToken else app.cipher.encrypt(plaintext),
                        watchChannelId = trimmedChannel,
                        humanize = humanize,
                        baseDelayMs = baseMs,
                        minDelayMs = (baseMs / 2).coerceAtLeast(100L),
                        maxDelayMs = maxOf(baseMs * 10, 30_000L),
                        maxClickRetries = maxClickRetries.coerceIn(1, 10)
                    )
                )
                _error.value = null
                onDone()
            }
        }
    }

    fun saveVerifier(
        id: Long,
        name: String,
        token: String?,
        watchChannelId: String,
        humanize: Boolean,
        baseDelaySeconds: Int,
        maxClickRetries: Int,
        onDone: () -> Unit = {}
    ) = viewModelScope.launch {
        val current = app.repository.getVerifier(id) ?: return@launch
        val others = app.repository.getAllVerifiersOnce().filter { it.id != id }
        val trimmedName = name.trim()
        val trimmedChannel = watchChannelId.trim()

        when {
            trimmedName.isEmpty() -> {
                _error.value = t("Verifier name is required", "Nama verifier wajib diisi")
                return@launch
            }
            trimmedChannel.isEmpty() -> {
                _error.value = t(
                    "Watch channel id is required",
                    "ID channel pantauan wajib diisi"
                )
                return@launch
            }
            others.any { it.name == trimmedName } -> {
                _error.value = t(
                    "Another verifier already uses that name",
                    "Nama itu sudah dipakai verifier lain"
                )
                return@launch
            }
            !token.isNullOrBlank() && others.any {
                app.cipher.decrypt(it.encryptedToken) == token.trim()
            } -> {
                _error.value = t(
                    "This token is already used by another verifier",
                    "Token ini sudah dipakai verifier lain"
                )
                return@launch
            }
        }

        val baseMs = baseDelaySeconds.coerceAtLeast(1) * 1_000L
        val updated = current.copy(
            name = trimmedName,
            watchChannelId = trimmedChannel,
            humanize = humanize,
            baseDelayMs = baseMs,
            minDelayMs = (baseMs / 2).coerceAtLeast(100L),
            maxDelayMs = maxOf(baseMs * 10, 30_000L),
            maxClickRetries = maxClickRetries.coerceIn(1, 10),
            encryptedToken = if (token.isNullOrBlank()) {
                current.encryptedToken
            } else {
                app.cipher.encrypt(token.trim())
            }
        )
        app.repository.updateVerifier(updated)
        _error.value = null
        _info.value = t("Verifier saved", "Verifier disimpan")
        // A running loop holds the old config; restart it so the edit takes effect now.
        if (current.running) {
            app.controller.verifyStop(id)
            app.controller.verifyStart(id)
        }
        onDone()
    }

    fun deleteVerifier(id: Long) = viewModelScope.launch {
        app.controller.verifyStop(id)
        app.repository.deleteVerifier(id)
    }

    fun revealVerifierToken(id: Long, onRevealed: (String?) -> Unit) = viewModelScope.launch {
        val entity = app.repository.getVerifier(id) ?: return@launch
        onRevealed(app.cipher.decrypt(entity.encryptedToken))
    }

    fun clearLogs() = viewModelScope.launch {
        app.repository.clearLogs()
    }

    private fun PostLogEntity.toRow(): LogRow = LogRow(
        id = id,
        botName = botName,
        channelId = channelId,
        summary = summary,
        statusCode = statusCode,
        occurredAt = occurredAt,
        kind = when (eventType) {
            PostLogEventType.POSTED, PostLogEventType.VERIFIED -> LogKind.POSTED
            PostLogEventType.RATE_LIMITED -> LogKind.RATE_LIMITED
            PostLogEventType.AUTH_FAILED -> LogKind.AUTH_FAILED
            PostLogEventType.CHANNEL_FAILING, PostLogEventType.VERIFY_FAILED -> LogKind.FAILING
            PostLogEventType.VERIFY_SKIPPED -> LogKind.OTHER
            else -> LogKind.OTHER
        }
    )

    private fun ChannelEntity.toRow(): ChannelRow {
        val status = lastStatus
        val kind = when {
            status == null && lastPostedAt == null -> ChannelStatusKind.NEVER_POSTED
            status == 401 || status == 403 -> ChannelStatusKind.AUTH_FAILED
            status == 429 -> ChannelStatusKind.RATE_LIMITED
            status == null -> ChannelStatusKind.FAILING
            else -> ChannelStatusKind.RUNNING
        }
        return ChannelRow(
            id = id,
            channelId = channelId,
            message = message,
            intervalSeconds = intervalSeconds,
            nextTickAt = nextTickAt,
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
            trimmedName.isEmpty() -> _error.value = t("Bot name is required", "Nama bot wajib diisi")
            trimmedToken.isEmpty() -> _error.value = t("Bot token is required", "Token bot wajib diisi")
            else -> {
                val existing = app.repository.getAllTokensOnce()
                if (existing.any { app.cipher.decrypt(it.encryptedToken) == trimmedToken }) {
                    _error.value = t(
                        "This token is already used by another bot",
                        "Token ini sudah dipakai bot lain"
                    )
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
            trimmedChannel.isEmpty() -> _error.value = t("Channel id is required", "ID channel wajib diisi")
            message.isBlank() -> _error.value = t("Message is required", "Pesan wajib diisi")
            message.length > com.gabut.autopost.data.PostRepository.MAX_MESSAGE_LENGTH ->
                _error.value = t(
                    "Discord messages are limited to 2,000 characters",
                    "Pesan Discord maksimal 2.000 karakter"
                )
            intervalSeconds < 1 -> _error.value = t(
                "Interval must be at least 1 second",
                "Interval minimal 1 detik"
            )
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
                    _error.value = t(
                        "Another bot already uses that name",
                        "Nama itu sudah dipakai bot lain"
                    )
                    return@launch
                }
            }
            if (token != null && token.isNotBlank()) {
                if (existing.any { app.cipher.decrypt(it.encryptedToken) == token.trim() }) {
                    _error.value = t(
                        "This token is already used by another bot",
                        "Token ini sudah dipakai bot lain"
                    )
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
            _info.value = t("Bot settings saved", "Setelan bot disimpan")
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
            _info.value = t("Webhook settings saved", "Setelan webhook disimpan")
            onDone()
        }

    private suspend fun currentWebhookPlain(tokenId: Long): String? =
        app.repository.getToken(tokenId)?.let { app.cipher.decrypt(it.encryptedWebhookUrl) }

    fun testWebhook(tokenId: Long, onResult: (Boolean, String) -> Unit) = viewModelScope.launch {
        val token = app.repository.getToken(tokenId) ?: return@launch
        val url = app.cipher.decrypt(token.encryptedWebhookUrl)
        if (!token.useWebhook || url.isNullOrBlank()) {
            onResult(
                false,
                t(
                    "Webhook is not enabled or the URL is missing",
                    "Webhook belum aktif atau URL-nya kosong"
                )
            )
            return@launch
        }
        val result = withContext(Dispatchers.IO) {
            app.client.sendWebhookLog(
                url,
                null,
                t("Test message from FunkPost", "Pesan uji dari FunkPost"),
                success = true
            )
        }
        val ok = result is com.gabut.autopost.network.SendResult.Success
        onResult(
            ok,
            if (ok) {
                t("Test webhook sent", "Webhook uji terkirim")
            } else {
                t("Test webhook failed", "Webhook uji gagal")
            }
        )
    }

    suspend fun batteryCardDismissed(): Boolean =
        app.settingsStore.batteryCardDismissedFlow.first()

    fun dismissBatteryCard() = viewModelScope.launch {
        app.settingsStore.setBatteryCardDismissed(true)
    }

    fun setDarkMode(enabled: Boolean) = viewModelScope.launch {
        app.settingsStore.setDarkMode(enabled)
    }

    fun setLanguage(language: AppLanguage) = viewModelScope.launch {
        app.settingsStore.setLanguage(language.code)
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