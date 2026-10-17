package com.github.opscalehub.dong.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF00695C), onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB), onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F), secondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFF8D6E00), tertiaryContainer = Color(0xFFFFE08A),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFF9DEDC),
    background = Color(0xFFF7FAF9), surface = Color(0xFFF7FAF9),
    surfaceVariant = Color(0xFFDAE5E2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF80CBC4), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB1CCC6), secondaryContainer = Color(0xFF334B47),
    tertiary = Color(0xFFE9C349), tertiaryContainer = Color(0xFF574500),
    error = Color(0xFFF2B8B5), errorContainer = Color(0xFF8C1D18),
    background = Color(0xFF101414), surface = Color(0xFF101414),
    surfaceVariant = Color(0xFF3F4947),
)

/** Persian text needs a little more line height than Latin defaults. */
private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.tall() = copy(lineHeight = (fontSize.value * 1.55f).sp)
    return base.copy(
        bodyLarge = base.bodyLarge.tall(), bodyMedium = base.bodyMedium.tall(), bodySmall = base.bodySmall.tall(),
        titleMedium = base.titleMedium.tall(), titleSmall = base.titleSmall.tall(), labelLarge = base.labelLarge.tall(),
    )
}

/** The whole app is Persian: layout is always right-to-left regardless of the device locale. */
@Composable
fun DongTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = typography()) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}
