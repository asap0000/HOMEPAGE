package com.privacycamera.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF5BC0BE)
private val DeepNavy = Color(0xFF0B132B)
private val MidNavy = Color(0xFF1C2541)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = DeepNavy,
    background = DeepNavy,
    surface = MidNavy,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    background = Color(0xFFF5F7FA),
    surface = Color.White
)

@Composable
fun PrivacyCameraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
