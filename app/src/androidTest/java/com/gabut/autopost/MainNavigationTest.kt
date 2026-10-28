package com.gabut.autopost

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun allTabsExist() {
        listOf("tab_channels", "tab_settings", "tab_webhook", "tab_credit").forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test
    fun navigatingToEachTabShowsPlaceholder() {
        listOf(
            "tab_settings" to "screen_settings",
            "tab_webhook" to "screen_webhook",
            "tab_credit" to "screen_credit",
            "tab_channels" to "screen_channels"
        ).forEach { (tabTag, screenTag) ->
            composeRule.onNodeWithTag(tabTag).performClick()
            composeRule.onNodeWithTag(screenTag).assertIsDisplayed()
        }
    }
}
