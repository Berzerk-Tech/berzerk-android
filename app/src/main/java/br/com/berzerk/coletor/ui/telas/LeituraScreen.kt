package br.com.berzerk.coletor.ui.telas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.berzerk.coletor.api.mensagemCurta
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.data.EpcEntity
import br.com.berzerk.coletor.rfid.RfidEstado
import br.com.berzerk.coletor.rfid.RfidReader
import br.com.berzerk.coletor.ui.comum.BotaoGrande
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.Cartao
import br.com.berzerk.coletor.ui.comum.ordenarTamanhos
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Preto
import br.com.berzerk.coletor.ui.theme.Superficie
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho
import br.com.berzerk.coletor.upload.SemTokenException
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GrupoProduto(val nome: String, val ean13: String?, val imagemUrl: String?, val porTamanho: Map<String, Int>) {
    val total: Int get() = porTamanho.values.sum()
}

data class LeituraUi(
    val totalEpcs: Int = 0,
    val semCadastro: Int = 0,
    val grupos: List<GrupoProduto> = emptyList(),
    val enviando: Boolean = false,
    val ultimoEnvio: String? = null,
    val erro: String? = null,
    val semToken: Boolean = false,
    val baseVazia: Boolean = false,
)

/**
 * Leitura livre: acumula EPCs, resolve contra a base iTag local e agrupa por
 * produto × tamanho ao vivo. "Enviar" manda o acumulado pro Nexus
 * (`rfid/upload`), onde entra na sessão de contagem aberta na Revisão RFID.
 */
class LeituraViewModel : ViewModel() {
    private val _ui = MutableStateFlow(LeituraUi())
    val ui: StateFlow<LeituraUi> = _ui
    private val lidos = LinkedHashSet<String>()
    private val resolvidos = HashMap<String, EpcEntity?>()
    private val imagens = HashMap<String, String>()
    private var sujo = false

    init {
        viewModelScope.launch { RfidReader.leituras.collect { if (lidos.add(it.epc)) sujo = true } }
        viewModelScope.launch {
            _ui.update { it.copy(baseVazia = Graph.db.epcs().total() == 0L) }
            while (isActive) {
                delay(400)
                if (sujo) { sujo = false; recalcular() }
            }
        }
    }

    private suspend fun recalcular() {
        val pendentes = lidos.filter { it !in resolvidos }
        if (pendentes.isNotEmpty()) {
            val achados = Graph.itag.resolver(pendentes)
            pendentes.forEach { resolvidos[it] = achados[it] }
            val eans = achados.values.mapNotNull { it.ean13 }.filter { it !in imagens }.distinct()
            if (eans.isNotEmpty()) imagens.putAll(Graph.itag.imagemPor(eans))
        }
        val porNome = LinkedHashMap<String, MutableMap<String, Int>>()
        val eanPorNome = HashMap<String, String?>()
        var semCadastro = 0
        lidos.forEach { epc ->
            val e = resolvidos[epc]
            if (e == null || e.nome.isNullOrBlank()) { semCadastro++; return@forEach }
            val tam = (e.tamanho ?: "?").uppercase()
            val mapa = porNome.getOrPut(e.nome) { LinkedHashMap() }
            mapa[tam] = (mapa[tam] ?: 0) + 1
            if (eanPorNome[e.nome] == null) eanPorNome[e.nome] = e.ean13
        }
        val grupos = porNome.map { (nome, mapa) ->
            val ordenado = ordenarTamanhos(mapa.keys).associateWith { mapa[it] ?: 0 }
            GrupoProduto(nome, eanPorNome[nome], eanPorNome[nome]?.let { imagens[it] }, ordenado)
        }.sortedByDescending { it.total }
        _ui.update { it.copy(totalEpcs = lidos.size, semCadastro = semCadastro, grupos = grupos) }
    }

    fun limpar() {
        lidos.clear(); resolvidos.clear(); sujo = false
        _ui.update { LeituraUi(baseVazia = it.baseVazia) }
    }

