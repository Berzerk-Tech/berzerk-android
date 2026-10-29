package br.com.berzerk.coletor.ui.telas

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.berzerk.coletor.api.InventarioProdutoDto
import br.com.berzerk.coletor.api.InventarioSessaoDto
import br.com.berzerk.coletor.api.JsonCfg
import br.com.berzerk.coletor.api.RfidSessaoDto
import br.com.berzerk.coletor.api.mensagemCurta
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.data.InventarioCacheEntity
import br.com.berzerk.coletor.data.Origem
import br.com.berzerk.coletor.data.Pastas
import br.com.berzerk.coletor.data.ResultadoCriar
import br.com.berzerk.coletor.ui.comum.BotaoGrande
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.Cartao
import br.com.berzerk.coletor.ui.comum.ChipCategoria
import br.com.berzerk.coletor.ui.comum.FotoProduto
import br.com.berzerk.coletor.ui.comum.NovaPastaDialog
import br.com.berzerk.coletor.ui.comum.Rotulo
import br.com.berzerk.coletor.ui.comum.formatarDataHora
import br.com.berzerk.coletor.ui.comum.formatarInteiro
import br.com.berzerk.coletor.ui.comum.ordenarTamanhos
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Muted
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho
import br.com.berzerk.coletor.upload.EstadoEnvio
import br.com.berzerk.coletor.upload.chaveEnvio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/** Sessão tocada na lista, em memória: o cabeçalho da tela da sessão aparece na hora. */
object PreviaSessoes {
    private val mapa = HashMap<String, RfidSessaoDto>()
    fun guardar(s: RfidSessaoDto) { mapa[s.id] = s }
    fun pegar(id: String): RfidSessaoDto? = mapa[id]
}

/** `abertaEm desc`: a API não garante ordem. */
fun ordenarSessoes(sessoes: List<RfidSessaoDto>): List<RfidSessaoDto> = sessoes.sortedByDescending { it.abertaEm }

// ---------------------------------------------------------------- lista

data class InventarioListaUi(
    val sessoes: List<RfidSessaoDto> = emptyList(),
    val carregando: Boolean = true,
    val erro: String? = null,
    val criando: Boolean = false,
    val erroCriar: String? = null,
)

class InventarioListaViewModel : ViewModel() {
    private val _ui = MutableStateFlow(InventarioListaUi())
    val ui: StateFlow<InventarioListaUi> = _ui

    init { carregar() }

    fun carregar() {
        viewModelScope.launch {
            _ui.update { it.copy(carregando = true, erro = null) }
            runCatching { Graph.api.listarSessoes("contagem").sessoes }
                .onSuccess { lista ->
                    Pastas.guardarCache(lista)
                    val ordenada = ordenarSessoes(lista)
                    _ui.update { it.copy(sessoes = ordenada, carregando = false) }
                }
                .onFailure { e -> _ui.update { it.copy(carregando = false, erro = e.mensagemCurta()) } }
        }
    }

    /** Abre a pasta (sessão de contagem) no Nexus. */
    fun criar(nome: String, aoCriada: (RfidSessaoDto) -> Unit) {
        if (_ui.value.criando) return
        viewModelScope.launch {
            _ui.update { it.copy(criando = true, erroCriar = null) }
            when (val r = Pastas.criar(nome)) {
                is ResultadoCriar.Criada -> {
                    _ui.update { it.copy(criando = false) }
                    aoCriada(r.pasta)
                }
                else -> _ui.update { it.copy(criando = false, erroCriar = Pastas.mensagem(r)) }
            }
        }
    }

    fun limparErroCriar() { _ui.update { it.copy(erroCriar = null) } }
}

