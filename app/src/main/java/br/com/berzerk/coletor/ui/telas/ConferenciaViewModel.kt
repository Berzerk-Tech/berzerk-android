package br.com.berzerk.coletor.ui.telas

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.berzerk.coletor.api.ApiException
import br.com.berzerk.coletor.api.ConferenciaLoteItemDto
import br.com.berzerk.coletor.api.ConfirmarBody
import br.com.berzerk.coletor.api.DivergenciaErro
import br.com.berzerk.coletor.api.JustificativaDto
import br.com.berzerk.coletor.api.LiberacaoDto
import br.com.berzerk.coletor.api.SupervisorDto
import br.com.berzerk.coletor.api.mensagemCurta
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.data.classificarParaLote
import br.com.berzerk.coletor.data.parseNomeItag
import br.com.berzerk.coletor.data.tamanhoDaEtiqueta
import br.com.berzerk.coletor.ui.comum.LinhaConsole
import br.com.berzerk.coletor.ui.comum.TipoLeitura
import br.com.berzerk.coletor.rfid.RfidReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Pergunta {
    /** 422 `conferencia_diverge`: mostrar a diferença e pedir pra aceitar. */
    data class Divergencia(val erro: DivergenciaErro) : Pergunta
    /** 422 `conferencia_exige_justificativa`: causa + motivo. */
    data class Justificativa(val erro: DivergenciaErro) : Pergunta
    /** 422 `conferencia_exige_pin`: supervisor + PIN + motivo (além da justificativa). */
    data class Pin(val erro: DivergenciaErro, val supervisores: List<SupervisorDto>) : Pergunta
}

data class ConferenciaUi(
    val sessaoId: String? = null,
    val lote: ConferenciaLoteItemDto? = null,
    val carregando: Boolean = true,
    val erro: String? = null,
    val lidosAqui: Int = 0,
    val pendentesEnvio: Int = 0,
    val enviando: Boolean = false,
    val ultimoEnvio: String? = null,
    val confirmando: Boolean = false,
    val pergunta: Pergunta? = null,
    val concluido: Boolean = false,
    val mensagem: String? = null,
    // Vista LOCAL (só console e contadores deste aparelho; a grade vem do Nexus).
    val doLote: Int = 0,
    val outros: Int = 0,
    val semCadastro: Int = 0,
    val unicos: Int = 0,
    val leituras: Int = 0,
    val porTamanhoLocal: Map<String, Int> = emptyMap(),
    val console: List<LinhaConsole> = emptyList(),
)

/**
 * Fluxo da conferência de um lote (as 5 rotas de `revisao-rfid:conferir`):
 * `conferir` abre/acha a sessão do dia → o gatilho acumula EPCs → a cada 4 s o
 * acumulado vai pro `rfid/upload` (o Nexus deduplica) → poll de 2,5 s na
 * conferência → `confirmar`, tratando os 422 em cascata.
 */
class ConferenciaViewModel(private val loteId: String) : ViewModel() {
    private val _ui = MutableStateFlow(ConferenciaUi())
    val ui: StateFlow<ConferenciaUi> = _ui

    private val lidos = LinkedHashSet<String>()
    private var enviados = 0
    private var ultimoEnvioHash = 0
    private var justificativa: JustificativaDto? = null
    private var liberacao: LiberacaoDto? = null
    private var pollJob: Job? = null
    /** Sobe a cada `zerar()`: envio/classificação que começou antes descarta o resultado. */
    private var geracao = 0

    // Classificação local (igual à Revisão por modelo)
    private val classificados = HashSet<String>()
    private val porTamanhoLocal = HashMap<String, Int>()
    private val console = ArrayDeque<LinhaConsole>()
    private var doLote = 0
    private var outros = 0
    private var semCadastro = 0
    private var leituras = 0
    private var sujo = false

    init {
        viewModelScope.launch { abrir() }
        viewModelScope.launch { RfidReader.leituras.collect { leituras++; sujo = true; registrar(it.epc) } }
        viewModelScope.launch {
            while (isActive) {
                delay(400)
                if (sujo) { sujo = false; classificar() }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(4_000)
                enviarPendentes()
            }
        }
    }

    private suspend fun abrir() {
        runCatching { Graph.api.conferir(loteId) }
            .onSuccess { resp ->
                _ui.update { it.copy(sessaoId = resp.sessaoId, carregando = false, erro = null) }
                pollJob?.cancel()
                pollJob = viewModelScope.launch {
                    while (isActive) {
                        atualizar()
                        delay(2_500)
                    }
                }
            }
            .onFailure { e ->
                val msg = if (e is ApiException && e.codigo == "sem_coletor_ativo") "Nenhum coletor ativo cadastrado no Nexus (Revisão RFID → Coletores)." else e.mensagemCurta()
                _ui.update { it.copy(carregando = false, erro = msg) }
            }
    }

