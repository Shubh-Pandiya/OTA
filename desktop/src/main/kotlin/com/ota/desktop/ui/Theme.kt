package com.ota.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.otaapplication.data.PaymentStatus

/** Saffron ("bhagva") light theme, the same brand colour as the phone app. */
object Brand {
    val Saffron = Color(0xFFFF6F00)
    val SaffronDark = Color(0xFFC43E00)
    val SaffronSoft = Color(0xFFFFE6D1)
    val SaffronTint = Color(0xFFFFF4EA)
    val Background = Color(0xFFF7F4F1)
    val Surface = Color.White
    val Sidebar = Color(0xFF2A1A10)
    val SidebarText = Color(0xFFF3E6DB)
    val Ink = Color(0xFF2A1F18)
    val Muted = Color(0xFF7A6D64)
    val Line = Color(0xFFE8E0D8)
    val Green = Color(0xFF2E7D32)
    val Amber = Color(0xFFE09A00)
    val Red = Color(0xFFC62828)
    val Blue = Color(0xFF1565C0)

    /** Chart series colours, saffron first. */
    val Series = listOf(
        Color(0xFFFF6F00), Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFF8E24AA),
        Color(0xFF00897B), Color(0xFFE09A00), Color(0xFF6D4C41), Color(0xFFD81B60)
    )

    fun status(s: PaymentStatus): Color = when (s) {
        PaymentStatus.PAID -> Green
        PaymentStatus.PARTLY_PAID -> Amber
        PaymentStatus.UNPAID -> Red
    }
}

private val colors = lightColorScheme(
    primary = Brand.Saffron,
    onPrimary = Color.White,
    primaryContainer = Brand.SaffronSoft,
    onPrimaryContainer = Brand.SaffronDark,
    secondary = Color(0xFF6D4C41),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E4DA),
    onSecondaryContainer = Brand.Ink,
    tertiary = Brand.Blue,
    background = Brand.Background,
    onBackground = Brand.Ink,
    surface = Brand.Surface,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFF4EEE9),
    onSurfaceVariant = Brand.Muted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCFAF8),
    surfaceContainer = Color(0xFFF8F4F0),
    surfaceContainerHigh = Color(0xFFF3EEE9),
    surfaceContainerHighest = Color(0xFFEEE7E1),
    outline = Color(0xFFCDBFB4),
    outlineVariant = Brand.Line,
    error = Brand.Red
)

private val base = Typography()
private val type = Typography(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 14.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 13.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.sp),
    labelLarge = base.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontSize = 12.sp),
    labelSmall = base.labelSmall.copy(fontSize = 11.sp)
)

val Numeric = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun OtaTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = type, content = content)
