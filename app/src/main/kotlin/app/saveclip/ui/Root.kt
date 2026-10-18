package app.saveclip.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
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

private data class Tab(val route: String, val label: String)

private val tabs = listOf(
    Tab("browser", "홈"),
    Tab("downloads", "다운로드"),
    Tab("private", "비공개"),
)

@Composable
fun SaveClipRoot() {
    val nav = rememberNavController()
    val container = LocalContainer.current
    val snackbar = remember { SnackbarHostState() }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val showBar = !route.startsWith("player")
    val badgeScale = rememberBadgeScale()

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
                NavigationBar(
                    modifier = Modifier.height(64.dp),
                    containerColor = MaterialTheme.colorScheme.background,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    tonalElevation = 0.dp,
                ) {
                    tabs.forEach { tab ->
                        val selected = route == tab.route
                        val color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
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
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(2.dp)
                                            .background(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent),
                                    )
                                    Box(
                                        Modifier.graphicsLayer {
                                            scaleX = if (tab.route == "downloads") badgeScale else 1f
                                            scaleY = if (tab.route == "downloads") badgeScale else 1f
                                        },
                                    ) {
                                        when (tab.route) {
                                            "browser" -> MarkBrowse(color)
                                            "downloads" -> MarkDown(color)
                                            else -> MarkLock(color)
                                        }
                                    }
                                }
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Color.Transparent,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
            composable("browser") {
                BrowserScreen(
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenDownloads = {
                        nav.navigate("downloads") {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onPlay = { uri -> nav.navigate("player?uri=${Uri.encode(uri)}") },
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    onPlay = { uri -> nav.navigate("player?uri=${Uri.encode(uri)}") },
                    onOpenPage = { url ->
                        container.incoming.offer(url)
                        nav.navigate("browser") {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onBrowse = {
                        nav.navigate("browser") {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable("private") {
                LibraryScreen(
                    section = LibrarySection.PRIVATE,
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
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
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
