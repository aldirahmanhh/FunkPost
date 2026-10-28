package com.gabut.autopost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gabut.autopost.ui.AutoPostViewModel
import com.gabut.autopost.ui.channels.ChannelsScreen
import com.gabut.autopost.ui.settings.CreditScreen
import com.gabut.autopost.ui.settings.SettingsScreen
import com.gabut.autopost.ui.settings.WebhookScreen
import com.gabut.autopost.ui.theme.AutoPostTheme

private data class Tab(val route: String, val label: String, val tag: String)

private val Tabs = listOf(
    Tab("channels", "Channels", "tab_channels"),
    Tab("settings", "Settings", "tab_settings"),
    Tab("webhook", "Webhook", "tab_webhook"),
    Tab("credit", "Credit", "tab_credit")
)

class MainActivity : ComponentActivity() {

    private val viewModel: AutoPostViewModel by viewModels()

    /** Set when the activity was launched from an alert; consumed by the first composition. */
    private var openSettingsTab = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // An alert's content intent carries the token whose settings the owner must open.
        intent?.getLongExtra(EXTRA_OPEN_SETTINGS_TOKEN_ID, -1L)?.takeIf { it > 0L }
            ?.let { tokenId -> viewModel.selectToken(tokenId); openSettingsTab = true }

        setContent {
            val darkMode by viewModel.darkMode.collectAsState()
            AutoPostTheme(darkTheme = darkMode) {
                AutoPostApp(viewModel, startOnSettings = openSettingsTab)
            }
        }
    }

    companion object {
        /** Set by an alert's content intent so the app can open that bot's settings. */
        const val EXTRA_OPEN_SETTINGS_TOKEN_ID = "extra_open_settings_token_id"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoPostApp(viewModel: AutoPostViewModel, startOnSettings: Boolean = false) {
    val navController = rememberNavController()

    // Launched from an alert's fix-token action: land on Settings, not on the default tab.
    androidx.compose.runtime.LaunchedEffect(startOnSettings) {
        if (startOnSettings) navController.navigate("settings") { launchSingleTop = true }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Tabs.first().route
    val summary by viewModel.summary.collectAsState()
    val darkMode by viewModel.darkMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Auto Post", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = com.gabut.autopost.notify.Notifications.summaryText(
                                summary.first,
                                summary.second
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("status_bar_summary")
                        )
                    }
                },
                actions = {
                    DarkModeToggle(
                        dark = darkMode,
                        onToggle = { viewModel.setDarkMode(it) }
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Tabs.forEach { tab ->
                    NavigationBarItem(
                        modifier = Modifier.testTag(tab.tag),
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Box(Modifier.size(24.dp)) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tabs.first().route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("channels") {
                ChannelsScreen(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("screen_channels")
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("screen_settings")
                )
            }
            composable("webhook") {
                WebhookScreen(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("screen_webhook")
                )
            }
            composable("credit") {
                CreditScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .fillMaxWidth()
                        .testTag("screen_credit")
                )
            }
        }
    }
}