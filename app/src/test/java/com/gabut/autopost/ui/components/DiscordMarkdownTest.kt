package com.gabut.autopost.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordMarkdownTest {

    private val palette = DiscordPalette(
        codeBackground = Color.Gray,
        quoteBar = Color.Gray,
        quoteText = Color.Gray,
        link = Color.Blue,
        mention = Color.Blue,
        mentionBackground = Color.Gray,
        spoilerBackground = Color.Gray,
        muted = Color.Gray
    )

    @Test
    fun boldMarkersAreStrippedAndWeighted() {
        val out = buildDiscordMarkdown("**bold**", palette)
        assertEquals("bold", out.text)
        val spans = out.spanStyles
        assertTrue("no bold span: $spans", spans.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun boldInsideTextKeepsSurroundingText() {
        val out = buildDiscordMarkdown("a **b** c", palette)
        assertEquals("a b c", out.text)
        val bold = out.spanStyles.firstOrNull { it.item.fontWeight == FontWeight.Bold }
        assertEquals(2, bold?.start)
        assertEquals(3, bold?.end)
    }

    @Test
    fun boldCombinedWithUnderline() {
        val out = buildDiscordMarkdown("**tes** __tes__", palette)
        assertEquals("tes tes", out.text)
        assertTrue(out.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }
}
