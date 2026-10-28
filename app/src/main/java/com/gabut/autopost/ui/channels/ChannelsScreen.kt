package com.gabut.autopost.ui.channels

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gabut.autopost.data.PostRepository
import com.gabut.autopost.ui.AutoPostViewModel
import com.gabut.autopost.ui.ChannelRow
import com.gabut.autopost.ui.ChannelStatusKind
import com.gabut.autopost.ui.TokenRow
import com.gabut.autopost.ui.components.AppDialog
import com.gabut.autopost.ui.components.DiscordMark
import com.gabut.autopost.ui.components.CompactIconButton
import com.gabut.autopost.ui.components.DiscordMarkdownText
import com.gabut.autopost.ui.components.DiscordMessageEditor
import com.gabut.autopost.ui.components.EmptyState
import com.gabut.autopost.ui.components.InitialAvatar
import com.gabut.autopost.ui.components.LiveDot
import com.gabut.autopost.ui.components.MessageBanner
import com.gabut.autopost.ui.components.SectionHeader
import com.gabut.autopost.ui.components.SoftDivider
import com.gabut.autopost.ui.components.StatusPill
import com.gabut.autopost.ui.components.Tone
import com.gabut.autopost.ui.components.formatInterval
import com.gabut.autopost.ui.components.pluralT
import com.gabut.autopost.ui.i18n.t
import kotlinx.coroutines.delay

/**
 * Channels tab: pick a bot, manage its channels, start/stop that bot.
 *
 * A token is only ever started by an explicit user tap here - never by a restore path (D17).
 *
 * The whole tab is one LazyColumn so the bot list and the channel list share a single
 * scroll surface instead of two competing ones.
 */
