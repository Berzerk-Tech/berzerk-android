package br.com.berzerk.coletor.data

import android.util.Log
import br.com.berzerk.coletor.api.ApiException
import br.com.berzerk.coletor.api.ItagSnapshotDto
import br.com.berzerk.coletor.api.NexusApi
import br.com.berzerk.coletor.api.mensagemCurta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/** Imagens por EAN exato e por prefixo de 12 dígitos (o 13º só separa o tamanho). */
private class CacheImagens(val porEan: Map<String, String>, val porPrefixo: Map<String, String>)

data class SyncStatus(
    val rodando: Boolean = false,
    val fase: String? = null,
    val pct: Int? = null,
    val totalLocal: Long = 0,
    val baixadosNestaRodada: Long = 0,
    val paginas: Int = 0,
    val concluidoEm: Long? = null,
    val erro: String? = null,
    val imagens: Long = 0,
    /** Download das imagens do catálogo: independente da base (job, erro e data próprios). */
    val imagensRodando: Boolean = false,
    val imagensEm: Long? = null,
    val imagensErro: String? = null,
    /** Modelos distintos no índice local (`modelos`). */
    val modelos: Long = 0,
    /** `true` enquanto `reindexarModelos` roda. */
    val indexando: Boolean = false,
)

/**
 * Base EPC → produto no aparelho (Room). Primeira carga = o SNAPSHOT diário
 * (`GET /revisao-rfid/itag/snapshot`: TSV gzip no S3, ~2,8 M linhas), gravado
 * em lote direto no SQLite; depois só o delta por `GET /revisao-rfid/itag/epcs`
 * (keyset + `desde`). Snapshot ainda sendo gerado (202) → espera e tenta de
 * novo; sem snapshot → cai nas páginas.
 */
