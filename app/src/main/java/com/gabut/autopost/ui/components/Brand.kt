package com.gabut.autopost.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gabut.autopost.R
import com.gabut.autopost.ui.theme.AutoPostIcons

/**
 * The supplied AutoPost mark: a violet-to-cyan tile carrying the ringed bolt emblem.
 *
 * The 512px asset already includes its rounded tile and transparent corners, so it is drawn
 * as-is instead of being redrawn with theme colours. This keeps the launcher icon and the
 * in-app mark pixel-identical.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 38.dp
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_brand),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Discord's mark, tinted for the surrounding text. */
@Composable
fun DiscordMark(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Icon(
        imageVector = AutoPostIcons.Discord,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size)
    )
}

/** Brand mark plus an optional caption, used inside dialogs and empty states. */
@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    title: String = "FunkPost",
    subtitle: String? = null
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark(size = 38.dp)
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
