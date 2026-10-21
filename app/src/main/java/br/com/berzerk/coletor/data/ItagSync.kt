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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

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

    suspend fun carregarStatus() {
        val estado = dao.estado()
        _status.value = _status.value.copy(totalLocal = dao.total(), concluidoEm = estado?.concluidoEm, imagens = dao.totalImagens())
    }

    fun iniciar(forcarImagens: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { rodar(forcarImagens) }
    }

    fun cancelar() { job?.cancel() }

    /** Apaga a base local e baixa de novo do zero (snapshot). */
    fun apagarEBaixar() {
        cancelar()
        job = scope.launch(Dispatchers.IO) {
            fase("Apagando a base…")
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("DELETE FROM sync_estado")
            sqlite.execSQL("DELETE FROM epcs")
            sqlite.execSQL("DELETE FROM imagens")
            runCatching { sqlite.execSQL("VACUUM") }
            _status.value = SyncStatus()
            rodar(forcarImagens = true)
        }
    }

    private fun fase(texto: String?, pct: Int? = null) { _status.value = _status.value.copy(fase = texto, pct = pct) }

    private suspend fun rodar(forcarImagens: Boolean) {
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
            val precisaImagens = forcarImagens || estado.imagensEm == null || System.currentTimeMillis() - estado.imagensEm > 24 * 3600_000L
            if (precisaImagens) {
                fase("Baixando imagens…")
                baixarImagens()
            }
            _status.value = _status.value.copy(rodando = false, fase = null, pct = null, concluidoEm = System.currentTimeMillis(), totalLocal = dao.total(), imagens = dao.totalImagens())
        } catch (e: CancellationException) {
            _status.value = _status.value.copy(rodando = false, fase = null, pct = null, totalLocal = dao.total())
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "sync falhou", e)
            val msg = if (e is ApiException && e.status == 403) "Sem permissão pra baixar a base (revisao-rfid:conferir)." else e.mensagemCurta()
            _status.value = _status.value.copy(rodando = false, fase = null, pct = null, erro = msg, totalLocal = dao.total())
        }
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
        val resposta = httpExterno.newCall(Request.Builder().url(url).build()).execute()
        if (!resposta.isSuccessful) throw IllegalStateException("HTTP ${resposta.code} ao baixar o snapshot")
        val corpo = resposta.body ?: throw IllegalStateException("snapshot vazio")
        val tamanho = if (totalBytes > 0) totalBytes else corpo.contentLength()
        val digest = MessageDigest.getInstance("SHA-256")
        val contado = ContadorStream(corpo.byteStream(), digest) { lidos ->
            fase("Baixando a base… ${mb(lidos)} de ${mb(tamanho)}", if (tamanho > 0) (lidos * 100 / tamanho).toInt().coerceIn(0, 100) else null)
        }
        contado.use { entrada -> arquivo.outputStream().use { saida -> entrada.copyTo(saida, 256 * 1024) } }
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
        // Carga em lote: sem fsync por transação e journal em memória (se o app morrer no meio, a
        // base é refeita do zero — `desde` só é gravado no fim). Restaurado no `finally`.
        sqlite.execSQL("PRAGMA synchronous = OFF")
        sqlite.execSQL("PRAGMA journal_mode = MEMORY")
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
            runCatching { sqlite.execSQL("PRAGMA journal_mode = WAL") }
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
        var maior: String? = estado.desde
        fase(if (estado.desde == null) "Baixando a base por páginas…" else "Atualizando…")
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
        Log.i(TAG, "delta: +${baixados} em $paginas páginas, total=${_status.value.totalLocal}")
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

    companion object {
        private const val TAG = "ItagSync"
        private const val LOTE = 100_000
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
