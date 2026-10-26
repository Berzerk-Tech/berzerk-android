package br.com.berzerk.coletor.ui.telas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import br.com.berzerk.coletor.ui.comum.RodapeLeitura
import br.com.berzerk.coletor.ui.comum.Rotulo
import br.com.berzerk.coletor.ui.comum.TileTamanho
import br.com.berzerk.coletor.ui.comum.TipoLeitura
import br.com.berzerk.coletor.ui.comum.tamanhosDaGrade
import br.com.berzerk.coletor.ui.theme.Fundo
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho
import br.com.berzerk.coletor.upload.EstadoEnvio
import br.com.berzerk.coletor.upload.ResultadoEnvio
import br.com.berzerk.coletor.upload.chaveEnvio
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job

/** O que está sendo lido. `origem` + `referencia` identificam as linhas em `leituras` (Room). */
sealed interface AlvoLeitura {
    val origem: String
    val referencia: String

    data class Inventario(val sessaoId: String, val nome: String) : AlvoLeitura {
        override val origem get() = Origem.INVENTARIO
        override val referencia get() = sessaoId
    }

    data class Modelo(val chave: String) : AlvoLeitura {
        override val origem get() = Origem.REVISAO
        override val referencia get() = chave
    }

    data object Livre : AlvoLeitura {
        override val origem get() = Origem.LIVRE
        override val referencia get() = ""
    }
}

