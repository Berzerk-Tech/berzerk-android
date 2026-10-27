package br.com.berzerk.coletor.ui.telas.impressao

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.data.ModeloResumo
import br.com.berzerk.coletor.data.montarResumos
import br.com.berzerk.coletor.impressora.EstadoImpressora
import br.com.berzerk.coletor.impressora.EtiquetaRender
import br.com.berzerk.coletor.impressora.PedirPermissaoBluetooth
import br.com.berzerk.coletor.impressora.TrabalhoImpressao
import br.com.berzerk.coletor.impressora.dimensoesPx
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.Cartao
import br.com.berzerk.coletor.ui.comum.FotoProduto
import br.com.berzerk.coletor.ui.comum.Rotulo
import br.com.berzerk.coletor.ui.comum.ordenarTamanhos
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Muted
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Vermelho
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Vibração curta de confirmação do toque (o coletor já tem a permissão VIBRATE). */
private fun vibrarCurto(c: Context) {
    runCatching {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

/** Resumo do produto [chave] a partir da base local (mesma montagem da lista); `null` até carregar ou se sumiu. */
@Composable
private fun rememberProduto(chave: String): ModeloResumo? {
    var produto by remember(chave) { mutableStateOf<ModeloResumo?>(null) }
    LaunchedEffect(chave) {
        val linhas = Graph.db.epcs().listarModelos().filter { br.com.berzerk.coletor.data.chaveModelo(it.categoria, it.modelo) == chave }
        val eans = linhas.map { it.ean13 }.filter { it.isNotEmpty() }.distinct()
        val imagens = if (eans.isEmpty()) emptyMap() else Graph.itag.imagemPor(eans)
        produto = withContext(Dispatchers.Default) { montarResumos(linhas, imagens).firstOrNull() }
    }
    return produto
}

@Composable
fun ImpressaoProdutoScreen(chave: String, voltar: () -> Unit, irAjustes: () -> Unit) {
    val contexto = LocalContext.current
    val produto = rememberProduto(chave)
    val estado by Graph.impressora.estado.collectAsState()
    val console by Graph.impressora.console.collectAsState()
    val prefs = Graph.prefs
    var qtd by remember { mutableIntStateOf(prefs.impressaoQtdPorToque.coerceIn(1, 20)) }
    var selecionado by remember(chave) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Imprimir", aoVoltar = voltar, acao = { IndicadorImpressora(irAjustes) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PedirPermissaoBluetooth(aoConceder = { Graph.impressora.reconectarSeNecessario() })

            if (produto == null) {
                Text("Carregando produto…", color = TextoFraco)
                return@Column
            }
            val m = produto
            val tamanhos = ordenarTamanhos(m.eanPorTamanho.keys.filter { it.isNotEmpty() })
            val eanDe = { t: String -> m.eanPorTamanho[t]?.firstOrNull().orEmpty() }

            Row(verticalAlignment = Alignment.CenterVertically) {
                FotoProduto(m.imagemUrl, 80.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.modelo, style = MaterialTheme.typography.titleLarge, color = Texto, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(m.categoria, style = MaterialTheme.typography.bodyMedium, color = TextoFraco)
                }
            }

            Rotulo("Toque no tamanho para imprimir")
            if (tamanhos.isEmpty()) Text("Este produto não tem EAN cadastrado nos tamanhos.", color = TextoFraco)
            tamanhos.chunked(3).forEach { linha ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    linha.forEach { t ->
                        TileImpressao(t, eanDe(t), t == selecionado, Modifier.weight(1f)) {
                            selecionado = t
                            vibrarCurto(contexto)
                            val copias = qtd
                            val (l, a) = dimensoesPx(prefs.etiquetaLarguraMm, prefs.etiquetaAlturaMm)
                            Graph.impressora.enfileirar(
                                TrabalhoImpressao(rotulo = t, tamanho = t, ean = eanDe(t), nome = m.modelo, larguraPx = l, alturaPx = a, copias = copias),
                            )
                        }
                    }
                    repeat(3 - linha.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Rotulo("Quantidade por toque")
            Stepper(qtd) { qtd = it; prefs.impressaoQtdPorToque = it }

            val alvo = selecionado ?: tamanhos.firstOrNull()
            if (alvo != null) {
                Rotulo("Prévia da etiqueta")
                val (l, a) = dimensoesPx(prefs.etiquetaLarguraMm, prefs.etiquetaAlturaMm)
                PreviaEtiqueta(alvo, l, a) { EtiquetaRender.renderizar(alvo, eanDe(alvo), m.modelo, l, a) }
            }

            (estado as? EstadoImpressora.Erro)?.let { e ->
                Cartao(Modifier.border(1.dp, Vermelho, RoundedCornerShape(14.dp))) {
                    Column {
                        Text(e.msg, color = Vermelho)
                        TextButton(onClick = { Graph.impressora.conectar() }) { Text("Tentar de novo", color = Texto) }
                    }
                }
            }
            ConsoleImpressao(console.takeLast(4))
        }
    }
}

@Composable
private fun TileImpressao(tamanho: String, ean: String, selecionado: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val forma = RoundedCornerShape(10.dp)
    Column(
        modifier.height(100.dp).clip(forma).background(Muted, forma)
            .border(if (selecionado) 2.dp else 1.dp, if (selecionado) Texto else Borda, forma)
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(tamanho, style = MaterialTheme.typography.displaySmall, color = Texto, maxLines = 1)
        Text(
            ean, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, letterSpacing = 0.sp), color = TextoFraco,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Stepper(valor: Int, aoMudar: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BotaoPasso("−", valor > 1) { aoMudar(valor - 1) }
        Text("$valor", style = MaterialTheme.typography.titleLarge, color = Texto, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 56.dp))
        BotaoPasso("+", valor < 20) { aoMudar(valor + 1) }
    }
}

@Composable
private fun BotaoPasso(texto: String, habilitado: Boolean, onClick: () -> Unit) {
    val forma = RoundedCornerShape(6.dp)
    Box(
        Modifier.size(44.dp).clip(forma).border(1.dp, Borda, forma).clickable(enabled = habilitado, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(texto, style = MaterialTheme.typography.titleLarge, color = if (habilitado) Texto else TextoFraco) }
}

/** Prévia em fundo branco com o bitmap exato que vai pra impressora (até 240 dp de largura). */
@Composable
internal fun PreviaEtiqueta(tamanho: String, larguraPx: Int, alturaPx: Int, gerar: () -> android.graphics.Bitmap) {
    val bmp by produceState<ImageBitmap?>(null, tamanho, larguraPx, alturaPx) {
        value = withContext(Dispatchers.Default) { gerar().asImageBitmap() }
    }
    Box(
        Modifier.widthIn(max = 240.dp).fillMaxWidth().background(Color.White).border(1.dp, Borda),
        contentAlignment = Alignment.Center,
    ) {
        val img = bmp
        if (img != null) {
            Image(img, contentDescription = "Prévia da etiqueta", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp))
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(larguraPx.toFloat() / alturaPx.coerceAtLeast(1)).background(Color.White))
        }
    }
}

/** Últimas linhas dos eventos da impressora; as que começam com "Erro" saem em vermelho. */
@Composable
internal fun ConsoleImpressao(linhas: List<String>) {
    val forma = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().clip(forma).background(br.com.berzerk.coletor.ui.theme.Fundo).border(1.dp, Borda, forma).padding(8.dp).heightIn(min = 72.dp)) {
        if (linhas.isEmpty()) Text("Nada impresso ainda.", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextoFraco)
        linhas.forEach {
            Text("▸ $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (it.startsWith("Erro")) Vermelho else TextoFraco, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
