package com.gabut.autopost

import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment

/**
 * The reference exposes dark mode as a plain manual toggle in the top bar (`autopost.py:1163-1165`),
 * with no "follow system" option - so there is none here either.
 */
@Composable
fun DarkModeToggle(dark: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 8.dp)
    ) {
        Text(
            text = if (dark) "Dark" else "Light",
            style = MaterialTheme.typography.labelSmall
        )
        Switch(
            modifier = Modifier
                .padding(start = 6.dp)
                .testTag("dark_mode_toggle"),
            checked = dark,
            onCheckedChange = onToggle
        )
    }
}