    private suspend fun atualizar() {
        val sessaoId = _ui.value.sessaoId ?: return
        runCatching { Graph.api.conferencia(sessaoId) }
            .onSuccess { resumo ->
                val lote = resumo.lotes.firstOrNull { it.loteId == loteId }
                _ui.update { it.copy(lote = lote ?: it.lote, erro = if (lote == null) "O lote saiu da sessão." else null) }
            }
            .onFailure { e ->
                Log.w("Conferencia", "poll: ${e.mensagemCurta()}", e)
                // Sem o lote a tela fica vazia e o Confirmar desabilitado: erro de poll (parse, rede) tem que aparecer.
                if (_ui.value.lote == null) _ui.update { it.copy(erro = "Não carregou a conferência: ${e.mensagemCurta()}") }
            }
    }

    private fun registrar(epc: String) {
        if (lidos.add(epc)) _ui.update { it.copy(lidosAqui = lidos.size, pendentesEnvio = lidos.size - enviados) }
    }

    private suspend fun classificar() {
        // Sem o lote (variantes/produto) ainda, toda leitura cairia em OUTRO pra sempre: espera o poll.
        val lote = _ui.value.lote
        if (lote == null) { sujo = true; return }
        val pendentes = lidos.filter { it !in classificados }
        if (pendentes.isNotEmpty()) {
            val variantes = lote.variantes
            val g = geracao
            val achados = Graph.itag.resolver(pendentes)
            if (g != geracao) return // zerou no meio: descarta
            pendentes.forEach { epc ->
                classificados.add(epc)
                val e = achados[epc]
                val tipo = classificarParaLote(e, variantes, lote.produto)
                val fim = epc.takeLast(8)
                if (tipo == TipoLeitura.SEM_CADASTRO || e == null) {
                    semCadastro++
                    console.addLast(LinhaConsole(fim, "", "", tipo))
                } else {
                    val (_, mod) = parseNomeItag(e.nome, e.tamanho)
                    val tam = tamanhoDaEtiqueta(e.nome, e.tamanho).ifEmpty { "?" }
                    if (tipo == TipoLeitura.DO_ALVO) {
                        doLote++
                        porTamanhoLocal[tam] = (porTamanhoLocal[tam] ?: 0) + 1
                    } else outros++
                    console.addLast(LinhaConsole(fim, tam, mod, tipo))
                }
                while (console.size > 200) console.removeFirst()
            }
        }
        publicar()
    }

    private fun publicar() {
        _ui.update {
            it.copy(
                doLote = doLote, outros = outros, semCadastro = semCadastro, unicos = lidos.size, leituras = leituras,
                porTamanhoLocal = HashMap(porTamanhoLocal), console = console.toList(),
            )
        }
    }

    /**
     * Limpa SÓ a vista local (lidos, classificação, console, contadores e controle de envio).
     * Antes de limpar, sobe o que ainda não foi enviado — Zerar nunca descarta leitura.
     * O que já subiu pro Nexus continua lá: a grade do Nexus NÃO zera.
     */
    fun zerar() {
        if (_ui.value.confirmando) return
        viewModelScope.launch {
            if (!enviarPendentes(forcar = true)) return@launch // erro já está na UI; não perde leitura
            geracao++
            lidos.clear(); classificados.clear(); porTamanhoLocal.clear(); console.clear()
            doLote = 0; outros = 0; semCadastro = 0; leituras = 0; sujo = false
            enviados = 0; ultimoEnvioHash = 0
            _ui.update { it.copy(lidosAqui = 0, pendentesEnvio = 0, ultimoEnvio = null) }
            publicar()
        }
    }

    fun enviarAgora() { viewModelScope.launch { enviarPendentes(forcar = true) } }

    /** `true` se não há nada a enviar ou o envio deu certo; `false` se falhou (o erro já está na UI). */
    private suspend fun enviarPendentes(forcar: Boolean = false): Boolean {
        // Envio periódico em andamento: espera acabar (confirmar não pode seguir sem as leituras)
        if (_ui.value.enviando) {
            if (!forcar) return true
            while (_ui.value.enviando) delay(100)
        }
        val snapshot = synchronized(lidos) { lidos.toList() }
        if (snapshot.isEmpty()) return true
        val hash = snapshot.hashCode()
        if (!forcar && hash == ultimoEnvioHash) return true
        _ui.update { it.copy(enviando = true) }
        val g = geracao
        try {
            val r = Graph.upload.enviar(snapshot)
            if (g != geracao) { // zerou enquanto subia: o snapshot já não existe aqui
                _ui.update { it.copy(enviando = false) }
                return true
            }
            ultimoEnvioHash = hash
            enviados = snapshot.size
            _ui.update { it.copy(enviando = false, pendentesEnvio = lidos.size - enviados, ultimoEnvio = if (r.duplicado == true) "sem novidade" else "${r.epcs} EPCs") }
            atualizar()
            return true
        } catch (e: ApiException) {
            val msg = if (e.codigo == "coletor_revogado") "Este aparelho foi revogado no Nexus (Revisão RFID → Coletores)." else "Envio falhou: ${e.mensagemCurta()}"
            _ui.update { it.copy(enviando = false, erro = msg) }
        } catch (e: Throwable) {
            _ui.update { it.copy(enviando = false, erro = "Envio falhou: ${e.mensagemCurta()}") }
        }
        return false
    }