@Composable
fun ChannelsScreen(viewModel: AutoPostViewModel, modifier: Modifier = Modifier) {
    val tokens by viewModel.tokens.collectAsState()
    val activeId by viewModel.activeTokenId.collectAsState()
    val error by viewModel.error.collectAsState()
    val alertBanner by viewModel.alertBanner.collectAsState()
    val context = LocalContext.current

    val selectedId = activeId ?: tokens.firstOrNull()?.id

    // Asking for the notification permission is required for the status notification to appear on
    // API 33+. Denying it never blocks posting: the banner below explains what is missing (todo 15).
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    var notificationsDenied by remember { mutableStateOf(false) }
    var showAddToken by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteTokenId by rememberSaveable { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        alertBanner?.let { message ->
            // The alert could not become a notification, so it must not vanish silently.
            item("alert_banner") {
                MessageBanner(
                    message = message,
                    tone = Tone.ERROR,
                    modifier = Modifier.testTag("alert_banner")
                )
            }
        }

        if (notificationsDenied) {
            item("notification_banner") {
                MessageBanner(
                    message = t(
                        "Notifications are off - posting continues, but you can't see status",
                        "Notifikasi mati - posting tetap jalan, tapi status tidak terlihat"
                    ),
                    tone = Tone.WARNING,
                    modifier = Modifier.testTag("notification_banner")
                )
            }
        }

        item("bots_header") {
            SectionHeader(
                title = t("Bots", "Bot"),
                subtitle = if (tokens.isEmpty()) {
                    t(
                        "Connect a Discord bot to start posting",
                        "Hubungkan bot Discord untuk mulai posting"
                    )
                } else {
                    t("${tokens.size} connected", "${tokens.size} terhubung")
                },
                trailing = {
                    Button(
                        modifier = Modifier.testTag("add_token_button"),
                        onClick = { showAddToken = true }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(t("Add bot", "Tambah bot"))
                    }
                }
            )
        }

        if (tokens.isEmpty()) {
            item("bots_empty") {
                EmptyState(
                    title = t("No bots yet", "Belum ada bot"),
                    hint = t(
                        "Add a bot token to begin. Your token is encrypted on this device.",
                        "Tambahkan token bot untuk mulai. Token kamu dienkripsi di perangkat ini."
                    ),
                    icon = Icons.Filled.Send,
                    action = {
                        Button(onClick = { showAddToken = true }) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(t("Add your first bot", "Tambah bot pertama"))
                        }
                    }
                )
            }
        } else {
            items(tokens, key = { "token_${it.id}" }) { token ->
                val isSelected = token.id == selectedId
                BotCard(
                    token = token,
                    isSelected = isSelected,
                    onSelect = viewModel::selectToken,
                    onStart = { tokenId ->
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher
                                .launch(Manifest.permission.POST_NOTIFICATIONS)
                            notificationsDenied = true
                        }
                        viewModel.start(tokenId)
                    },
                    onStop = viewModel::stop,
                    onDelete = { pendingDeleteTokenId = token.id },
                    // Rendered only for the selected bot, so a collapsed card costs nothing.
                    channelsContent = {
                        ChannelsSection(viewModel = viewModel, token = token)
                    }
                )
            }
        }

        error?.let {
            item("error_text") {
                MessageBanner(
                    message = it,
                    tone = Tone.ERROR,
                    modifier = Modifier.testTag("error_text")
                )
            }
        }

    }

    if (showAddToken) {
        AddTokenDialog(
            onDismiss = { showAddToken = false },
            onConfirm = { name, token ->
                // A bot with zero channels is legal, matching the reference.
                viewModel.addToken(name, token) { showAddToken = false }
            }
        )
    }

    val pendingDeleteToken = tokens.firstOrNull { it.id == pendingDeleteTokenId }
    pendingDeleteToken?.let { token ->
        AppDialog(
            onDismissRequest = { pendingDeleteTokenId = null },
            title = { Text(t("Remove bot?", "Hapus bot?")) },
            content = {
                Text(
                    t(
                        "${token.name} and its ${pluralT(token.channelCount, "channel", "channels")} " +
                            "will be deleted. Posting stops immediately.",
                        "${token.name} dan ${pluralT(token.channelCount, "channel", "channels")} " +
                            "miliknya akan dihapus. Posting langsung berhenti."
                    )
                )
            },
            actions = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        modifier = Modifier.testTag("cancel_delete_token"),
                        onClick = { pendingDeleteTokenId = null }
                    ) { Text(t("Cancel", "Batal")) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        modifier = Modifier.testTag("confirm_delete_token"),
                        onClick = {
                            viewModel.deleteToken(token.id)
                            pendingDeleteTokenId = null
                        }
                    ) { Text(t("Remove", "Hapus"), color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }
}

/** A bot card: identity on the left, live status pill, actions at the bottom. */
@Composable
private fun BotCard(
    token: TokenRow,
    isSelected: Boolean,
    onSelect: (Long) -> Unit,
    onStart: (Long) -> Unit,
    onStop: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    channelsContent: @Composable () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val statusTone = when {
        token.needsReentry -> Tone.ERROR
        token.running -> Tone.SUCCESS
        else -> Tone.NEUTRAL
    }
    val statusText = when {
        token.needsReentry -> t("Token unreadable", "Token tidak terbaca")
        token.running -> t("Running", "Jalan")
        else -> t("Stopped", "Berhenti")
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("token_row_${token.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onSelect(token.id) }
                    .testTag("select_token_${token.id}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InitialAvatar(name = token.name, selected = isSelected)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = token.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${token.maskedToken} · " +
                            pluralT(token.channelCount, "channel", "channels"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (token.running) {
                    LiveDot(active = true)
                    Spacer(Modifier.width(8.dp))
                }
                StatusPill(
                    text = statusText,
                    tone = statusTone,
                    showDot = !token.running,
                    modifier = Modifier.testTag("token_status_${token.id}")
                )
                Spacer(Modifier.weight(1f))
                val chevronRotation by animateFloatAsState(
                    targetValue = if (isSelected) 180f else 0f,
                    label = "botChevron"
                )
                Text(
                    text = if (isSelected) {
                        t("Hide channels", "Sembunyikan channel")
                    } else {
                        t("Show channels", "Tampilkan channel")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(18.dp)
                        .rotate(chevronRotation)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (token.running) {
                    OutlinedButton(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("stop_token_${token.id}"),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStop(token.id)
                        }
                    ) { Text(t("Stop", "Berhenti")) }
                } else {
                    FilledTonalButton(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("start_token_${token.id}"),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStart(token.id)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(t("Start", "Mulai"))
                    }
                }

                CompactIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = t("Delete ${token.name}", "Hapus ${token.name}"),
                    onClick = { onDelete(token.id) },
                    tint = MaterialTheme.colorScheme.error,
                    container = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    modifier = Modifier.testTag("delete_token_${token.id}")
                )
            }

            AnimatedVisibility(
                visible = isSelected,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftDivider()
                    channelsContent()
                }
            }
        }
    }
}

/** Header + channel list for the selected bot, rendered inside its expanded card. */
@Composable
private fun ChannelsSection(viewModel: AutoPostViewModel, token: TokenRow) {
    // remember(token.id) keeps one subscription instead of re-subscribing on every recomposition.
    val channels by remember(token.id) { viewModel.observeChannels(token.id) }.collectAsState()

    // Mid-task form state survives screen rotation (rememberSaveable), so a half-typed message
    // or interval does not vanish when the user flips the device.
    var showForm by rememberSaveable { mutableStateOf(false) }
    var editingChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = channels.firstOrNull { it.channelId == editingChannelId }

    var pendingDeleteChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDelete = channels.firstOrNull { it.channelId == pendingDeleteChannelId }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = t("Channels", "Channel"),
            subtitle = t("Where ${token.name} posts", "Tempat ${token.name} posting"),
            trailing = {
                FilledTonalButton(
                    modifier = Modifier.testTag("add_channel_button"),
                    onClick = { editingChannelId = null; showForm = true },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(t("Add", "Tambah"))
                }
            }
        )

        if (channels.isEmpty()) {
            Text(
                text = t(
                    "No channels yet. Add one to schedule the first post.",
                    "Belum ada channel. Tambahkan satu untuk menjadwalkan posting pertama."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            val now = rememberSecondClock()
            channels.forEach { channel ->
                ChannelCard(
                    channel = channel,
                    now = now,
                    onEdit = { editingChannelId = channel.channelId; showForm = true },
                    onDelete = { pendingDeleteChannelId = channel.channelId }
                )
            }
        }
    }

    if (showForm) {
        ChannelFormDialog(
            initial = editing,
            onDismiss = { showForm = false },
            onConfirm = { channelId, message, intervalSeconds ->
                viewModel.saveChannel(token.id, channelId, message, intervalSeconds) {
                    showForm = false
                }
            }
        )
    }

    pendingDelete?.let { channel ->
        AppDialog(
            onDismissRequest = { pendingDeleteChannelId = null },
            title = { Text(t("Remove channel?", "Hapus channel?")) },
            content = {
                Text(
                    t(
                        "#${channel.channelId} will stop posting and be removed from this bot.",
                        "#${channel.channelId} akan berhenti posting dan dihapus dari bot ini."
                    )
                )
            },
            actions = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        modifier = Modifier.testTag("cancel_delete_channel"),
                        onClick = { pendingDeleteChannelId = null }
                    ) { Text(t("Cancel", "Batal")) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        modifier = Modifier.testTag("confirm_delete_channel"),
                        onClick = {
                            viewModel.deleteChannel(token.id, channel.channelId)
                            pendingDeleteChannelId = null
                        }
                    ) { Text(t("Remove", "Hapus"), color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }
}

/**
 * A clock that ticks once a second while it is on screen.
 *
 * Compose has no built-in timer that drives recomposition, and the countdown is the one value on
 * this screen that changes without the database changing. The producer is frame-aligned so the
 * text updates on a second boundary instead of drifting.
 */
@Composable
private fun rememberSecondClock(): Long {
    val now = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now.value = System.currentTimeMillis() }
            delay(1000L - (System.currentTimeMillis() % 1000L))
        }
    }
    return now.value
}

