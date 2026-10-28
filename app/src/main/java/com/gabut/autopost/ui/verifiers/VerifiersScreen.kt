package com.gabut.autopost.ui.verifiers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gabut.autopost.ui.AutoPostViewModel
import com.gabut.autopost.ui.TokenRow
import com.gabut.autopost.ui.VerifierRow
import com.gabut.autopost.ui.VerifierStatusKind
import com.gabut.autopost.ui.components.AppDialog
import com.gabut.autopost.ui.components.CompactIconButton
import com.gabut.autopost.ui.components.DiscordMark
import com.gabut.autopost.ui.components.EmptyState
import com.gabut.autopost.ui.components.InitialAvatar
import com.gabut.autopost.ui.components.LiveDot
import com.gabut.autopost.ui.components.MessageBanner
import com.gabut.autopost.ui.components.SectionHeader
import com.gabut.autopost.ui.components.SoftDivider
import com.gabut.autopost.ui.components.StatusPill
import com.gabut.autopost.ui.components.Tone
import com.gabut.autopost.ui.components.pluralT
import com.gabut.autopost.ui.i18n.t

/**
 * Verifiers tab: user accounts that watch a channel and press the verify buttons on their own
 * messages.
 *
 * Kept on its own tab rather than folded into Bots because the two are different credentials with
 * different powers: a bot token posts, a user token acts as the human who owns it. Putting them in
 * one list would invite pasting the wrong kind into the wrong form.
 */