@Composable
fun InventarioListaScreen(voltar: () -> Unit, abrir: (RfidSessaoDto) -> Unit) {
    val vm: InventarioListaViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    var novo by remember { mutableStateOf(false) }
    val semPasta by remember { Graph.db.leituras().contarSemPastaTotal() }.collectAsState(0)

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Pastas", "A leitura entra na pasta que você escolhe no Salvar", aoVoltar = voltar, rotulo = "Berzerk Nexus")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (semPasta > 0) item { Text("$semPasta leituras sem pasta: abra uma pasta, toque em LER e depois em SALVAR pra escolher.", color = Ambar, style = MaterialTheme.typography.bodyMedium) }
            if (ui.erro != null) item { Text(ui.erro!!, color = Vermelho) }
            if (ui.carregando && ui.sessoes.isEmpty()) item { Text("Carregando…", color = TextoFraco) }
            val abertas = ui.sessoes.filter { it.aberta }
            val fechadas = ui.sessoes.filter { !it.aberta }
            if (abertas.isNotEmpty()) {
                item { Rotulo("Abertas") }
                items(abertas, key = { it.id }) { CartaoSessao(it) { abrir(it) } }
            }
            if (fechadas.isNotEmpty()) {
                item { Spacer(Modifier.height(6.dp)); Rotulo("Fechadas") }
                items(fechadas, key = { it.id }) { CartaoSessao(it) { abrir(it) } }
            }
            if (!ui.carregando && ui.erro == null && ui.sessoes.isEmpty()) item { Text("Nenhuma pasta ainda.", color = TextoFraco) }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
            BotaoGrande("Nova pasta", onClick = { vm.limparErroCriar(); novo = true }, cor = Verde, corTexto = br.com.berzerk.coletor.ui.theme.Fundo)
        }
    }

    if (novo) {
        NovaPastaDialog(
            criando = ui.criando, erro = ui.erroCriar,
            aoCriar = { nome -> vm.criar(nome) { s -> novo = false; abrir(s) } },
            aoCancelar = { novo = false },
        )
    }
}

@Composable
private fun CartaoSessao(s: RfidSessaoDto, onClick: () -> Unit) {
    Cartao(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.nome, style = MaterialTheme.typography.headlineSmall, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Criado em ${formatarDataHora(s.abertaEm)}", style = MaterialTheme.typography.bodySmall, color = TextoFraco)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(formatarInteiro(s.pecas), style = MaterialTheme.typography.headlineMedium, color = if (s.aberta) Verde else Texto)
                Text("PEÇAS", style = MaterialTheme.typography.labelSmall, color = TextoFraco)
            }
        }
    }
}

// ---------------------------------------------------------------- sessão

data class InventarioSessaoUi(
    val sessao: RfidSessaoDto? = null,
    val inventario: InventarioSessaoDto? = null,
    val carregando: Boolean = true,
    val erro: String? = null,
    /** Leituras DESTE aparelho pra esta pasta (Room): o que já leu e o que ainda não subiu. */
    val lidasAqui: Int = 0,
    val pendentesAqui: Int = 0,
)

class InventarioSessaoViewModel(private val sessaoId: String) : ViewModel() {
    private val dao = Graph.db.leituras()
    private val _ui = MutableStateFlow(InventarioSessaoUi(sessao = PreviaSessoes.pegar(sessaoId)))
    val ui: StateFlow<InventarioSessaoUi> = _ui

    init {
        viewModelScope.launch {
            val cache = dao.cacheInventario(sessaoId)
            val inv = cache?.let { runCatching { JsonCfg.json.decodeFromString<InventarioSessaoDto>(it.json) }.getOrNull() }
            val sess = cache?.sessaoJson?.let { runCatching { JsonCfg.json.decodeFromString<RfidSessaoDto>(it) }.getOrNull() }
            _ui.update { it.copy(inventario = inv, sessao = it.sessao ?: sess, carregando = inv == null) }
            // Só busca na abertura se não há cache: depois é pull-to-refresh ou envio bem-sucedido.
            if (inv == null) recarregar()
        }
        viewModelScope.launch {
            Graph.upload.concluidos.collect { if (it.pastaId == sessaoId) recarregar() }
        }
        viewModelScope.launch {
            dao.contagem(Origem.INVENTARIO, sessaoId).collect { c ->
                _ui.update { it.copy(lidasAqui = c.lidas, pendentesAqui = (c.lidas - c.enviadas).coerceAtLeast(0)) }
            }
        }
    }

    fun recarregar() {
        viewModelScope.launch {
            _ui.update { it.copy(carregando = true, erro = null) }
            // Cabeçalho (barato) primeiro; o cálculo do inventário pode levar 15–25 s.
            runCatching { Graph.api.listarSessoes("contagem").sessoes.firstOrNull { it.id == sessaoId } }.getOrNull()
                ?.let { s -> PreviaSessoes.guardar(s); _ui.update { it.copy(sessao = s) } }
            runCatching { Graph.api.inventarioSessao(sessaoId) }
                .onSuccess { inv ->
                    _ui.update { it.copy(inventario = inv, carregando = false) }
                    runCatching {
                        dao.gravarCacheInventario(
                            InventarioCacheEntity(
                                sessaoId, JsonCfg.json.encodeToString(inv),
                                _ui.value.sessao?.let { JsonCfg.json.encodeToString(it) }, System.currentTimeMillis(),
                            ),
                        )
                    }
                }
                .onFailure { e -> _ui.update { it.copy(carregando = false, erro = e.mensagemCurta()) } }
        }
    }
}

