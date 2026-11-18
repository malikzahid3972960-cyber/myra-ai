package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val MyraDarkColorScheme = darkColorScheme(
    primary = MyraCyanPrimary,
    onPrimary = MyraVoidBlack,
    primaryContainer = Color(0xFF0D2F52),
    onPrimaryContainer = MyraCyanBright,
    secondary = MyraVioletSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF281952),
    onSecondaryContainer = Color(0xFFE0D4FF),
    tertiary = MyraTealTertiary,
    onTertiary = MyraVoidBlack,
    background = MyraVoidBlack,
    onBackground = MyraTextPrimaryDark,
    surface = MyraDeepSpace,
    onSurface = MyraTextPrimaryDark,
    surfaceVariant = MyraGlassSurface,
    onSurfaceVariant = MyraTextSecondaryDark,
    surfaceContainerHigh = MyraElevatedSurface,
    outline = MyraOutlineDark,
    error = MyraErrorDark,
    onError = MyraVoidBlack
)

private val MyraLightColorScheme = lightColorScheme(
    primary = MyraBluePrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EEFF),
    onPrimaryContainer = Color(0xFF002B49),
    secondary = MyraVioletSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE4FF),
    onSecondaryContainer = Color(0xFF2D0A6E),
    tertiary = MyraTealTertiaryLight,
    onTertiary = Color.White,
    background = MyraLightBackground,
    onBackground = MyraTextPrimaryLight,
    surface = MyraLightSurface,
    onSurface = MyraTextPrimaryLight,
    surfaceVariant = MyraLightSurfaceVariant,
    onSurfaceVariant = MyraTextSecondaryLight,
    outline = MyraOutlineLight,
    error = MyraErrorLight,
    onError = Color.White
)

private val MyraShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MyraTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) MyraDarkColorScheme else MyraLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MyraTypography,
        shapes = MyraShapes,
        content = content
    )
}
