package com.winwasm64.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Единая палитра OmniBrowser: тёмные нейтральные поверхности, один акцентный цвет
 * и три смысловых цвета (успех / предупреждение / ошибка). Никакой «радуги».
 */
object Omni {
    val Bg = Color(0xFF0E1116)
    val Surface = Color(0xFF161A21)
    val SurfaceHigh = Color(0xFF1D222B)
    val SurfaceHighest = Color(0xFF262C36)
    val Outline = Color(0xFF2E3541)
    val Text = Color(0xFFE6E9EF)
    val TextDim = Color(0xFF9AA3B2)
    val TextFaint = Color(0xFF6B7482)
    val Accent = Color(0xFF4F8CFF)
    val AccentContainer = Color(0xFF1C2D4A)
    val Success = Color(0xFF3DDC97)
    val Warning = Color(0xFFF2B84B)
    val Danger = Color(0xFFFF6B6B)
    val DangerContainer = Color(0xFF3A1D22)
}

private val OmniColorScheme = darkColorScheme(
    primary = Omni.Accent,
    onPrimary = Color.White,
    primaryContainer = Omni.AccentContainer,
    onPrimaryContainer = Omni.Text,
    secondary = Omni.Accent,
    onSecondary = Color.White,
    secondaryContainer = Omni.SurfaceHighest,
    onSecondaryContainer = Omni.Text,
    tertiary = Omni.Success,
    onTertiary = Color.Black,
    background = Omni.Bg,
    onBackground = Omni.Text,
    surface = Omni.Surface,
    onSurface = Omni.Text,
    surfaceVariant = Omni.SurfaceHigh,
    onSurfaceVariant = Omni.TextDim,
    surfaceTint = Omni.Accent,
    outline = Omni.Outline,
    outlineVariant = Omni.Outline,
    error = Omni.Danger,
    onError = Color.White,
    surfaceContainerLowest = Omni.Bg,
    surfaceContainerLow = Omni.Surface,
    surfaceContainer = Omni.Surface,
    surfaceContainerHigh = Omni.SurfaceHigh,
    surfaceContainerHighest = Omni.SurfaceHighest
)

@Composable
fun OmniTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OmniColorScheme, typography = Typography(), content = content)
}