private const val SEM_CATEGORIA = "Sem categoria"

internal fun categoriaDe(p: InventarioProdutoDto): String = p.categoriaLoja?.nome?.takeIf { it.isNotBlank() } ?: SEM_CATEGORIA

/** Linha de baixo do card: `P 334 · M 299 · G 326` (só tamanho com peça contada, na régua). */
internal fun linhaDeTamanhos(p: InventarioProdutoDto): String {
    val porTam = p.tamanhos.filter { it.contado > 0 }.groupBy { it.tamanho?.takeIf { t -> t.isNotBlank() } ?: "?" }
    return ordenarTamanhos(porTam.keys).joinToString(" · ") { t -> "$t ${formatarInteiro(porTam.getValue(t).sumOf { it.contado })}" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventarioSessaoScreen(sessaoId: String, voltar: () -> Unit, ler: (RfidSessaoDto) -> Unit) {
    val vm: InventarioSessaoViewModel = viewModel(key = "inventario:$sessaoId", factory = viewModelFactory { initializer { InventarioSessaoViewModel(sessaoId) } })
    val ui by vm.ui.collectAsState()
    var busca by remember { mutableStateOf("") }
    var categoria by remember { mutableStateOf<String?>(null) }
    var expandidos by remember { mutableStateOf(setOf<String>()) }
    val sessao = ui.sessao
    val inv = ui.inventario
    val estadosEnvio by Graph.upload.estados.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Cabecalho(
            titulo = sessao?.nome ?: "Pasta",
            subtitulo = sessao?.let { "Criado em ${formatarDataHora(it.abertaEm)}" + if (!it.aberta) " · fechado" else "" },
            aoVoltar = voltar, rotulo = "Pastas",
        )
        PullToRefreshBox(isRefreshing = ui.carregando && inv != null, onRefresh = vm::recarregar, modifier = Modifier.weight(1f)) {
            val produtos = inv?.produtos.orEmpty()
            val porCategoria = produtos.groupBy(::categoriaDe).mapValues { (_, v) -> v.sumOf { it.contado } }
            val filtrados = produtos
                .filter { categoria == null || categoriaDe(it) == categoria }
                .filter { busca.isBlank() || listOfNotNull(it.nome, it.cor).joinToString(" ").contains(busca.trim(), ignoreCase = true) }
                .sortedByDescending { it.contado }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Column {
                        Rotulo("Peças em estoque")
                        Text(formatarInteiro(inv?.totais?.contado ?: sessao?.pecas ?: 0), style = MaterialTheme.typography.displayLarge, color = Verde)
                        if (inv != null) {
                            Text(
                                "${formatarInteiro(inv.totais.modelos)} modelos · etiquetas únicas" + if (inv.totais.semCadastro > 0) " · ${formatarInteiro(inv.totais.semCadastro)} sem cadastro" else "",
                                style = MaterialTheme.typography.bodyMedium, color = TextoFraco,
                            )
                        }
                    }
                }
                if (ui.erro != null) item { Text(ui.erro!!, color = Vermelho) }
                val envio = estadosEnvio[chaveEnvio(Origem.INVENTARIO, sessaoId)]
                if (envio is EstadoEnvio.Erro) item { Text(envio.motivo, color = Vermelho) }
                if (inv == null && ui.carregando) {
                    item { Text("Calculando o inventário… pode levar até 25 s.", color = TextoFraco, style = MaterialTheme.typography.bodySmall) }
                    items(3) { Box(Modifier.fillMaxWidth().height(84.dp).background(Muted, RoundedCornerShape(14.dp))) }
                }
                if (inv != null) {
                    item {
                        OutlinedTextField(
                            value = busca, onValueChange = { busca = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Buscar produto…", color = TextoFraco) },
                        )
                    }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChipCategoria("Todas (${formatarInteiro(produtos.sumOf { it.contado })})", categoria == null) { categoria = null }
                            porCategoria.entries.sortedByDescending { it.value }.forEach { (nome, total) ->
                                ChipCategoria("$nome (${formatarInteiro(total)})", categoria == nome) { categoria = if (categoria == nome) null else nome }
                            }
                        }
                    }
                    if (filtrados.isEmpty()) item { Text("Nenhum produto.", color = TextoFraco) }
                    items(filtrados, key = { it.chave }) { p ->
                        CartaoProdutoInventario(p, expandidos.contains(p.chave)) {
                            expandidos = if (expandidos.contains(p.chave)) expandidos - p.chave else expandidos + p.chave
                        }
                    }
                }
            }
        }
        // Rodapé: LER (pasta aberta) ou RETOMAR LEITURA (este aparelho tem leitura pendente, mesmo com a pasta fechada:
        // o SALVAR da leitura deixa escolher outra pasta).
        val pendentes = ui.pendentesAqui
        if (sessao != null && (sessao.aberta || pendentes > 0)) {
            Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pendentes > 0) {
                    val forma = RoundedCornerShape(br.com.berzerk.coletor.ui.theme.RaioCartao)
                    Row(
                        Modifier.fillMaxWidth().background(br.com.berzerk.coletor.ui.theme.Cartao, forma).border(1.dp, Ambar, forma).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${formatarInteiro(pendentes)} peças lidas neste aparelho ainda não salvas", style = MaterialTheme.typography.titleSmall, color = Ambar)
                            Text(
                                if (ui.lidasAqui > pendentes) "${formatarInteiro(ui.lidasAqui)} lidas aqui no total. Retome pra continuar de onde parou e salvar." else "Retome pra continuar de onde parou e salvar.",
                                style = MaterialTheme.typography.bodySmall, color = TextoFraco,
                            )
                        }
                    }
                }
                BotaoGrande(if (pendentes > 0) "RETOMAR LEITURA" else "LER", onClick = { ler(sessao) }, cor = Verde, corTexto = br.com.berzerk.coletor.ui.theme.Fundo)
            }
        }
    }
}

