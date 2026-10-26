package br.com.berzerk.coletor.upload

import br.com.berzerk.coletor.api.ApiException
import br.com.berzerk.coletor.api.UploadResponse
import br.com.berzerk.coletor.api.mensagemCurta
import br.com.berzerk.coletor.data.LeituraDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/** Onde a fila lê o que falta enviar e marca o que subiu (Room em produção, fake nos testes). */
interface RepoLeituras {
    suspend fun pendentes(origem: String, referencia: String, limite: Int): List<String>
    suspend fun marcarEnviadas(origem: String, referencia: String, epcs: List<String>, em: Long)
}

class RoomRepoLeituras(private val dao: LeituraDao) : RepoLeituras {
    override suspend fun pendentes(origem: String, referencia: String, limite: Int) = dao.pendentes(origem, referencia, limite)

    override suspend fun marcarEnviadas(origem: String, referencia: String, epcs: List<String>, em: Long) {
        // Limite de variáveis do SQLite: IN (...) em pedaços.
        epcs.chunked(500).forEach { dao.marcarEnviadas(origem, referencia, it, em) }
    }
}

sealed interface ResultadoEnvio {
    data class Ok(val enviadas: Int) : ResultadoEnvio

    /** Rede ou 5xx (ou 408/429): tenta de novo. */
    data class SemRede(val motivo: String) : ResultadoEnvio

    /** 4xx (ou envio bloqueado de propósito): a fila para e mostra o erro. */
    data class Rejeitado(val status: Int, val motivo: String) : ResultadoEnvio
}

/** Envio recusado ANTES de sair (ex.: inventário fechado no Nexus): o bloco continua pendente. */
class EnvioBloqueadoException(val motivo: String) : IOException(motivo)

/** Erro de envio → resultado. 4xx para (exceto 408/429, que são "tente depois"); o resto é rede/servidor. */
fun classificarFalha(e: Throwable): ResultadoEnvio = when {
    e is EnvioBloqueadoException -> ResultadoEnvio.Rejeitado(0, e.motivo)
    e is ApiException && e.status in 400..499 && e.status != 408 && e.status != 429 ->
        ResultadoEnvio.Rejeitado(e.status, if (e.codigo == "coletor_revogado") "Este aparelho foi revogado no Nexus." else e.mensagemCurta())
    else -> ResultadoEnvio.SemRede(e.mensagemCurta())
}

/**
 * Fila de envio: pega as leituras com `enviadoEm = null` em blocos de até [bloco] EPCs, manda e
 * marca `enviadoEm` SÓ depois do 2xx. A interpretação do corpo da resposta (JSON ilegível = aceito,
 * HTML/texto = retentável) é do `UploadService`; aqui qualquer exceção do [enviar] é falha.
 */
class FilaEnvio(
    private val repo: RepoLeituras,
    private val enviar: suspend (origem: String, referencia: String, epcs: List<String>) -> UploadResponse,
    private val agora: () -> Long = System::currentTimeMillis,
    private val bloco: Int = TAMANHO_BLOCO,
) {
    /** Manda UM bloco. `Ok(0)` = nada pendente. */
    suspend fun enviarUmBloco(origem: String, referencia: String): ResultadoEnvio {
        val lote = repo.pendentes(origem, referencia, bloco)
        if (lote.isEmpty()) return ResultadoEnvio.Ok(0)
        try {
            enviar(origem, referencia, lote)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return classificarFalha(e)
        }
        // 2xx (inclusive 202 `aceito`): o Nexus guardou o upload e processa depois num job dele;
        // a partir daqui a responsabilidade pelo EPC é do Nexus, não mais da fila do aparelho.
        repo.marcarEnviadas(origem, referencia, lote, agora())
        return ResultadoEnvio.Ok(lote.size)
    }

    /** Esvazia a fila uma vez; para no primeiro erro. */
    suspend fun drenar(origem: String, referencia: String): ResultadoEnvio {
        var total = 0
        while (true) {
            val r = enviarUmBloco(origem, referencia)
            if (r !is ResultadoEnvio.Ok) return r
            if (r.enviadas == 0) return ResultadoEnvio.Ok(total)
            total += r.enviadas
        }
    }

    /**
     * [drenar] com retry em rede/5xx (backoff 5 s → 15 s → 60 s, repete o último), até [maxTentativas]
     * falhas seguidas. [exclusivo] envolve cada BLOCO (o `delay` do backoff fica fora): o `UploadService`
     * passa o Mutex global aqui. Só devolve `Ok`, `Rejeitado` ou (tentativas esgotadas) `SemRede`.
     */
    suspend fun drenarComRetry(
        origem: String,
        referencia: String,
        backoffMs: List<Long> = BACKOFF_MS,
        maxTentativas: Int = Int.MAX_VALUE,
        exclusivo: suspend (suspend () -> ResultadoEnvio) -> ResultadoEnvio = { it() },
        aoFalhar: (ResultadoEnvio.SemRede) -> Unit = {},
    ): ResultadoEnvio {
        var total = 0
        var falhas = 0
        while (true) {
            when (val r = exclusivo { enviarUmBloco(origem, referencia) }) {
                is ResultadoEnvio.Ok -> {
                    if (r.enviadas == 0) return ResultadoEnvio.Ok(total)
                    total += r.enviadas
                    falhas = 0
                }
                is ResultadoEnvio.Rejeitado -> return r
                is ResultadoEnvio.SemRede -> {
                    aoFalhar(r)
                    falhas++
                    if (falhas >= maxTentativas) return r
                    delay(backoffMs[minOf(falhas - 1, backoffMs.lastIndex)])
                }
            }
        }
    }

    companion object {
        const val TAMANHO_BLOCO = 5_000
        val BACKOFF_MS = listOf(5_000L, 15_000L, 60_000L)
    }
}
