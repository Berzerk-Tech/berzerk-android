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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.berzerk.coletor.api.ApiException
import br.com.berzerk.coletor.api.mensagemCurta
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.ui.comum.FotoProduto
import br.com.berzerk.coletor.data.ModeloResumo
import br.com.berzerk.coletor.data.chaveModelo
import br.com.berzerk.coletor.data.montarResumos
import br.com.berzerk.coletor.data.parseNomeItag
import br.com.berzerk.coletor.data.tamanhoDaEtiqueta
import br.com.berzerk.coletor.rfid.RfidEstado
import br.com.berzerk.coletor.rfid.RfidReader
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Cartao
import br.com.berzerk.coletor.ui.theme.Fundo
import br.com.berzerk.coletor.ui.theme.Muted
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class TipoLeitura { DO_MODELO, OUTRO_MODELO, SEM_CADASTRO }

data class LinhaConsole(val epc: String, val tamanho: String, val modelo: String, val tipo: TipoLeitura)

data class RevisaoModeloUi(
    val resumo: ModeloResumo? = null,
    val porTamanho: Map<String, Int> = emptyMap(),
    val doModelo: Int = 0,
    val outros: Int = 0,
    val semCadastro: Int = 0,
    val unicos: Int = 0,
    val leituras: Int = 0,
    val console: List<LinhaConsole> = emptyList(),
    val enviando: Boolean = false,
    val ultimoEnvio: String? = null,
    val erro: String? = null,
)

/**
 * Leitura de UM modelo: cada EPC lido é resolvido na base local e classificado como do modelo
 * (entra na grade por tamanho), de outro modelo ou sem cadastro. SALVAR manda só os do modelo
 * pro Nexus (`rfid/upload`); a decisão de negócio continua lá.
 */
class RevisaoModeloViewModel(private val chave: String) : ViewModel() {
    private val _ui = MutableStateFlow(RevisaoModeloUi())
    val ui: StateFlow<RevisaoModeloUi> = _ui

    private val lidos = LinkedHashSet<String>()
    private val doModelo = LinkedHashSet<String>()
    private val classificados = HashSet<String>()
    private val porTamanho = HashMap<String, Int>()
    private val console = ArrayDeque<LinhaConsole>()
    private var outros = 0
    private var semCadastro = 0
    private var leituras = 0
    private var sujo = false

    init {
        viewModelScope.launch { carregarResumo() }
        viewModelScope.launch {
            RfidReader.leituras.collect {
                leituras++
                lidos.add(it.epc)
                sujo = true
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(400)
                if (sujo) { sujo = false; classificar() }
            }
        }
    }

    private suspend fun carregarResumo() {
        val linhas = Graph.db.epcs().listarModelos().filter { chaveModelo(it.categoria, it.modelo) == chave }
        val eans = linhas.map { it.ean13 }.filter { it.isNotEmpty() }.distinct()
        val imagens = if (eans.isEmpty()) emptyMap() else Graph.itag.imagemPor(eans)
        val resumo = montarResumos(linhas, imagens).firstOrNull()
        _ui.update { it.copy(resumo = resumo) }
    }

    private suspend fun classificar() {
        val pendentes = lidos.filter { it !in classificados }
        if (pendentes.isNotEmpty()) {
            val achados = Graph.itag.resolver(pendentes)
            pendentes.forEach { epc ->
                classificados.add(epc)
                val e = achados[epc]
                val fim = epc.takeLast(8)
                if (e == null || e.nome.isNullOrBlank()) {
                    semCadastro++
                    console.addLast(LinhaConsole(fim, "", "", TipoLeitura.SEM_CADASTRO))
                } else {
                    val (cat, mod) = parseNomeItag(e.nome, e.tamanho)
                    val tam = tamanhoDaEtiqueta(e.nome, e.tamanho).ifEmpty { "?" }
                    if (chaveModelo(cat, mod) == chave) {
                        doModelo.add(epc)
                        porTamanho[tam] = (porTamanho[tam] ?: 0) + 1
                        console.addLast(LinhaConsole(fim, tam, mod, TipoLeitura.DO_MODELO))
                    } else {
                        outros++
                        console.addLast(LinhaConsole(fim, tam, mod, TipoLeitura.OUTRO_MODELO))
                    }
                }
                while (console.size > 200) console.removeFirst()
            }
        }
        publicar()
    }