data class LeituraColetorUi(
    val resumo: ModeloResumo? = null,
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

    private val _abertas = MutableStateFlow<List<RfidSessaoDto>?>(null)
    val abertas: StateFlow<List<RfidSessaoDto>?> = _abertas

    private val buffer = BufferLeituras()
    private val classificados = HashSet<String>()
    private val doModelo = HashSet<String>()
    private val modelosVistos = HashSet<String>()
    private val porTamanho = HashMap<String, Int>()
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
        viewModelScope.launch {
            upload.estados.collect { m -> _ui.update { it.copy(estadoEnvio = m[chaveEnvio(alvo.origem, alvo.referencia)] ?: EstadoEnvio.Ocioso) } }
        }
        if (alvo is AlvoLeitura.Modelo) viewModelScope.launch { carregarResumo(alvo.chave) }
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

    private suspend fun carregarResumo(chave: String) {
        val linhas = Graph.db.epcs().listarModelos().filter { chaveModelo(it.categoria, it.modelo) == chave }
        val eans = linhas.map { it.ean13 }.filter { it.isNotEmpty() }.distinct()
        val imagens = if (eans.isEmpty()) emptyMap() else Graph.itag.imagemPor(eans)
        val resumo = montarResumos(linhas, imagens).firstOrNull()
        _ui.update { it.copy(resumo = resumo) }
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
            return alvo !is AlvoLeitura.Modelo
        }
        val (cat, mod) = parseNomeItag(e.nome, e.tamanho)
        val tam = tamanhoDaEtiqueta(e.nome, e.tamanho).ifEmpty { "?" }
        if (alvo is AlvoLeitura.Modelo) {
            if (chaveModelo(cat, mod) == alvo.chave) {
                doModelo.add(epc)
                porTamanho[tam] = (porTamanho[tam] ?: 0) + 1
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
                porTamanho = HashMap(porTamanho), doModelo = doModelo.size, outros = outros, semCadastro = semCadastro,
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
                classificados.clear(); doModelo.clear(); modelosVistos.clear(); porTamanho.clear(); console.clear()
                outros = 0; semCadastro = 0
                publicar()
            }
        }
    }

    /** SALVAR: grava o que está no buffer e dispara a fila (com retry). [sairAoConcluir]: fecha a tela quando tudo subir. */
    fun salvar(sairAoConcluir: Boolean = false) {
        envioJob?.cancel()
        envioJob = viewModelScope.launch {
            processar()
            val r = upload.enviarComRetry(alvo.origem, alvo.referencia)
            if (sairAoConcluir && r is ResultadoEnvio.Ok) _sair.tryEmit(Unit)
        }
    }

    /** Sessões abertas pro seletor do topo (inventário). */
    fun carregarAbertas() {
        if (_abertas.value != null) return
        viewModelScope.launch {
            _abertas.value = runCatching { Graph.api.listarSessoes("contagem").sessoes.filter { it.aberta } }.getOrDefault(emptyList())
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
 * Tela de leitura única (inventário, revisão por modelo e leitura livre). [trocarSessao] só no inventário.
 * Sair com leitura não enviada pergunta: Salvar agora / Sair mesmo assim / Ficar.
 */
@Composable
fun LeituraColetorScreen(alvo: AlvoLeitura, voltar: () -> Unit, trocarSessao: ((RfidSessaoDto) -> Unit)? = null) {
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

    LaunchedEffect(vm) { vm.sair.collect { voltar() } }
    val tentarSair = { if (ui.aSalvar > 0) confirmaSaida = true else vm.sair(voltar) }
    BackHandler(onBack = tentarSair)

    Column(Modifier.fillMaxSize().background(Fundo)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 1. Topo: foto + modelo, ou nome do inventário
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (alvo) {
                    is AlvoLeitura.Modelo -> {
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
                    is AlvoLeitura.Inventario -> Column(Modifier.weight(1f).let { if (trocarSessao != null) it.clickable { vm.carregarAbertas(); seletor = true } else it }) {
                        Rotulo("Inventário")
                        Text(alvo.nome + if (trocarSessao != null) "  ▾" else "", style = MaterialTheme.typography.headlineMedium, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            val tamanhos = if (alvo is AlvoLeitura.Modelo) resumo?.tamanhos.orEmpty() + if ((ui.porTamanho["?"] ?: 0) > 0) listOf("?") else emptyList()
            else tamanhosDaGrade(ui.porTamanho.keys)
            tamanhos.chunked(3).forEach { linha ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    linha.forEach { t ->
                        val n = ui.porTamanho[t] ?: 0
                        val eans = resumo?.eanPorTamanho?.get(if (t == "?") "" else t).orEmpty()
                        TileTamanho(t, n, eans.firstOrNull().orEmpty(), Modifier.weight(1f), extra = if (eans.size > 1) "+${eans.size - 1}" else null)
                    }
                    repeat(3 - linha.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            // 3. Contadores (colunas iguais)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (alvo is AlvoLeitura.Modelo) {
                    Contador("Do modelo", ui.doModelo, Verde, Modifier.weight(1f))
                    Contador("Sem cadastro", ui.semCadastro, Vermelho, Modifier.weight(1f))
                    Contador("Outros", ui.outros, TextoFraco, Modifier.weight(1f))
                    Contador("Total lidas", ui.unicos, Texto, Modifier.weight(1f))
                } else {
                    Contador("Modelos", ui.modelos, Verde, Modifier.weight(1f))
                    Contador("Sem cadastro", ui.semCadastro, Vermelho, Modifier.weight(1f))
                    Contador("Total lidas", ui.unicos, Texto, Modifier.weight(1f))
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
            aoSalvar = { vm.salvar() },
            salvarHabilitado = ui.aSalvar > 0 && ui.estadoEnvio !is EstadoEnvio.Enviando,
            salvando = ui.estadoEnvio is EstadoEnvio.Enviando,
            indicador = { IndicadorEnvio(ui.lidas + ui.naoGravadas, ui.enviadas, ui.aSalvar, ui.estadoEnvio) },
        )
    }

    if (confirmaSaida) {
        AlertDialog(
            onDismissRequest = { confirmaSaida = false },
            title = { Text("Você tem ${ui.aSalvar} peças lidas e não salvas") },
            text = { Text("Salve agora pra mandar pro Nexus. Se sair sem salvar, elas ficam neste aparelho e o app tenta enviar de novo quando houver rede.") },
            confirmButton = { TextButton(onClick = { confirmaSaida = false; vm.salvar(sairAoConcluir = true) }) { Text("Salvar agora", color = Verde) } },
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
    if (seletor && trocarSessao != null) {
        val abertas by vm.abertas.collectAsState()
        AlertDialog(
            onDismissRequest = { seletor = false },
            title = { Text("Inventários em andamento") },
            text = {
                val lista = abertas
                Column {
                    when {
                        lista == null -> Text("Carregando…", color = TextoFraco)
                        lista.isEmpty() -> Text("Nenhum inventário aberto.", color = TextoFraco)
                        else -> lista.forEach { s ->
                            TextButton(onClick = { seletor = false; trocarSessao(s) }, modifier = Modifier.fillMaxWidth()) {
                                Text(s.nome, color = if (s.id == alvo.referencia) Verde else Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { seletor = false }) { Text("Fechar", color = Texto) } },
        )
    }
}