    /**
     * Toda confirmação (primeira ou resposta à cascata de 422) passa por aqui: para a leitura, dá
     * 300 ms pra última leva de EPCs entrar e sobe o que falta. Sem isso a confirmação iria sem as
     * últimas leituras (422 `conferencia_sem_leitura` ou divergência falsa). `false` = não confirma.
     */
    private suspend fun pararEEnviar(): Boolean {
        _ui.update { it.copy(confirmando = true, erro = null) }
        RfidReader.parar()
        delay(300)
        if (enviarPendentes(forcar = true)) return true
        _ui.update { it.copy(confirmando = false) }
        return false
    }

    private fun confirmarCom(body: ConfirmarBody) {
        if (_ui.value.confirmando) return
        viewModelScope.launch {
            if (!pararEEnviar()) return@launch
            tentarConfirmar(body)
        }
    }

    fun confirmar() = confirmarCom(ConfirmarBody())

    private suspend fun tentarConfirmar(body: ConfirmarBody) {
        val sessaoId = _ui.value.sessaoId
        if (sessaoId == null) {
            _ui.update { it.copy(confirmando = false, erro = "Sessão de conferência não aberta.") }
            return
        }
        _ui.update { it.copy(confirmando = true, pergunta = null, erro = null) }
        try {
            val r = Graph.api.confirmar(sessaoId, loteId, body)
            pollJob?.cancel()
            _ui.update { it.copy(confirmando = false, lote = r.lote, concluido = true, mensagem = "Lote ${r.lote.codigo} conferido. Aguarda a autorização do recebimento no Nexus.") }
        } catch (e: ApiException) {
            val div = e.corpoComo<DivergenciaErro>()
            when (e.codigo) {
                "conferencia_diverge" -> _ui.update { it.copy(confirmando = false, pergunta = Pergunta.Divergencia(div ?: DivergenciaErro(e.codigo))) }
                "conferencia_exige_justificativa" -> _ui.update { it.copy(confirmando = false, pergunta = Pergunta.Justificativa(div ?: DivergenciaErro(e.codigo))) }
                "conferencia_exige_pin" -> {
                    val sup = runCatching { Graph.api.supervisores().supervisores.filter { it.temPin } }.getOrDefault(emptyList())
                    _ui.update { it.copy(confirmando = false, pergunta = Pergunta.Pin(div ?: DivergenciaErro(e.codigo), sup)) }
                }
                "conferencia_sem_leitura" -> _ui.update { it.copy(confirmando = false, erro = "Leia as peças do lote antes de confirmar.") }
                "lote_ja_confirmado" -> _ui.update { it.copy(confirmando = false, concluido = true, mensagem = "Este lote já foi confirmado.") }
                "pin_invalido", "pin_nao_definido", "nao_supervisor" -> _ui.update { it.copy(confirmando = false, erro = "PIN recusado (${e.codigo}).", pergunta = null) }
                "pin_bloqueado" -> _ui.update { it.copy(confirmando = false, erro = "PIN bloqueado por 15 min (5 erros).", pergunta = null) }
                else -> _ui.update { it.copy(confirmando = false, erro = e.mensagem ?: e.mensagemCurta()) }
            }
        } catch (e: Throwable) {
            _ui.update { it.copy(confirmando = false, erro = e.mensagemCurta()) }
        }
    }

    /** Operador viu a diferença e aceita: manda a vista exata, mais o que já respondeu antes. */
    fun aceitarDivergencia(erro: DivergenciaErro) {
        confirmarCom(ConfirmarBody(aceitarDivergencia = true, divergenciaVista = erro.diferencaPorTamanho, justificativa = justificativa, liberacao = liberacao))
    }

    fun justificar(erro: DivergenciaErro, causa: String, motivo: String) {
        justificativa = JustificativaDto(causa, motivo.trim())
        confirmarCom(ConfirmarBody(aceitarDivergencia = true, divergenciaVista = erro.diferencaPorTamanho, justificativa = justificativa, liberacao = liberacao))
    }

    fun liberar(erro: DivergenciaErro, supervisorId: String, pin: String, motivo: String, causa: String?, motivoJust: String?) {
        liberacao = LiberacaoDto(supervisorId, pin.trim(), motivo.trim())
        if (causa != null && !motivoJust.isNullOrBlank()) justificativa = JustificativaDto(causa, motivoJust.trim())
        confirmarCom(ConfirmarBody(aceitarDivergencia = true, divergenciaVista = erro.diferencaPorTamanho, justificativa = justificativa, liberacao = liberacao))
    }

    fun fecharPergunta() { _ui.update { it.copy(pergunta = null) } }
    fun limparErro() { _ui.update { it.copy(erro = null) } }

    override fun onCleared() {
        RfidReader.parar()
        super.onCleared()
    }
}
