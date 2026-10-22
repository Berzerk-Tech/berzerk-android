package br.com.berzerk.coletor.ui.telas

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.data.ModeloResumo
import br.com.berzerk.coletor.data.montarResumos
import br.com.berzerk.coletor.rfid.RfidEstado
import br.com.berzerk.coletor.rfid.RfidReader
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.Cartao
import br.com.berzerk.coletor.ui.comum.FotoProduto
import br.com.berzerk.coletor.ui.comum.Ponto
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Fundo
import br.com.berzerk.coletor.ui.theme.Muted
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Cartao as CorCartao
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minúsculas e sem acento, pra busca. */
internal fun normalizarBusca(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

/** `agora` / `há N min` / `há N h` / `dd/MM HH:mm` / `nunca`. */
internal fun tempoRelativo(ms: Long?, agora: Long = System.currentTimeMillis()): String {
    if (ms == null) return "nunca"
    val min = (agora - ms) / 60_000
    return when {
        min < 1 -> "agora"
        min < 60 -> "há $min min"
        min < 24 * 60 -> "há ${min / 60} h"
        else -> SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(ms))
    }
}

data class RevisaoUi(
    val carregando: Boolean = true,
    val resumos: List<ModeloResumo> = emptyList(),
    /** (categoria, nº de modelos), do maior pro menor. */
    val categorias: List<Pair<String, Int>> = emptyList(),
    /** `null` = Todas. */
    val categoriaSel: String? = null,
    val busca: String = "",
    val filtrados: List<ModeloResumo> = emptyList(),
)

private class IndiceBusca(val resumo: ModeloResumo, val texto: String)

@OptIn(FlowPreview::class)
class RevisaoViewModel : ViewModel() {
    private val _ui = MutableStateFlow(RevisaoUi())
    val ui: StateFlow<RevisaoUi> = _ui

    private val indice = MutableStateFlow<List<IndiceBusca>>(emptyList())
    private val buscaTexto = MutableStateFlow("")
    private val categoriaSel = MutableStateFlow<String?>(null)
    private var categoriaInicialAplicada = false

    init {
        viewModelScope.launch {
            Graph.itag.status.map { listOf(it.concluidoEm, it.modelos, it.indexando, it.imagensEm) }.distinctUntilChanged().collect { carregar() }
        }
        viewModelScope.launch {
            combine(indice, buscaTexto.debounce(250), categoriaSel) { idx, busca, cat -> Triple(idx, busca, cat) }
                .map { (idx, busca, cat) -> filtrar(idx, busca, cat) }
                .flowOn(Dispatchers.Default)
                .collect { filtrados -> _ui.update { it.copy(filtrados = filtrados) } }
        }
    }

    private fun filtrar(idx: List<IndiceBusca>, busca: String, cat: String?): List<ModeloResumo> {
        val termo = normalizarBusca(busca)
        val daCategoria = if (cat == null) idx else idx.filter { it.resumo.categoria.equals(cat, ignoreCase = true) }
        if (termo.isEmpty()) return daCategoria.map { it.resumo }
        val achados = daCategoria.filter { it.texto.contains(termo) }
        // Categoria selecionada sem resultado mas com busca: procura em todas (como o app antigo).
        if (achados.isEmpty() && cat != null) return idx.filter { it.texto.contains(termo) }.map { it.resumo }
        return achados.map { it.resumo }
    }

    private suspend fun carregar() {
        val linhas = Graph.db.epcs().listarModelos()
        val eans = linhas.map { it.ean13 }.filter { it.isNotEmpty() }.distinct()
        val imagens = if (eans.isEmpty()) emptyMap() else Graph.itag.imagemPor(eans)
        val (resumos, categorias, idx) = withContext(Dispatchers.Default) {
            val r = montarResumos(linhas, imagens)
            val c = r.groupingBy { it.categoria.lowercase() }.eachCount()
            val nomes = r.associate { it.categoria.lowercase() to it.categoria }
            val cats = c.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { nomes.getValue(it.key) to it.value }
            val i = r.map { m -> IndiceBusca(m, normalizarBusca((listOf(m.modelo, m.categoria) + m.eans).joinToString(" "))) }
            Triple(r, cats, i)
        }
        if (!categoriaInicialAplicada && resumos.isNotEmpty()) {
            categoriaInicialAplicada = true
            categoriaSel.value = categorias.firstOrNull { it.first.equals("Oversized", ignoreCase = true) }?.first
        }
        // Categoria escolhida que sumiu depois de um sync volta pra "Todas".
        if (categoriaSel.value != null && categorias.none { it.first.equals(categoriaSel.value, ignoreCase = true) }) categoriaSel.value = null
        _ui.update { it.copy(carregando = false, resumos = resumos, categorias = categorias, categoriaSel = categoriaSel.value) }
        indice.value = idx
    }

    fun setBusca(texto: String) {
        buscaTexto.value = texto
        _ui.update { it.copy(busca = texto) }
    }

    fun escolherCategoria(cat: String?) {
        categoriaSel.value = cat
        _ui.update { it.copy(categoriaSel = cat) }
    }
}

