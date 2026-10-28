package com.gabut.autopost.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Discord's message markdown, rendered for the in-app composer preview.
 *
 * Discord itself accepts a large but well-defined subset of markdown. The app stores the raw text
 * (that is what the API expects) and only *renders* it here, so editing never loses formatting:
 *
 *  - `**bold**`, `*italic*`, `__underline__`, `~~strike~~`, `||spoiler||`
 *  - `` `inline code` `` and fenced code blocks
 *  - `> quote`, `# heading`, `- bullet` / `1. numbered`
 *  - `[label](url)`, `<@user>`, `<#channel>`, `<:emoji:id>`, `<t:unix:style>`
 */
@Immutable
data class DiscordPalette(
    val codeBackground: Color,
    val quoteBar: Color,
    val quoteText: Color,
    val link: Color,
    val mention: Color,
    val mentionBackground: Color,
    val spoilerBackground: Color,
    val muted: Color
)

@Composable
fun rememberDiscordPalette(): DiscordPalette {
    val colors = MaterialTheme.colorScheme
    return DiscordPalette(
        codeBackground = colors.surfaceVariant.copy(alpha = 0.75f),
        quoteBar = colors.primary.copy(alpha = 0.65f),
        quoteText = colors.onSurfaceVariant,
        link = colors.primary,
        mention = colors.primary,
        mentionBackground = colors.primary.copy(alpha = 0.14f),
        spoilerBackground = colors.onSurface.copy(alpha = 0.16f),
        muted = colors.onSurfaceVariant
    )
}

/**
 * Draws [text] with Discord markdown applied. Rendering is deliberately read-only: the preview is
 * meant to show what the channel will see, not to be edited in place.
 */
@Composable
fun DiscordMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    palette: DiscordPalette = rememberDiscordPalette()
) {
    val annotated = remember(text, palette) { buildDiscordMarkdown(text, palette) }
    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow
    )
}

/** One-pass (line aware) parser. Inline markers may nest, fenced blocks are passed through. */
fun buildDiscordMarkdown(source: String, palette: DiscordPalette): AnnotatedString {
    val lines = source.replace("\r\n", "\n").split('\n')
    var inCodeBlock = false
    val rendered = ArrayList<AnnotatedString>(lines.size)

    for (raw in lines) {
        if (raw.trimStart().startsWith("```")) {
            // The fence itself is Discord syntax, never shown in the rendered message.
            inCodeBlock = !inCodeBlock
            rendered += AnnotatedString("")
            continue
        }
        rendered += if (inCodeBlock) {
            AnnotatedString(
                raw,
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = palette.codeBackground,
                    fontSize = 13.sp
                )
            )
        } else {
            renderLine(raw, palette)
        }
    }

    // Trim the blank lines introduced by fences at the very start/end.
    while (rendered.isNotEmpty() && rendered.first().text.isEmpty()) rendered.removeAt(0)
    while (rendered.isNotEmpty() && rendered.last().text.isEmpty()) rendered.removeAt(rendered.lastIndex)

    return buildAnnotatedString {
        rendered.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            append(line)
        }
    }
}

private val HEADING = Regex("^(#{1,3})\\s+(.*)$")
private val QUOTE = Regex("^>\\s?(.*)$")
private val BULLET = Regex("^[-*]\\s+(.*)$")

private fun renderLine(line: String, palette: DiscordPalette): AnnotatedString =
    buildAnnotatedString {
        val heading = HEADING.matchEntire(line)
        val quote = QUOTE.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        when {
            heading != null -> {
                val level = heading.groupValues[1].length
                val size = when (level) {
                    1 -> 19.sp
                    2 -> 17.sp
                    else -> 15.sp
                }
                appendInline(
                    text = heading.groupValues[2],
                    builder = this,
                    base = SpanStyle(fontWeight = FontWeight.Bold, fontSize = size),
                    palette = palette
                )
            }

            quote != null -> {
                withStyle(SpanStyle(color = palette.quoteBar, fontWeight = FontWeight.Bold)) {
                    append("▏ ")
                }
                appendInline(
                    text = quote.groupValues[1],
                    builder = this,
                    base = SpanStyle(color = palette.quoteText, fontStyle = FontStyle.Italic),
                    palette = palette
                )
            }

            bullet != null -> {
                withStyle(SpanStyle(color = palette.muted)) { append("•  ") }
                appendInline(bullet.groupValues[1], this, SpanStyle(), palette)
            }

            else -> appendInline(line, this, SpanStyle(), palette)
        }
    }

