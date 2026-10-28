package com.gabut.autopost.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gabut.autopost.ui.AutoPostViewModel
import com.gabut.autopost.ui.i18n.t

/**
 * Webhook tab: per-bot webhook logging (the reference feature) plus a Test button.
 *
 * The URL is encrypted before it is stored, exactly like the bot token.
 */
@Composable
fun WebhookScreen(viewModel: AutoPostViewModel, modifier: Modifier = Modifier) {
    val tokens by viewModel.tokens.collectAsState()
    val activeId by viewModel.activeTokenId.collectAsState()
    val selected = tokens.firstOrNull { it.id == (activeId ?: tokens.firstOrNull()?.id) }

    if (selected == null) {
        Column(modifier = modifier.padding(16.dp)) {
            Text(
                t("No bot selected.", "Belum ada bot yang dipilih."),
                modifier = Modifier.testTag("webhook_empty")
            )
        }
        return
    }

    var enabled by remember(selected.id) { mutableStateOf(false) }
    var url by remember(selected.id) { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(t("Webhook logging", "Log webhook"), style = MaterialTheme.typography.titleLarge)
        Text(selected.name, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Enable webhook", "Aktifkan webhook"), style = MaterialTheme.typography.bodyLarge)
            Switch(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .testTag("webhook_enable_switch"),
                checked = enabled,
                onCheckedChange = { enabled = it }
            )
        }

        OutlinedTextField(
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("webhook_url_field"),
            value = url,
            onValueChange = { url = it },
            label = { Text(t("Webhook URL", "URL webhook")) },
            singleLine = true
        )

        Row {
            Button(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("webhook_save_button"),
                onClick = { viewModel.saveWebhook(selected.id, enabled, url) }
            ) { Text(t("Save", "Simpan")) }

            Button(
                modifier = Modifier
                    .padding(top = 8.dp, start = 8.dp)
                    .testTag("webhook_test_button"),
                onClick = {
                    viewModel.testWebhook(selected.id) { ok, message ->
                        result = if (ok) {
                            t("Success: $message", "Berhasil: $message")
                        } else {
                            t("Error: $message", "Gagal: $message")
                        }
                    }
                }
            ) { Text(t("Test webhook", "Uji webhook")) }
        }

        result?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                color = if (it.startsWith(t("Success", "Berhasil"))) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.testTag("webhook_result")
            )
        }
    }
}