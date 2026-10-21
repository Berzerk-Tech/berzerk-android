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

    init {
        viewModelScope.launch { abrir() }
        viewModelScope.launch { RfidReader.leituras.collect { registrar(it.epc) } }
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
            .onFailure { e -> Log.w("Conferencia", "poll: ${e.mensagemCurta()}") }
    }

    private fun registrar(epc: String) {
        if (lidos.add(epc)) _ui.update { it.copy(lidosAqui = lidos.size, pendentesEnvio = lidos.size - enviados) }
    }

    fun enviarAgora() { viewModelScope.launch { enviarPendentes(forcar = true) } }

    private suspend fun enviarPendentes(forcar: Boolean = false) {
        if (_ui.value.enviando) return
        val snapshot = synchronized(lidos) { lidos.toList() }
        if (snapshot.isEmpty()) return
        val hash = snapshot.hashCode()
        if (!forcar && hash == ultimoEnvioHash) return
        _ui.update { it.copy(enviando = true) }
        try {
            val r = Graph.upload.enviar(snapshot)
            ultimoEnvioHash = hash
            enviados = snapshot.size
            _ui.update { it.copy(enviando = false, pendentesEnvio = lidos.size - enviados, ultimoEnvio = if (r.duplicado == true) "sem novidade" else "${r.epcs} EPCs") }
            atualizar()
        } catch (e: ApiException) {
            val msg = if (e.codigo == "coletor_revogado") "Este aparelho foi revogado no Nexus (Revisão RFID → Coletores)." else "Envio falhou: ${e.mensagemCurta()}"
            _ui.update { it.copy(enviando = false, erro = msg) }
        } catch (e: Throwable) {
            _ui.update { it.copy(enviando = false, erro = "Envio falhou: ${e.mensagemCurta()}") }
        }
    }

    fun confirmar() {
        viewModelScope.launch {
            enviarPendentes()
            tentarConfirmar(ConfirmarBody())
        }
    }

    private suspend fun tentarConfirmar(body: ConfirmarBody) {
        val sessaoId = _ui.value.sessaoId ?: return
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
        viewModelScope.launch { tentarConfirmar(ConfirmarBody(aceitarDivergencia = true, divergenciaVista = erro.diferencaPorTamanho, justificativa = justificativa, liberacao = liberacao)) }
    }

    fun justificar(erro: DivergenciaErro, causa: String, motivo: String) {
        justificativa = JustificativaDto(causa, motivo.trim())
        viewModelScope.launch { tentarConfirmar(ConfirmarBody(aceitarDivergencia = true, divergenciaVista = erro.diferencaPorTamanho, justificativa = justificativa, liberacao = liberacao)) }
    }

    fun liberar(erro: DivergenciaErro, supervisorId: String, pin: String, motivo: String, causa: String?, motivoJust: String?) {
        liberacao = LiberacaoDto(supervisorId, pin.trim(), motivo.trim())
        if (causa != null && !motivoJust.isNullOrBlank()) justificativa = JustificativaDto(causa, motivoJust.trim())
        viewModelScope.launch { tentarConfirmar(ConfirmarBody(aceitarDivergencia = true, divergenciaVista = erro.diferencaPorTamanho, justificativa = justificativa, liberacao = liberacao)) }
    }

    fun fecharPergunta() { _ui.update { it.copy(pergunta = null) } }
    fun limparErro() { _ui.update { it.copy(erro = null) } }

    override fun onCleared() {
        RfidReader.parar()
        super.onCleared()
    }
}
