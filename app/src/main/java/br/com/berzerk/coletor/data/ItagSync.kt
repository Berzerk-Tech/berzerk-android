package br.com.berzerk.coletor.data

import android.util.Log
import br.com.berzerk.coletor.api.ApiException
import br.com.berzerk.coletor.api.NexusApi
import br.com.berzerk.coletor.api.mensagemCurta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncStatus(
    val rodando: Boolean = false,
    val totalLocal: Long = 0,
    val baixadosNestaRodada: Long = 0,
    val paginas: Int = 0,
    val concluidoEm: Long? = null,
    val erro: String? = null,
    val imagens: Long = 0,
)

/**
 * Base EPC → produto no aparelho (Room), baixada do Nexus em páginas de 20k
 * (`GET /revisao-rfid/itag/epcs`, keyset + `desde`). A primeira carga é a
 * base inteira (~2,8 M linhas); depois só o delta. Interrompido no meio,
 * retoma da página onde parou (`cursorPendente`).
 */
class ItagSync(private val dao: EpcDao, private val api: () -> NexusApi, private val scope: CoroutineScope) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status
    private var job: Job? = null

    suspend fun carregarStatus() {
        val estado = dao.estado()
        _status.value = _status.value.copy(totalLocal = dao.total(), concluidoEm = estado?.concluidoEm, imagens = dao.totalImagens())
    }

    fun iniciar(forcarImagens: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { rodar(forcarImagens) }
    }

    fun cancelar() { job?.cancel() }

    private suspend fun rodar(forcarImagens: Boolean) {
        _status.value = _status.value.copy(rodando = true, erro = null, baixadosNestaRodada = 0, paginas = 0)
        try {
            val estado = dao.estado() ?: SyncEstado()
            var desde = estado.desdePendente ?: estado.desde
            var cursor = estado.cursorPendente
            var baixados = 0L
            var paginas = 0
            var maior: String? = estado.desde
            while (true) {
                val pagina = api().itagEpcs(desde = desde, cursor = cursor)
                val linhas = pagina.epcs.map { EpcEntity(it.epc.uppercase(), it.ean13, it.nome, it.tamanho, it.cor, it.referencia, it.dataGerado) }
                linhas.lastOrNull()?.dataGerado?.let { if (maior == null || it > maior!!) maior = it }
                paginas++
                baixados += linhas.size
                val proximo = pagina.proximoCursor
                val novoEstado = if (proximo == null) {
                    SyncEstado(desde = maior, cursorPendente = null, desdePendente = null, concluidoEm = System.currentTimeMillis(), imagensEm = estado.imagensEm)
                } else {
                    SyncEstado(desde = estado.desde, cursorPendente = proximo, desdePendente = desde, concluidoEm = estado.concluidoEm, imagensEm = estado.imagensEm)
                }
                dao.inserirPagina(linhas, novoEstado)
                _status.value = _status.value.copy(totalLocal = dao.total(), baixadosNestaRodada = baixados, paginas = paginas)
                if (proximo == null) break
                cursor = proximo
            }
            val precisaImagens = forcarImagens || estado.imagensEm == null || System.currentTimeMillis() - estado.imagensEm > 24 * 3600_000L
            if (precisaImagens) baixarImagens()
            _status.value = _status.value.copy(rodando = false, concluidoEm = System.currentTimeMillis(), totalLocal = dao.total(), imagens = dao.totalImagens())
            Log.i(TAG, "sync ok: +$baixados em $paginas páginas, total=${_status.value.totalLocal}")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                _status.value = _status.value.copy(rodando = false)
                throw e
            }
            Log.w(TAG, "sync falhou", e)
            val msg = if (e is ApiException && e.status == 403) "Sem permissão pra baixar a base (revisao-rfid:conferir)." else e.mensagemCurta()
            _status.value = _status.value.copy(rodando = false, erro = msg)
        }
    }

    private suspend fun baixarImagens() {
        val resp = api().itagImagens()
        dao.inserirImagens(resp.imagens.map { ImagemEntity(it.ean13, it.sku, it.imagemUrl) })
        val estado = dao.estado() ?: SyncEstado()
        dao.gravarEstado(estado.copy(imagensEm = System.currentTimeMillis()))
    }

    /** EPCs → produto, em lotes de 500 (limite de variáveis do SQLite). */
    suspend fun resolver(epcs: Collection<String>): Map<String, EpcEntity> = withContext(Dispatchers.IO) {
        val saida = HashMap<String, EpcEntity>(epcs.size)
        epcs.chunked(500).forEach { parte -> dao.buscar(parte).forEach { saida[it.epc] = it } }
        saida
    }

    suspend fun imagemPor(eans: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val saida = HashMap<String, String>()
        eans.chunked(500).forEach { parte -> dao.imagens(parte).forEach { saida[it.ean13] = it.imagemUrl } }
        saida
    }

    companion object { private const val TAG = "ItagSync" }
}
