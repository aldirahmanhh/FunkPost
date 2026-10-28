package com.gabut.autopost.ui.channels

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gabut.autopost.ui.AutoPostViewModel
import com.gabut.autopost.ui.ChannelRow
import com.gabut.autopost.ui.ChannelStatusKind
import com.gabut.autopost.ui.TokenRow

/**
 * Channels tab: pick a bot, manage its channels, start/stop that bot.
 *
 * A token is only ever started by an explicit user tap here - never by a restore path (D17).
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

    Column(modifier = modifier.padding(12.dp)) {
        alertBanner?.let { message ->
            // The alert could not become a notification, so it must not vanish silently.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("alert_banner")
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(10.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        if (notificationsDenied) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("notification_banner")
            ) {
                Text(
                    "Notifications are off - posting continues but you can't see status",
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        TokenList(
            tokens = tokens,
            selectedId = selectedId,
            onSelect = viewModel::selectToken,
            onStart = { tokenId ->
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    notificationsDenied = true
                }
                viewModel.start(tokenId)
            },
            onStop = viewModel::stop,
            onDelete = viewModel::deleteToken,
            onAddToken = viewModel::addToken
        )

        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error_text"))
        }

        Spacer(Modifier.height(8.dp))
        if (selectedId == null) {
            Text("Add a bot token to get started.", style = MaterialTheme.typography.bodyLarge)
        } else {
            ChannelSection(viewModel, selectedId)
        }
    }
}

@Composable
private fun TokenList(
    tokens: List<TokenRow>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onStart: (Long) -> Unit,
    onStop: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onAddToken: (String, String, () -> Unit) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Bots", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        Button(
            modifier = Modifier.testTag("add_token_button"),
            onClick = { showAdd = true }
        ) { Text("Add bot") }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        items(tokens, key = { it.id }) { token ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("token_row_${token.id}")
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(token.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${token.maskedToken} - ${token.channelCount} channels",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = if (token.needsReentry) {
                            "Token unreadable - re-enter it"
                        } else if (token.running) {
                            "Running"
                        } else {
                            "Stopped"
                        },
                        color = if (token.running) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("token_status_${token.id}")
                    )
                    if (token.selected) {
                        Text("Active", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            modifier = Modifier.testTag("select_token_${token.id}"),
                            onClick = { onSelect(token.id) }
                        ) { Text("Select") }

                        if (token.running) {
                            OutlinedButton(
                                modifier = Modifier.testTag("stop_token_${token.id}"),
                                onClick = { onStop(token.id) }
                            ) { Text("Stop") }
                        } else {
                            Button(
                                modifier = Modifier.testTag("start_token_${token.id}"),
                                onClick = { onStart(token.id) }
                            ) { Text("Start") }
                        }

                        OutlinedButton(
                            modifier = Modifier.testTag("delete_token_${token.id}"),
                            onClick = { onDelete(token.id) }
                        ) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddTokenDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, token ->
                // A bot with zero channels is legal, matching the reference.
                onAddToken(name, token) { showAdd = false }
            }
        )
    }
}

@Composable
private fun AddTokenDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add bot token") },
        text = {
            Column {
                OutlinedTextField(
                    modifier = Modifier.testTag("new_token_name"),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.testTag("new_token_value"),
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bot token") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("confirm_add_token"),
                onClick = { onConfirm(name, token) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelSection(viewModel: AutoPostViewModel, tokenId: Long) {
    val channels by viewModel.observeChannels(tokenId).collectAsState()
    var editing by remember { mutableStateOf<ChannelRow?>(null) }
    var showForm by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Channels", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        Button(
            modifier = Modifier.testTag("add_channel_button"),
            onClick = { editing = null; showForm = true }
        ) { Text("Add channel") }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(channels, key = { it.id }) { channel ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("channel_row_${channel.channelId}")
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(channel.channelId, style = MaterialTheme.typography.titleLarge)
                    Text(channel.message, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = channel.statusLine,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("channel_status_${channel.channelId}")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            modifier = Modifier.testTag("edit_channel_${channel.channelId}"),
                            onClick = { editing = channel; showForm = true }
                        ) { Text("Edit") }
                        OutlinedButton(
                            modifier = Modifier.testTag("delete_channel_${channel.channelId}"),
                            onClick = { viewModel.deleteChannel(tokenId, channel.channelId) }
                        ) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (showForm) {
        ChannelFormDialog(
            initial = editing,
            onDismiss = { showForm = false },
            onConfirm = { channelId, message, intervalSeconds ->
                viewModel.saveChannel(tokenId, channelId, message, intervalSeconds) {
                    showForm = false
                }
            }
        )
    }
}

@Composable
private fun ChannelFormDialog(
    initial: ChannelRow?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int) -> Unit
) {
    var channelId by remember { mutableStateOf(initial?.channelId ?: "") }
    var message by remember { mutableStateOf(initial?.message ?: "") }
    val total = initial?.intervalSeconds ?: 60
    var hours by remember { mutableStateOf((total / 3600).toString()) }
    var minutes by remember { mutableStateOf(((total % 3600) / 60).toString()) }
    var seconds by remember { mutableStateOf((total % 60).toString()) }

    val intervalSeconds = (hours.toIntOrNull() ?: 0) * 3600 +
        (minutes.toIntOrNull() ?: 0) * 60 +
        (seconds.toIntOrNull() ?: 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add channel" else "Edit channel") },
        text = {
            Column {
                OutlinedTextField(
                    modifier = Modifier.testTag("channel_id_field"),
                    value = channelId,
                    onValueChange = { channelId = it },
                    label = { Text("Channel id") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.testTag("channel_message_field"),
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Message") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("interval_hours"),
                        value = hours,
                        onValueChange = { hours = it },
                        label = { Text("h") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("interval_minutes"),
                        value = minutes,
                        onValueChange = { minutes = it },
                        label = { Text("m") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("interval_seconds"),
                        value = seconds,
                        onValueChange = { seconds = it },
                        label = { Text("s") },
                        singleLine = true
                    )
                }
                if (intervalSeconds in 1..4) {
                    Text(
                        text = "Posting this often can get your bot rate limited.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("interval_warning")
                    )
                }
                if (intervalSeconds < 1) {
                    Text(
                        text = "Interval must be at least 1 second.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("confirm_channel"),
                onClick = { onConfirm(channelId, message, intervalSeconds) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}