package com.example.aichesscoach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Board palette - chess.com green board
val BoardLight = Color(0xFFEBECD0)
val BoardDark = Color(0xFF739552)
val SquareSelected = Color(0x99F7EC76)
val SquareLastMove = Color(0x66F7EC76)
val SquareTarget = Color(0x33000000)
val SquareCheck = Color(0xCCDB3C30)
val PieceWhite = Color(0xFFF9F8F6)
val PieceWhiteOutline = Color(0xFF2B2926)
val PieceBlack = Color(0xFF2B2926)

// App palette - chess.com-inspired dark theme
private val CcGreen = Color(0xFF81B64C)
private val CcGreenDeep = Color(0xFF43582B)
private val CcGold = Color(0xFFE9B44C)
private val CcBg = Color(0xFF262421)
private val CcSurface = Color(0xFF302E2B)
private val CcSurfaceHigh = Color(0xFF3C3A37)
private val CcText = Color(0xFFF1F0EE)
private val CcTextDim = Color(0xFFBBB9B4)

private val DarkColors = darkColorScheme(
    primary = CcGreen,
    onPrimary = Color(0xFF15200A),
    primaryContainer = CcGreenDeep,
    onPrimaryContainer = Color(0xFFE2EFCB),
    secondary = Color(0xFF96AF8B),
    onSecondary = Color(0xFF15200A),
    secondaryContainer = Color(0xFF3E4A33),
    onSecondaryContainer = Color(0xFFCFE3B8),
    tertiary = CcGold,
    onTertiary = Color(0xFF2B2006),
    background = CcBg,
    onBackground = CcText,
    surface = CcSurface,
    onSurface = CcText,
    surfaceVariant = CcSurfaceHigh,
    onSurfaceVariant = CcTextDim,
    surfaceContainer = Color(0xFF343230),
    surfaceContainerHigh = CcSurfaceHigh,
    error = Color(0xFFE54040),
    outline = Color(0xFF4A4744)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

/** Always-dark, chess.com-style theme. */
@Composable
fun ChessCoachTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        shapes = AppShapes,
        content = content
    )
}
