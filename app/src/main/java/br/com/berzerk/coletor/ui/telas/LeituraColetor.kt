package br.com.berzerk.coletor.ui.telas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import br.com.berzerk.coletor.api.RfidSessaoDto
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.data.BufferLeituras
import br.com.berzerk.coletor.data.LeituraEntity
import br.com.berzerk.coletor.data.ModeloResumo
import br.com.berzerk.coletor.data.Origem
import br.com.berzerk.coletor.data.Pastas
import br.com.berzerk.coletor.data.ResultadoCriar
import br.com.berzerk.coletor.data.chaveModelo
import br.com.berzerk.coletor.data.montarResumos
import br.com.berzerk.coletor.data.parseNomeItag
import br.com.berzerk.coletor.data.tamanhoDaEtiqueta
import br.com.berzerk.coletor.rfid.RfidEstado
import br.com.berzerk.coletor.rfid.RfidReader
import br.com.berzerk.coletor.ui.comum.ConsoleLeituras
import br.com.berzerk.coletor.ui.comum.Contador
import br.com.berzerk.coletor.ui.comum.FotoProduto
import br.com.berzerk.coletor.ui.comum.IndicadorEnvio
import br.com.berzerk.coletor.ui.comum.LinhaConsole
import br.com.berzerk.coletor.ui.comum.NovaPastaDialog
import br.com.berzerk.coletor.ui.comum.PastasUi
import br.com.berzerk.coletor.ui.comum.RodapeLeitura
import br.com.berzerk.coletor.ui.comum.Rotulo
import br.com.berzerk.coletor.ui.comum.SeletorPasta
import br.com.berzerk.coletor.ui.comum.TileTamanho
import br.com.berzerk.coletor.ui.comum.TipoLeitura
import br.com.berzerk.coletor.ui.comum.tamanhosDaGrade
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Cartao as CorCartao
import br.com.berzerk.coletor.ui.theme.Fundo
import br.com.berzerk.coletor.ui.theme.Muted
import br.com.berzerk.coletor.ui.theme.RaioCartao
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho
import br.com.berzerk.coletor.upload.EstadoEnvio
import br.com.berzerk.coletor.upload.PastaAlvo
import br.com.berzerk.coletor.upload.ResultadoEnvio
import br.com.berzerk.coletor.upload.chaveEnvio
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** O que está sendo lido. `origem` + `referencia` identificam as linhas em `leituras` (Room). */
sealed interface AlvoLeitura {
    val origem: String
    val referencia: String

    data class Inventario(val sessaoId: String, val nome: String) : AlvoLeitura {
        override val origem get() = Origem.INVENTARIO
        override val referencia get() = sessaoId
    }

    /** Um ou mais modelos da Revisão. `chaves` sem repetição e ordenadas (a referência é estável). */
    data class Modelos(val chaves: List<String>) : AlvoLeitura {
        override val origem get() = Origem.REVISAO
        /** 1 modelo = a própria chave (compatível com linhas já gravadas); vários = hash estável do conjunto. */
        override val referencia get() = chaves.singleOrNull() ?: ("multi:" + sha1Hex(chaves.joinToString("\n")).take(16))

        companion object {
            fun de(chaves: Collection<String>) = Modelos(chaves.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted())
        }
    }

    data object Livre : AlvoLeitura {
        override val origem get() = Origem.LIVRE
        override val referencia get() = ""
    }
}