    private fun publicar() {
        _ui.update {
            it.copy(
                porTamanho = HashMap(porTamanho), doModelo = doModelo.size, outros = outros, semCadastro = semCadastro,
                unicos = lidos.size, leituras = leituras, console = console.toList(),
            )
        }
    }

    fun zerar() {
        lidos.clear(); doModelo.clear(); classificados.clear(); porTamanho.clear(); console.clear()
        outros = 0; semCadastro = 0; leituras = 0; sujo = false
        _ui.update { it.copy(ultimoEnvio = null, erro = null) }
        publicar()
    }

    fun salvar() {
        val snapshot = doModelo.toList()
        if (snapshot.isEmpty() || _ui.value.enviando) return
        viewModelScope.launch {
            _ui.update { it.copy(enviando = true, erro = null) }
            try {
                val r = Graph.upload.enviar(snapshot)
                _ui.update { it.copy(enviando = false, ultimoEnvio = if (r.duplicado == true) "já enviado" else "${r.epcs} EPCs enviados") }
            } catch (e: ApiException) {
                _ui.update { it.copy(enviando = false, erro = if (e.codigo == "coletor_revogado") "Este aparelho foi revogado no Nexus." else e.mensagemCurta()) }
            } catch (e: Throwable) {
                _ui.update { it.copy(enviando = false, erro = e.mensagemCurta()) }
            }
        }
    }

    override fun onCleared() { RfidReader.parar(); super.onCleared() }
}

