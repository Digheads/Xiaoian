package com.xiaoian.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Cyan80,
    secondary = BlueGrey80,
    tertiary = Cyan40,
    background = BackgroundDark,
    surface = SurfaceDark
)

@Composable
fun XiaoianTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