class ItagSync(
    private val db: ColetorDb,
    private val api: () -> NexusApi,
    private val httpExterno: OkHttpClient,
    private val scope: CoroutineScope,
    private val cacheDir: File,
) {
    private val dao = db.epcs()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status
    private var job: Job? = null
    private var jobImagens: Job? = null
    @Volatile private var callAtual: Call? = null
    @Volatile private var cacheImagens: CacheImagens? = null
    private val mutexIndice = Mutex()
    /** Operador pausou o download nesta execução: a atualização automática não religa sozinha. */
    @Volatile private var pausadaPeloOperador = false
    @Volatile private var ultimaTentativaAuto = 0L

    suspend fun carregarStatus() {
        val estado = dao.estado()
        val total = dao.total()
        val imagens = dao.totalImagens()
        _status.value = _status.value.copy(
            totalLocal = total, concluidoEm = estado?.concluidoEm, imagens = imagens, imagensEm = estado?.imagensEm, modelos = dao.totalModelos(),
        )
        // Base presente mas índice ausente (migração 1 → 2) ou de uma regra antiga de `parseNomeItag`.
        if (total > 0 && (_status.value.modelos == 0L || (estado?.indiceVersao ?: 0) != VERSAO_INDICE)) reindexarModelos()
        val em = estado?.imagensEm
        if (imagens == 0L || em == null || System.currentTimeMillis() - em > IMAGENS_VALIDADE_MS) atualizarImagens(false)
    }

    /** Reconstrói a tabela `modelos` a partir de `epcs` (agrega por nome/EAN/tamanho e aplica `parseNomeItag`). */
    suspend fun reindexarModelos() = withContext(Dispatchers.IO) {
        mutexIndice.withLock {
            val inicio = System.currentTimeMillis()
            _status.value = _status.value.copy(indexando = true)
            try {
                val agregadas = dao.agregarPorNome()
                val linhas = indexarLinhas(agregadas)
                dao.substituirModelos(linhas)
                dao.gravarEstado((dao.estado() ?: SyncEstado()).copy(indiceVersao = VERSAO_INDICE))
                _status.value = _status.value.copy(modelos = dao.totalModelos())
                Log.i(TAG, "modelos reindexados: ${agregadas.size} grupos → ${linhas.size} linhas em ${System.currentTimeMillis() - inicio} ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "reindexar modelos falhou", e)
            } finally {
                _status.value = _status.value.copy(indexando = false)
            }
        }
    }

    fun iniciar() {
        if (job?.isActive == true) return
        pausadaPeloOperador = false
        job = scope.launch(Dispatchers.IO) { rodar() }
    }

    /**
     * Atualização automática (abertura do app, volta pro primeiro plano, checagem horária): roda
     * `iniciar()` se a base está vazia, nunca concluiu ou concluiu há mais de [BASE_VALIDADE_MS].
     * O delta é só o que mudou desde o último `desde`, então chamar isso a toda abertura é barato.
     * Não religa um download que o operador pausou nesta execução nem repete tentativa (erro de
     * rede, por exemplo) em menos de [AUTO_INTERVALO_MS].
     */
    fun atualizarSeVelha() {
        if (job?.isActive == true || pausadaPeloOperador) return
        val agora = System.currentTimeMillis()
        if (agora - ultimaTentativaAuto < AUTO_INTERVALO_MS) return
        ultimaTentativaAuto = agora
        scope.launch(Dispatchers.IO) {
            val concluidoEm = dao.estado()?.concluidoEm
            val velha = concluidoEm == null || agora - concluidoEm > BASE_VALIDADE_MS
            if (velha || dao.total() == 0L) {
                Log.i(TAG, "base ${if (concluidoEm == null) "nunca concluída" else "concluída há ${(agora - concluidoEm) / 3600_000} h"}: atualizando sozinho")
                iniciar()
            }
        }
    }

    /** Pausa: o status já mostra "Pausando…" e o download em andamento (OkHttp) é cancelado. */
    fun cancelar() {
        if (job?.isActive == true) { fase("Pausando…", _status.value.pct); pausadaPeloOperador = true }
        job?.cancel()
        callAtual?.cancel()
    }

    /** Apaga a base local e baixa de novo do zero (snapshot + imagens). */
    fun apagarEBaixar() {
        cancelar()
        val anterior = job
        job = scope.launch(Dispatchers.IO) {
            anterior?.cancelAndJoin()
            jobImagens?.cancelAndJoin()
            _status.value = SyncStatus(rodando = true, fase = "Apagando a base…")
            try {
                dao.limparTudo()
                cacheImagens = null
            } catch (e: Throwable) {
                _status.value = SyncStatus(erro = "Não deu pra apagar: ${e.mensagemCurta()}")
                return@launch
            }
            _status.value = SyncStatus()
            rodar()
            atualizarImagens(forcar = true)
        }
    }

    /**
     * Baixa as imagens do catálogo (job próprio, independente da base): troca a tabela `imagens`
     * inteira numa transação. Pula se já rodando ou se a última foi há menos de 24 h (salvo `forcar`).
     */
    fun atualizarImagens(forcar: Boolean = false) {
        if (jobImagens?.isActive == true) return
        jobImagens = scope.launch(Dispatchers.IO) { executarImagens(forcar) }
    }

    private suspend fun executarImagens(forcar: Boolean) {
        try {
            val em = dao.estado()?.imagensEm
            if (!forcar && em != null && System.currentTimeMillis() - em < IMAGENS_VALIDADE_MS && dao.totalImagens() > 0) return
            _status.value = _status.value.copy(imagensRodando = true, imagensErro = null)
            val resp = api().itagImagens()
            dao.substituirImagens(resp.imagens.map { ImagemEntity(it.ean13, it.sku, it.imagemUrl) })
            val agora = System.currentTimeMillis()
            dao.gravarEstado((dao.estado() ?: SyncEstado()).copy(imagensEm = agora))
            cacheImagens = null
            _status.value = _status.value.copy(imagens = dao.totalImagens(), imagensEm = agora)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "imagens falharam", e)
            val msg = if (e is ApiException && e.status == 403) "Sem permissão pra baixar as imagens (revisao-rfid:conferir)." else e.mensagemCurta()
            _status.value = _status.value.copy(imagensErro = msg)
        } finally {
            _status.value = _status.value.copy(imagensRodando = false)
        }
    }

    private fun fase(texto: String?, pct: Int? = null) { _status.value = _status.value.copy(fase = texto, pct = pct) }

    private suspend fun rodar() {
        _status.value = _status.value.copy(rodando = true, erro = null, baixadosNestaRodada = 0, paginas = 0, pct = null)
        try {
            var estado = dao.estado() ?: SyncEstado()
            // Base nunca concluída (sem `desde`) = carga pelo snapshot, mesmo que uma paginação
            // antiga tenha ficado pela metade (era o que prendia o app na versão velha).
            if (estado.desde == null) {
                val ok = carregarSnapshot()
                if (ok) estado = dao.estado() ?: SyncEstado()
                else if (estado.cursorPendente != null) { /* segue a paginação de onde parou */ }
            }
            delta(estado)
            val novas = _status.value.baixadosNestaRodada > 0
            _status.value = _status.value.copy(rodando = false, fase = null, pct = null, concluidoEm = System.currentTimeMillis(), totalLocal = dao.total())
            atualizarImagens(false)
            if (novas || dao.totalModelos() == 0L) reindexarModelos()
        } catch (e: CancellationException) {
            pausado()
            throw e
        } catch (e: Throwable) {
            // Cancelar a chamada do OkHttp (pausa no download) estoura IOException, não Cancellation.
            if (!currentCoroutineContext().isActive) {
                pausado()
                throw CancellationException("pausado", e)
            }
            Log.w(TAG, "sync falhou", e)
            val msg = if (e is ApiException && e.status == 403) "Sem permissão pra baixar a base (revisao-rfid:conferir)." else e.mensagemCurta()
            _status.value = _status.value.copy(rodando = false, fase = null, pct = null, erro = msg, totalLocal = dao.total())
        }
    }

    /** Roda fora do escopo cancelado (o Room recusa suspend em coroutine cancelada). */
    private suspend fun pausado() = withContext(NonCancellable) {
        _status.value = _status.value.copy(rodando = false, fase = null, pct = null, totalLocal = dao.total())
    }

    /** `true` se o snapshot foi aplicado; `false` = indisponível (cai nas páginas). */
    private suspend fun carregarSnapshot(): Boolean {
        var manifesto: ItagSnapshotDto? = null
        for (tentativa in 1..SNAPSHOT_ESPERA_MAX) {
            val r = runCatching { api().itagSnapshot() }.getOrElse { e ->
                if (e is ApiException && (e.status == 404 || e.status == 503)) return false
                throw e
            }
            if (r.url != null) { manifesto = r; break }
            fase("O Nexus está gerando a base (${tentativa}/$SNAPSHOT_ESPERA_MAX)…")
            delay(SNAPSHOT_ESPERA_MS)
        }
        val m = manifesto ?: return false
        val url = m.url ?: return false
        val inicio = System.currentTimeMillis()

        // Fase 1: baixar o arquivo inteiro (~8 MB gzip) pro cache, com progresso em bytes.
        val pasta = File(cacheDir, "itag").apply { mkdirs() }
        val arquivo = File(pasta, "snapshot.tsv.gz")
        val totalBytes = m.bytes ?: -1L
        fase("Baixando a base… 0 de ${mb(totalBytes)}", 0)
        val chamada = httpExterno.newCall(Request.Builder().url(url).build())
        callAtual = chamada
        val resposta = try { chamada.execute() } catch (e: Throwable) { callAtual = null; throw e }
        if (!resposta.isSuccessful) { callAtual = null; resposta.close(); throw IllegalStateException("HTTP ${resposta.code} ao baixar o snapshot") }
        val corpo = resposta.body ?: throw IllegalStateException("snapshot vazio")
        val tamanho = if (totalBytes > 0) totalBytes else corpo.contentLength()
        val digest = MessageDigest.getInstance("SHA-256")
        val contado = ContadorStream(corpo.byteStream(), digest) { lidos ->
            fase("Baixando a base… ${mb(lidos)} de ${mb(tamanho)}", if (tamanho > 0) (lidos * 100 / tamanho).toInt().coerceIn(0, 100) else null)
        }
        try {
            contado.use { entrada -> arquivo.outputStream().use { saida -> entrada.copyTo(saida, 256 * 1024) } }
        } finally {
            callAtual = null
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (m.sha256 != null && !m.sha256.equals(sha, ignoreCase = true)) {
            arquivo.delete()
            throw IllegalStateException("O arquivo baixado não bate com o publicado (sha256); tente de novo.")
        }
        val tBaixado = System.currentTimeMillis()
        Log.i(TAG, "snapshot baixado: ${arquivo.length()} bytes em ${(tBaixado - inicio) / 1000}s")

        // Fase 2: gravar no SQLite, com progresso em linhas (o manifesto diz quantas são).
        val totalLinhas = m.linhas ?: 0L
        fase("Gravando no aparelho… 0 de ${fmtInt(totalLinhas)}", 0)
        var linhas = 0L
        val sqlite = db.openHelper.writableDatabase
        // Carga em lote: sem fsync por transação (se o app morrer no meio, a base é refeita do
        // zero — `desde` só é gravado no fim). Restaurado no `finally`. O journal fica em WAL:
        // trocar o modo com as conexões do Room abertas dá SQLITE_BUSY (0.2.2).
        sqlite.execSQL("PRAGMA synchronous = OFF")
        sqlite.execSQL("PRAGMA cache_size = -65536")
        val stmt = sqlite.compileStatement("INSERT OR REPLACE INTO epcs (epc, ean13, nome, tamanho, cor, referencia, dataGerado) VALUES (?,?,?,?,?,?,?)")
        try {
        BufferedReader(InputStreamReader(GZIPInputStream(arquivo.inputStream(), 64 * 1024), Charsets.UTF_8), 256 * 1024).use { leitor ->
            var lote = 0
            sqlite.beginTransaction()
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    val linha = leitor.readLine() ?: break
                    if (linha.isEmpty()) continue
                    val c = linha.split('\t')
                    if (c.size < 7 || c[0].length != 24) continue
                    stmt.clearBindings()
                    stmt.bindString(1, c[0].uppercase())
                    bindOuNulo(stmt, 2, c[1]); bindOuNulo(stmt, 3, c[2]); bindOuNulo(stmt, 4, c[3]); bindOuNulo(stmt, 5, c[4]); bindOuNulo(stmt, 6, c[5])
                    stmt.bindString(7, c[6])
                    stmt.executeInsert()
                    linhas++
                    if (++lote >= LOTE) {
                        sqlite.setTransactionSuccessful(); sqlite.endTransaction(); sqlite.beginTransaction(); lote = 0
                        _status.value = _status.value.copy(baixadosNestaRodada = linhas)
                        fase("Gravando no aparelho… ${fmtInt(linhas)} de ${fmtInt(totalLinhas)}", if (totalLinhas > 0) (linhas * 100 / totalLinhas).toInt().coerceIn(0, 100) else null)
                    }
                }
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
                stmt.close()
            }
        }
        } finally {
            runCatching { sqlite.execSQL("PRAGMA synchronous = NORMAL") }
            arquivo.delete()
        }
        fase("Otimizando a base…")
        runCatching { sqlite.execSQL("ANALYZE epcs") }
        Log.i(TAG, "snapshot gravado: $linhas linhas em ${(System.currentTimeMillis() - tBaixado) / 1000}s")
        val anterior = dao.estado() ?: SyncEstado()
        dao.gravarEstado(anterior.copy(desde = m.maxDataGerado ?: m.geradoEm, cursorPendente = null, desdePendente = null, concluidoEm = System.currentTimeMillis()))
        _status.value = _status.value.copy(totalLocal = dao.total(), baixadosNestaRodada = linhas, pct = null)
        return true
    }

    private fun mb(bytes: Long): String = if (bytes <= 0) "?" else "%.1f MB".format(bytes / 1_048_576.0)
    private fun fmtInt(n: Long): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale("pt", "BR")).format(n)

    private fun bindOuNulo(stmt: androidx.sqlite.db.SupportSQLiteStatement, i: Int, v: String) {
        if (v.isEmpty()) stmt.bindNull(i) else stmt.bindString(i, v)
    }

    private suspend fun delta(estadoInicial: SyncEstado) {
        var estado = estadoInicial
        var desde = estado.desdePendente ?: estado.desde
        var cursor = estado.cursorPendente
        var baixados = _status.value.baixadosNestaRodada
        var paginas = 0
        var novos = 0L
        var maior: String? = estado.desde
        fase(if (estado.desde == null) "Baixando a base por páginas…" else "Atualizando…")
        while (true) {
            val pagina = api().itagEpcs(desde = desde, cursor = cursor)
            val linhas = pagina.epcs.map { EpcEntity(it.epc.uppercase(), it.ean13, it.nome, it.tamanho, it.cor, it.referencia, it.dataGerado) }
            linhas.lastOrNull()?.dataGerado?.let { if (maior == null || it > maior!!) maior = it }
            paginas++
            baixados += linhas.size
            val proximo = pagina.proximoCursor
            // Em pedaços, com ponto de cancelamento entre eles; o estado só avança depois da página inteira
            // (cancelou no meio → a próxima rodada refaz a página, o REPLACE absorve a duplicata).
            linhas.chunked(PEDACO).forEach { pedaco ->
                dao.inserir(pedaco)
                novos += pedaco.size
                fase("${if (estado.desde == null) "Baixando a base por páginas…" else "Atualizando…"} +${fmtInt(novos)}")
                coroutineContext.ensureActive()
            }
            val atual = dao.estado() ?: SyncEstado()
            val novoEstado = if (proximo == null) {
                atual.copy(desde = maior, cursorPendente = null, desdePendente = null, concluidoEm = System.currentTimeMillis())
            } else {
                atual.copy(desde = estado.desde, cursorPendente = proximo, desdePendente = desde, concluidoEm = estado.concluidoEm)
            }
            dao.gravarEstado(novoEstado)
            _status.value = _status.value.copy(totalLocal = dao.total(), baixadosNestaRodada = baixados, paginas = paginas)
            if (proximo == null) break
            cursor = proximo
        }
        Log.i(TAG, "delta: +${baixados} em $paginas páginas, total=${_status.value.totalLocal}")
    }

    /** EPCs → produto, em lotes de 500 (limite de variáveis do SQLite). */
    suspend fun resolver(epcs: Collection<String>): Map<String, EpcEntity> = withContext(Dispatchers.IO) {
        val saida = HashMap<String, EpcEntity>(epcs.size)
        epcs.chunked(500).forEach { parte -> dao.buscar(parte).forEach { saida[it.epc] = it } }
        saida
    }

    private suspend fun cacheDeImagens(): CacheImagens {
        cacheImagens?.let { return it }
        val todas = dao.todasImagens()
        val porEan = HashMap<String, String>(todas.size * 2)
        val porPrefixo = HashMap<String, String>(todas.size * 2)
        todas.forEach { i ->
            porEan[i.ean13] = i.imagemUrl
            porPrefixo.putIfAbsent(i.ean13.take(12), i.imagemUrl) // ordenado por EAN: a primeira url é estável
        }
        return CacheImagens(porEan, porPrefixo).also { cacheImagens = it }
    }

    /** EAN → url da foto: casa o EAN exato e, se não houver, o prefixo de 12 dígitos (etiquetas antigas). */
    suspend fun imagemPor(eans: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val c = cacheDeImagens()
        val saida = HashMap<String, String>()
        eans.forEach { ean -> (c.porEan[ean] ?: c.porPrefixo[ean.take(12)])?.let { saida[ean] = it } }
        saida
    }

    companion object {
        private const val TAG = "ItagSync"
        private const val LOTE = 100_000
        private const val PEDACO = 2_000
        private const val IMAGENS_VALIDADE_MS = 24 * 3600_000L
        /** Idade máxima da base antes de a atualização automática rodar. */
        private const val BASE_VALIDADE_MS = 24 * 3600_000L
        /** Intervalo mínimo entre tentativas automáticas (não martelar o Nexus sem rede). */
        private const val AUTO_INTERVALO_MS = 60 * 60_000L
        private const val SNAPSHOT_ESPERA_MAX = 12
        private const val SNAPSHOT_ESPERA_MS = 15_000L
    }
}

/** InputStream que conta bytes (progresso) e alimenta o SHA-256. */
private class ContadorStream(
    private val origem: java.io.InputStream,
    private val digest: MessageDigest,
    private val aoLer: (Long) -> Unit,
) : java.io.InputStream() {
    private var lidos = 0L
    private var ultimoAviso = 0L
    override fun read(): Int {
        val b = origem.read()
        if (b >= 0) { digest.update(b.toByte()); contar(1) }
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = origem.read(b, off, len)
        if (n > 0) { digest.update(b, off, n); contar(n) }
        return n
    }
    private fun contar(n: Int) {
        lidos += n
        if (lidos - ultimoAviso > 512 * 1024) { ultimoAviso = lidos; aoLer(lidos) }
    }
    override fun close() = origem.close()
}
