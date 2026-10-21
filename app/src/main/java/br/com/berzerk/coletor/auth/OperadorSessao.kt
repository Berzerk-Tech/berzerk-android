package br.com.berzerk.coletor.auth

import android.util.Log
import br.com.berzerk.coletor.api.FacialIdentificarResponse
import br.com.berzerk.coletor.api.NexusApi
import br.com.berzerk.coletor.core.Config
import br.com.berzerk.coletor.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

sealed interface OperadorEstado {
    data object Nenhum : OperadorEstado
    data class Ativo(val id: String, val nome: String, val username: String, val expiraEm: Long) : OperadorEstado
}

/**
 * Sessão do operador (identificado por reconhecimento facial depois do login da
 * conta compartilhada do aparelho). O token vai no header `x-berzerk-operador`
 * e mora SÓ em `Prefs` (cifrado); nunca em log. Acaba por: `expiraEm` (12 h no
 * servidor), 15 min sem toque/tecla/gatilho, "Trocar operador" ou 403
 * `operador_sessao_invalida`. Nada disso mexe na sessão do Cognito.
 */
class OperadorSessao(
    private val prefs: Prefs,
    private val api: () -> NexusApi,
    private val scope: CoroutineScope,
    /** Conta do aparelho (Cognito) está logada? Sem ela não existe sessão de operador. */
    private val logado: () -> Boolean,
) {
    @Volatile private var token: String? = null
    @Volatile private var ultimaAtividade: Long = 0L
    @Volatile private var ultimaGravacao: Long = 0L

    private val _estado = MutableStateFlow<OperadorEstado>(OperadorEstado.Nenhum)
    val estado: StateFlow<OperadorEstado> = _estado

    init {
        val salvo = prefs.operadorSalvo()
        // Restaura só se ainda vale E não ficou os 15 min parado (processo morto no meio da inatividade).
        if (salvo != null && salvo.expiraEm > agora() && agora() - salvo.ultimaAtividade <= Config.OPERADOR_INATIVIDADE_MS) {
            token = salvo.token
            ultimaAtividade = salvo.ultimaAtividade
            ultimaGravacao = salvo.ultimaAtividade
            _estado.value = OperadorEstado.Ativo(salvo.id, salvo.nome, salvo.username, salvo.expiraEm)
        } else if (salvo != null) {
            prefs.limparOperador()
        }
    }

    /**
     * Grava a sessão devolvida por `POST android/operador/identificar` e marca atividade agora.
     * `false` = resposta sem token (contrato divergente) ou conta do aparelho já deslogada
     * enquanto a identificação estava em voo; nada é gravado.
     */
    fun iniciar(resp: FacialIdentificarResponse): Boolean {
        if (resp.token.isBlank() || !logado()) return false
        val agora = agora()
        // `expiraEm` ilegível: assume as 12 h do servidor a partir de agora (o 403 corrige se errar).
        val expira = runCatching { Instant.parse(resp.expiraEm).toEpochMilli() }.getOrDefault(agora + 12 * 3_600_000L)
        prefs.salvarOperador(Prefs.OperadorSalvo(resp.token, expira, resp.operador.id, resp.operador.nome, resp.operador.username, agora))
        token = resp.token
        ultimaAtividade = agora
        ultimaGravacao = agora
        _estado.value = OperadorEstado.Ativo(resp.operador.id, resp.operador.nome, resp.operador.username, expira)
        return true
    }

    /**
     * Encerra SÓ se `token` ainda é o da sessão atual. É o que o 403 `operador_sessao_invalida`
     * chama: uma request antiga (sync do iTag, leitura em voo) voltando com o token do operador
     * anterior não pode derrubar a sessão do operador novo.
     */
    fun encerrarSe(token: String?) {
        if (token != null && token == this.token) encerrar()
    }

    /** Limpa a sessão local e, se havia token, pede a revogação ao Nexus (fire-and-forget, erro ignorado). */
    fun encerrar() {
        val antigo = token
        token = null
        prefs.limparOperador()
        _estado.value = OperadorEstado.Nenhum
        if (antigo != null) {
            scope.launch {
                runCatching { api().encerrarSessaoOperador(antigo) }
                    .onFailure { Log.d(TAG, "revogação da sessão não confirmada: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** Token pra mandar no header; `null` se não há sessão, ou se expirou/ficou inativa (e nesse caso encerra). */
    fun tokenValido(): String? {
        val atual = token ?: return null
        if (inativoOuExpirado()) {
            encerrar()
            return null
        }
        return atual
    }

    /**
     * Toque/tecla/gatilho. Request de rede NÃO chama isto. Disco no máximo a cada 30 s.
     * Se já passou dos 15 min, o toque NÃO revive a sessão: quem tocou pode ser outra pessoa,
     * então encerra na hora em vez de esperar o verificador periódico.
     */
    fun registrarAtividade() {
        if (token == null) return
        if (inativoOuExpirado()) {
            encerrar()
            return
        }
        val agora = agora()
        ultimaAtividade = agora
        if (agora - ultimaGravacao >= GRAVACAO_MIN_MS) {
            ultimaGravacao = agora
            prefs.atualizarAtividadeOperador(agora)
        }
    }

    fun inativoOuExpirado(): Boolean {
        val ativo = _estado.value as? OperadorEstado.Ativo ?: return false
        val agora = agora()
        return agora - ultimaAtividade > Config.OPERADOR_INATIVIDADE_MS || ativo.expiraEm <= agora
    }

    private fun agora() = System.currentTimeMillis()

    companion object {
        private const val TAG = "Facial"
        private const val GRAVACAO_MIN_MS = 30_000L
    }
}
