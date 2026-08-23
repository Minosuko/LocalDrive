package com.minosuko.clouddrive

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private enum class RootDestination(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Outlined.Home),
    Photos("photos", "Medias", Icons.Outlined.PhotoLibrary),
    Files("files", "Files", Icons.Outlined.Folder),
    Messages("messages", "Messages", Icons.Outlined.ChatBubbleOutline),
    Settings("settings", "Settings", Icons.Outlined.Settings),
}

@Composable
fun CloudDriveRoot(
    theme: ThemeMode,
    onThemeChanged: (ThemeMode) -> Unit,
    messageLaunch: MessageLaunch? = null,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    var photosChromeVisible by remember { mutableStateOf(true) }

    LaunchedEffect(currentRoute) {
        if (currentRoute != RootDestination.Photos.route) photosChromeVisible = true
    }

    LaunchedEffect(messageLaunch?.requestId) {
        if (messageLaunch != null) {
            navController.navigate(RootDestination.Messages.route) {
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = currentRoute != RootDestination.Photos.route || photosChromeVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    RootDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController,
            startDestination = RootDestination.Home.route,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn(tween(140)) },
            exitTransition = { fadeOut(tween(90)) },
            popEnterTransition = { fadeIn(tween(140)) },
            popExitTransition = { fadeOut(tween(90)) },
        ) {
            composable(RootDestination.Home.route) {
                DashboardScreen()
            }
            composable(RootDestination.Photos.route) {
                PhotosScreen(onNavigationVisibilityChanged = { photosChromeVisible = it })
            }
            composable(RootDestination.Files.route) { ProductFilesScreen() }
            composable(RootDestination.Messages.route) { MessagesScreen(messageLaunch?.address, messageLaunch?.body) }
            composable(RootDestination.Settings.route) {
                ProductSettingsScreen(theme, onThemeChanged)
            }
        }
    }
}
