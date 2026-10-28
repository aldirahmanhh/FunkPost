package com.gabut.autopost.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gabut.autopost.ui.i18n.AppLanguage
import com.gabut.autopost.ui.i18n.LanguageState
import com.gabut.autopost.ui.i18n.t
import com.gabut.autopost.ui.theme.LocalAutoPostColors

/**
 * Small shared building blocks so every tab looks like it belongs to the same app.
 *
 * Everything here is presentation-only: no view model, no repository, no side effects.
 */

/** Semantic tone for banners, pills and inline status. */
enum class Tone { NEUTRAL, INFO, SUCCESS, WARNING, ERROR }

@Composable
fun Tone.container(): Color {
    val semantic = LocalAutoPostColors.current
    return when (this) {
        Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
        Tone.INFO -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        Tone.SUCCESS -> semantic.successContainer
        Tone.WARNING -> semantic.warningContainer
        Tone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
}

@Composable
fun Tone.content(): Color {
    val semantic = LocalAutoPostColors.current
    return when (this) {
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        Tone.INFO -> MaterialTheme.colorScheme.primary
        Tone.SUCCESS -> semantic.onSuccessContainer
        Tone.WARNING -> semantic.onWarningContainer
        Tone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
}

/** The saturated accent used for the dot inside a [StatusPill]. */
@Composable
fun Tone.dot(): Color {
    val semantic = LocalAutoPostColors.current
    return when (this) {
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        Tone.INFO -> MaterialTheme.colorScheme.primary
        Tone.SUCCESS -> semantic.success
        Tone.WARNING -> semantic.warning
        Tone.ERROR -> MaterialTheme.colorScheme.error
    }
}

/**
 * A section heading with an optional trailing action slot, used to break the long
 * single-column screens into scannable groups.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A compact colored chip for a status word (Running, Stopped, Auth failed, ...). */
@Composable
fun StatusPill(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    showDot: Boolean = true
) {
    val container by animateColorAsState(tone.container(), label = "pillContainer")
    val content by animateColorAsState(tone.content(), label = "pillContent")
    val dot by animateColorAsState(tone.dot(), label = "pillDot")

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A full-width message strip for banners and inline feedback, with a matching
 * icon picked from the [tone].
 */
@Composable
fun MessageBanner(
    message: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = tone.defaultIcon()
) {
    val container by animateColorAsState(tone.container(), label = "bannerContainer")
    val content by animateColorAsState(tone.content(), label = "bannerContent")

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun Tone.defaultIcon(): ImageVector = when (this) {
    Tone.SUCCESS -> Icons.Filled.CheckCircle
    Tone.ERROR, Tone.WARNING -> Icons.Filled.Warning
    Tone.INFO, Tone.NEUTRAL -> Icons.Filled.Info
}

/**
 * A friendly empty state: icon in a soft circle, a headline, a hint line and an optional action.
 * Used instead of a bare sentence so a blank tab still looks intentional.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    hint: String? = null,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold
        )
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/** A thin divider that respects the app's `outlineVariant` instead of a hard-coded grey. */
@Composable
fun SoftDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** Key/value line used across the bot cards and settings summaries. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A rounded, outlined surface used for every grouped block.
 *
 * [selected] thickens the border and paints it with the accent color, which is how the app marks
 * the active bot without adding yet another badge.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/** Back-compat alias kept for the existing settings blocks. */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) = AppCard(modifier = modifier, contentPadding = contentPadding, content = content)

/** Animated alpha used to hint that a running row is live. */
@Composable
fun LiveDot(active: Boolean, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val dotColor by animateColorAsState(
        targetValue = if (active) {
            LocalAutoPostColors.current.success
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        },
        label = "liveDot"
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(dotColor)
    )
}

/** Rounded avatar with the bot's initial, so cards have a visual anchor. */
@Composable
fun InitialAvatar(
    name: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Dp = 42.dp
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.34f))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.22f), RoundedCornerShape(size * 0.34f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium,
            color = fg
        )
    }
}

/**
 * A small circular icon button with a tonal background, for secondary row actions.
 *
 * It is visually 34dp but Material still enforces the 48dp touch target around it.
 */
@Composable
fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(34.dp),
        enabled = enabled
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * One modal shell for every popup in the app: brand surface, rounded shape, outlineVariant border,
 * and tap-outside dismissal via [onDismissRequest]. Every modal in the app uses this so the
 * add-bot, add-channel and remove dialogs look like one system instead of three different ones.
 *
 * [fillHeight] stretches the surface to 92% of the window height - use it for scrollable forms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    title: @Composable () -> Unit = {},
    content: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {}
) {
    // Margin on the surface (space to the screen edge), padding on the inner column (space to the
    // border). Padding on the Surface modifier would have acted as a margin instead.
    val surfaceModifier = modifier
        .then(if (fillHeight) Modifier.fillMaxHeight(0.92f) else Modifier)
        .padding(horizontal = 16.dp, vertical = 24.dp)
        .imePadding()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = surfaceModifier,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .then(if (fillHeight) Modifier.fillMaxSize() else Modifier)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                title()
                if (fillHeight) {
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) { content() }
                }
                actions()
            }
        }
    }
}

/** "1 channel" / "3 channels" - avoids string concatenation noise at every call site. */
fun plural(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count == 1) singular else plural}"

/**
 * [plural] with both languages supplied, because Indonesian nouns do not inflect:
 * `pluralT(2, "bot", "bots", "bot", "bot")` reads "2 bots" in English and "2 bot" in Indonesian.
 */
fun pluralT(
    count: Int,
    enSingular: String,
    enPlural: String,
    idSingular: String = enSingular,
    idPlural: String = idSingular
): String = if (LanguageState.current == AppLanguage.INDONESIAN) {
    "$count ${if (count == 1) idSingular else idPlural}"
} else {
    "$count ${if (count == 1) enSingular else enPlural}"
}

/**
 * Humanized interval: 45 -> "45s", 90 -> "1m 30s", 3600 -> "1h", 5400 -> "1h 30m".
 *
 * The raw "every 90s" reading is technically correct but forces the user to do arithmetic to
 * picture how often their bot posts.
 */
fun formatInterval(seconds: Int): String {
    val hour = t("h", "j")
    val minute = t("m", "mnt")
    val second = t("s", "dtk")
    if (seconds < 60) return "$seconds$second"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return buildString {
        if (hours > 0) append("$hours$hour")
        if (minutes > 0) {
            if (isNotEmpty()) append(' ')
            append("$minutes$minute")
        }
        if (secs > 0 && hours == 0) {
            if (isNotEmpty()) append(' ')
            append("$secs$second")
        }
    }.ifEmpty { "$seconds$second" }
}
