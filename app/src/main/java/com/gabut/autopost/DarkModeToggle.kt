package com.gabut.autopost

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.gabut.autopost.ui.theme.AutoPostIcons

/**
 * The reference exposes dark mode as a plain manual toggle in the top bar (`autopost.py:1163-1165`),
 * with no "follow system" option - so there is none here either.
 *
 * It is an icon button rather than a labelled switch: the "Dark/Light" text cost a third of the
 * app bar and pushed the status summary into an ellipsis on small phones.
 */
@Composable
fun DarkModeToggle(dark: Boolean, onToggle: (Boolean) -> Unit) {
    IconButton(
        onClick = { onToggle(!dark) },
        modifier = Modifier.testTag("dark_mode_toggle")
    ) {
        Icon(
            imageVector = if (dark) AutoPostIcons.Sun else AutoPostIcons.Moon,
            contentDescription = if (dark) "Switch to light mode" else "Switch to dark mode",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