private fun sha1Hex(texto: String): String =
    MessageDigest.getInstance("SHA-1").digest(texto.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

data class LeituraColetorUi(
    val resumo: ModeloResumo? = null,
    /** Modelos do alvo, em ordem fixa (categoria, modelo): não reordena quando chega leitura. */
    val resumos: List<ModeloResumo> = emptyList(),
    /** Chave do modelo → etiquetas únicas dele. */
    val porModelo: Map<String, Int> = emptyMap(),
    /** Chave do modelo → tamanho → etiquetas únicas. */
    val porModeloTamanho: Map<String, Map<String, Int>> = emptyMap(),
    val porTamanho: Map<String, Int> = emptyMap(),
    val doModelo: Int = 0,
    val outros: Int = 0,
    val semCadastro: Int = 0,
    val modelos: Int = 0,
    /** Etiquetas únicas classificadas (o "Total lidas"). */
    val unicos: Int = 0,
    val leituras: Int = 0,
    val console: List<LinhaConsole> = emptyList(),
    /** Contagens vindas do Room: o que está gravado, o que já subiu. */
    val lidas: Int = 0,
    val enviadas: Int = 0,
    val estadoEnvio: EstadoEnvio = EstadoEnvio.Ocioso,
    val baseVazia: Boolean = false,
    /** Pendentes sem pasta escolhida (o SALVAR atribui). */
    val semPasta: Int = 0,
    /** Lidas que ainda não chegaram no Room: no buffer ou sendo gravadas. */
    val naoGravadas: Int = 0,
) {
    /** Gravadas e não enviadas (Room). */
    val pendentes: Int get() = (lidas - enviadas).coerceAtLeast(0)

    /** Tudo que ainda não subiu pro Nexus, inclusive o que nem foi pro Room: é o que "sair" pode perder. */
    val aSalvar: Int get() = pendentes + naoGravadas
}

/**
 * Leitura persistida. O leitor emite cada tag; o buffer junta ~250 ms e grava em lote no Room
 * (`INSERT OR IGNORE`, PK origem+referencia+epc); a UI mostra contagens do Room. Reabrir com a mesma
 * referência traz as leituras de volta. SALVAR dispara a fila de envio (`UploadService`).
 *
 * Inventário e leitura livre gravam TODO EPC lido (inclusive sem cadastro: o Nexus resolve de novo
 * pela iTag). Revisão por modelo grava só os "do modelo": o resto (outros/sem cadastro) é só
 * contador da sessão da tela e não é reaberto.
 */
class LeituraColetorViewModel(private val alvo: AlvoLeitura) : ViewModel() {
    private val dao = Graph.db.leituras()
    private val upload = Graph.upload

    private val _ui = MutableStateFlow(LeituraColetorUi())
    val ui: StateFlow<LeituraColetorUi> = _ui

    private val _sair = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sair: SharedFlow<Unit> = _sair

    private val _pastas = MutableStateFlow(PastasUi())
    val pastas: StateFlow<PastasUi> = _pastas

    /** A pasta fechou no meio do envio (nome): a tela avisa e reabre o seletor. */
    private val _pastaFechada = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pastaFechada: SharedFlow<String> = _pastaFechada

    /** Pasta da rota (alvo Inventário) vem pré-selecionada. */
    private val preId: String? = (alvo as? AlvoLeitura.Inventario)?.sessaoId

    private val buffer = BufferLeituras()
    private val classificados = HashSet<String>()
    private val doModelo = HashSet<String>()
    private val modelosVistos = HashSet<String>()
    private val porTamanho = HashMap<String, Int>()
    private val porModeloTamanho = HashMap<String, HashMap<String, Int>>()
    private val chavesAlvo: Set<String> = (alvo as? AlvoLeitura.Modelos)?.chaves.orEmpty().toSet()
    private val console = ArrayDeque<LinhaConsole>()
    private var outros = 0
    private var semCadastro = 0
    private var publicadoLeituras = -1
    private var emProcessamento = 0
    private val trava = Mutex()
    private var envioJob: Job? = null

    init {
        viewModelScope.launch {
            RfidReader.leituras.collect {
                if (it.epc in classificados) buffer.registrarRepetida() else buffer.registrar(it.epc)
                atualizarNaoGravadas()
            }
        }
        viewModelScope.launch { dao.contagem(alvo.origem, alvo.referencia).collect { c -> _ui.update { it.copy(lidas = c.lidas, enviadas = c.enviadas) } } }
        viewModelScope.launch { dao.contarSemPasta(alvo.origem, alvo.referencia).collect { n -> _ui.update { it.copy(semPasta = n) } } }
        viewModelScope.launch {
            upload.estados.collect { m -> _ui.update { it.copy(estadoEnvio = m[chaveEnvio(alvo.origem, alvo.referencia)] ?: EstadoEnvio.Ocioso) } }
        }
        if (alvo is AlvoLeitura.Modelos) viewModelScope.launch { carregarResumos(alvo.chaves) }
        viewModelScope.launch {
            _ui.update { it.copy(baseVazia = Graph.db.epcs().total() == 0L) }
            // Revisão e "Ler tudo" não acumulam: o que já subiu sai ao abrir (os pendentes ficam).
            if (alvo !is AlvoLeitura.Inventario) dao.apagarEnviadas(alvo.origem, alvo.referencia)
            restaurar()
            while (isActive) {
                delay(250)
                processar()
            }
        }
    }

    private suspend fun carregarResumos(chaves: List<String>) {
        val alvos = chaves.toSet()
        val linhas = Graph.db.epcs().listarModelos().filter { chaveModelo(it.categoria, it.modelo) in alvos }
        val eans = linhas.map { it.ean13 }.filter { it.isNotEmpty() }.distinct()
        val imagens = if (eans.isEmpty()) emptyMap() else Graph.itag.imagemPor(eans)
        val resumos = montarResumos(linhas, imagens)
            .sortedWith(compareBy<ModeloResumo, String>(String.CASE_INSENSITIVE_ORDER) { it.categoria }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.modelo })
        _ui.update { it.copy(resumos = resumos, resumo = resumos.firstOrNull()) }
    }

    /** Leituras que já estavam no Room (a tela foi fechada e reaberta): classifica de novo, sem console. */
    private suspend fun restaurar() = trava.withLock {
        val existentes = dao.epcs(alvo.origem, alvo.referencia)
        existentes.chunked(2_000).forEach { parte ->
            val achados = Graph.itag.resolver(parte)
            parte.forEach { epc -> classificar(epc, achados[epc], restaurando = true) }
        }
        buffer.somarLeituras(existentes.size)
        publicar()
    }

    /** Classifica um EPC novo. Devolve `true` se ele deve ser gravado no Room. */
    private fun classificar(epc: String, e: br.com.berzerk.coletor.data.EpcEntity?, restaurando: Boolean): Boolean {
        classificados.add(epc)
        val fim = epc.takeLast(8)
        val agora = System.currentTimeMillis()
        if (e == null || e.nome.isNullOrBlank()) {
            semCadastro++
            if (!restaurando) adicionarConsole(LinhaConsole(fim, "", "", TipoLeitura.SEM_CADASTRO, agora))
            return alvo !is AlvoLeitura.Modelos
        }
        val (cat, mod) = parseNomeItag(e.nome, e.tamanho)
        val tam = tamanhoDaEtiqueta(e.nome, e.tamanho).ifEmpty { "?" }
        if (alvo is AlvoLeitura.Modelos) {
            val chave = chaveModelo(cat, mod)
            if (chave in chavesAlvo) {
                doModelo.add(epc)
                porTamanho[tam] = (porTamanho[tam] ?: 0) + 1
                val doM = porModeloTamanho.getOrPut(chave) { HashMap() }
                doM[tam] = (doM[tam] ?: 0) + 1
                if (!restaurando) adicionarConsole(LinhaConsole(fim, tam, mod, TipoLeitura.DO_ALVO, agora))
                return true
            }
            outros++
            if (!restaurando) adicionarConsole(LinhaConsole(fim, tam, mod, TipoLeitura.OUTRO, agora))
            return false
        }
        porTamanho[tam] = (porTamanho[tam] ?: 0) + 1
        modelosVistos.add(chaveModelo(cat, mod))
        if (!restaurando) adicionarConsole(LinhaConsole(fim, tam, mod, TipoLeitura.DO_ALVO, agora))
        return true
    }

    private fun adicionarConsole(l: LinhaConsole) {
        console.addLast(l)
        while (console.size > LINHAS_CONSOLE) console.removeFirst()
    }

    private fun atualizarNaoGravadas() { _ui.update { it.copy(naoGravadas = buffer.tamanho + emProcessamento) } }

    /** Esvazia o buffer: classifica, grava em lote no Room e publica. */
    private suspend fun processar() = trava.withLock {
        gravarLote(buffer.drenar())
        if (buffer.leituras != publicadoLeituras) publicar()
    }

    /**
     * Classifica e grava [brutos] (já tirados do buffer). Depois de tirar do buffer nada cancela:
     * EPC lido nunca é descartado. Chamar com a [trava].
     */
    private suspend fun gravarLote(brutos: List<String>) {
        val novos = brutos.filter { it !in classificados }
        if (novos.isEmpty()) { atualizarNaoGravadas(); return }
        emProcessamento = novos.size
        atualizarNaoGravadas()
        withContext(NonCancellable) {
            try {
                val achados = Graph.itag.resolver(novos)
                val gravar = ArrayList<String>(novos.size)
                novos.forEach { epc -> if (classificar(epc, achados[epc], restaurando = false)) gravar.add(epc) }
                if (gravar.isNotEmpty()) {
                    val agora = System.currentTimeMillis()
                    dao.inserir(gravar.map { LeituraEntity(it, agora, alvo.origem, alvo.referencia) })
                }
            } finally {
                emProcessamento = 0
            }
            publicar()
            atualizarNaoGravadas()
        }
    }

    private fun publicar() {
        publicadoLeituras = buffer.leituras
        _ui.update {
            it.copy(
                porTamanho = HashMap(porTamanho), porModeloTamanho = porModeloTamanho.mapValues { (_, v) -> HashMap(v) },
                porModelo = porModeloTamanho.mapValues { (_, v) -> v.values.sum() }, doModelo = doModelo.size, outros = outros, semCadastro = semCadastro,
                modelos = modelosVistos.size, unicos = classificados.size, leituras = buffer.leituras, console = console.toList(),
            )
        }
    }

    /** Apaga só as linhas desta (origem, referência) e zera a vista. */
    fun zerar() {
        viewModelScope.launch {
            envioJob?.cancel()
            trava.withLock {
                buffer.zerar()
                dao.apagar(alvo.origem, alvo.referencia)
                classificados.clear(); doModelo.clear(); modelosVistos.clear(); porTamanho.clear(); porModeloTamanho.clear(); console.clear()
                outros = 0; semCadastro = 0
                publicar()
            }
        }
    }

    /** Abre o seletor: lista as pastas abertas (rede) e, sem rede, as do cache. Sem nenhuma, erro e nada é enviado. */
    fun carregarPastas() {
        _pastas.update { it.copy(carregando = true, erro = null) }
        viewModelScope.launch {
            runCatching { Graph.api.listarSessoes("contagem").sessoes }
                .onSuccess { todas ->
                    Pastas.guardarCache(todas)
                    publicarPastas(todas, doCache = false)
                }
                .onFailure {
                    val cache = Pastas.doCache()
                    if (cache.isEmpty()) _pastas.update { u -> u.copy(carregando = false, lista = emptyList(), erro = Pastas.MENSAGEM_SEM_REDE) }
                    else publicarPastas(cache, doCache = true)
                }
        }
    }

    private fun publicarPastas(todas: List<RfidSessaoDto>, doCache: Boolean) {
        val lista = Pastas.paraSalvar(todas, preId)
        _pastas.update { it.copy(lista = lista, carregando = false, erro = null, doCache = doCache, escolhida = Pastas.escolhaInicial(lista, preId, it.escolhida)) }
    }

    fun escolherPasta(id: String) { _pastas.update { it.copy(escolhida = id) } }

    fun limparErroCriar() { _pastas.update { it.copy(erroCriar = null) } }

    /** "Nova pasta…": abre no Nexus, entra na lista e já fica escolhida. [aoCriada] fecha o diálogo. */
    fun criarPasta(nome: String, aoCriada: () -> Unit) {
        if (_pastas.value.criando) return
        _pastas.update { it.copy(criando = true, erroCriar = null) }
        viewModelScope.launch {
            when (val r = Pastas.criar(nome)) {
                is ResultadoCriar.Criada -> {
                    val lista = Pastas.paraSalvar(_pastas.value.lista + r.pasta, preId)
                    Pastas.guardarCache(lista)
                    _pastas.update { it.copy(criando = false, lista = lista, escolhida = r.pasta.id, erro = null) }
                    aoCriada()
                }
                is ResultadoCriar.JaHaUma -> {
                    // Nexus velho: a que já existe é a pasta; seleciona-a (relistando se não está na lista).
                    val existente = r.sessaoId
                    val conhecida = existente != null && _pastas.value.lista.any { it.id == existente }
                    if (existente != null && !conhecida) {
                        runCatching { Graph.api.listarSessoes("contagem").sessoes }.onSuccess { publicarPastas(it, doCache = false) }
                    }
                    _pastas.update { it.copy(criando = false, erroCriar = Pastas.MENSAGEM_UMA_POR_VEZ, escolhida = existente?.takeIf { id -> it.lista.any { p -> p.id == id } } ?: it.escolhida) }
                }
                else -> _pastas.update { it.copy(criando = false, erroCriar = Pastas.mensagem(r)) }
            }
        }
    }

    /** SALVAR em [pasta]: grava o que está no buffer e dispara a fila (com retry). [sairAoConcluir]: fecha a tela quando tudo subir. */
    fun salvar(pasta: RfidSessaoDto, sairAoConcluir: Boolean = false) {
        // Envio vivo (inclusive em retry de rede): não cancela nem reatribui, senão o mesmo EPC iria pra duas pastas.
        if (envioJob?.isActive == true) return
        envioJob = viewModelScope.launch {
            processar()
            var r = upload.enviarComRetry(alvo.origem, alvo.referencia, PastaAlvo(pasta.id, pasta.nome))
            // Lido durante o envio fica sem pasta: antes de sair, manda pra mesma pasta.
            while (sairAoConcluir && r is ResultadoEnvio.Ok) {
                processar()
                if (dao.contarSemPastaAgora(alvo.origem, alvo.referencia) == 0) break
                r = upload.enviarComRetry(alvo.origem, alvo.referencia, PastaAlvo(pasta.id, pasta.nome))
            }
            if (sairAoConcluir && r is ResultadoEnvio.Ok) _sair.tryEmit(Unit)
            if (r is ResultadoEnvio.PastaFechada) {
                _pastas.update { u -> u.copy(lista = u.lista.filter { it.id != pasta.id }, escolhida = null) }
                _pastaFechada.tryEmit(pasta.nome)
            }
        }
    }

    /**
     * Saída da tela: para o leitor, espera a cauda de tags chegar, grava tudo que estava no buffer
     * (sem cancelamento) e só então chama [aoTerminar].
     */
    fun sair(aoTerminar: () -> Unit) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                RfidReader.parar()
                delay(150)
                trava.withLock { gravarLote(buffer.drenar()) }
            }
            aoTerminar()
        }
    }

    override fun onCleared() {
        // Caminho de segurança (saída por outro meio): o buffer é tirado AGORA, antes de parar o leitor, e
        // gravado num job do Graph porque o viewModelScope já está cancelado.
        val resto = buffer.drenar()
        RfidReader.parar()
        if (resto.isNotEmpty()) Graph.scope.launch { trava.withLock { gravarLote(resto) } }
        super.onCleared()
    }

    companion object {
        const val LINHAS_CONSOLE = 50
    }
}

