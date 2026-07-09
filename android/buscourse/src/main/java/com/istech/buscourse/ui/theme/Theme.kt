package com.istech.buscourse.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * BusCourse の Compose テーマ（フェーズ2 UI、設計書§9）。
 * istech ブランド方針（istech/CLAUDE.md）: 基調色ブルー #3366FF・背景ホワイト・シンプル/ミニマル。
 * 構成は :app（PrivacyCamera）の `ui/theme/Theme.kt` の慣習に合わせる。
 */
private val IstechBlue = Color(0xFF3366FF)
private val IstechBlueDark = Color(0xFF8FA8FF)
private val DarkSurface = Color(0xFF14171F)
private val DarkBackground = Color(0xFF0D0F14)

private val LightColors = lightColorScheme(
    primary = IstechBlue,
    onPrimary = Color.White,
    background = Color.White,
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = IstechBlueDark,
    onPrimary = DarkBackground,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun BusCourseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
