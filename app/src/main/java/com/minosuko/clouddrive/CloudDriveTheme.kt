package com.minosuko.clouddrive

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7EFFF),
    onPrimaryContainer = Color(0xFF123A86),
    secondary = Color(0xFF52637D),
    background = Color(0xFFF7F9FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDF1F7),
    outline = Color(0xFFD8DFEA),
    onSurface = Color(0xFF182033),
    onSurfaceVariant = Color(0xFF667085),
    error = Color(0xFFB42318),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DBBFF),
    onPrimary = Color(0xFF002D6C),
    primaryContainer = Color(0xFF17458E),
    background = Color(0xFF101521),
    surface = Color(0xFF171D2A),
    surfaceVariant = Color(0xFF242C3B),
    outline = Color(0xFF364155),
    onSurface = Color(0xFFE8ECF4),
    onSurfaceVariant = Color(0xFFADB8CA),
)

@Composable
fun CloudDriveTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
