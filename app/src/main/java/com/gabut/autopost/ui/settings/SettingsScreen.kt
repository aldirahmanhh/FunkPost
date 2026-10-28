package com.gabut.autopost.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.gabut.autopost.ui.AutoPostViewModel

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
            Text("No bot selected.", modifier = Modifier.testTag("settings_empty"))
        }
        return
    }

    var name by remember(selected.id) { mutableStateOf(selected.name) }
    var token by remember(selected.id) { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Bot settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("settings_name_field"),
            value = name,
            onValueChange = { name = it },
            label = { Text("Bot name") },
            singleLine = true
        )

        OutlinedTextField(
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("settings_token_field"),
            value = token,
            onValueChange = { token = it },
            label = { Text("New token (leave blank to keep)") },
            singleLine = true,
            visualTransformation = if (reveal) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            }
        )

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Reveal", style = MaterialTheme.typography.labelSmall)
            Switch(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .testTag("settings_reveal_toggle"),
                checked = reveal,
                onCheckedChange = { reveal = it }
            )
        }

        Button(
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("settings_save_button"),
            onClick = { viewModel.saveTokenSettings(selected.id, name, token.ifBlank { null }) }
        ) { Text("Save") }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("settings_error")
            )
        }
        info?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, modifier = Modifier.testTag("settings_info"))
        }

        Spacer(Modifier.height(24.dp))
        BatteryOptimizationCard(viewModel)
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
    val ignoring = remember { com.gabut.autopost.service.PostingService.isIgnoringBatteryOptimizations(context) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        dismissed = viewModel.batteryCardDismissed()
    }

    if (dismissed || ignoring) return

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            "Android may pause background posting. Allow Auto Post to ignore battery optimization.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("battery_card_text")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            ) { Text("Allow") }
            OutlinedButton(
                modifier = Modifier.testTag("battery_card_dismiss"),
                onClick = {
                    dismissed = true
                    viewModel.dismissBatteryCard()
                }
            ) { Text("Dismiss") }
        }
    }
}

/**
 * Credit tab: static text plus four user-tapped links.
 *
 * Nothing here performs a request, so opening the tab is provably network-free (IS-8).
 */
@Composable
fun CreditScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val links = listOf(
        "Discord" to "https://discord.gg/",
        "YouTube" to "https://youtube.com/",
        "GitHub" to "https://github.com/",
        "Saweria" to "https://saweria.co/"
    )

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("About", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Auto Post By Lantas Continental. design By Void_String",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(16.dp))

        links.forEach { (label, url) ->
            OutlinedButton(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .testTag("credit_link_$label"),
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                }
            ) { Text(label) }
        }
    }
}