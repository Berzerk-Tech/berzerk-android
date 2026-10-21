package br.com.berzerk.coletor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.berzerk.coletor.R

// Tokens do tema DARK do Nexus (packages/ui/src/styles.css, `.dark`):
// identidade monocromática, sem cor de acento — acento é inversão de contraste.
val Fundo = Color(0xFF0E0E10)        // --background 240 6% 6%
val Cartao = Color(0xFF16161A)       // --card 240 6% 9%
val Muted = Color(0xFF1F1F23)        // --muted 240 5% 13%
val Accent = Color(0xFF2B2B30)       // --accent 240 5% 18%
val Borda = Color(0xFF35353B)        // --border 240 5% 22%
val Texto = Color(0xFFF5F5F5)        // --foreground 0 0% 96%
val TextoFraco = Color(0xFF9C9CA3)   // --muted-foreground 240 4% 62%
val Destrutivo = Color(0xFFD93A3A)   // --destructive 0 72% 50%
val Verde = Color(0xFF3FB950)
val Ambar = Color(0xFFD29922)

/** Compat com nomes antigos usados nas telas. */
val Preto = Fundo
val Superficie = Cartao
val SuperficieAlta = Muted
val Vermelho = Destrutivo
val Azul = Texto

val RaioCartao = 14.dp   // --radius 0.875rem
val RaioBotao = 6.dp     // rounded-md

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)
val Bebas = FontFamily(Font(R.font.bebas_neue, FontWeight.Normal))

private val Esquema = darkColorScheme(
    primary = Texto,
    onPrimary = Fundo,
    secondary = TextoFraco,
    onSecondary = Fundo,
    background = Fundo,
    onBackground = Texto,
    surface = Cartao,
    onSurface = Texto,
    surfaceVariant = Muted,
    onSurfaceVariant = TextoFraco,
    surfaceContainer = Cartao,
    surfaceContainerHigh = Muted,
    surfaceContainerHighest = Accent,
    outline = Borda,
    outlineVariant = Borda,
    error = Destrutivo,
    onError = Color.White,
)

/** `display-heading` do Nexus = Bebas Neue, caixa alta, tracking 0.005em; corpo = Geist. */
private val Tipografia = Typography(
    displayLarge = TextStyle(fontFamily = Bebas, fontSize = 64.sp, lineHeight = 60.sp),
    displayMedium = TextStyle(fontFamily = Bebas, fontSize = 44.sp, lineHeight = 42.sp),
    displaySmall = TextStyle(fontFamily = Bebas, fontSize = 34.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = Bebas, fontSize = 30.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontFamily = Bebas, fontSize = 24.sp, lineHeight = 22.sp),
    titleLarge = TextStyle(fontFamily = Geist, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = Geist, fontSize = 15.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = Geist, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Geist, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Geist, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = Geist, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.sp),
    labelSmall = TextStyle(fontFamily = Geist, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.5.sp),
)

/** Sempre escuro: é o tema do chão (telão, Berzerk Client) e poupa a bateria do coletor. */
@Composable
fun BerzerkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Esquema, typography = Tipografia, content = content)
}
