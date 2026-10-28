package com.gabut.autopost

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gabut.autopost.ui.AutoPostViewModel
import com.gabut.autopost.ui.channels.ChannelsScreen
import com.gabut.autopost.ui.components.BrandMark
import com.gabut.autopost.ui.components.plural
import com.gabut.autopost.notify.Notifications
import com.gabut.autopost.ui.components.pluralT
import com.gabut.autopost.ui.i18n.AppLanguage
import com.gabut.autopost.ui.i18n.LanguageState
import com.gabut.autopost.ui.i18n.t
import com.gabut.autopost.ui.logs.LogsScreen
import com.gabut.autopost.ui.settings.CreditScreen
import com.gabut.autopost.ui.settings.SettingsScreen
import com.gabut.autopost.ui.theme.AutoPostTheme
import com.gabut.autopost.ui.verifiers.VerifiersScreen

private data class Tab(
    val route: String,
    val enLabel: String,
    val idLabel: String,
    val tag: String,
    val icon: ImageVector
) {
    val label: String get() = t(enLabel, idLabel)
}

// Content first, configuration last: the three working tabs sit on the left, so the primary
// surfaces are reachable with one thumb without crossing the whole bar.
private val Tabs = listOf(
    Tab("channels", "Bots", "Bot", "tab_channels", Icons.AutoMirrored.Filled.Send),
    Tab("verifiers", "Verify", "Verifikasi", "tab_verifiers", Icons.Filled.CheckCircle),
    Tab("logs", "Activity", "Riwayat", "tab_logs", Icons.AutoMirrored.Filled.List),
    Tab("settings", "Settings", "Setelan", "tab_settings", Icons.Filled.Settings),
    Tab("credit", "About", "Tentang", "tab_credit", Icons.Filled.Info)
)

class MainActivity : ComponentActivity() {

    private val viewModel: AutoPostViewModel by viewModels()

    /** Set when the activity was launched from an alert; consumed by the first composition. */
    private var openSettingsTab = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars; Scaffold and the bars themselves apply the insets.
        enableEdgeToEdge()
        // An alert's content intent carries the token whose settings the owner must open.
        intent?.getLongExtra(EXTRA_OPEN_SETTINGS_TOKEN_ID, -1L)?.takeIf { it > 0L }
            ?.let { tokenId -> viewModel.selectToken(tokenId); openSettingsTab = true }

        setContent {
            val darkMode by viewModel.darkMode.collectAsState()
            val language by viewModel.language.collectAsState()

            // The ViewModel keeps LanguageState in sync (see AutoPostViewModel.init); this only
            // rebuilds the resource-backed notification channels on an actual change.
            LaunchedEffect(language) {
                // Channel names are string resources and Android caches them on creation, so the
                // notification channels are rebuilt whenever the language actually changes.
                Notifications.createChannels(this@MainActivity)
            }

            // Keying the tree on the language forces a full recomposition of every screen, so no
            // remembered string can survive the switch.
            key(language) {
                AutoPostTheme(darkTheme = darkMode) {
                    SystemBarAppearance(darkTheme = darkMode)
                    AutoPostApp(viewModel, startOnSettings = openSettingsTab)
                }
            }
        }
    }

    companion object {
        /** Set by an alert's content intent so the app can open that bot's settings. */
        const val EXTRA_OPEN_SETTINGS_TOKEN_ID = "extra_open_settings_token_id"
    }
}

/**
 * Dark mode is manual, so the status/navigation bar icon color has to follow the app's own flag,
 * not the system one. Without this the clock stays black on a near-black status bar.
 */
@Composable
private fun SystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoPostApp(viewModel: AutoPostViewModel, startOnSettings: Boolean = false) {
    val navController = rememberNavController()

    // Launched from an alert's fix-token action: land on Settings, not on the default tab.
    LaunchedEffect(startOnSettings) {
        if (startOnSettings) navController.navigate("settings") { launchSingleTop = true }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Tabs.first().route
    val summary by viewModel.summary.collectAsState()
    val verifying by viewModel.runningVerifiers.collectAsState()
    val darkMode by viewModel.darkMode.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val running = summary.first
            val channels = summary.second
            val anyWork = running > 0 || verifying > 0
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BrandMark(size = 38.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "FunkPost",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (anyWork) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.45f)
                                        }
                                    )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    running > 0 && verifying > 0 -> t(
                                        "${plural(running, "bot")} posting · " +
                                            "${plural(channels, "channel")} · " +
                                            plural(verifying, "verifier"),
                                        "${pluralT(running, "bot", "bots")} memposting · " +
                                            "${pluralT(channels, "channel", "channels")} · " +
                                            pluralT(verifying, "verifier", "verifier")
                                    )
                                    running > 0 -> t(
                                        "${plural(running, "bot")} posting · " +
                                            plural(channels, "channel"),
                                        "${pluralT(running, "bot", "bots")} memposting · " +
                                            pluralT(channels, "channel", "channels")
                                    )
                                    verifying > 0 -> t(
                                        plural(verifying, "verifier") + " watching",
                                        pluralT(verifying, "verifier", "verifier") + " mengawasi"
                                    )
                                    else -> t("Idle · nothing running", "Diam · tidak ada yang jalan")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("status_bar_summary")
                            )
                        }
                        }
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
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Tabs.forEach { tab ->
                    NavigationBarItem(
                        modifier = Modifier.testTag(tab.tag),
                        selected = currentRoute == tab.route,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Box(
                                modifier = Modifier.size(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.label,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        },
                        label = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tabs.first().route,
            modifier = Modifier.padding(innerPadding),
            // A short cross-fade with a hint of lateral motion: enough to feel alive, fast enough
            // that switching tabs never waits for the animation.
            enterTransition = {
                fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 18 }
            },
            exitTransition = { fadeOut(tween(130)) },
            popEnterTransition = {
                fadeIn(tween(180)) + slideInHorizontally(tween(220)) { -it / 18 }
            },
            popExitTransition = { fadeOut(tween(130)) }
        ) {
            composable("channels") {
                ChannelsScreen(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("screen_channels")
                )
            }
            composable("verifiers") {
                VerifiersScreen(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("screen_verifiers")
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
            composable("logs") {
                LogsScreen(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("screen_logs")
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