@Composable
fun RevisaoModeloScreen(chave: String, voltar: () -> Unit) {
    val vm: RevisaoModeloViewModel = viewModel(key = "revisao-modelo:$chave", factory = viewModelFactory { initializer { RevisaoModeloViewModel(chave) } })
    val ui by vm.ui.collectAsState()
    val rfid by RfidReader.estado.collectAsState()
    val lendo = rfid is RfidEstado.Lendo
    val resumo = ui.resumo

    Column(Modifier.fillMaxSize().background(Fundo)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 1. Topo
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FotoProduto(resumo?.imagemUrl, 56.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (resumo != null) { SeloCategoria(resumo.categoria); Spacer(Modifier.width(8.dp)) }
                        Text(resumo?.modelo ?: "…", style = MaterialTheme.typography.headlineSmall, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (resumo != null) Text(resumo.prefixos.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = TextoFraco, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = voltar, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextoFraco)
                }
            }

            // 2. Grade por tamanho (3 colunas, linhas manuais: sem scroll aninhado)
            val tamanhos = (resumo?.tamanhos.orEmpty()) + if ((ui.porTamanho["?"] ?: 0) > 0) listOf("?") else emptyList()
            tamanhos.chunked(3).forEach { linha ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    linha.forEach { t ->
                        val n = ui.porTamanho[t] ?: 0
                        val eans = resumo?.eanPorTamanho?.get(if (t == "?") "" else t).orEmpty()
                        TileTamanho(t, n, eans, Modifier.weight(1f))
                    }
                    repeat(3 - linha.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            // 3. Contadores
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Contador("Do modelo", ui.doModelo, Verde, Modifier.weight(1f))
                Contador("Sem cadastro", ui.semCadastro, Vermelho, Modifier.weight(1f))
                Contador("Outros", ui.outros, TextoFraco, Modifier.weight(1f))
                Contador("Total lidas", ui.unicos, Texto, Modifier.weight(1f))
            }

            // 4. Console
            Text("$ ${ui.unicos} etiquetas únicas · ${ui.leituras} leituras", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextoFraco)
            val formaConsole = RoundedCornerShape(8.dp)
            val lista = rememberLazyListState()
            LaunchedEffect(ui.console) { if (ui.console.isNotEmpty()) lista.scrollToItem(ui.console.lastIndex) }
            Box(Modifier.fillMaxWidth().height(140.dp).clip(formaConsole).background(Fundo).border(1.dp, Borda, formaConsole).padding(8.dp)) {
                if (ui.console.isEmpty()) {
                    Text("Segure o gatilho ou toque em LER.", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextoFraco)
                } else {
                    LazyColumn(state = lista) {
                        itemsIndexed(ui.console) { _, l ->
                            val (cor, texto) = when (l.tipo) {
                                TipoLeitura.DO_MODELO -> Verde to "▸ …${l.epc}  ${l.tamanho}  ${l.modelo}"
                                TipoLeitura.OUTRO_MODELO -> TextoFraco to "▸ …${l.epc}  ${l.tamanho}  ${l.modelo}"
                                TipoLeitura.SEM_CADASTRO -> Vermelho to "▸ …${l.epc}  sem cadastro"
                            }
                            Text(texto, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = cor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // 5. Salvar
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val (msg, corMsg) = when {
                    ui.erro != null -> ui.erro!! to Vermelho
                    ui.ultimoEnvio != null -> ui.ultimoEnvio!! to TextoFraco
                    else -> "" to TextoFraco
                }
                Text(msg, style = MaterialTheme.typography.bodySmall, color = corMsg, modifier = Modifier.weight(1f).padding(end = 8.dp))
                val podeSalvar = ui.doModelo > 0 && !lendo && !ui.enviando
                Row(
                    Modifier.height(44.dp).clip(RoundedCornerShape(6.dp)).background(Muted).border(1.dp, Borda, RoundedCornerShape(6.dp))
                        .clickable(enabled = podeSalvar, onClick = vm::salvar).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val cor = if (podeSalvar || ui.enviando) Texto else TextoFraco
                    Icon(Icons.Default.Save, contentDescription = null, tint = cor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (ui.enviando) "Salvando…" else "SALVAR", style = MaterialTheme.typography.titleSmall, color = cor)
                }
            }
            val estadoRfid = rfid
            if (estadoRfid is RfidEstado.Erro) Text(estadoRfid.motivo, color = Vermelho, style = MaterialTheme.typography.bodyMedium)
        }

        // 6. Rodapé fixo
        Row(Modifier.fillMaxWidth().background(Fundo).navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(6.dp)).background(Muted).clickable(onClick = vm::zerar),
                contentAlignment = Alignment.Center,
            ) { Text("Zerar", style = MaterialTheme.typography.titleSmall, color = Texto) }
            val habilitado = rfid is RfidEstado.Pronto || rfid is RfidEstado.Lendo
            Box(
                Modifier.weight(2.2f).height(56.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (!habilitado) Muted else if (lendo) Vermelho else Verde)
                    .clickable(enabled = habilitado) { RfidReader.gatilho(!lendo) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (lendo) "■  PARAR" else "▶  LER", style = MaterialTheme.typography.titleLarge,
                    color = if (!habilitado) TextoFraco else if (lendo) Texto else Fundo,
                )
            }
        }
    }
}

@Composable
private fun TileTamanho(tamanho: String, qtd: Int, eans: List<String>, modifier: Modifier) {
    val forma = RoundedCornerShape(10.dp)
    Column(
        modifier.height(112.dp).background(Muted, forma).border(1.dp, Borda, forma).padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(tamanho, style = MaterialTheme.typography.labelLarge, color = TextoFraco)
        Text("$qtd", style = MaterialTheme.typography.displaySmall, color = if (qtd > 0) Verde else Texto)
        if (eans.isNotEmpty()) {
            Text(
                eans.first() + if (eans.size > 1) " +${eans.size - 1}" else "",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, letterSpacing = 0.sp), color = TextoFraco, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Contador(rotulo: String, valor: Int, cor: Color, modifier: Modifier) {
    val forma = RoundedCornerShape(10.dp)
    Column(modifier.background(Cartao, forma).border(1.dp, Borda, forma).padding(10.dp)) {
        Text(rotulo.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp), color = TextoFraco, maxLines = 2, minLines = 2)
        Text("$valor", style = MaterialTheme.typography.headlineSmall, color = cor)
    }
}