/** Recursive inline pass: handles nesting such as `**bold _and italic_**`. */
private fun appendInline(
    text: String,
    builder: AnnotatedString.Builder,
    base: SpanStyle,
    palette: DiscordPalette
) {
    var index = 0
    val length = text.length

    fun emit(piece: String, style: SpanStyle) {
        builder.withStyle(base.merge(style)) { append(piece) }
    }

    while (index < length) {
        val char = text[index]

        // Backslash escapes the next character: \* is a literal star.
        if (char == '\\' && index + 1 < length) {
            emit(text[index + 1].toString(), SpanStyle())
            index += 2
            continue
        }

        if (char == '`') {
            val close = text.indexOf('`', index + 1)
            if (close > index) {
                emit(
                    text.substring(index + 1, close),
                    SpanStyle(fontFamily = FontFamily.Monospace, background = palette.codeBackground)
                )
                index = close + 1
                continue
            }
        }

        val tripleBold = text.startsWith("***", index)
        if (tripleBold) {
            val close = text.indexOf("***", index + 3)
            if (close > index) {
                appendInline(
                    text.substring(index + 3, close),
                    builder,
                    base.merge(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)),
                    palette
                )
                index = close + 3
                continue
            }
        }

        val wrap = when {
            text.startsWith("**", index) -> "**" to SpanStyle(fontWeight = FontWeight.Bold)
            text.startsWith("__", index) -> "__" to SpanStyle(textDecoration = TextDecoration.Underline)
            text.startsWith("~~", index) -> "~~" to SpanStyle(textDecoration = TextDecoration.LineThrough)
            text.startsWith("||", index) -> "||" to SpanStyle(background = palette.spoilerBackground)
            char == '*' -> "*" to SpanStyle(fontStyle = FontStyle.Italic)
            char == '_' -> "_" to SpanStyle(fontStyle = FontStyle.Italic)
            else -> null
        }
        if (wrap != null) {
            val (marker, style) = wrap
            val close = text.indexOf(marker, index + marker.length)
            if (close > index) {
                appendInline(
                    text.substring(index + marker.length, close),
                    builder,
                    base.merge(style),
                    palette
                )
                index = close + marker.length
                continue
            }
        }

        if (char == '[') {
            val closeBracket = text.indexOf(']', index + 1)
            if (closeBracket > index && closeBracket + 1 < length && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen > closeBracket) {
                    emit(
                        text.substring(index + 1, closeBracket),
                        SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)
                    )
                    index = closeParen + 1
                    continue
                }
            }
        }

        if (char == '<') {
            val close = text.indexOf('>', index + 1)
            if (close in (index + 1) until length && close - index <= 80) {
                val renderedToken = renderSpecialToken(text.substring(index + 1, close))
                if (renderedToken != null) {
                    emit(
                        renderedToken,
                        SpanStyle(color = palette.mention, background = palette.mentionBackground)
                    )
                    index = close + 1
                    continue
                }
            }
        }

        if (char == '@' && (text.startsWith("@everyone", index) || text.startsWith("@here", index))) {
            val word = if (text.startsWith("@everyone", index)) "@everyone" else "@here"
            emit(word, SpanStyle(color = palette.mention, background = palette.mentionBackground))
            index += word.length
            continue
        }

        // Plain character. Style it with the current base so nesting keeps working.
        builder.withStyle(base) { append(char) }
        index++
    }
}

/** `<@id>`, `<#id>`, custom emoji and timestamps become readable preview text. */
private fun renderSpecialToken(token: String): String? = when {
    token.startsWith("@&") -> "@role"
    token.startsWith("@") -> "@user"
    token.startsWith("#") -> "#channel"
    token.startsWith("a:") || token.startsWith(":") -> {
        val parts = token.split(':')
        if (parts.size >= 3) ":${parts[1]}:" else null
    }

    token.startsWith("t:") -> formatDiscordTimestamp(token)
    else -> null
}

private fun formatDiscordTimestamp(token: String): String? {
    val parts = token.split(':')
    val seconds = parts.getOrNull(1)?.toLongOrNull() ?: return null
    val style = parts.getOrNull(2)
    val date = Date(seconds * 1000L)
    return when (style) {
        "R" -> relativeTime(date)
        "d" -> SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(date)
        "D" -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(date)
        "t" -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        "T" -> SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
        else -> SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(date)
    }
}

private fun relativeTime(date: Date): String {
    val diffSeconds = (date.time - System.currentTimeMillis()) / 1000
    val past = diffSeconds < 0
    val abs = kotlin.math.abs(diffSeconds)
    val (value, unit) = when {
        abs < 60 -> abs to "second"
        abs < 3_600 -> (abs / 60) to "minute"
        abs < 86_400 -> (abs / 3_600) to "hour"
        abs < 2_592_000 -> (abs / 86_400) to "day"
        abs < 31_536_000 -> (abs / 2_592_000) to "month"
        else -> (abs / 31_536_000) to "year"
    }
    val plural = if (value == 1L) unit else "${unit}s"
    return if (past) "$value $plural ago" else "in $value $plural"
}
