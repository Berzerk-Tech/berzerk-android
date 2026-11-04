package br.com.berzerk.coletor.ui.telas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.berzerk.coletor.api.SubstituicaoHistoricoDto
import br.com.berzerk.coletor.api.VarianteHistoricoDto
import br.com.berzerk.coletor.api.mensagemCurta
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.data.parseNomeItag
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.ChipCategoria
import br.com.berzerk.coletor.ui.comum.FotoProduto
import br.com.berzerk.coletor.ui.comum.ordenarTamanhos
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Cartao as CorCartao
import br.com.berzerk.coletor.ui.theme.Fundo
import br.com.berzerk.coletor.ui.theme.Muted
import br.com.berzerk.coletor.ui.theme.RaioCartao
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Estado + regras puras (testadas em src/test)
// ---------------------------------------------------------------------------------------------

/**
 * Estado da tela: a lista anterior fica na tela enquanto recarrega (barra fina no topo), em vez de
 * sumir a cada toggle de filtro ou refresh. [erro] só toma a tela quando não há nada pra mostrar.
 * "Todos" = nem [soEsteAparelho] nem [soEsteModelo]. [dia] nulo = todos os dias.
 */
data class HistoricoUi(
    val carregando: Boolean = true,
    val itens: List<SubstituicaoHistoricoDto> = emptyList(),
    val erro: String? = null,
    val soEsteAparelho: Boolean = false,
    /** Só existe quando a tela foi aberta de dentro de um modelo (botão LOJA). */
    val soEsteModelo: Boolean = false,
    val dia: LocalDate? = LocalDate.now(),
) {
    val todos: Boolean get() = !soEsteAparelho && !soEsteModelo
}

/** Direção do total de uma substituição: define a cor da seta. */
enum class Tendencia { SOBE, DESCE, IGUAL }

fun tendencia(item: SubstituicaoHistoricoDto): Tendencia = tendencia(item.totalDe, item.totalPara)

fun tendencia(de: Int, para: Int): Tendencia = when {
    para > de -> Tendencia.SOBE
    para < de -> Tendencia.DESCE
    else -> Tendencia.IGUAL
}

/** Tamanhos que valem mostrar: sai quem tem de == para == 0 (ou sem valores). */
fun variantesVisiveis(item: SubstituicaoHistoricoDto): List<VarianteHistoricoDto> =
    item.variantes.filter { !((it.de ?: 0) == 0 && (it.para ?: 0) == 0) }

/** `"P 0→36 · M 199→261"`: tamanho nulo vira "?". */
fun resumoVariantes(item: SubstituicaoHistoricoDto): String =
    variantesVisiveis(item).joinToString(" · ") { "${it.tamanho ?: "?"} ${it.de ?: 0}→${it.para ?: 0}" }

/** Filtro local por produto: o Nexus só filtra por aparelho, então o "Só este modelo" é feito aqui sobre a página maior. */
fun filtrarPorProduto(itens: List<SubstituicaoHistoricoDto>, produtoId: String?): List<SubstituicaoHistoricoDto> =
    if (produtoId.isNullOrBlank()) itens else itens.filter { it.produto.id == produtoId }

