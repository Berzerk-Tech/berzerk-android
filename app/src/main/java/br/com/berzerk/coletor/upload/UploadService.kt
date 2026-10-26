package br.com.berzerk.coletor.upload

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import br.com.berzerk.coletor.api.ApiException
import br.com.berzerk.coletor.api.JsonCfg
import br.com.berzerk.coletor.api.NexusApi
import br.com.berzerk.coletor.api.UploadResponse
import br.com.berzerk.coletor.core.Prefs
import br.com.berzerk.coletor.data.LeituraDao
import br.com.berzerk.coletor.data.Origem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** O que a tela mostra no indicador de envio. */
sealed interface EstadoEnvio {
    data object Ocioso : EstadoEnvio
    data object Enviando : EstadoEnvio
    data class SemRede(val motivo: String) : EstadoEnvio
    data class Erro(val motivo: String) : EstadoEnvio
}

/** Um envio com 2xx marcou leituras como enviadas (a tela do inventário recarrega). */
data class EnvioConcluido(val origem: String, val referencia: String)

/** 2xx cujo corpo não é JSON (portal cativo, proxy): não prova que o Nexus recebeu, então é retentável. */
class RespostaNaoJsonException : IOException("Resposta inesperada do Nexus (a rede pode estar pedindo login).")

/**
 * Corpo de um 2xx de leitura. Vazio (202 sem corpo) ou JSON de formato novo/ilegível = aceito;
 * qualquer coisa que não seja JSON no `Content-Type` = [RespostaNaoJsonException].
 */
fun interpretarResposta(contentType: String?, corpo: String?): UploadResponse {
    if (corpo.isNullOrBlank()) return UploadResponse()
    if (contentType?.contains("json", ignoreCase = true) != true) throw RespostaNaoJsonException()
    return try {
        JsonCfg.json.decodeFromString<UploadResponse>(corpo)
    } catch (e: SerializationException) {
        UploadResponse()
    }
}

fun chaveEnvio(origem: String, referencia: String) = "$origem|$referencia"

/**
 * Envia EPCs lidos pro Nexus: texto puro, um EPC por linha, AUTENTICADO. O aparelho se identifica
 * pelo `ANDROID_ID` (estável por app+aparelho) e o Nexus cadastra o coletor sozinho na primeira
 * leitura (`rfid_coletores.dispositivo_id`) — ninguém digita token.
 *
 * As leituras moram no Room (`leituras`); [enviarComRetry] é a fila: blocos de até 5.000, `enviadoEm`
 * só com 2xx, backoff em rede/5xx, para em 4xx. Rota por origem: `inventario` →
 * `POST revisao-rfid/sessoes/{id}/leituras` (cai pro `/leituras` genérico num 404 de rota ausente,
 * lembrado até reabrir o app); o resto → `POST revisao-rfid/leituras`.
 */