/** Status text for a channel, computed against [now] so the countdown keeps moving. */
@Composable
private fun channelStatusLine(channel: ChannelRow, now: Long): String {
    val remaining = ((channel.nextTickAt ?: now) - now).coerceAtLeast(0) / 1000
    return when (channel.statusKind) {
        ChannelStatusKind.RUNNING -> {
            val clock = "%02d:%02d".format(remaining / 60, remaining % 60)
            t("Running (next in $clock)", "Jalan (berikutnya $clock)")
        }
        ChannelStatusKind.RATE_LIMITED -> t(
            "Rate limited (retry in ${remaining}s)",
            "Kena rate limit (coba lagi ${remaining}dtk)"
        )
        ChannelStatusKind.AUTH_FAILED -> t("Auth failed - fix token", "Token ditolak - perbaiki token")
        ChannelStatusKind.FAILING -> t(
            "Failing - retrying with backoff",
            "Gagal terus - mencoba ulang dengan jeda"
        )
        ChannelStatusKind.NEVER_POSTED -> t("Never posted yet", "Belum pernah posting")
    }
}

@Composable
private fun ChannelCard(
    channel: ChannelRow,
    now: Long,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .testTag("channel_row_${channel.channelId}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DiscordMark(modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "#${channel.channelId}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(
                    text = channel.statusKind.shortLabel(),
                    tone = channel.statusKind.toTone(),
                    modifier = Modifier.testTag("channel_status_${channel.channelId}")
                )
            }

            // Rendered markdown, clipped: the card is a preview of what the channel will receive.
            DiscordMarkdownText(
                text = channel.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = t(
                        "${channelStatusLine(channel, now)} · every " +
                            formatInterval(channel.intervalSeconds),
                        "${channelStatusLine(channel, now)} · setiap " +
                            formatInterval(channel.intervalSeconds)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_channel_${channel.channelId}"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = t("Edit", "Ubah"),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(Modifier.width(2.dp))
                CompactIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = t(
                        "Delete channel ${channel.channelId}",
                        "Hapus channel ${channel.channelId}"
                    ),
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.error,
                    container = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    modifier = Modifier.testTag("delete_channel_${channel.channelId}")
                )
            }
        }
    }
}