fun emMs(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

fun diaLocal(iso: String, zona: ZoneId = ZoneId.systemDefault()): LocalDate? =
    emMs(iso)?.let { Instant.ofEpochMilli(it).atZone(zona).toLocalDate() }

/** Filtro local por dia (fuso do aparelho); [dia] nulo = tudo. Item com data inválida só aparece sem filtro. */
fun filtrarPorDia(itens: List<SubstituicaoHistoricoDto>, dia: LocalDate?, zona: ZoneId = ZoneId.systemDefault()): List<SubstituicaoHistoricoDto> =
    if (dia == null) itens else itens.filter { diaLocal(it.em, zona) == dia }

/** Card "Resumo do dia": soma do que está listado, tamanho a tamanho (na régua; `?` por último). */
data class ResumoHistorico(
    val substituicoes: Int,
    val produtos: Int,
    val totalDe: Int,
    val totalPara: Int,
    val lidas: Int,
    val picking: Int,
    val variantes: List<VarianteHistoricoDto>,
)

fun resumir(itens: List<SubstituicaoHistoricoDto>): ResumoHistorico {
    val de = HashMap<String, Int>()
    val para = HashMap<String, Int>()
    itens.flatMap { it.variantes }.forEach { v ->
        val t = v.tamanho?.trim()?.takeIf { it.isNotEmpty() } ?: "?"
        de[t] = (de[t] ?: 0) + (v.de ?: 0)
        para[t] = (para[t] ?: 0) + (v.para ?: 0)
    }
    val (semTamanho, comTamanho) = de.keys.partition { it == "?" }
    val ordem = ordenarTamanhos(comTamanho) + semTamanho
    val variantes = ordem.map { VarianteHistoricoDto(it, de[it] ?: 0, para[it] ?: 0) }.filter { !((it.de ?: 0) == 0 && (it.para ?: 0) == 0) }
    return ResumoHistorico(
        substituicoes = itens.size,
        produtos = itens.map { it.produto.id }.distinct().size,
        totalDe = itens.sumOf { it.totalDe },
        totalPara = itens.sumOf { it.totalPara },
        lidas = itens.sumOf { it.lidas },
        picking = itens.sumOf { it.picking },
        variantes = variantes,
    )
}

/** "Hoje · 04/11", "Ontem · 03/11", "02/11" ou "Todos os dias". */
fun rotuloDia(dia: LocalDate?, hoje: LocalDate = LocalDate.now()): String {
    if (dia == null) return "Todos os dias"
    val ddmm = dia.format(DateTimeFormatter.ofPattern("dd/MM"))
    return when (dia) {
        hoje -> "Hoje · $ddmm"
        hoje.minusDays(1) -> "Ontem · $ddmm"
        else -> ddmm
    }
}

private val fmtInt: NumberFormat = NumberFormat.getIntegerInstance(Locale("pt", "BR"))
fun numero(n: Int): String = fmtInt.format(n)
/** "+586", "−12" ou "0" (sinal tipográfico, sem hífen). */
fun delta(de: Int, para: Int): String = when {
    para > de -> "+" + numero(para - de)
    para < de -> "−" + numero(de - para)
    else -> "0"
}

// ---------------------------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------------------------

class HistoricoEstoqueLojaViewModel(private val produtoId: String?) : ViewModel() {
    private val _ui = MutableStateFlow(HistoricoUi(soEsteModelo = !produtoId.isNullOrBlank()))
    val ui: StateFlow<HistoricoUi> = _ui
    private var brutos: List<SubstituicaoHistoricoDto> = emptyList()

    init { carregar() }

    fun verTodos() {
        val recarrega = _ui.value.soEsteAparelho
        _ui.value = _ui.value.copy(soEsteAparelho = false, soEsteModelo = false)
        if (recarrega) carregar() else aplicar()
    }

    /** Filtro do Nexus (`dispositivo`): precisa recarregar. */
    fun alternarAparelho() {
        _ui.value = _ui.value.copy(soEsteAparelho = !_ui.value.soEsteAparelho)
        carregar()
    }

    /** Filtro local: não precisa ir no Nexus. */
    fun alternarModelo() {
        _ui.value = _ui.value.copy(soEsteModelo = !_ui.value.soEsteModelo)
        aplicar()
    }

    /** Filtro local por dia; nulo = todos. */
    fun escolherDia(dia: LocalDate?) {
        _ui.value = _ui.value.copy(dia = dia)
        aplicar()
    }

    fun carregar() {
        _ui.value = _ui.value.copy(carregando = true, erro = null)
        val dispositivo = if (_ui.value.soEsteAparelho) Graph.upload.dispositivoId else null
        viewModelScope.launch {
            // Dia e modelo são filtros locais: pede a página máxima do Nexus pra não "sumir" substituição antiga.
            runCatching { Graph.api.historicoEstoqueLoja(200, dispositivo) }
                .onSuccess { brutos = it.itens; aplicar() }
                .onFailure { _ui.value = _ui.value.copy(carregando = false, erro = it.mensagemCurta()) }
        }
    }

    private fun aplicar() {
        val u = _ui.value
        val porModelo = if (u.soEsteModelo) filtrarPorProduto(brutos, produtoId) else brutos
        _ui.value = u.copy(carregando = false, erro = null, itens = filtrarPorDia(porModelo, u.dia))
    }
}

// ---------------------------------------------------------------------------------------------
// Tela
// ---------------------------------------------------------------------------------------------

/**
 * Histórico das substituições de estoque da loja. [produtoId] vem do botão LOJA de um modelo:
 * a lista abre filtrada nele ("Só este modelo"), e os chips permitem ver tudo.
 */
@Composable
fun HistoricoEstoqueLojaScreen(voltar: () -> Unit, produtoId: String? = null) {
    val vm: HistoricoEstoqueLojaViewModel = viewModel(
        key = "historico-loja:${produtoId.orEmpty()}",
        factory = viewModelFactory { initializer { HistoricoEstoqueLojaViewModel(produtoId) } },
    )
    val ui by vm.ui.collectAsState()
    val temModelo = !produtoId.isNullOrBlank()
    var escolhendoDia by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Fundo)) {
        Cabecalho("Histórico", subtitulo = "Substituições de estoque da loja", aoVoltar = voltar, acao = {
            IconButton(onClick = vm::carregar, enabled = !ui.carregando) {
                Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = if (ui.carregando) TextoFraco else Texto)
            }
        })

        // Filtros: chips à esquerda (rolam se não couberem), dia fixo à direita.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipCategoria("Todos", ui.todos, vm::verTodos)
                ChipCategoria("Só este aparelho", ui.soEsteAparelho, vm::alternarAparelho)
                if (temModelo) ChipCategoria("Só este modelo", ui.soEsteModelo, vm::alternarModelo)
            }
            Spacer(Modifier.width(8.dp))
            ChipDia(rotuloDia(ui.dia)) { escolhendoDia = true }
        }
        // Recarga com lista na tela: só a barra fina avisa. Spinner cheio só na primeira carga.
        if (ui.carregando && ui.itens.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Texto, trackColor = Borda)
        } else {
            Spacer(Modifier.height(2.dp))
        }

        when {
            ui.carregando && ui.itens.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Texto) }
            ui.erro != null && ui.itens.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(ui.erro!!, color = Ambar, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                TextButton(onClick = vm::carregar) { Text("Tentar de novo", color = Texto) }
            }
            ui.itens.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(mensagemVazio(ui), color = TextoFraco, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ui.itens, key = { it.id }) { CartaoSubstituicao(it) }
                item(key = "resumo") { CartaoResumo(resumir(ui.itens), subtituloResumo(ui)) }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }

    if (escolhendoDia) {
        DialogoDia(ui.dia, aoEscolher = { vm.escolherDia(it); escolhendoDia = false }, aoFechar = { escolhendoDia = false })
    }
}

