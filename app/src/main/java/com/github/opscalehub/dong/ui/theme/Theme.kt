package com.github.opscalehub.dong.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.github.opscalehub.dong.R

/** Vazirmatn (SIL OFL 1.1, bundled; license in /licenses). */
val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

private val Light = lightColorScheme(
    primary = Color(0xFF00695C), onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F), secondaryContainer = Color(0xFFCCE8E2), onSecondaryContainer = Color(0xFF052019),
    tertiary = Color(0xFF8D6E00), tertiaryContainer = Color(0xFFFFE08A),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFF9DEDC),
    background = Color(0xFFF7FAF9), surface = Color(0xFFF7FAF9),
    surfaceVariant = Color(0xFFDAE5E2),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F5F4),
    surfaceContainer = Color(0xFFEBF0EF), surfaceContainerHigh = Color(0xFFE5EBE9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF80CBC4), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB1CCC6), secondaryContainer = Color(0xFF334B47), onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFE9C349), tertiaryContainer = Color(0xFF574500),
    error = Color(0xFFF2B8B5), errorContainer = Color(0xFF8C1D18),
    background = Color(0xFF101414), surface = Color(0xFF101414),
    surfaceVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFF0B0F0F), surfaceContainerLow = Color(0xFF1B2020),
    surfaceContainer = Color(0xFF1F2525), surfaceContainerHigh = Color(0xFF2A302F),
)

/**
 * Chart colours (validated with the dataviz palette checker against the card
 * surfaces above: categorical adjacent CVD ΔE ≥ 8.4, ordinal ramp light end ≥ 2:1).
 */
@Immutable
data class VizColors(
    val categorical: List<Color>,
    /** Ordinal ramp for debt aging, newest → oldest. */
    val aging: List<Color>,
    val positive: Color,
    val negative: Color,
    val neutral: Color,
    val track: Color,
    val grid: Color,
    val baseline: Color,
    val muted: Color,
)

private val VizLight = VizColors(
    categorical = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300).map(::Color),
    aging = listOf(0xFF6DA7EC, 0xFF2A78D6, 0xFF1C5CAB, 0xFF0D366B).map(::Color),
    positive = Color(0xFF2A78D6), negative = Color(0xFFE34948), neutral = Color(0xFF898781),
    track = Color(0xFFDDE5E3), grid = Color(0xFFE1E0D9), baseline = Color(0xFFC3C2B7), muted = Color(0xFF6B6A66),
)

private val VizDark = VizColors(
    categorical = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300).map(::Color),
    aging = listOf(0xFF1C5CAB, 0xFF3987E5, 0xFF86B6EF, 0xFFCDE2FB).map(::Color),
    positive = Color(0xFF3987E5), negative = Color(0xFFE66767), neutral = Color(0xFF898781),
    track = Color(0xFF2E3535), grid = Color(0xFF2C2C2A), baseline = Color(0xFF383835), muted = Color(0xFFA9A89F),
)

val LocalViz = staticCompositionLocalOf { VizLight }

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.fa() = copy(fontFamily = Vazirmatn, lineHeight = (fontSize.value * 1.6f).sp)
    return Typography(
        displayLarge = base.displayLarge.fa(), displayMedium = base.displayMedium.fa(), displaySmall = base.displaySmall.fa(),
        headlineLarge = base.headlineLarge.fa(), headlineMedium = base.headlineMedium.fa(), headlineSmall = base.headlineSmall.fa(),
        titleLarge = base.titleLarge.fa(), titleMedium = base.titleMedium.fa(), titleSmall = base.titleSmall.fa(),
        bodyLarge = base.bodyLarge.fa(), bodyMedium = base.bodyMedium.fa(), bodySmall = base.bodySmall.fa(),
        labelLarge = base.labelLarge.fa(), labelMedium = base.labelMedium.fa(), labelSmall = base.labelSmall.fa(),
    )
}

/** The whole app is Persian: layout is always right-to-left regardless of the device locale. */
@Composable
fun DongTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = typography()) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalViz provides if (dark) VizDark else VizLight,
            content = content,
        )
    }
}