@Composable
private fun CartaoProdutoInventario(p: InventarioProdutoDto, expandido: Boolean, alternar: () -> Unit) {
    val forma = RoundedCornerShape(br.com.berzerk.coletor.ui.theme.RaioCartao)
    Column(
        Modifier.fillMaxWidth().background(br.com.berzerk.coletor.ui.theme.Cartao, forma).border(1.dp, Borda, forma).clickable(onClick = alternar).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FotoProduto(p.imagemUrl, 56.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(categoriaDe(p).uppercase(), style = MaterialTheme.typography.labelSmall, color = TextoFraco, maxLines = 1)
                Text(
                    listOfNotNull(p.nome, p.cor).joinToString(" · ").ifBlank { "Sem cadastro" },
                    style = MaterialTheme.typography.headlineSmall, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(linhaDeTamanhos(p), style = MaterialTheme.typography.bodySmall, color = TextoFraco, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(formatarInteiro(p.contado), style = MaterialTheme.typography.headlineMedium, color = Verde)
                Text("PEÇAS", style = MaterialTheme.typography.labelSmall, color = TextoFraco)
            }
        }
        if (expandido) {
            Spacer(Modifier.height(8.dp))
            LinhaDetalhe("Já saiu", p.jaSaiu)
            LinhaDetalhe("Reservado", p.reservado)
            LinhaDetalhe("Reservado (não liberado)", p.reservadoNaoLiberado)
            LinhaDetalhe("Disponível", p.disponivel)
            if (p.tamanhos.isNotEmpty()) Spacer(Modifier.height(6.dp))
            p.tamanhos.forEach { t ->
                Text(
                    "${t.tamanho?.takeIf { it.isNotBlank() } ?: "?"}: contado ${t.contado} · saiu ${t.jaSaiu} · reservado ${t.reservado} · disponível ${t.disponivel}",
                    style = MaterialTheme.typography.bodySmall, color = TextoFraco,
                )
            }
        }
    }
}

@Composable
private fun LinhaDetalhe(rotulo: String, valor: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(rotulo, style = MaterialTheme.typography.bodySmall, color = TextoFraco)
        Text(formatarInteiro(valor), style = MaterialTheme.typography.bodySmall, color = Texto)
    }
}