@Composable
fun VerifiersScreen(viewModel: AutoPostViewModel, modifier: Modifier = Modifier) {
    val verifiers by viewModel.verifiers.collectAsState()
    val error by viewModel.error.collectAsState()
    /** Existing bot tokens, offered as a reuse source so one token can power both autopost and verify. */
    val bots by viewModel.tokens.collectAsState()

    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }

    val editing = verifiers.firstOrNull { it.id == editingId }
    val pendingDelete = verifiers.firstOrNull { it.id == pendingDeleteId }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item("verifiers_header") {
            SectionHeader(
                title = t("Auto-verify", "Verifikasi otomatis"),
                subtitle = if (verifiers.isEmpty()) {
                    t("Press verify buttons automatically", "Menekan tombol verifikasi otomatis")
                } else {
                    pluralT(verifiers.size, "account", "accounts", "akun", "akun")
                },
                trailing = {
                    Button(
                        modifier = Modifier.testTag("add_verifier_button"),
                        onClick = { showAdd = true }
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
        }

        if (verifiers.isEmpty()) {
            item("verifiers_empty") {
                EmptyState(
                    title = t("No verifier yet", "Belum ada verifier"),
                    hint = t(
                        "Reuse a token you already post with, or paste a new one, plus the channel " +
                            "where verification panels appear. FunkPost will press the button for " +
                            "that account only.",
                        "Pakai ulang token yang sudah kamu posting, atau tempel token baru, plus " +
                            "channel tempat panel verifikasi muncul. FunkPost hanya menekan tombol " +
                            "untuk akun itu."
                    ),
                    icon = Icons.Filled.CheckCircle,
                    action = {
                        Button(onClick = { showAdd = true }) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(t("Add your first verifier", "Tambah verifier pertama"))
                        }
                    }
                )
            }
        } else {
            items(verifiers, key = { "verifier_${it.id}" }) { row ->
                VerifierCard(
                    row = row,
                    onStart = { viewModel.startVerifier(row.id) },
                    onStop = { viewModel.stopVerifier(row.id) },
                    onEdit = { editingId = row.id },
                    onDelete = { pendingDeleteId = row.id }
                )
            }
        }

        error?.let {
            item("verifier_error") {
                MessageBanner(
                    message = it,
                    tone = Tone.ERROR,
                    modifier = Modifier.testTag("verifier_error")
                )
            }
        }

        item("verifiers_note") {
            HowItWorksCard()
        }
    }

    if (showAdd) {
        VerifierFormDialog(
            initial = null,
            bots = bots,
            onDismiss = { showAdd = false },
            onConfirm = { form ->
                viewModel.addVerifier(
                    name = form.name,
                    token = form.token,
                    watchChannelId = form.watchChannelId,
                    humanize = form.humanize,
                    baseDelaySeconds = form.baseDelaySeconds,
                    maxClickRetries = form.maxClickRetries,
                    botTokenId = form.botTokenId
                ) { showAdd = false }
            }
        )
    }

    editing?.let { row ->
        VerifierFormDialog(
            initial = row,
            onDismiss = { editingId = null },
            onConfirm = { form ->
                viewModel.saveVerifier(
                    id = row.id,
                    name = form.name,
                    token = form.token.ifBlank { null },
                    watchChannelId = form.watchChannelId,
                    humanize = form.humanize,
                    baseDelaySeconds = form.baseDelaySeconds,
                    maxClickRetries = form.maxClickRetries
                ) { editingId = null }
            }
        )
    }

    pendingDelete?.let { row ->
        AppDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(t("Remove verifier?", "Hapus verifier?")) },
            content = {
                Text(
                    t(
                        "${row.name} will stop watching #${row.watchChannelId}. " +
                            "Its token stays in the activity log history only.",
                        "${row.name} akan berhenti memantau #${row.watchChannelId}. " +
                            "Token-nya hanya tersisa di riwayat aktivitas."
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
                        modifier = Modifier.testTag("cancel_delete_verifier"),
                        onClick = { pendingDeleteId = null }
                    ) { Text(t("Cancel", "Batal")) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        modifier = Modifier.testTag("confirm_delete_verifier"),
                        onClick = {
                            viewModel.deleteVerifier(row.id)
                            pendingDeleteId = null
                        }
                    ) { Text(t("Remove", "Hapus"), color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }
}

@Composable
private fun VerifierCard(
    row: VerifierRow,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("verifier_row_${row.id}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (row.running) 1.5.dp else 1.dp,
            color = if (row.running) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(name = row.name, selected = row.running)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = t(
                            "${row.maskedToken} · watching #${row.watchChannelId}",
                            "${row.maskedToken} · memantau #${row.watchChannelId}"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.running) {
                    LiveDot(active = true)
                    Spacer(Modifier.width(8.dp))
                }
                StatusPill(
                    text = row.statusKind.shortLabel(),
                    tone = row.statusKind.toTone(),
                    showDot = !row.running,
                    modifier = Modifier.testTag("verifier_status_${row.id}")
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = t(
                        "${row.clickedCount} verified",
                        "${row.clickedCount} terverifikasi"
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            row.lastEventText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("verifier_last_event_${row.id}")
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (row.running) {
                    OutlinedButton(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("stop_verifier_${row.id}"),
                        onClick = onStop
                    ) { Text(t("Stop", "Berhenti")) }
                } else {
                    FilledTonalButton(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("start_verifier_${row.id}"),
                        onClick = onStart,
                        enabled = row.statusKind != VerifierStatusKind.UNREADABLE
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

                TextButton(
                    modifier = Modifier.testTag("edit_verifier_${row.id}"),
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        t("Edit", "Ubah"),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                CompactIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = t("Delete ${row.name}", "Hapus ${row.name}"),
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.error,
                    container = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    modifier = Modifier.testTag("delete_verifier_${row.id}")
                )
            }
        }
    }
}

@Composable
private fun HowItWorksCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionHeader(
                title = t("How it works", "Cara kerjanya"),
                subtitle = t(
                    "Only buttons that belong to the account are pressed",
                    "Hanya tombol milik akun ini yang ditekan"
                ),
                trailing = { DiscordMark(size = 20.dp) }
            )
            Text(
                text = t(
                    "FunkPost stays connected to Discord, watches the channel you pick, and " +
                        "presses Authenticate and Yes, Log Me In when the panel is yours. " +
                        "Panels for other accounts are left alone.",
                    "FunkPost tetap terhubung ke Discord, memantau channel yang kamu pilih, dan " +
                        "menekan Authenticate serta Yes, Log Me In saat panel itu milik akunmu. " +
                        "Panel milik akun lain dibiarkan."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SoftDivider()
            Text(
                text = t(
                    "One token can do both jobs: reuse a token from the Bots tab here and the " +
                        "same account keeps posting while it verifies.",
                    "Satu token bisa dua pekerjaan sekaligus: pakai token dari tab Bot di sini, " +
                        "akun yang sama tetap posting sambil memverifikasi."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = t(
                    "Use this on servers you own or are allowed to automate. A user token " +
                        "acts as your account.",
                    "Gunakan ini di server milikmu atau yang boleh kamu otomatiskan. Token user " +
                        "bertindak sebagai akunmu."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Everything the form collects, so the call site does not juggle six parameters twice. */
private data class VerifierForm(
    val name: String,
    val token: String,
    val watchChannelId: String,
    val humanize: Boolean,
    val baseDelaySeconds: Int,
    val maxClickRetries: Int,
    /** When set, the verifier reuses that bot's stored token instead of [token]. */
    val botTokenId: Long? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifierFormDialog(
    initial: VerifierRow?,
    onDismiss: () -> Unit,
    onConfirm: (VerifierForm) -> Unit,
    bots: List<TokenRow> = emptyList()
) {
    val initialId = initial?.id

    // rememberSaveable: a half-typed token or channel survives rotation, same as the bot dialogs.
    var name by rememberSaveable(initialId) { mutableStateOf(initial?.name.orEmpty()) }
    var token by rememberSaveable(initialId) { mutableStateOf("") }
    var channelId by rememberSaveable(initialId) {
        mutableStateOf(initial?.watchChannelId.orEmpty())
    }
    var humanize by rememberSaveable(initialId) { mutableStateOf(true) }
    var delaySeconds by rememberSaveable(initialId) { mutableStateOf("1") }
    var retries by rememberSaveable(initialId) { mutableStateOf("3") }
    var reveal by remember { mutableStateOf(false) }
    var selectedBotId by rememberSaveable(initialId) { mutableStateOf<Long?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    // Reusing a token is an add-time convenience: an edit keeps its own token unless replaced.
    val reusableBots = remember(bots) { bots.filterNot { it.needsReentry } }
    val selectedBot = reusableBots.firstOrNull { it.id == selectedBotId }
    val usingBot = initial == null && selectedBot != null

    val delayValue = delaySeconds.toIntOrNull() ?: 0
    val retryValue = retries.toIntOrNull() ?: 0
    val valid = name.isNotBlank() &&
        channelId.isNotBlank() &&
        delayValue >= 1 &&
        retryValue in 1..10 &&
        (usingBot || initial != null || token.isNotBlank())

    AppDialog(
        onDismissRequest = onDismiss,
        fillHeight = true,
        title = {
            Text(
                text = if (initial == null) {
                    t("Add verifier", "Tambah verifier")
                } else {
                    t("Edit verifier", "Ubah verifier")
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
                Text(
                    text = if (initial == null) {
                        t(
                            "Reuse a token you already post with, or paste one now. It clicks the " +
                                "verify button for the account it belongs to.",
                            "Pakai ulang token yang sudah kamu posting, atau tempel sekarang. " +
                                "Token ini menekan tombol verifikasi akunnya sendiri."
                        )
                    } else {
                        t(
                            "Leave the token blank to keep the current one. It clicks the verify " +
                                "button for the account it belongs to.",
                            "Biarkan token kosong untuk memakai yang sekarang. Token ini menekan " +
                                "tombol verifikasi akunnya sendiri."
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("verifier_name_field"),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(t("Name", "Nama")) },
                    placeholder = { Text(t("Main account", "Akun utama")) },
                    singleLine = true
                )

                if (initial == null && reusableBots.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .testTag("verifier_token_source"),
                            value = selectedBot?.let { "${it.name} · ${it.maskedToken}" }
                                ?: t("Paste a token manually", "Tempel token manual"),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(t("Token source", "Sumber token")) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = t("Choose token source", "Pilih sumber token")
                                )
                            }
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                modifier = Modifier.testTag("token_source_manual"),
                                text = { Text(t("Paste a token manually", "Tempel token manual")) },
                                onClick = {
                                    selectedBotId = null
                                    dropdownExpanded = false
                                }
                            )
                            reusableBots.forEach { bot ->
                                DropdownMenuItem(
                                    modifier = Modifier.testTag("token_source_bot_${bot.id}"),
                                    text = {
                                        Column {
                                            Text(bot.name)
                                            Text(
                                                text = bot.maskedToken,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedBotId = bot.id
                                        dropdownExpanded = false
                                        // Starting from the bot's name keeps the form short.
                                        if (name.isBlank()) name = bot.name
                                    }
                                )
                            }
                        }
                    }
                }

                if (usingBot && selectedBot != null) {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("verifier_reused_token"),
                        value = selectedBot.maskedToken,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(t("Token from ${selectedBot.name}", "Token dari ${selectedBot.name}")) },
                        supportingText = {
                            Text(
                                t(
                                    "The same token will keep posting and start verifying.",
                                    "Token yang sama akan terus posting dan mulai memverifikasi."
                                )
                            )
                        }
                    )
                } else {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("verifier_token_field"),
                        value = token,
                        onValueChange = { token = it },
                        label = {
                            Text(
                                if (initial == null) {
                                    t("User token", "Token user")
                                } else {
                                    t("New token", "Token baru")
                                }
                            )
                        },
                        placeholder = {
                            Text(
                                if (initial == null) {
                                    t("Paste the user token", "Tempel token user")
                                } else {
                                    t("Leave blank to keep current", "Kosongkan untuk pakai yang sekarang")
                                }
                            )
                        },
                        singleLine = true,
                        visualTransformation = if (reveal) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(onClick = { reveal = !reveal }) {
                                Text(
                                    text = if (reveal) t("Hide", "Sembunyi") else t("Show", "Lihat"),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    )
                }

                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("verifier_channel_field"),
                    value = channelId,
                    onValueChange = { channelId = it },
                    label = { Text(t("Watch channel id", "ID channel pantauan")) },
                    placeholder = { Text("123456789012345678") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            t(
                                "The channel where verify panels appear.",
                                "Channel tempat panel verifikasi muncul."
                            )
                        )
                    }
                )

                SoftDivider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = t("Human-like delay", "Jeda ala manusia"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = t(
                                "Adds a random pause before each click.",
                                "Menambah jeda acak sebelum setiap klik."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = humanize,
                        onCheckedChange = { humanize = it },
                        modifier = Modifier.testTag("verifier_humanize_switch")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("verifier_delay_field"),
                        value = delaySeconds,
                        onValueChange = { delaySeconds = it.filter(Char::isDigit).take(3) },
                        label = { Text(t("Delay (s)", "Jeda (dtk)")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("verifier_retries_field"),
                        value = retries,
                        onValueChange = { retries = it.filter(Char::isDigit).take(2) },
                        label = { Text(t("Retries", "Percobaan")) },
                        singleLine = true
                    )
                }

                Text(
                    text = t(
                        "The delay grows automatically when Discord rate limits a click, " +
                            "and settles back down after a success.",
                        "Jeda bertambah otomatis saat Discord membatasi klik, dan kembali normal " +
                            "setelah berhasil."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                Button(
                    modifier = Modifier.testTag("confirm_verifier"),
                    enabled = valid,
                    onClick = {
                        onConfirm(
                            VerifierForm(
                                name = name,
                                // A reused token travels by id; its ciphertext never leaves the DB.
                                token = if (usingBot) "" else token,
                                watchChannelId = channelId,
                                humanize = humanize,
                                baseDelaySeconds = delayValue,
                                maxClickRetries = retryValue,
                                botTokenId = if (usingBot) selectedBot?.id else null
                            )
                        )
                    }
                ) {
                    Text(
                        if (initial == null) {
                            t("Add verifier", "Tambah verifier")
                        } else {
                            t("Save changes", "Simpan perubahan")
                        }
                    )
                }
            }
        }
    )
}

private fun VerifierStatusKind.toTone(): Tone = when (this) {
    VerifierStatusKind.WATCHING -> Tone.SUCCESS
    VerifierStatusKind.AUTH_FAILED, VerifierStatusKind.UNREADABLE -> Tone.ERROR
    VerifierStatusKind.STOPPED -> Tone.NEUTRAL
}

private fun VerifierStatusKind.shortLabel(): String = when (this) {
    VerifierStatusKind.WATCHING -> t("Watching", "Mengawasi")
    VerifierStatusKind.AUTH_FAILED -> t("Token rejected", "Token ditolak")
    VerifierStatusKind.UNREADABLE -> t("Token unreadable", "Token tidak terbaca")
    VerifierStatusKind.STOPPED -> t("Stopped", "Berhenti")
}
