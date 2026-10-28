package com.gabut.autopost.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.gabut.autopost.data.PostRepository
import com.gabut.autopost.ui.i18n.t

/** The pieces of Discord formatting the composer can wrap a selection in. */
enum class DiscordFormat {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKE,
    SPOILER,
    INLINE_CODE,
    CODE_BLOCK,
    QUOTE,
    LIST,
    LINK
}

/**
 * Message composer with a Discord markdown toolbar and a live preview.
 *
 * The stored value is always the raw markdown, so editing a message never loses syntax; only the
 * preview pane renders it.
 */
@Composable
fun DiscordMessageEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    maxLength: Int = PostRepository.MAX_MESSAGE_LENGTH
) {
    var previewMode by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val overLimit = value.text.length > maxLength
    val colors = MaterialTheme.colorScheme

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !previewMode,
                onClick = { previewMode = false },
                label = { Text(t("Write", "Tulis")) },
                modifier = Modifier.testTag("message_mode_write")
            )
            FilterChip(
                selected = previewMode,
                onClick = { previewMode = true },
                label = { Text(t("Preview", "Pratinjau")) },
                modifier = Modifier.testTag("message_mode_preview")
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${value.text.length} / $maxLength",
                style = MaterialTheme.typography.labelSmall,
                color = if (overLimit) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.testTag("message_counter")
            )
        }

        if (previewMode) {
            DiscordPreviewCard(
                text = value.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("message_preview")
            )
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = fieldModifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                label = { Text(t("Message", "Pesan")) },
                placeholder = { Text(t("Type the message to post…", "Tulis pesan yang akan diposting…")) },
                minLines = 5,
                maxLines = 10,
                isError = overLimit,
                supportingText = {
                    Text(
                        if (overLimit) {
                            t(
                                "Discord allows at most $maxLength characters",
                                "Discord maksimal $maxLength karakter"
                            )
                        } else {
                            t(
                                "Discord markdown supported · **bold**, *italic*, __underline__, ~~strike~~, ||spoiler||, `code`",
                                "Markdown Discord didukung · **tebal**, *miring*, __garis bawah__, ~~coret~~, ||spoiler||, `kode`"
                            )
                        }
                    )
                }
            )
            FormatToolbar(
                onFormat = { format ->
                    onValueChange(applyDiscordFormat(value, format))
                    focusRequester.requestFocus()
                }
            )
        }
    }
}

/** What the channel will actually look like: rendered markdown inside a Discord-ish card. */
@Composable
private fun DiscordPreviewCard(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 132.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = t("PREVIEW", "PRATINJAU"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (text.isBlank()) {
                Text(
                    text = t(
                        "Nothing to preview yet. Type a message in the Write tab.",
                        "Belum ada yang bisa dipratinjau. Tulis pesan di tab Tulis."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                DiscordMarkdownText(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun FormatToolbar(
    onFormat: (DiscordFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FormatChip("B", t("Bold", "Tebal"), DiscordFormat.BOLD, onFormat, "format_bold", FontWeight.Bold)
        FormatChip("I", t("Italic", "Miring"), DiscordFormat.ITALIC, onFormat, "format_italic", fontStyle = FontStyle.Italic)
        FormatChip("U", t("Underline", "Garis bawah"), DiscordFormat.UNDERLINE, onFormat, "format_underline", textDecoration = TextDecoration.Underline)
        FormatChip("S", t("Strikethrough", "Coret"), DiscordFormat.STRIKE, onFormat, "format_strike", textDecoration = TextDecoration.LineThrough)
        FormatChip("||", t("Spoiler", "Spoiler"), DiscordFormat.SPOILER, onFormat, "format_spoiler")
        FormatChip("`code`", t("Inline code", "Kode sebaris"), DiscordFormat.INLINE_CODE, onFormat, "format_code")
        FormatChip("```", t("Code block", "Blok kode"), DiscordFormat.CODE_BLOCK, onFormat, "format_code_block")
        FormatChip(">", t("Quote", "Kutipan"), DiscordFormat.QUOTE, onFormat, "format_quote")
        FormatChip("•", t("List", "Daftar"), DiscordFormat.LIST, onFormat, "format_list")
        FormatChip("[link]", t("Link", "Tautan"), DiscordFormat.LINK, onFormat, "format_link")
    }
}

@Composable
private fun FormatChip(
    label: String,
    description: String,
    format: DiscordFormat,
    onFormat: (DiscordFormat) -> Unit,
    tag: String,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    textDecoration: TextDecoration? = null
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onFormat(format) }
            .semantics { contentDescription = description }
            .testTag(tag),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium.merge(
                TextStyle(
                    fontWeight = fontWeight,
                    fontStyle = fontStyle,
                    textDecoration = textDecoration
                )
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Applies a toolbar action to the current selection.
 *
 * Wrap-style formats keep the selected text selected so the user can chain them; line-style
 * formats (quote/list) prefix every touched line.
 */
fun applyDiscordFormat(value: TextFieldValue, format: DiscordFormat): TextFieldValue {
    val text = value.text
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)

    return when (format) {
        DiscordFormat.BOLD -> wrapSelection(text, start, end, "**", "**")
        DiscordFormat.ITALIC -> wrapSelection(text, start, end, "*", "*")
        DiscordFormat.UNDERLINE -> wrapSelection(text, start, end, "__", "__")
        DiscordFormat.STRIKE -> wrapSelection(text, start, end, "~~", "~~")
        DiscordFormat.SPOILER -> wrapSelection(text, start, end, "||", "||")
        DiscordFormat.INLINE_CODE -> wrapSelection(text, start, end, "`", "`")
        DiscordFormat.CODE_BLOCK -> wrapSelection(text, start, end, "```\n", "\n```")
        DiscordFormat.QUOTE -> prefixLines(text, start, end, "> ")
        DiscordFormat.LIST -> prefixLines(text, start, end, "- ")
        DiscordFormat.LINK -> insertLink(text, start, end)
    }
}

private fun wrapSelection(
    text: String,
    start: Int,
    end: Int,
    before: String,
    after: String
): TextFieldValue {
    val selected = text.substring(start, end)
    val updated = text.substring(0, start) + before + selected + after + text.substring(end)
    val selectionStart = start + before.length
    return TextFieldValue(
        text = updated,
        selection = TextRange(selectionStart, selectionStart + selected.length)
    )
}

private fun prefixLines(
    text: String,
    start: Int,
    end: Int,
    prefix: String
): TextFieldValue {
    val blockStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0))
        .let { if (it < 0) 0 else it + 1 }
    val blockEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
    val block = text.substring(blockStart, blockEnd)
    val prefixed = block.split('\n').joinToString("\n") { line ->
        if (line.startsWith(prefix)) line else prefix + line
    }
    val updated = text.substring(0, blockStart) + prefixed + text.substring(blockEnd)
    return TextFieldValue(
        text = updated,
        selection = TextRange(blockStart, blockStart + prefixed.length)
    )
}

private fun insertLink(text: String, start: Int, end: Int): TextFieldValue {
    val selected = text.substring(start, end)
    val updated = text.substring(0, start) + "[" + selected + "](url)" + text.substring(end)
    val urlStart = start + 1 + selected.length + 2
    return TextFieldValue(
        text = updated,
        selection = TextRange(urlStart, urlStart + 3)
    )
}
