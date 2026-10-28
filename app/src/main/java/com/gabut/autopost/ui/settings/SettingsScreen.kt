package com.gabut.autopost.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.gabut.autopost.ui.AutoPostViewModel
import com.gabut.autopost.ui.components.EmptyState
import com.gabut.autopost.ui.components.InitialAvatar
import com.gabut.autopost.ui.components.MessageBanner
import com.gabut.autopost.ui.components.SectionHeader
import com.gabut.autopost.ui.components.Tone
import com.gabut.autopost.ui.components.pluralT
import com.gabut.autopost.ui.i18n.AppLanguage
import com.gabut.autopost.ui.i18n.t
import com.gabut.autopost.ui.theme.LocalAutoPostColors

/** Settings tab: rename the active bot, rotate its token, test nothing, write nothing to disk. */
@Composable
fun SettingsScreen(viewModel: AutoPostViewModel, modifier: Modifier = Modifier) {
    val tokens by viewModel.tokens.collectAsState()
    val activeId by viewModel.activeTokenId.collectAsState()
    val error by viewModel.error.collectAsState()
    val info by viewModel.info.collectAsState()
    val selected = tokens.firstOrNull { it.id == (activeId ?: tokens.firstOrNull()?.id) }

    if (selected == null) {
        Column(modifier = modifier.padding(16.dp)) {
            EmptyState(
                title = t("Nothing to configure", "Belum ada yang bisa diatur"),
                hint = t(
                    "Add a bot on the Bots tab first, then come back here.",
                    "Tambahkan bot di tab Bot dulu, lalu kembali ke sini."
                ),
                icon = Icons.Filled.Settings,
                modifier = Modifier.testTag("settings_empty")
            )
            LanguageCard(viewModel)
            TutorialsCard()
        }
        return
    }

    var name by remember(selected.id) { mutableStateOf(selected.name) }
    var token by remember(selected.id) { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Identity header: who am I editing.
        Row(verticalAlignment = Alignment.CenterVertically) {
            InitialAvatar(name = selected.name, selected = true)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = selected.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${selected.maskedToken}  •  " +
                        pluralT(selected.channelCount, "channel", "channels"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Bot identity card.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionHeader(title = t("Bot identity", "Identitas bot"))

                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_name_field"),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(t("Bot name", "Nama bot")) },
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_token_field"),
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(t("New token", "Token baru")) },
                    placeholder = {
                        Text(t("Leave blank to keep current", "Kosongkan untuk pakai yang sekarang"))
                    },
                    singleLine = true,
                    visualTransformation = if (reveal) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        // A word instead of an icon: the app has no eye glyph and "Show/Hide"
                        // needs no explanation.
                        TextButton(
                            modifier = Modifier.testTag("settings_reveal_toggle"),
                            onClick = { reveal = !reveal }
                        ) {
                            Text(
                                text = if (reveal) t("Hide", "Sembunyi") else t("Show", "Lihat"),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    },
                    supportingText = {
                        Text(
                            t(
                                "The token is replaced only when you type a new one.",
                                "Token hanya diganti kalau kamu mengetik token baru."
                            )
                        )
                    }
                )
            }
        }

        val dirty = name.trim() != selected.name || token.isNotBlank()
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_save_button"),
            enabled = dirty,
            onClick = {
                viewModel.saveTokenSettings(selected.id, name, token.ifBlank { null }) {
                    // Clearing the field makes the "saved" state visibly clean again.
                    token = ""
                }
            }
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(t("Save changes", "Simpan perubahan"))
        }

        error?.let {
            MessageBanner(
                message = it,
                tone = Tone.ERROR,
                modifier = Modifier.testTag("settings_error")
            )
        }
        info?.let {
            MessageBanner(
                message = it,
                tone = Tone.SUCCESS,
                modifier = Modifier.testTag("settings_info")
            )
        }

        LanguageCard(viewModel)
        BatteryOptimizationCard(viewModel)
        TutorialsCard()
    }
}

private const val TUTORIAL_PC_URL = "https://youtu.be/LnBnm_tZlyU?si=J3wSpuRaXqI5ycUj"
private const val TUTORIAL_ANDROID_URL = "https://youtu.be/OvOKuKZwuwQ?si=LCoqhtTlKJv74VxG"

@Composable
fun TutorialsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionHeader(
                title = t("Tutorials", "Tutorial"),
                subtitle = t(
                    "Videos on how to get a token for this app.",
                    "Video cara mendapatkan token untuk dipakai di app ini."
                ),
            )
            TutorialLink(
                tag = "tutorial_link_pc",
                title = t("PC setup", "Lewat PC"),
                subtitle = t(
                    "How to get a token on a PC or laptop",
                    "Cara mendapatkan token lewat PC atau laptop"
                ),
                url = TUTORIAL_PC_URL,
                onClick = { openUrl(context, it) }
            )
            TutorialLink(
                tag = "tutorial_link_android",
                title = t("Android setup", "Lewat Android"),
                subtitle = t(
                    "How to get a token on an Android phone",
                    "Cara mendapatkan token lewat HP Android"
                ),
                url = TUTORIAL_ANDROID_URL,
                onClick = { openUrl(context, it) }
            )
        }
    }
}

@Composable
private fun TutorialLink(
    tag: String,
    title: String,
    subtitle: String,
    url: String,
    onClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable { onClick(url) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = t("Watch", "Tonton"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())
        )
    }
}

/**
 * First-run guidance only, and dismissible (todo 15).
 *
 * The app never exempts itself: it opens the system screen and lets the user decide.
 */
@Composable
fun BatteryOptimizationCard(viewModel: AutoPostViewModel) {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(false) }
    val ignoring = remember {
        com.gabut.autopost.service.PostingService.isIgnoringBatteryOptimizations(context)
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        dismissed = viewModel.batteryCardDismissed()
    }

    if (dismissed || ignoring) return

    val semantic = LocalAutoPostColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = semantic.warningContainer
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(semantic.warning.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = semantic.onWarningContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    t("Keep posting alive", "Agar posting tetap jalan"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                t(
                    "Android may pause background posting when the screen is off. " +
                        "Allow FunkPost to ignore battery optimization.",
                    "Android bisa menjeda posting latar belakang saat layar mati. " +
                        "Izinkan FunkPost mengabaikan optimasi baterai."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("battery_card_text")
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("battery_card_allow"),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                com.gabut.autopost.service.PostingService
                                    .buildBatteryOptimizationIntent(context)
                            )
                        }
                    }
                ) { Text(t("Allow", "Izinkan")) }
                OutlinedButton(
                    modifier = Modifier.testTag("battery_card_dismiss"),
                    onClick = {
                        dismissed = true
                        viewModel.dismissBatteryCard()
                    }
                ) { Text(t("Dismiss", "Nanti")) }
            }
        }
    }
}

/**
 * Language switch. The two options are written in their own language ("English", "Bahasa
 * Indonesia") so the choice stays readable no matter which one is active.
 */
@Composable
fun LanguageCard(viewModel: AutoPostViewModel, modifier: Modifier = Modifier) {
    val current by viewModel.language.collectAsState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionHeader(
                title = t("Language", "Bahasa"),
                subtitle = t("Applies to every screen and notification", "Berlaku untuk semua layar dan notifikasi")
            )
            AppLanguage.entries.forEach { language ->
                val selected = current == language
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { viewModel.setLanguage(language) }
                        .testTag("language_${language.code}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = language.nativeName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = language.englishName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
