package com.alcoholchecker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = lightColorScheme(
    primary         = Color(0xFF1565C0),
    onPrimary       = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    secondary       = Color(0xFF2E7D32),
    onSecondary     = Color.White,
    error           = Color(0xFFC62828),
    background      = Color(0xFFF5F7FA),
    surface         = Color.White
)

val PassGreen  = Color(0xFF2E7D32)
val FailRed    = Color(0xFFC62828)
val WarningAmber = Color(0xFFF57F17)

@Composable
fun AlcoholCheckerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