    fun enviar() {
        val snapshot = lidos.toList()
        if (snapshot.isEmpty() || _ui.value.enviando) return
        viewModelScope.launch {
            _ui.update { it.copy(enviando = true, erro = null) }
            try {
                val r = Graph.upload.enviar(snapshot)
                _ui.update { it.copy(enviando = false, semToken = false, ultimoEnvio = if (r.duplicado == true) "já enviado" else "${r.epcs} EPCs enviados") }
            } catch (e: SemTokenException) {
                _ui.update { it.copy(enviando = false, semToken = true) }
            } catch (e: Throwable) {
                _ui.update { it.copy(enviando = false, erro = e.mensagemCurta()) }
            }
        }
    }

    override fun onCleared() { RfidReader.parar(); super.onCleared() }
}

@Composable
fun LeituraScreen(voltar: () -> Unit, irConfig: () -> Unit) {
    val vm: LeituraViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val rfid by RfidReader.estado.collectAsState()
    val lendo = rfid is RfidEstado.Lendo

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Leitura livre", "${ui.totalEpcs} etiquetas" + (if (ui.semCadastro > 0) " · ${ui.semCadastro} sem cadastro" else ""), aoVoltar = voltar, rotulo = "Berzerk Nexus", acao = {
            TextButton(onClick = vm::limpar) { Text("Limpar", color = TextoFraco) }
        })
        if (ui.baseVazia) Cartao(Modifier.padding(horizontal = 16.dp)) {
            Column {
                Text("A base de etiquetas ainda não foi baixada: as peças aparecem só como EPC.", color = Ambar)
                TextButton(onClick = irConfig) { Text("Baixar base", color = Texto) }
            }
        }
        if (ui.semToken) Cartao(Modifier.padding(16.dp, 8.dp)) {
            Column {
                Text("Token do coletor não configurado: não dá pra enviar.", color = Ambar)
                TextButton(onClick = irConfig) { Text("Configurar", color = Texto) }
            }
        }
        if (ui.erro != null) Text(ui.erro!!, color = Vermelho, modifier = Modifier.padding(16.dp, 4.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.grupos.isEmpty()) item { Text("Segure o gatilho e passe o leitor nas peças.", color = TextoFraco, modifier = Modifier.padding(top = 24.dp)) }
            items(ui.grupos, key = { it.nome }) { g ->
                Cartao {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (g.imagemUrl != null) {
                            AsyncImage(model = g.imagemUrl, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Preto))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(g.nome.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Texto)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                g.porTamanho.forEach { (t, n) ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(t, style = MaterialTheme.typography.labelSmall, color = TextoFraco)
                                        Text("$n", fontWeight = FontWeight.Bold, color = Texto)
                                    }
                                }
                            }
                        }
                        Text("${g.total}", style = MaterialTheme.typography.headlineMedium, color = Texto)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(Preto).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val estadoRfid = rfid
            BotaoGrande(
                texto = if (lendo) "■  Parar" else "▶  Ler (ou segure o gatilho)",
                onClick = { RfidReader.gatilho(!lendo) },
                cor = if (lendo) Vermelho else Texto, corTexto = if (lendo) Texto else Preto,
                habilitado = estadoRfid is RfidEstado.Pronto || estadoRfid is RfidEstado.Lendo,
            )
            if (estadoRfid is RfidEstado.Erro) Text(estadoRfid.motivo, color = Vermelho, style = MaterialTheme.typography.bodyMedium)
            BotaoGrande(
                texto = when { ui.enviando -> "Enviando…"; ui.ultimoEnvio != null -> "Enviar pro Nexus (${ui.ultimoEnvio})"; else -> "Enviar pro Nexus" },
                onClick = vm::enviar, cor = Verde, corTexto = Preto,
                habilitado = !ui.enviando && ui.totalEpcs > 0 && !lendo,
            )
        }
    }
}
