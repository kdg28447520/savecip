package app.saveclip.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.saveclip.LocalContainer
import app.saveclip.ui.theme.Persimmon

private data class Tab(val route: String, val label: String)

private val tabs = listOf(
    Tab("browser", "탐색"),
    Tab("queue", "받기"),
    Tab("library", "보관함"),
    Tab("settings", "설정"),
)

@Composable
fun SaveClipRoot() {
    val nav = rememberNavController()
    val container = LocalContainer.current
    val snackbar = remember { SnackbarHostState() }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val showBar = !route.startsWith("player")

    LaunchedEffect(container) {
        container.messages.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(container) {
        container.incoming.pending.collect { url ->
            if (url.isNullOrBlank()) return@collect
            nav.navigate("browser") {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        val selected = route == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                val color = if (selected) Persimmon else MaterialTheme.colorScheme.onSurfaceVariant
                                when (tab.route) {
                                    "browser" -> MarkBrowse(color)
                                    "queue" -> MarkDown(color)
                                    "library" -> MarkFilm(color)
                                    else -> MarkSliders(color)
                                }
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "browser",
            modifier = androidx.compose.ui.Modifier.padding(padding),
        ) {
            composable("browser") { BrowserScreen() }
            composable("queue") { QueueScreen() }
            composable("library") {
                LibraryScreen(
                    onPlay = { uri -> nav.navigate("player?uri=${Uri.encode(uri)}") },
                    onOpenPage = { url ->
                        container.incoming.offer(url)
                        nav.navigate("browser") {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable("settings") { SettingsScreen() }
            composable(
                route = "player?uri={uri}",
                arguments = listOf(navArgument("uri") { type = NavType.StringType }),
            ) { backStack ->
                val uri = Uri.decode(backStack.arguments?.getString("uri").orEmpty())
                PlayerScreen(uri = uri, onBack = { nav.popBackStack() })
            }
        }
    }
}