@Composable
fun RevisaoScreen(voltar: () -> Unit, irConfig: () -> Unit, irLeitura: () -> Unit, abrirModelo: (String) -> Unit) {
    val vm: RevisaoViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val sync by Graph.itag.status.collectAsState()
    val rfid by RfidReader.estado.collectAsState()
    val ocupado = sync.rodando || sync.indexando
    val fmt = NumberFormat.getIntegerInstance(Locale("pt", "BR"))

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Revisão RFID", rotulo = null, aoVoltar = voltar, acao = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).background(Muted).border(1.dp, Borda, RoundedCornerShape(8.dp))
                        .clickable(enabled = !ocupado) { Graph.itag.iniciar() }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (ocupado) "Sync…" else "Sync", style = MaterialTheme.typography.labelLarge, color = if (ocupado) TextoFraco else Texto) }
                IconButton(onClick = irConfig) { Icon(Icons.Default.Settings, contentDescription = "Configurações", tint = Texto) }
            }
        })

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            val (corRfid, textoRfid) = when (val e = rfid) {
                RfidEstado.Pronto, RfidEstado.Lendo -> Verde to "Leitor pronto"
                RfidEstado.Iniciando -> Ambar to "Iniciando o leitor…"
                RfidEstado.Desligado -> TextoFraco to "Leitor desligado"
                is RfidEstado.Erro -> Vermelho to e.motivo
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ponto(corRfid)
                Spacer(Modifier.width(6.dp))
                Text(
                    textoRfid + "  ·  base offline: " + if (sync.totalLocal == 0L) "não baixada" else "${fmt.format(sync.totalLocal)} etiquetas",
                    style = MaterialTheme.typography.bodySmall, color = TextoFraco, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            val modelosTxt = if (sync.indexando) "indexando modelos…" else "${fmt.format(sync.modelos)} modelos"
            Text("$modelosTxt · sync ${tempoRelativo(sync.concluidoEm)}", style = MaterialTheme.typography.bodySmall, color = TextoFraco)
            if (sync.erro != null) Text(sync.erro!!, style = MaterialTheme.typography.bodySmall, color = Vermelho)
        }

        OutlinedTextField(
            value = ui.busca,
            onValueChange = vm::setBusca,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            placeholder = { Text("Buscar produto, SKU ou EAN…", color = TextoFraco) },
            singleLine = true,
            trailingIcon = {
                if (ui.busca.isNotEmpty()) IconButton(onClick = { vm.setBusca("") }) { Icon(Icons.Default.Clear, contentDescription = "Limpar", tint = TextoFraco) }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CorCartao, unfocusedContainerColor = CorCartao,
                focusedBorderColor = Texto, unfocusedBorderColor = Borda,
                focusedTextColor = Texto, unfocusedTextColor = Texto, cursorColor = Texto,
            ),
        )

        LazyRow(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ChipCategoria("Todas (${ui.resumos.size})", ui.categoriaSel == null) { vm.escolherCategoria(null) } }
            items(ui.categorias, key = { it.first }) { (nome, qtd) ->
                ChipCategoria("$nome ($qtd)", ui.categoriaSel.equals(nome, ignoreCase = true)) { vm.escolherCategoria(nome) }
            }
        }

        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(44.dp).clip(RoundedCornerShape(6.dp))
                .background(Muted).border(1.dp, Borda, RoundedCornerShape(6.dp)).clickable(onClick = irLeitura),
            contentAlignment = Alignment.Center,
        ) { Text("Ler tudo (sem filtro de modelo)", style = MaterialTheme.typography.titleSmall, color = Texto) }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.filtrados.isEmpty() && !ui.carregando) {
                item {
                    when {
                        sync.totalLocal == 0L && !sync.rodando -> Cartao {
                            Column {
                                Text("A base de etiquetas ainda não foi baixada.", color = Ambar)
                                TextButton(onClick = irConfig) { Text("Baixar base", color = Texto) }
                            }
                        }
                        sync.indexando -> Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Texto, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Indexando modelos…", color = TextoFraco)
                        }
                        ui.busca.isNotBlank() -> Text("Nenhum modelo com \"${ui.busca}\".", color = TextoFraco, modifier = Modifier.padding(top = 24.dp))
                        else -> Text("Nenhum modelo nesta categoria.", color = TextoFraco, modifier = Modifier.padding(top = 24.dp))
                    }
                }
            }
            items(ui.filtrados, key = { it.chave }) { m -> CartaoModelo(m) { abrirModelo(m.chave) } }
        }
    }
}

@Composable
private fun ChipCategoria(texto: String, selecionado: Boolean, onClick: () -> Unit) {
    val forma = RoundedCornerShape(18.dp)
    Box(
        Modifier.height(36.dp).clip(forma)
            .background(if (selecionado) Texto else Muted, forma)
            .let { if (selecionado) it else it.border(1.dp, Borda, forma) }
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(texto, style = MaterialTheme.typography.labelLarge, color = if (selecionado) Fundo else Texto, maxLines = 1) }
}

@Composable
internal fun SeloCategoria(categoria: String) {
    Box(Modifier.background(Muted, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Text(categoria.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextoFraco, maxLines = 1)
    }
}

@Composable
private fun CartaoModelo(m: ModeloResumo, onClick: () -> Unit) {
    val forma = RoundedCornerShape(br.com.berzerk.coletor.ui.theme.RaioCartao)
    Row(
        Modifier.fillMaxWidth().clip(forma).background(CorCartao, forma).border(1.dp, Borda, forma).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FotoProduto(m.imagemUrl, 64.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeloCategoria(m.categoria)
                Spacer(Modifier.width(8.dp))
                Text(m.modelo, style = MaterialTheme.typography.headlineSmall, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Text(
                (listOf(m.categoria) + m.prefixos).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = TextoFraco, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                m.tamanhos.forEach { Text(it, style = MaterialTheme.typography.labelLarge, color = Texto) }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("›", style = MaterialTheme.typography.titleLarge, color = TextoFraco)
    }
}