/**
 * Tela de leitura única (inventário, revisão por modelo e leitura livre). SALVAR abre "Salvar em…" (a pasta
 * é escolhida ali; a da rota, no inventário, vem pré-selecionada). Sair com leitura não enviada pergunta:
 * Salvar agora / Sair mesmo assim / Ficar.
 */
@Composable
fun LeituraColetorScreen(alvo: AlvoLeitura, voltar: () -> Unit) {
    val vm: LeituraColetorViewModel = viewModel(
        key = "leitura:${alvo.origem}:${alvo.referencia}",
        factory = viewModelFactory { initializer { LeituraColetorViewModel(alvo) } },
    )
    val ui by vm.ui.collectAsState()
    val rfid by RfidReader.estado.collectAsState()
    val lendo = rfid is RfidEstado.Lendo
    val resumo = ui.resumo
    var confirmaSaida by remember { mutableStateOf(false) }
    var confirmaZerar by remember { mutableStateOf(false) }
    var seletor by remember { mutableStateOf(false) }
    var novaPasta by remember { mutableStateOf(false) }
    var sairDepoisDeSalvar by remember { mutableStateOf(false) }
    var pastaFechada by remember { mutableStateOf<String?>(null) }
    val pastas by vm.pastas.collectAsState()
    val envioVivo = ui.estadoEnvio is EstadoEnvio.Enviando || ui.estadoEnvio is EstadoEnvio.SemRede
    val abrirSeletor = { vm.carregarPastas(); seletor = true }

    LaunchedEffect(vm) { vm.sair.collect { voltar() } }
    LaunchedEffect(vm) { vm.pastaFechada.collect { pastaFechada = it } }
    val tentarSair = { if (ui.aSalvar > 0) confirmaSaida = true else vm.sair(voltar) }
    BackHandler(onBack = tentarSair)

    Column(Modifier.fillMaxSize().background(Fundo)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 1. Topo: foto + modelo, ou nome do inventário
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (alvo) {
                    is AlvoLeitura.Modelos -> if (alvo.chaves.size > 1) {
                        Column(Modifier.weight(1f)) {
                            Rotulo("Revisão RFID")
                            Text("${if (ui.resumos.isNotEmpty()) ui.resumos.size else alvo.chaves.size} modelos", style = MaterialTheme.typography.headlineMedium, color = Texto)
                            val cats = ui.resumos.map { it.categoria }.distinct()
                            if (cats.isNotEmpty()) Text(cats.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = TextoFraco, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        FotoProduto(resumo?.imagemUrl, 56.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (resumo != null) { SeloCategoria(resumo.categoria); Spacer(Modifier.width(8.dp)) }
                                Text(resumo?.modelo ?: "…", style = MaterialTheme.typography.headlineSmall, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (resumo != null) Text(resumo.prefixos.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = TextoFraco, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    is AlvoLeitura.Inventario -> Column(Modifier.weight(1f)) {
                        Rotulo("Pasta sugerida")
                        Text(alvo.nome, style = MaterialTheme.typography.headlineMedium, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    AlvoLeitura.Livre -> Column(Modifier.weight(1f)) {
                        Rotulo("Berzerk Nexus")
                        Text("LER TUDO", style = MaterialTheme.typography.headlineMedium, color = Texto)
                    }
                }
                IconButton(onClick = tentarSair, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextoFraco)
                }
            }
            if (ui.baseVazia) Text("A base de etiquetas ainda não foi baixada: as peças aparecem só como sem cadastro (Configurações → Baixar base).", color = TextoFraco, style = MaterialTheme.typography.bodySmall)

            // 2. Grade por tamanho: tiles do mesmo tamanho, 3 colunas (linhas manuais, sem scroll aninhado)
            val varios = alvo is AlvoLeitura.Modelos && alvo.chaves.size > 1
            if (!varios) {
                val tamanhos = if (alvo is AlvoLeitura.Modelos) resumo?.tamanhos.orEmpty() + if ((ui.porTamanho["?"] ?: 0) > 0) listOf("?") else emptyList()
                else tamanhosDaGrade(ui.porTamanho.keys)
                GradeTamanhos(tamanhos, ui.porTamanho, resumo?.eanPorTamanho.orEmpty())
            }

            // 3. Contadores (colunas iguais)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (alvo is AlvoLeitura.Modelos) {
                    Contador(if (varios) "Dos modelos" else "Do modelo", ui.doModelo, Verde, Modifier.weight(1f))
                    Contador("Sem cadastro", ui.semCadastro, Vermelho, Modifier.weight(1f))
                    Contador("Outros", ui.outros, TextoFraco, Modifier.weight(1f))
                    Contador("Total lidas", ui.unicos, Texto, Modifier.weight(1f))
                } else {
                    Contador("Modelos", ui.modelos, Verde, Modifier.weight(1f))
                    Contador("Sem cadastro", ui.semCadastro, Vermelho, Modifier.weight(1f))
                    Contador("Total lidas", ui.unicos, Texto, Modifier.weight(1f))
                }
            }

            // 3b. Uma seção por modelo (vários modelos)
            if (varios) {
                ui.resumos.forEach { r ->
                    val contagens = ui.porModeloTamanho[r.chave].orEmpty()
                    val total = ui.porModelo[r.chave] ?: 0
                    val forma = RoundedCornerShape(RaioCartao)
                    Column(
                        Modifier.fillMaxWidth().background(CorCartao, forma).border(1.dp, Borda, forma).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FotoProduto(r.imagemUrl, 56.dp)
                                SeloCategoria(r.categoria)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(r.modelo, style = MaterialTheme.typography.titleMedium, color = Texto, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            BadgeTotal(total)
                        }
                        GradeTamanhos(r.tamanhos + if ((contagens["?"] ?: 0) > 0) listOf("?") else emptyList(), contagens, r.eanPorTamanho)
                    }
                }
            }

            // 4. Console
            ConsoleLeituras(ui.console, ui.unicos, ui.leituras, destacarNovas = true)
            val estadoRfid = rfid
            if (estadoRfid is RfidEstado.Erro) Text(estadoRfid.motivo, color = Vermelho, style = MaterialTheme.typography.bodyMedium)
        }

        // 5. Rodapé fixo: indicador de envio + [Zerar] [LER/PARAR] [SALVAR]
        RodapeLeitura(
            lendo = lendo,
            habilitado = rfid is RfidEstado.Pronto || rfid is RfidEstado.Lendo,
            aoZerar = { if (ui.aSalvar > 0) confirmaZerar = true else vm.zerar() },
            aoLer = { RfidReader.gatilho(!lendo) },
            aoSalvar = { sairDepoisDeSalvar = false; abrirSeletor() },
            salvarHabilitado = ui.aSalvar > 0 && !envioVivo,
            salvando = ui.estadoEnvio is EstadoEnvio.Enviando,
            indicador = { IndicadorEnvio(ui.lidas + ui.naoGravadas, ui.enviadas, ui.aSalvar, ui.estadoEnvio, ui.semPasta) },
        )
    }

    if (confirmaSaida) {
        AlertDialog(
            onDismissRequest = { confirmaSaida = false },
            title = { Text("Você tem ${ui.aSalvar} peças lidas e não salvas") },
            text = { Text("Salve agora pra mandar pro Nexus. Se sair sem salvar, elas ficam neste aparelho até você voltar aqui e salvar numa pasta.") },
            confirmButton = { TextButton(enabled = !envioVivo, onClick = { confirmaSaida = false; sairDepoisDeSalvar = true; abrirSeletor() }) { Text("Salvar agora", color = Verde) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmaSaida = false }) { Text("Ficar", color = Texto) }
                    TextButton(onClick = { confirmaSaida = false; vm.sair(voltar) }) { Text("Sair mesmo assim", color = Vermelho) }
                }
            },
        )
    }
    if (confirmaZerar) {
        AlertDialog(
            onDismissRequest = { confirmaZerar = false },
            title = { Text("Zerar a leitura?") },
            text = { Text("${ui.aSalvar} peças lidas ainda não foram enviadas e serão apagadas deste aparelho.") },
            confirmButton = { TextButton(onClick = { confirmaZerar = false; vm.zerar() }) { Text("Zerar", color = Vermelho) } },
            dismissButton = { TextButton(onClick = { confirmaZerar = false }) { Text("Cancelar", color = Texto) } },
        )
    }
    if (seletor) {
        SeletorPasta(
            ui = pastas,
            aoEscolher = vm::escolherPasta,
            aoNovaPasta = { vm.limparErroCriar(); novaPasta = true },
            aoRecarregar = vm::carregarPastas,
            aoSalvar = {
                pastas.lista.firstOrNull { it.id == pastas.escolhida }?.let { p ->
                    seletor = false
                    vm.salvar(p, sairAoConcluir = sairDepoisDeSalvar)
                }
            },
            aoFechar = { seletor = false },
        )
    }
    if (novaPasta) {
        NovaPastaDialog(
            criando = pastas.criando, erro = pastas.erroCriar,
            aoCriar = { nome -> vm.criarPasta(nome) { novaPasta = false } },
            aoCancelar = { novaPasta = false },
        )
    }
    pastaFechada?.let { nome ->
        AlertDialog(
            onDismissRequest = { pastaFechada = null },
            title = { Text("Pasta fechada") },
            text = { Text("A pasta $nome foi fechada no Nexus. Escolha outra. As leituras continuam neste aparelho.") },
            confirmButton = { TextButton(onClick = { pastaFechada = null; abrirSeletor() }) { Text("Escolher outra", color = Verde) } },
        )
    }
}

/** Total do modelo no card (vários modelos): caixa clara quando já leu algo. */
@Composable
private fun BadgeTotal(total: Int) {
    val forma = RoundedCornerShape(10.dp)
    val temLeitura = total > 0
    Column(
        Modifier.background(if (temLeitura) Texto else Muted, forma).border(1.dp, Borda, forma).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TOTAL", style = MaterialTheme.typography.labelSmall, color = if (temLeitura) Fundo else TextoFraco)
        Text("$total", style = MaterialTheme.typography.headlineMedium, color = if (temLeitura) Fundo else Texto, maxLines = 1, softWrap = false)
    }
}

/**
 * Grade de tiles por tamanho, 5 colunas (a régua P/M/G/GG/XG cabe numa linha; com mais tamanhos quebra
 * pra baixo). Linhas manuais, sem scroll aninhado.
 */
@Composable
private fun GradeTamanhos(tamanhos: List<String>, contagens: Map<String, Int>, eanPorTamanho: Map<String, List<String>>, colunas: Int = 5) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tamanhos.chunked(colunas).forEach { linha ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                linha.forEach { t ->
                    val n = contagens[t] ?: 0
                    val eans = eanPorTamanho[if (t == "?") "" else t].orEmpty()
                    TileTamanho(t, n, eans.firstOrNull().orEmpty(), Modifier.weight(1f), extra = if (eans.size > 1) "+${eans.size - 1}" else null, compacto = true)
                }
                repeat(colunas - linha.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
