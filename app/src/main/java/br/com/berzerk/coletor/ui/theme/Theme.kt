package br.com.berzerk.coletor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Preto = Color(0xFF0E0E10)
val Superficie = Color(0xFF17171A)
val SuperficieAlta = Color(0xFF222226)
val Borda = Color(0xFF2E2E33)
val Texto = Color(0xFFF4F4F5)
val TextoFraco = Color(0xFF9A9AA3)
val Verde = Color(0xFF22C55E)
val Vermelho = Color(0xFFEF4444)
val Ambar = Color(0xFFF59E0B)
val Azul = Color(0xFF60A5FA)

private val Esquema = darkColorScheme(
    primary = Texto,
    onPrimary = Preto,
    secondary = TextoFraco,
    background = Preto,
    onBackground = Texto,
    surface = Superficie,
    onSurface = Texto,
    surfaceVariant = SuperficieAlta,
    onSurfaceVariant = TextoFraco,
    outline = Borda,
    error = Vermelho,
)

private val Tipografia = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
)

/** Sempre escuro: é o padrão das telas de chão (telão, Berzerk Client) e poupa a bateria do coletor. */
@Composable
fun BerzerkTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = Esquema, typography = Tipografia, content = content)
}