private fun mensagemVazio(ui: HistoricoUi): String {
    val sb = StringBuilder("Nenhuma substituição")
    if (ui.soEsteModelo) sb.append(" deste modelo")
    if (ui.soEsteAparelho) sb.append(" por este aparelho")
    when (val d = ui.dia) {
        null -> sb.append(" ainda")
        LocalDate.now() -> sb.append(" hoje")
        else -> sb.append(" em ").append(d.format(DateTimeFormatter.ofPattern("dd/MM")))
    }
    return sb.toString()
}

private fun subtituloResumo(ui: HistoricoUi): String {
    val partes = mutableListOf(rotuloDia(ui.dia))
    if (ui.soEsteModelo) partes += "este modelo"
    if (ui.soEsteAparelho) partes += "este aparelho"
    if (ui.todos) partes += "todas as peças"
    return partes.joinToString(" · ")
}

@Composable
private fun ChipDia(texto: String, onClick: () -> Unit) {
    val forma = RoundedCornerShape(18.dp)
    Row(
        Modifier.height(36.dp).clip(forma).background(Muted, forma).border(1.dp, Borda, forma).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.DateRange, contentDescription = "Escolher dia", tint = Texto, modifier = Modifier.size(16.dp))
        Text(texto, style = MaterialTheme.typography.labelLarge, color = Texto, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogoDia(atual: LocalDate?, aoEscolher: (LocalDate?) -> Unit, aoFechar: () -> Unit) {
    // O DatePicker trabalha em millis UTC do início do dia.
    val estado = rememberDatePickerState(initialSelectedDateMillis = (atual ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = aoFechar,
        confirmButton = {
            TextButton(onClick = {
                val ms = estado.selectedDateMillis
                aoEscolher(if (ms == null) null else Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
            }) { Text("OK", color = Texto) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { aoEscolher(null) }) { Text("Todos os dias", color = TextoFraco) }
                TextButton(onClick = aoFechar) { Text("Cancelar", color = TextoFraco) }
            }
        },
        colors = DatePickerDefaults.colors(containerColor = CorCartao),
    ) {
        DatePicker(state = estado, showModeToggle = false, colors = DatePickerDefaults.colors(containerColor = CorCartao))
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

private fun corTendencia(t: Tendencia): Color = when (t) { Tendencia.SOBE -> Verde; Tendencia.DESCE -> Ambar; Tendencia.IGUAL -> Texto }

@Composable
private fun CartaoSubstituicao(item: SubstituicaoHistoricoDto) {
    val (categoria, modelo) = parseNomeItag(item.produto.nome, null)
    val esteAparelho = item.dispositivo != null && item.dispositivo == Graph.upload.dispositivoId
    val quando = emMs(item.em)?.let { tempoRelativo(it) } ?: "—"
    val meta = listOfNotNull(quando, item.ator?.takeIf { it.isNotBlank() }, "este aparelho".takeIf { esteAparelho }).joinToString(" · ")

    Moldura {
        Row(verticalAlignment = Alignment.Top) {
            FotoProduto(item.produto.imagemUrl, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SeloCategoria(categoria)
                Text(modelo.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta, style = MaterialTheme.typography.bodySmall, color = TextoFraco, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Totais(item.totalDe, item.totalPara)
        }
        GradeAntesNovo(variantesVisiveis(item))
        LinhaTracejada()
        RodapeMono(listOf("lidas" to numero(item.lidas), "picking" to numero(item.picking), "margem" to "${item.margemPct}%"))
    }
}

@Composable
private fun CartaoResumo(r: ResumoHistorico, subtitulo: String) {
    Moldura(destaque = true) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SeloCategoria("Resumo do dia")
                val titulo = when {
                    r.produtos == 1 && r.substituicoes == 1 -> "1 PRODUTO SUBSTITUÍDO"
                    r.produtos == r.substituicoes -> "${r.produtos} PRODUTOS SUBSTITUÍDOS"
                    else -> "${r.produtos} PRODUTOS · ${r.substituicoes} SUBSTITUIÇÕES"
                }
                Text(titulo, style = MaterialTheme.typography.headlineSmall, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitulo, style = MaterialTheme.typography.bodySmall, color = TextoFraco, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Totais(r.totalDe, r.totalPara)
        }
        GradeAntesNovo(r.variantes)
        LinhaTracejada()
        RodapeMono(listOf("lidas" to numero(r.lidas), "picking" to numero(r.picking)))
    }
}

@Composable
private fun Moldura(destaque: Boolean = false, conteudo: @Composable () -> Unit) {
    val forma = RoundedCornerShape(RaioCartao)
    Column(
        Modifier.fillMaxWidth().background(CorCartao, forma)
            .border(if (destaque) 1.5.dp else 1.dp, if (destaque) Verde.copy(alpha = 0.6f) else Borda, forma)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { conteudo() }
}

/** Coluna da direita: total anterior riscado, total novo grande e o delta, todos alinhados à direita. */
@Composable
private fun Totais(de: Int, para: Int) {
    val cor = corTendencia(tendencia(de, para))
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(numero(de), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp, lineHeight = 20.sp), color = TextoFraco, textDecoration = TextDecoration.LineThrough)
            Text(" pçs", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = TextoFraco, textDecoration = TextDecoration.LineThrough)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(numero(para), style = MaterialTheme.typography.displaySmall, color = cor, maxLines = 1, softWrap = false)
            Text(" pçs", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp), color = cor, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(delta(de, para), style = MaterialTheme.typography.titleSmall, color = cor)
    }
}

private val LARGURA_ROTULO = 52.dp
private val LARGURA_TILE_ROLANDO = 60.dp
private const val MAX_TILES_SEM_ROLAR = 6

/** Duas linhas alinhadas por tamanho: ANTES (riscado) e NOVO (verde com delta). Mais de 6 tamanhos rolam juntas. */
@Composable
private fun GradeAntesNovo(variantes: List<VarianteHistoricoDto>) {
    if (variantes.isEmpty()) return
    val rola = variantes.size > MAX_TILES_SEM_ROLAR
    val rolagem = rememberScrollState()
    val tileMod = if (rola) Modifier.width(LARGURA_TILE_ROLANDO) else Modifier
    Column(
        (if (rola) Modifier.fillMaxWidth().horizontalScroll(rolagem) else Modifier.fillMaxWidth()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RotuloLinha("Antes")
            variantes.forEach { v -> TileAntes(v.tamanho ?: "?", v.de ?: 0, if (rola) tileMod else Modifier.weight(1f)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RotuloLinha("Novo")
            variantes.forEach { v -> TileNovo(v.de ?: 0, v.para ?: 0, if (rola) tileMod else Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun RotuloLinha(texto: String) {
    Text(texto.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextoFraco, maxLines = 1, modifier = Modifier.width(LARGURA_ROTULO))
}

@Composable
private fun TileAntes(tamanho: String, valor: Int, modifier: Modifier) {
    val forma = RoundedCornerShape(8.dp)
    Column(
        modifier.height(50.dp).background(Muted, forma).border(1.dp, Borda, forma).padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(tamanho, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp), color = TextoFraco, maxLines = 1)
        Text(numero(valor), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp, lineHeight = 20.sp), color = TextoFraco, textDecoration = TextDecoration.LineThrough, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun TileNovo(de: Int, para: Int, modifier: Modifier) {
    val forma = RoundedCornerShape(8.dp)
    val cor = corTendencia(tendencia(de, para))
    Column(
        modifier.height(58.dp).background(cor.copy(alpha = 0.08f), forma).border(1.dp, cor.copy(alpha = 0.55f), forma).padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(numero(para), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 26.sp), color = cor, maxLines = 1, softWrap = false)
        Text(delta(de, para), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp), color = cor, maxLines = 1)
    }
}

@Composable
private fun LinhaTracejada() {
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(Borda, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
    }
}

/** `lidas 1.886   picking 382   margem 5%` em mono, espalhado na largura. */
@Composable
private fun RodapeMono(pares: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        pares.forEach { (rotulo, valor) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$rotulo ", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextoFraco, maxLines = 1)
                Text(valor, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Texto, maxLines = 1)
            }
        }
    }
}
