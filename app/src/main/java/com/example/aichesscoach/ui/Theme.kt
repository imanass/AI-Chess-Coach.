package com.example.aichesscoach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Board palette
val BoardLight = Color(0xFFF0D9B5)
val BoardDark = Color(0xFFB58863)
val SquareSelected = Color(0xFFE8D26A)
val SquareLastMove = Color(0x77E9C46A)
val SquareTarget = Color(0x54393B1F)
val SquareCheck = Color(0xE6D84A3F)
val PieceWhite = Color(0xFFFBF9F4)
val PieceWhiteOutline = Color(0xFF23201A)
val PieceBlack = Color(0xFF23201A)

// App palette (warm walnut + brass)
private val Brass = Color(0xFFE3B858)
private val BrassDim = Color(0xFF9A7A2F)
private val NightBg = Color(0xFF12161B)
private val NightSurface = Color(0xFF1A2027)
private val NightSurfaceVariant = Color(0xFF242D36)
private val Sage = Color(0xFF9CC08B)
private val Cream = Color(0xFFF2EDE3)

private val DarkColors = darkColorScheme(
    primary = Brass,
    onPrimary = Color(0xFF241A05),
    primaryContainer = Color(0xFF4A3B14),
    onPrimaryContainer = Color(0xFFF6E3B4),
    secondary = Sage,
    onSecondary = Color(0xFF1B2413),
    secondaryContainer = Color(0xFF2C3A24),
    onSecondaryContainer = Color(0xFFD8EBCC),
    tertiary = Color(0xFF8FB4D9),
    background = NightBg,
    onBackground = Color(0xFFE5E1DA),
    surface = NightSurface,
    onSurface = Color(0xFFE5E1DA),
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = Color(0xFFC4BEB2),
    surfaceContainer = Color(0xFF1E252D),
    surfaceContainerHigh = Color(0xFF262E37),
    error = Color(0xFFE57373),
    outline = Color(0xFF4E5A66)
)

private val LightColors = lightColorScheme(
    primary = BrassDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E1B2),
    onPrimaryContainer = Color(0xFF3A2E0C),
    secondary = Color(0xFF4F7942),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEBD3),
    onSecondaryContainer = Color(0xFF1B2A15),
    tertiary = Color(0xFF8A5A2B),
    background = Cream,
    onBackground = Color(0xFF201D18),
    surface = Color.White,
    onSurface = Color(0xFF201D18),
    surfaceVariant = Color(0xFFEDE6D8),
    onSurfaceVariant = Color(0xFF4F483D),
    error = Color(0xFFB3402F),
    outline = Color(0xFF84796A)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun ChessCoachTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content
    )
}
