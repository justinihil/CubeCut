/*
 * CubeCut - cuts a speedcubing session video into one clip per solve.
 * Copyright (C) 2026 DadCuber
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, version 3 of the License,
 * with the additional terms in NOTICE.md (GPL section 7): the author credit must stay in the app's
 * About window, and changed versions must be marked as changed.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program (the
 * LICENSE file). If not, see <https://www.gnu.org/licenses/>.
 */
package com.example.cubecut

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ---------------------------------------------------------------------------------------
// CubeCut's colours: white (or near-black in dark mode), shades of grey, and the blue of the logo. Nothing else.
// Every screen takes its colours from MaterialTheme.colorScheme, so these two lists are the only place to change them.
// ---------------------------------------------------------------------------------------

/** The blue of the CubeCut logo. */
val LogoBlue = Color(0xFF02A3F6)

/** The logo blue a shade darker: used in light mode so blue text and buttons stay readable on white. */
private val LogoBlueDeep = Color(0xFF0288D1)

/** Light, dark, or whatever the phone is set to. Chosen in the Gallery's Settings. */
enum class ThemeMode(val label: String) {
    SYSTEM("Same as the phone"),
    LIGHT("Light"),
    DARK("Dark");

    companion object {
        fun from(name: String?): ThemeMode = ThemeMode.entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

private val LightColors = lightColorScheme(
    primary = LogoBlueDeep, onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF1FD), onPrimaryContainer = Color(0xFF00344F),
    inversePrimary = LogoBlue,
    secondary = Color(0xFF5F6368), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EAED), onSecondaryContainer = Color(0xFF202124),
    tertiary = Color(0xFF5F6368), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8EAED), onTertiaryContainer = Color(0xFF202124),
    background = Color.White, onBackground = Color(0xFF1F1F1F),
    surface = Color.White, onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFF1F3F4), onSurfaceVariant = Color(0xFF5F6368),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF303134), inverseOnSurface = Color(0xFFF1F3F4),
    error = Color(0xFFD93025), onError = Color.White,
    errorContainer = Color(0xFFFCE8E6), onErrorContainer = Color(0xFF5F0B05),
    outline = Color(0xFFBDC1C6), outlineVariant = Color(0xFFDADCE0),
    scrim = Color.Black,
    surfaceBright = Color.White, surfaceDim = Color(0xFFDADCE0),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F9FA), surfaceContainer = Color(0xFFF1F3F4),
    surfaceContainerHigh = Color(0xFFECEEF0), surfaceContainerHighest = Color(0xFFE3E5E8)
)

private val DarkColors = darkColorScheme(
    primary = LogoBlue, onPrimary = Color(0xFF00243A),
    primaryContainer = Color(0xFF0B3A55), onPrimaryContainer = Color(0xFFCDEBFD),
    inversePrimary = LogoBlueDeep,
    secondary = Color(0xFFBDC1C6), onSecondary = Color(0xFF202124),
    secondaryContainer = Color(0xFF3C4043), onSecondaryContainer = Color(0xFFE8EAED),
    tertiary = Color(0xFFBDC1C6), onTertiary = Color(0xFF202124),
    tertiaryContainer = Color(0xFF3C4043), onTertiaryContainer = Color(0xFFE8EAED),
    background = Color(0xFF121212), onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF121212), onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF2A2B2E), onSurfaceVariant = Color(0xFFBDC1C6),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFE8EAED), inverseOnSurface = Color(0xFF202124),
    error = Color(0xFFF28B82), onError = Color(0xFF3C0B07),
    errorContainer = Color(0xFF5F0B05), onErrorContainer = Color(0xFFFCE8E6),
    outline = Color(0xFF5F6368), outlineVariant = Color(0xFF3C4043),
    scrim = Color.Black,
    surfaceBright = Color(0xFF38393B), surfaceDim = Color(0xFF121212),
    surfaceContainerLowest = Color(0xFF0D0D0D), surfaceContainerLow = Color(0xFF1A1A1B), surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF28292B), surfaceContainerHighest = Color(0xFF333537)
)

/** The few colours that are not part of MaterialTheme: they change with light and dark mode too. */
object AppColors {
    /** "Look at this one": amber, darker in light mode so it stays readable on white. */
    var check by mutableStateOf(Color(0xFFFFB74D))
        private set

    /** "This stays / is kept": green ticks in the delete and forget warnings, darker in light mode so it stays readable on white. */
    var keep by mutableStateOf(Color(0xFF66BB6A))
        private set

    /** Delete buttons. */
    val danger = Color(0xFFD93025)

    internal fun use(dark: Boolean) {
        check = if (dark) Color(0xFFFFB74D) else Color(0xFFB06000)
        keep = if (dark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
    }
}

/** Wraps the whole app: picks light or dark colours and makes the status bar icons readable on them. */
@Composable
fun CubeCutTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SideEffect { AppColors.use(dark) }          // after composing (a change then redraws whatever shows the colour)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect   // findActivity: see Gallery.kt
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}