package com.gabut.autopost.ui.logs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gabut.autopost.ui.AutoPostViewModel
import com.gabut.autopost.ui.LogKind
import com.gabut.autopost.ui.LogRow
import com.gabut.autopost.ui.components.EmptyState
import com.gabut.autopost.ui.components.SectionHeader
import com.gabut.autopost.ui.components.StatusPill
import com.gabut.autopost.ui.components.Tone
import com.gabut.autopost.ui.components.container
import com.gabut.autopost.ui.components.content
import com.gabut.autopost.ui.components.pluralT
import com.gabut.autopost.ui.i18n.appLocale
import com.gabut.autopost.ui.i18n.t
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class LogFilter(val enLabel: String, val idLabel: String) {
    ALL("All", "Semua"),
    POSTED("Posted", "Terkirim"),
    ISSUES("Issues", "Masalah");

    val label: String get() = t(enLabel, idLabel)
}

@Composable
fun LogsScreen(viewModel: AutoPostViewModel, modifier: Modifier = Modifier) {
    val logs by viewModel.logs.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }

    val filtered = remember(logs, filter) {
        when (filter) {
            LogFilter.ALL -> logs
            LogFilter.POSTED -> logs.filter { it.kind == LogKind.POSTED }
            LogFilter.ISSUES -> logs.filter {
                it.kind == LogKind.RATE_LIMITED ||
                    it.kind == LogKind.AUTH_FAILED ||
                    it.kind == LogKind.FAILING
            }
        }
    }
    val grouped = remember(filtered) {
        filtered.groupBy { dayKey(it.occurredAt) }.toList()
    }

    Column(modifier = modifier.fillMaxSize()) {
        SectionHeader(
            title = t("Activity", "Riwayat"),
            subtitle = when (logs.size) {
                0 -> t("Nothing yet", "Belum ada")
                1 -> pluralT(1, "entry", "entries", "entri", "entri")
                else -> pluralT(logs.size, "entry", "entries", "entri", "entri")
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            trailing = {
                if (logs.isNotEmpty()) {
                    TextButton(
                        modifier = Modifier.testTag("clear_logs"),
                        onClick = { confirmClear = true }
                    ) { Text(t("Clear", "Hapus")) }
                }
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LogFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    modifier = Modifier.testTag("log_filter_${option.name.lowercase()}")
                )
            }
        }

        when {
            logs.isEmpty() -> EmptyState(
                title = t("No activity yet", "Belum ada aktivitas"),
                hint = t(
                    "Posting results, rate limits and errors will appear here.",
                    "Hasil posting, rate limit dan error akan muncul di sini."
                ),
                icon = Icons.Filled.List,
                modifier = Modifier.testTag("logs_empty")
            )

            filtered.isEmpty() -> EmptyState(
                title = t("Nothing here", "Tidak ada isinya"),
                hint = t("No entries match this filter.", "Tidak ada entri yang cocok dengan filter ini."),
                icon = Icons.Filled.List,
                modifier = Modifier.testTag("logs_filtered_empty")
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                grouped.forEach { (day, entries) ->
                    item(key = "day_$day") {
                        DayHeader(
                            label = dayLabel(entries.first().occurredAt),
                            count = entries.size
                        )
                    }
                    items(entries, key = { it.id }) { row -> LogEntry(row) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            modifier = Modifier.testTag("clear_logs_dialog"),
            onDismissRequest = { confirmClear = false },
            title = { Text(t("Clear activity?", "Hapus riwayat?")) },
            text = {
                Text(
                    t(
                        "This removes every saved log entry. It cannot be undone.",
                        "Semua entri riwayat yang tersimpan akan dihapus. Tindakan ini tidak bisa dibatalkan."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("clear_logs_confirm"),
                    onClick = {
                        viewModel.clearLogs()
                        confirmClear = false
                    }
                ) { Text(t("Clear", "Hapus")) }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag("clear_logs_cancel"),
                    onClick = { confirmClear = false }
                ) { Text(t("Cancel", "Batal")) }
            }
        )
    }
}

/** Day separation: a list that spans days should not repeat the date on every row. */
@Composable
private fun DayHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = pluralT(count, "entry", "entries", "entri", "entri"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)

private fun dayKey(timestamp: Long): Long {
    val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    return calendar.get(Calendar.YEAR).toLong() * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val diff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && diff == 0 -> t("Today", "Hari ini")
        sameYear && diff == 1 -> t("Yesterday", "Kemarin")
        sameYear -> SimpleDateFormat("d MMM", appLocale()).format(Date(timestamp))
        else -> SimpleDateFormat("d MMM yyyy", appLocale()).format(Date(timestamp))
    }
}

@Composable
private fun LogEntry(row: LogRow) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("log_row_${row.id}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(row.kind.tone().container()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = row.kind.icon(),
                    contentDescription = null,
                    tint = row.kind.tone().content(),
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = buildString {
                            append(row.botName)
                            row.channelId?.let { append(" · #").append(it) }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = timeFormat.format(Date(row.occurredAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            row.statusCode?.let { code ->
                Spacer(Modifier.width(8.dp))
                StatusPill(text = code.toString(), tone = row.kind.tone(), showDot = false)
            }
        }
    }
}

private fun LogKind.tone(): Tone = when (this) {
    LogKind.POSTED -> Tone.SUCCESS
    LogKind.RATE_LIMITED -> Tone.WARNING
    LogKind.AUTH_FAILED -> Tone.ERROR
    LogKind.FAILING -> Tone.WARNING
    LogKind.OTHER -> Tone.NEUTRAL
}

private fun LogKind.icon(): ImageVector = when (this) {
    LogKind.POSTED -> Icons.Filled.CheckCircle
    LogKind.RATE_LIMITED, LogKind.AUTH_FAILED, LogKind.FAILING -> Icons.Filled.Warning
    LogKind.OTHER -> Icons.Filled.Info
}
