package es.us.ussync.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

private val EditorialLight = lightColorScheme(
    primary = Color(0xFF1259D3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1259D3),
    onPrimaryContainer = Color(0xFFD2DCFF),
    secondary = Color(0xFF525F72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E3FA),
    onSecondaryContainer = Color(0xFF586578),
    tertiary = Color(0xFF4A7C59),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEBF3ED),
    onTertiaryContainer = Color(0xFF215333),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE5EFFF),
    surfaceContainerLow = Color(0xFFEEF4FF),
    surfaceContainer = Color(0xFFE5EFFF),
    surfaceContainerHigh = Color(0xFFDDE9FA),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF0E1C2C),
    onSurface = Color(0xFF0E1C2C),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFC3C6D6),
    outlineVariant = Color(0xFFEAECEF),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
)

private val EditorialDark = darkColorScheme(
    primary = Color(0xFF3D75FF), onPrimary = Color.White,
    primaryContainer = Color(0xFF174BAE), onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFFBAC7DD), onSecondary = Color(0xFF243142),
    secondaryContainer = Color(0xFF3B4859), onSecondaryContainer = Color(0xFFD6E3FA),
    tertiary = Color(0xFF9DD3AA), onTertiary = Color(0xFF06391D),
    tertiaryContainer = Color(0xFF1E5031), onTertiaryContainer = Color(0xFFB9EFC5),
    background = Color(0xFF101926), surface = Color(0xFF121D2B),
    surfaceVariant = Color(0xFF26384D), surfaceContainerLow = Color(0xFF182434),
    surfaceContainer = Color(0xFF1D2B3C), surfaceContainerHigh = Color(0xFF26384D),
    onBackground = Color(0xFFE9F1FF),
    onSurface = Color(0xFFE9F1FF), onSurfaceVariant = Color(0xFFBAC7DD),
    outline = Color(0xFF8D9AB0), outlineVariant = Color(0xFF33465C),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A),
)

private val EditorialShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val EditorialTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun USSyncTheme(appearance: String = "system", content: @Composable () -> Unit) {
    val dark = appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) EditorialDark else EditorialLight, typography = EditorialTypography, shapes = EditorialShapes, content = content)
}
