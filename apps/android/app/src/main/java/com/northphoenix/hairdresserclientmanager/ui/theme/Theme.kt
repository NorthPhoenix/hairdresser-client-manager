package com.northphoenix.hairdresserclientmanager.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.clerk.api.ui.ClerkColors
import com.clerk.api.ui.ClerkDesign
import com.clerk.api.ui.ClerkTheme
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.data.AppointmentStatus

/** Shared theme tokens from packages/shared, extended with the tints the native UI needs. */
object HcmColors {
    val Ink = Color(0xFF17130F)
    val Muted = Color(0xFF6D6259)
    val Paper = Color(0xFFF7F1E8)
    val Surface = Color(0xFFFFFAF3)
    val Accent = Color(0xFF7D2F1B)

    val AccentSoft = Color(0xFFF3E0D6)
    val AccentDeep = Color(0xFF4A1A0E)
    val Sand = Color(0xFFF0E6D7)
    val Hairline = Color(0xFFE5D8C5)
    val Outline = Color(0xFFCDBDA8)
    val Faint = Color(0xFF9A8E82)
    val Success = Color(0xFF2E6B4A)
    val SuccessSoft = Color(0xFFDCEBE0)
    val Warning = Color(0xFF8F5600)
    val WarningSoft = Color(0xFFF7E7C9)
    val Danger = Color(0xFFA6281E)
    val DangerSoft = Color(0xFFF8DFDB)
}

data class StatusColors(val content: Color, val container: Color)

fun AppointmentStatus.colors(): StatusColors =
    when (this) {
        AppointmentStatus.SCHEDULED -> StatusColors(HcmColors.Accent, HcmColors.AccentSoft)
        AppointmentStatus.COMPLETED -> StatusColors(HcmColors.Success, HcmColors.SuccessSoft)
        AppointmentStatus.CANCELED -> StatusColors(HcmColors.Muted, HcmColors.Sand)
        AppointmentStatus.NO_SHOW -> StatusColors(HcmColors.Warning, HcmColors.WarningSoft)
    }

private val colorScheme = lightColorScheme(
    primary = HcmColors.Accent,
    onPrimary = HcmColors.Surface,
    primaryContainer = HcmColors.AccentSoft,
    onPrimaryContainer = HcmColors.AccentDeep,
    secondary = HcmColors.Ink,
    onSecondary = HcmColors.Surface,
    secondaryContainer = HcmColors.AccentSoft,
    onSecondaryContainer = HcmColors.AccentDeep,
    tertiary = HcmColors.Success,
    onTertiary = HcmColors.Surface,
    tertiaryContainer = HcmColors.SuccessSoft,
    onTertiaryContainer = HcmColors.Success,
    background = HcmColors.Paper,
    onBackground = HcmColors.Ink,
    surface = HcmColors.Surface,
    onSurface = HcmColors.Ink,
    surfaceVariant = HcmColors.Sand,
    onSurfaceVariant = HcmColors.Muted,
    surfaceTint = Color.Transparent,
    surfaceBright = HcmColors.Surface,
    surfaceDim = HcmColors.Sand,
    surfaceContainerLowest = Color(0xFFFFFDF9),
    surfaceContainerLow = HcmColors.Surface,
    surfaceContainer = Color(0xFFFBF4EA),
    surfaceContainerHigh = Color(0xFFF7EEE1),
    surfaceContainerHighest = HcmColors.Sand,
    outline = HcmColors.Outline,
    outlineVariant = HcmColors.Hairline,
    error = HcmColors.Danger,
    onError = HcmColors.Surface,
    errorContainer = HcmColors.DangerSoft,
    onErrorContainer = HcmColors.Danger,
    scrim = HcmColors.Ink,
)

private fun variableFamily(resId: Int, vararg weights: Int): FontFamily =
    FontFamily(
        weights.map { weight ->
            Font(
                resId = resId,
                weight = FontWeight(weight),
                variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
            )
        },
    )

/** Serif for names and screen titles. Chosen for its Cyrillic, which is drawn as carefully as its Latin. */
val DisplayFamily = variableFamily(R.font.lora, 500, 600, 700)

/** Body and UI text. Golos Text was designed for Russian first, with matching Latin. */
val BodyFamily = variableFamily(R.font.golos_text, 400, 500, 600, 700, 800)

private fun display(size: Int, lineHeight: Int, weight: Int = 600) =
    TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight(weight),
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = (-0.01).em,
    )

private fun body(size: Int, lineHeight: Int, weight: Int, tracking: Double = 0.0) =
    TextStyle(
        fontFamily = BodyFamily,
        fontWeight = FontWeight(weight),
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = tracking.em,
    )

private val typography = Typography(
    displayLarge = display(40, 46),
    displayMedium = display(34, 40),
    displaySmall = display(30, 36),
    headlineLarge = display(28, 34),
    headlineMedium = display(24, 30),
    headlineSmall = display(21, 27),
    titleLarge = display(19, 25),
    titleMedium = body(16, 22, 700),
    titleSmall = body(14, 20, 700),
    bodyLarge = body(16, 24, 500),
    bodyMedium = body(14, 20, 500),
    bodySmall = body(12, 17, 500),
    labelLarge = body(15, 20, 700),
    labelMedium = body(13, 18, 700),
    labelSmall = body(11, 16, 700, tracking = 0.06),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun HcmTheme(content: @Composable () -> Unit) {
    // The product is light-only, matching the previous app's `userInterfaceStyle: light`.
    MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes, content = content)
}

/** Makes Clerk's prebuilt sign-in views match the app. */
fun hcmClerkTheme(): ClerkTheme =
    ClerkTheme(
        colors = ClerkColors(
            primary = HcmColors.Accent,
            primaryForeground = HcmColors.Surface,
            background = HcmColors.Paper,
            foreground = HcmColors.Ink,
            mutedForeground = HcmColors.Muted,
            input = HcmColors.Surface,
            inputForeground = HcmColors.Ink,
            border = HcmColors.Outline,
            muted = HcmColors.Sand,
            danger = HcmColors.Danger,
            success = HcmColors.Success,
        ),
        design = ClerkDesign(borderRadius = 14.dp),
    )
