package br.com.berzerk.coletor.ui.telas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.berzerk.coletor.api.SubstituicaoHistoricoDto
import br.com.berzerk.coletor.api.mensagemCurta
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.data.parseNomeItag
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.ChipCategoria
import br.com.berzerk.coletor.ui.comum.FotoProduto
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Cartao as CorCartao
import br.com.berzerk.coletor.ui.theme.Fundo
import br.com.berzerk.coletor.ui.theme.RaioCartao
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface HistoricoUi {
    data object Carregando : HistoricoUi
    data class Pronto(val itens: List<SubstituicaoHistoricoDto>) : HistoricoUi
    data class Erro(val mensagem: String) : HistoricoUi
}

/** Direção do total de uma substituição: define a cor da seta. */
enum class Tendencia { SOBE, DESCE, IGUAL }

fun tendencia(item: SubstituicaoHistoricoDto): Tendencia = when {
    item.totalPara > item.totalDe -> Tendencia.SOBE
    item.totalPara < item.totalDe -> Tendencia.DESCE
    else -> Tendencia.IGUAL
}

/** `"P 0→36 · M 199→261"`: tamanho nulo vira "?", e tamanho com de == para == 0 (ou sem valores) sai. */
fun resumoVariantes(item: SubstituicaoHistoricoDto): String =
    item.variantes
        .filter { !((it.de ?: 0) == 0 && (it.para ?: 0) == 0) }
        .joinToString(" · ") { "${it.tamanho ?: "?"} ${it.de ?: 0}→${it.para ?: 0}" }

class HistoricoEstoqueLojaViewModel : ViewModel() {
    private val _ui = MutableStateFlow<HistoricoUi>(HistoricoUi.Carregando)
    val ui: StateFlow<HistoricoUi> = _ui
    private val _soEsteAparelho = MutableStateFlow(false)
    val soEsteAparelho: StateFlow<Boolean> = _soEsteAparelho

    init { carregar() }

    fun alternarAparelho() {
        _soEsteAparelho.value = !_soEsteAparelho.value
        carregar()
    }

    fun carregar() {
        _ui.value = HistoricoUi.Carregando
        val dispositivo = if (_soEsteAparelho.value) Graph.upload.dispositivoId else null
        viewModelScope.launch {
            _ui.value = runCatching { Graph.api.historicoEstoqueLoja(50, dispositivo) }
                .fold({ HistoricoUi.Pronto(it.itens) }, { HistoricoUi.Erro(it.mensagemCurta()) })
        }
    }
}

@Composable
fun HistoricoEstoqueLojaScreen(voltar: () -> Unit) {
    val vm: HistoricoEstoqueLojaViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val soEste by vm.soEsteAparelho.collectAsState()

    Column(Modifier.fillMaxSize().background(Fundo)) {
        Cabecalho("Histórico", subtitulo = "Substituições de estoque da loja", aoVoltar = voltar, acao = {
            IconButton(onClick = vm::carregar) { Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = Texto) }
        })
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            ChipCategoria("Só este aparelho", soEste, vm::alternarAparelho)
        }
        when (val e = ui) {
            HistoricoUi.Carregando -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Texto) }
            is HistoricoUi.Erro -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(e.mensagem, color = Ambar, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = vm::carregar) { Text("Tentar de novo", color = Texto) }
            }
            is HistoricoUi.Pronto -> if (e.itens.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhuma substituição ainda", color = TextoFraco, style = MaterialTheme.typography.bodyMedium) }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { items(e.itens, key = { it.id }) { CartaoHistorico(it) } }
            }
        }
    }
}

private fun emMs(iso: String): Long? = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()

@Composable
private fun CartaoHistorico(item: SubstituicaoHistoricoDto) {
    val forma = RoundedCornerShape(RaioCartao)
    val (categoria, modelo) = parseNomeItag(item.produto.nome, null)
    val corSeta: Color = when (tendencia(item)) { Tendencia.SOBE -> Verde; Tendencia.DESCE -> Ambar; Tendencia.IGUAL -> TextoFraco }
    val esteAparelho = item.dispositivo != null && item.dispositivo == Graph.upload.dispositivoId
    Column(Modifier.fillMaxWidth().background(CorCartao, forma).border(1.dp, Borda, forma).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FotoProduto(item.produto.imagemUrl, 48.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SeloCategoria(categoria)
                    Spacer(Modifier.width(8.dp))
                    Text(modelo, style = MaterialTheme.typography.titleMedium, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(tempoRelativo(emMs(item.em)), item.ator).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = TextoFraco)
                    if (esteAparelho) {
                        Spacer(Modifier.width(6.dp))
                        br.com.berzerk.coletor.ui.comum.Chip("este aparelho")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${item.totalDe} → ", color = TextoFraco, style = MaterialTheme.typography.titleSmall)
                Text("${item.totalPara}", color = corSeta, style = MaterialTheme.typography.titleSmall)
                Text(" pçs", color = TextoFraco, style = MaterialTheme.typography.bodySmall)
            }
        }
        val linha = resumoVariantes(item)
        if (linha.isNotEmpty()) Text(linha, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextoFraco)
        Text("lidas ${item.lidas} · picking ${item.picking} · margem ${item.margemPct}%", style = MaterialTheme.typography.labelSmall, color = TextoFraco)
    }
}
