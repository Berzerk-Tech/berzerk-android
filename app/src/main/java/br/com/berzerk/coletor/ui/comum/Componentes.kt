package br.com.berzerk.coletor.ui.comum

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import br.com.berzerk.coletor.R
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Cartao as CorCartao
import br.com.berzerk.coletor.ui.theme.Fundo
import br.com.berzerk.coletor.ui.theme.Muted
import br.com.berzerk.coletor.ui.theme.RaioBotao
import br.com.berzerk.coletor.ui.theme.RaioCartao
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco

/** Símbolo da marca (`brand/icon-dark.png`, branco) pintado com a cor pedida — o `BrandMask` do web. */
@Composable
fun Simbolo(modifier: Modifier = Modifier, cor: Color = Texto) {
    Image(
        painterResource(R.drawable.berzerk_simbolo),
        contentDescription = "Berzerk",
        modifier = modifier,
        colorFilter = ColorFilter.tint(cor),
    )
}

/** A grade sutil do painel institucional da tela de login do Nexus (56 px, border/0.45). */
fun Modifier.gradeDeFundo(passo: Dp = 56.dp, cor: Color = Borda.copy(alpha = 0.45f)): Modifier = drawBehind {
    val p = passo.toPx()
    var x = 0f
    while (x < size.width) { drawLine(cor, Offset(x, 0f), Offset(x, size.height), 1f); x += p }
    var y = 0f
    while (y < size.height) { drawLine(cor, Offset(0f, y), Offset(size.width, y), 1f); y += p }
}

/** Rótulo pequeno em caixa alta com tracking largo (`text-xs uppercase tracking-[0.35em]`). */
@Composable
fun Rotulo(texto: String, cor: Color = TextoFraco) {
    Text(texto.uppercase(), style = MaterialTheme.typography.labelSmall, color = cor)
}

/** Título de página no padrão do `PageHeader` do Nexus: rótulo em cima, display em Bebas embaixo. */
@Composable
fun Cabecalho(titulo: String, subtitulo: String? = null, aoVoltar: (() -> Unit)? = null, rotulo: String? = null, acao: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(Fundo).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = if (aoVoltar != null) 4.dp else 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (aoVoltar != null) {
                IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Texto) }
            }
            Column(Modifier.weight(1f)) {
                if (rotulo != null) Rotulo(rotulo)
                Text(titulo.uppercase(), style = MaterialTheme.typography.headlineMedium, color = Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitulo != null) Text(subtitulo, style = MaterialTheme.typography.bodyMedium, color = TextoFraco, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            acao?.invoke()
        }
        HorizontalDivider(Modifier.padding(top = 10.dp), color = Borda)
    }
}

@Composable
fun Cartao(modifier: Modifier = Modifier, conteudo: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(CorCartao, RoundedCornerShape(RaioCartao))
            .border(1.dp, Borda, RoundedCornerShape(RaioCartao))
            .padding(16.dp),
    ) { conteudo() }
}

/** `Button` primário do Nexus: foreground sobre background (inversão), `rounded-md`, 44 dp. */
@Composable
fun BotaoGrande(
    texto: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cor: Color = Texto,
    corTexto: Color = Fundo,
    habilitado: Boolean = true,
    contorno: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.fillMaxWidth().height(52.dp).let { if (contorno) it.border(1.dp, Borda, RoundedCornerShape(RaioBotao)) else it },
        shape = RoundedCornerShape(RaioBotao),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (contorno) Color.Transparent else cor,
            contentColor = if (contorno) Texto else corTexto,
            disabledContainerColor = if (contorno) Color.Transparent else Muted,
            disabledContentColor = TextoFraco,
        ),
    ) { Text(texto, style = MaterialTheme.typography.titleSmall) }
}

/** Chip de contexto (`ACESSO RESTRITO · @berzerk.com.br`): borda, fundo muted, caixa alta. */
@Composable
fun Chip(rotulo: String, valor: String? = null, cor: Color = TextoFraco) {
    Row(
        Modifier.background(Muted, RoundedCornerShape(4.dp)).border(1.dp, Borda, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(rotulo.uppercase(), style = MaterialTheme.typography.labelMedium, color = cor)
        if (valor != null) {
            Text("  ·  ", style = MaterialTheme.typography.labelMedium, color = TextoFraco)
            Text(valor, style = MaterialTheme.typography.bodySmall, color = cor)
        }
    }
}

@Composable
fun LinhaInfo(rotulo: String, valor: String, corValor: Color = Texto) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(rotulo, color = TextoFraco, style = MaterialTheme.typography.bodyMedium)
        Text(valor, color = corValor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Ponto(cor: Color) {
    Box(Modifier.size(8.dp).background(cor, RoundedCornerShape(50)))
}

@Composable
fun Separador() {
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = Borda)
    Spacer(Modifier.height(4.dp))
}

@Composable
fun Lacuna(altura: Dp = 8.dp) = Spacer(Modifier.height(altura))

@Composable
fun LacunaH(largura: Dp = 8.dp) = Spacer(Modifier.width(largura))

/**
 * Foto do produto: caixa quadrada com a imagem em `Crop` + zoom (as fotos são camiseta em fundo branco;
 * o zoom aproxima a estampa). Toque abre a foto inteira em tela cheia, sem disparar o clique do card.
 */
@Composable
fun FotoProduto(url: String?, tamanho: Dp, modifier: Modifier = Modifier) {
    val forma = RoundedCornerShape(6.dp)
    var ampliada by remember { mutableStateOf(false) }
    Box(modifier.size(tamanho).clip(forma).background(Fundo).let { if (url != null) it.clickable { ampliada = true } else it }) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(tamanho),
            )
        }
    }
    if (ampliada && url != null) FotoTelaCheia(url) { ampliada = false }
}

@Composable
private fun FotoTelaCheia(url: String, fechar: () -> Unit) {
    // Shopify serve a versão pelo `?width=`: no diálogo pede a grande.
    val grande = remember(url) { url.replace(Regex("([?&])width=\\d+"), "$1width=1200") }
    val contexto = LocalContext.current
    // Abre na hora com a miniatura que já está no cache de memória (chave = url pequena) e troca pela grande quando chegar.
    val pedido = remember(url) {
        ImageRequest.Builder(contexto).data(grande).placeholderMemoryCacheKey(MemoryCache.Key(url)).crossfade(false).build()
    }
    Dialog(onDismissRequest = fechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = fechar), contentAlignment = Alignment.Center) {
            AsyncImage(model = pedido, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}