private fun ChannelStatusKind.toTone(): Tone = when (this) {
    ChannelStatusKind.RUNNING -> Tone.SUCCESS
    ChannelStatusKind.RATE_LIMITED -> Tone.WARNING
    ChannelStatusKind.AUTH_FAILED, ChannelStatusKind.FAILING -> Tone.ERROR
    ChannelStatusKind.NEVER_POSTED -> Tone.NEUTRAL
}

private fun ChannelStatusKind.shortLabel(): String = when (this) {
    ChannelStatusKind.RUNNING -> t("Running", "Jalan")
    ChannelStatusKind.RATE_LIMITED -> t("Rate limited", "Kena limit")
    ChannelStatusKind.AUTH_FAILED -> t("Auth failed", "Token ditolak")
    ChannelStatusKind.FAILING -> t("Failing", "Gagal terus")
    ChannelStatusKind.NEVER_POSTED -> t("Idle", "Diam")
}

@Composable
private fun AddTokenDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }

    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = t("Add bot token", "Tambah token bot"),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DiscordMark(size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t(
                            "Paste a Discord bot token. It stays encrypted on this device.",
                            "Tempel token bot Discord. Token tetap terenkripsi di perangkat ini."
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_token_name"),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(t("Name", "Nama")) },
                    placeholder = { Text(t("My bot", "Bot saya")) },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_token_value"),
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(t("Bot token", "Token bot")) },
                    singleLine = true
                )
            }
        },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text(t("Cancel", "Batal")) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    modifier = Modifier.testTag("confirm_add_token"),
                    enabled = name.isNotBlank() && token.isNotBlank(),
                    onClick = { onConfirm(name, token) }
                ) { Text(t("Save", "Simpan")) }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelFormDialog(
    initial: ChannelRow?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int) -> Unit
) {
    val initialId = initial?.channelId

    // rememberSaveable: half-typed channel id, message and interval survive a rotation.
    var channelId by rememberSaveable(initialId) { mutableStateOf("") }
    var message by rememberSaveable(initialId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var hours by rememberSaveable(initialId) { mutableStateOf("0") }
    var minutes by rememberSaveable(initialId) {
        mutableStateOf(if (initial == null) "1" else "0")
    }
    var seconds by rememberSaveable(initialId) { mutableStateOf("0") }

    // The dialog may compose before the channels flow emits; fill pristine fields from the
    // channel being edited without clobbering anything the user has typed.
    LaunchedEffect(initialId) {
        val current = initial
        if (current == null) return@LaunchedEffect
        if (channelId.isEmpty()) channelId = current.channelId
        if (message.text.isEmpty()) message = TextFieldValue(current.message)
        if (hours.isEmpty()) hours = (current.intervalSeconds / 3600).toString()
        if (minutes.isEmpty()) minutes = ((current.intervalSeconds % 3600) / 60).toString()
        if (seconds.isEmpty()) seconds = (current.intervalSeconds % 60).toString()
    }

    val intervalSeconds = (hours.toIntOrNull() ?: 0) * 3600 +
        (minutes.toIntOrNull() ?: 0) * 60 +
        (seconds.toIntOrNull() ?: 0)
    val overLimit = message.text.length > PostRepository.MAX_MESSAGE_LENGTH

    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        fillHeight = true,
        title = {
            Text(
                text = if (initial == null) {
                    t("Add channel", "Tambah channel")
                } else {
                    t("Edit channel", "Ubah channel")
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("channel_id_field"),
                    value = channelId,
                    onValueChange = { channelId = it },
                    label = { Text(t("Channel id", "ID channel")) },
                    placeholder = { Text("123456789012345678") },
                    singleLine = true,
                    enabled = initial == null,
                    supportingText = if (initial != null) {
                        {
                            Text(
                                t(
                                    "Channel id can't be changed. Remove and re-add to move it.",
                                    "ID channel tidak bisa diubah. Hapus lalu tambah ulang untuk memindahkannya."
                                )
                            )
                        }
                    } else {
                        null
                    }
                )

                DiscordMessageEditor(
                    value = message,
                    onValueChange = { message = it },
                    fieldModifier = Modifier.testTag("channel_message_field")
                )

                Text(
                    t("How often to post", "Seberapa sering posting"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("interval_hours"),
                        value = hours,
                        onValueChange = { hours = it.filter(Char::isDigit).take(3) },
                        label = { Text(t("h", "j")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("interval_minutes"),
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit).take(2) },
                        label = { Text(t("m", "mnt")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("interval_seconds"),
                        value = seconds,
                        onValueChange = { seconds = it.filter(Char::isDigit).take(2) },
                        label = { Text(t("s", "dtk")) },
                        singleLine = true
                    )
                }
                if (intervalSeconds in 1..4) {
                    InlineWarning(
                        text = t(
                            "Posting this often can get your bot rate limited.",
                            "Posting sesering ini bisa membuat bot kamu kena rate limit."
                        ),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.testTag("interval_warning")
                    )
                }
                if (intervalSeconds < 1) {
                    InlineWarning(
                        text = t(
                            "Interval must be at least 1 second.",
                            "Interval minimal 1 detik."
                        ),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text(t("Cancel", "Batal")) }
                Spacer(Modifier.width(8.dp))
                Button(
                    modifier = Modifier.testTag("confirm_channel"),
                    enabled = channelId.isNotBlank() &&
                        message.text.isNotBlank() &&
                        intervalSeconds >= 1 &&
                        !overLimit,
                    onClick = { onConfirm(channelId, message.text, intervalSeconds) }
                ) {
                    Text(
                        if (initial == null) {
                            t("Add channel", "Tambah channel")
                        } else {
                            t("Save changes", "Simpan perubahan")
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun InlineWarning(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(text = text, color = color, style = MaterialTheme.typography.labelSmall)
    }
}