class UploadService(
    private val context: Context,
    private val api: () -> NexusApi,
    private val prefs: Prefs,
    private val dao: LeituraDao,
) {
    @SuppressLint("HardwareIds")
    val dispositivoId: String = run {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.takeIf { it.isNotBlank() } ?: "sem-id"
        "android-$id".replace(Regex("[^A-Za-z0-9._:-]"), "")
    }
    val modelo: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().replace(Regex("[^\\x20-\\x7E]"), "").take(80).ifBlank { "Android" }

    @Volatile private var rotaDaSessaoIndisponivel = false
    private val repo = RoomRepoLeituras(dao)
    private val fila = FilaEnvio(repo, ::enviarBloco)
    private val trava = Mutex()

    private val _estados = MutableStateFlow<Map<String, EstadoEnvio>>(emptyMap())
    val estados: StateFlow<Map<String, EstadoEnvio>> = _estados

    private val _concluidos = MutableSharedFlow<EnvioConcluido>(extraBufferCapacity = 16)
    val concluidos: SharedFlow<EnvioConcluido> = _concluidos

    private fun corpoDe(epcs: Collection<String>): RequestBody =
        epcs.joinToString("\n").toRequestBody("text/plain; charset=utf-8".toMediaType())

    private fun interpretar(r: Response<ResponseBody>): UploadResponse =
        lembrarColetor(interpretarResposta(r.headers()["Content-Type"], r.body()?.string()))

    /** Envio direto pela rota genérica, sem fila (usado pela Conferência de lote, que tem o seu próprio controle). */
    suspend fun enviar(epcs: Collection<String>): UploadResponse = interpretar(api().leituras(dispositivoId, modelo, corpoDe(epcs)))

    private suspend fun enviarBloco(origem: String, referencia: String, epcs: List<String>): UploadResponse {
        val corpo = corpoDe(epcs)
        if (origem == Origem.INVENTARIO && referencia.isNotBlank()) {
            if (!rotaDaSessaoIndisponivel) {
                try {
                    return interpretar(api().leiturasSessao(referencia, dispositivoId, modelo, corpo))
                } catch (e: ApiException) {
                    // Lista positiva: só o 404 padrão do Nest (sem `error`, ou "Not Found") é rota ausente.
                    // Qualquer outro 404 (`sessao_nao_encontrada`, código futuro) é erro normal do grupo.
                    if (!ehRotaAusente(e)) throw e
                    rotaDaSessaoIndisponivel = true
                }
            }
            // Pela rota genérica o Nexus não olha a sessão: confere antes que ela está aberta e inclui este aparelho.
            conferirSessao(referencia)
        }
        return interpretar(api().leituras(dispositivoId, modelo, corpo))
    }

    private fun ehRotaAusente(e: ApiException) = e.status == 404 && (e.codigo == null || e.codigo == "Not Found")

    private val guardas = ConcurrentHashMap<String, Pair<Long, String?>>()

    /** Sessão aberta E com este aparelho em `coletorIds`; resultado em cache por 60 s. Senão [EnvioBloqueadoException]. */
    private suspend fun conferirSessao(sessaoId: String) {
        val agora = System.currentTimeMillis()
        guardas[sessaoId]?.takeIf { agora - it.first < GUARDA_MS }?.let { (_, motivo) ->
            if (motivo != null) throw EnvioBloqueadoException(motivo)
            return
        }
        val coletorId = garantirColetorId()
        val sessao = api().cabecalhoSessao(sessaoId).sessao
        val motivo = when {
            !sessao.aberta -> "Inventário fechado no Nexus"
            coletorId !in sessao.coletorIds -> "Este aparelho não está neste inventário"
            else -> null
        }
        guardas[sessaoId] = agora to motivo
        if (motivo != null) throw EnvioBloqueadoException(motivo)
    }

    private fun lembrarColetor(r: UploadResponse): UploadResponse {
        r.coletorId?.takeIf { it.isNotBlank() }?.let { prefs.coletorId = it }
        return r
    }

    /**
     * Id deste aparelho como coletor no Nexus. Sem ele guardado, manda um texto sem EPC válido
     * (`registro-aparelho`): o Nexus cadastra o aparelho e responde 200 com `coletorId` e `epcs: 0`
     * (corpo vazio dá 400 `corpo_vazio`). Falha propaga: quem chama mostra o motivo.
     */
    suspend fun garantirColetorId(): String {
        prefs.coletorId?.let { return it }
        interpretar(api().leituras(dispositivoId, modelo, corpoDe(listOf("registro-aparelho"))))
        return prefs.coletorId ?: throw IllegalStateException("o Nexus não devolveu o id do aparelho")
    }

    private fun estado(origem: String, referencia: String, e: EstadoEnvio) {
        _estados.update { it + (chaveEnvio(origem, referencia) to e) }
    }

    /**
     * Fila da tela: manda os pendentes de (origem, referencia) e, em rede/5xx, segue tentando
     * (5 s → 15 s → 60 s) até a coroutine ser cancelada. Também agenda o WorkManager pra próxima abertura.
     */
    suspend fun enviarComRetry(origem: String, referencia: String): ResultadoEnvio {
        estado(origem, referencia, EstadoEnvio.Enviando)
        var agendou = false
        try {
            // O Mutex vale por bloco: o delay do backoff roda solto (não trava outras filas nem o worker).
            val r = fila.drenarComRetry(origem, referencia, exclusivo = { bloco -> trava.withLock { bloco() } }) { falha ->
                estado(origem, referencia, EstadoEnvio.SemRede(falha.motivo))
                if (!agendou) { agendou = true; EnvioPendentesWorker.enfileirar(context) }
            }
            when (r) {
                is ResultadoEnvio.Ok -> {
                    estado(origem, referencia, EstadoEnvio.Ocioso)
                    if (r.enviadas > 0) _concluidos.tryEmit(EnvioConcluido(origem, referencia))
                }
                is ResultadoEnvio.Rejeitado -> estado(origem, referencia, EstadoEnvio.Erro(r.motivo))
                is ResultadoEnvio.SemRede -> estado(origem, referencia, EstadoEnvio.SemRede(r.motivo))
            }
            return r
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Tela fechou no meio: fica pendente pro worker.
            estado(origem, referencia, EstadoEnvio.Ocioso)
            EnvioPendentesWorker.enfileirar(context)
            throw e
        }
    }

    /**
     * Passada única sobre tudo que ficou pendente (WorkManager): sem retry próprio, o worker é quem
     * reagenda. `false` = houve falha de rede, tentar de novo depois.
     */
    suspend fun enviarTudoPendente(): Boolean = trava.withLock {
        var rede = false
        dao.gruposPendentes().forEach { g ->
            val r = fila.drenar(g.origem, g.referencia)
            when (r) {
                is ResultadoEnvio.Ok -> if (r.enviadas > 0) _concluidos.tryEmit(EnvioConcluido(g.origem, g.referencia))
                is ResultadoEnvio.SemRede -> rede = true
                is ResultadoEnvio.Rejeitado -> estado(g.origem, g.referencia, EstadoEnvio.Erro(r.motivo))
            }
        }
        !rede
    }

    private companion object {
        const val GUARDA_MS = 60_000L
    }
}
