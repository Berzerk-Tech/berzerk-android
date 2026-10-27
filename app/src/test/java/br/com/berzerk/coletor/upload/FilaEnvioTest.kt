package br.com.berzerk.coletor.upload

import br.com.berzerk.coletor.api.ApiException
import br.com.berzerk.coletor.api.CodigoErro
import br.com.berzerk.coletor.api.UploadResponse
import br.com.berzerk.coletor.data.GrupoLeitura
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Linhas (epc, pasta, enviada) como no Room: pasta nula = ainda não escolhida. */
private class RepoFalso(epcs: List<String>, pastaInicial: String? = "p1", val origem: String = "") : RepoLeituras {
    class Linha(val epc: String, var pastaId: String?, var enviada: Boolean = false)

    val linhas = epcs.map { Linha(it, pastaInicial) }.toMutableList()
    val pendentes get() = linhas.filter { !it.enviada }.map { it.epc }
    val enviados get() = linhas.filter { it.enviada }.map { it.epc }
    var marcadoEm: Long? = null

    override suspend fun pendentes(origem: String, referencia: String, pastaId: String, limite: Int) =
        linhas.filter { !it.enviada && it.pastaId == pastaId }.map { it.epc }.take(limite)

    override suspend fun marcarEnviadas(origem: String, referencia: String, pastaId: String, epcs: List<String>, em: Long) {
        linhas.filter { it.pastaId == pastaId && it.epc in epcs }.forEach { it.enviada = true }
        marcadoEm = em
    }

    override suspend fun atribuirPasta(origem: String, referencia: String, pastaId: String, fechadas: Set<String>) {
        linhas.filter { !it.enviada && (it.pastaId == null || it.pastaId in fechadas) }.forEach { it.pastaId = pastaId }
    }

    override suspend fun gruposPendentes() =
        linhas.filter { !it.enviada && it.pastaId != null }.map { GrupoLeitura(origem, "", it.pastaId!!) }.distinct()
}

class FilaEnvioTest {
    private val epcs = (1..5).map { "E$it" }

    @Test
    fun `2xx marca enviadoEm e manda em blocos`() = runBlocking {
        val repo = RepoFalso(epcs)
        val blocos = ArrayList<List<String>>()
        val fila = FilaEnvio(repo, { _, _, _, lote -> blocos.add(lote); UploadResponse(epcs = lote.size) }, { 42L }, bloco = 2)
        val r = fila.drenar("inventario", "s1", "p1")
        assertEquals(ResultadoEnvio.Ok(5), r)
        assertEquals(listOf(listOf("E1", "E2"), listOf("E3", "E4"), listOf("E5")), blocos)
        assertTrue(repo.pendentes.isEmpty())
        assertEquals(42L, repo.marcadoEm)
    }

    @Test
    fun `5xx nao marca nada e retenta ate subir`() = runBlocking {
        val repo = RepoFalso(epcs)
        var chamadas = 0
        val fila = FilaEnvio(repo, { _, _, _, _ ->
            chamadas++
            if (chamadas < 3) throw ApiException(503, null, null, "u")
            UploadResponse()
        })
        val falhas = ArrayList<String>()
        val r = fila.drenarComRetry("revisao", "m", "p1", backoffMs = listOf(0L), aoFalhar = { falhas.add(it.motivo) })
        assertEquals(ResultadoEnvio.Ok(5), r)
        assertEquals(3, chamadas)
        assertEquals(2, falhas.size)
        assertEquals(5, repo.enviados.size)
    }

    @Test
    fun `erro de rede fica pendente`() = runBlocking {
        val repo = RepoFalso(epcs)
        val fila = FilaEnvio(repo, { _, _, _, _ -> throw IOException("sem rede") })
        val r = fila.drenarComRetry("livre", "", "p1", backoffMs = listOf(0L), maxTentativas = 3)
        assertTrue(r is ResultadoEnvio.SemRede)
        assertEquals(5, repo.pendentes.size)
        assertTrue(repo.enviados.isEmpty())
    }

    @Test
    fun `4xx para sem retry e sem marcar`() = runBlocking {
        val repo = RepoFalso(epcs)
        var chamadas = 0
        val fila = FilaEnvio(repo, { _, _, _, _ -> chamadas++; throw ApiException(403, "coletor_revogado", null, "u") })
        val r = fila.drenarComRetry("inventario", "s1", "p1", backoffMs = listOf(0L))
        assertTrue(r is ResultadoEnvio.Rejeitado)
        assertEquals(403, (r as ResultadoEnvio.Rejeitado).status)
        assertEquals(1, chamadas)
        assertEquals(5, repo.pendentes.size)
    }

    @Test
    fun `408 e 429 sao retentaveis`() {
        assertTrue(classificarFalha(ApiException(408, null, null, "u")) is ResultadoEnvio.SemRede)
        assertTrue(classificarFalha(ApiException(429, null, null, "u")) is ResultadoEnvio.SemRede)
        assertTrue(classificarFalha(ApiException(500, null, null, "u")) is ResultadoEnvio.SemRede)
        assertTrue(classificarFalha(ApiException(422, null, null, "u")) is ResultadoEnvio.Rejeitado)
        assertTrue(classificarFalha(ApiException(401, null, null, "u")) is ResultadoEnvio.Rejeitado)
    }

    @Test
    fun `resposta nao JSON e retentavel e JSON ilegivel conta como aceito`() {
        assertTrue(runCatching { interpretarResposta("text/html", "<html>login</html>") }.exceptionOrNull() is RespostaNaoJsonException)
        assertTrue(classificarFalha(RespostaNaoJsonException()) is ResultadoEnvio.SemRede)
        assertEquals(UploadResponse(), interpretarResposta("application/json", """{"formato":"novo","epcs":"x"}"""))
        assertEquals(UploadResponse(), interpretarResposta(null, ""))
    }

    @Test
    fun `backoff nao segura o exclusivo`() = runBlocking {
        val repo = RepoFalso(epcs)
        var travado = false
        var travadoNoDelay = false
        var chamadas = 0
        val fila = FilaEnvio(repo, { _, _, _, _ ->
            chamadas++
            if (chamadas == 1) throw IOException("rede")
            UploadResponse()
        })
        fila.drenarComRetry("livre", "", "p1", backoffMs = listOf(1L), exclusivo = { bloco ->
            travado = true
            try { bloco() } finally { travado = false }
        }, aoFalhar = { travadoNoDelay = travado })
        assertTrue(!travadoNoDelay) // aoFalhar roda fora do bloco exclusivo
        assertEquals(5, repo.enviados.size)
    }

    @Test
    fun `sem pendentes nao chama a API`() = runBlocking {
        var chamadas = 0
        val fila = FilaEnvio(RepoFalso(emptyList()), { _, _, _, _ -> chamadas++; UploadResponse() })
        assertEquals(ResultadoEnvio.Ok(0), fila.drenar("livre", "", "p1"))
        assertEquals(0, chamadas)
    }

    @Test
    fun `agrupa por pasta e so manda os pendentes da pasta pedida`() = runBlocking {
        val repo = RepoFalso(listOf("A", "B", "C", "D"))
        repo.linhas[2].pastaId = "p2"; repo.linhas[3].pastaId = "p2"
        val destinos = ArrayList<Pair<String, List<String>>>()
        val fila = FilaEnvio(repo, { _, _, pasta, lote -> destinos.add(pasta to lote); UploadResponse() })
        assertEquals(setOf("p1", "p2"), fila.gruposPendentes().map { it.pastaId }.toSet())
        fila.drenar("revisao", "m", "p2")
        assertEquals(listOf("p2" to listOf("C", "D")), destinos)
        assertEquals(listOf("A", "B"), repo.pendentes)
    }

    @Test
    fun `linha sem pasta nao sobe sozinha e recebe a pasta no proximo salvar`() = runBlocking {
        val repo = RepoFalso(listOf("A", "B"), pastaInicial = null)
        val destinos = ArrayList<Pair<String, List<String>>>()
        val fila = FilaEnvio(repo, { _, _, pasta, lote -> destinos.add(pasta to lote); UploadResponse() })
        assertTrue(fila.gruposPendentes().isEmpty()) // o worker não enxerga
        assertEquals(ResultadoEnvio.Ok(0), fila.drenar("livre", "", "p1"))
        assertTrue(destinos.isEmpty())
        fila.atribuirPasta("livre", "", "p9")
        assertEquals(ResultadoEnvio.Ok(2), fila.drenar("livre", "", "p9"))
        assertEquals(listOf("p9" to listOf("A", "B")), destinos)
        assertTrue(repo.pendentes.isEmpty())
    }

    @Test
    fun `409 sessao_nao_aceita_leituras sinaliza pasta fechada e nao marca`() = runBlocking {
        val repo = RepoFalso(epcs)
        var chamadas = 0
        val fila = FilaEnvio(repo, { _, _, _, _ -> chamadas++; throw ApiException(409, CodigoErro.SESSAO_NAO_ACEITA_LEITURAS, null, "u") })
        val r = fila.drenarComRetry("inventario", "s1", "p1", backoffMs = listOf(0L))
        assertEquals(ResultadoEnvio.PastaFechada("p1"), r)
        assertEquals(1, chamadas) // não retenta
        assertEquals(5, repo.pendentes.size)
        assertTrue(repo.enviados.isEmpty())
        // as linhas seguem pendentes e vão pra outra pasta no próximo SALVAR
        fila.atribuirPasta("inventario", "s1", "p2", setOf("p1")) // p1 marcada fechada
        assertTrue(fila.gruposPendentes().all { it.pastaId == "p2" })
    }

    @Test
    fun `salvar nao reatribui linha de pasta com envio vivo`() = runBlocking {
        val repo = RepoFalso(listOf("A", "B", "C"))
        repo.linhas[2].pastaId = null
        val fila = FilaEnvio(repo, { _, _, _, _ -> UploadResponse() })
        fila.atribuirPasta("livre", "", "p2") // p1 não está fechada
        assertEquals(listOf("p1", "p1", "p2"), repo.linhas.map { it.pastaId })
    }

    @Test
    fun `outros 409 continuam rejeicao comum`() {
        val r = classificarFalha(ApiException(409, "outra_coisa", null, "u"))
        assertTrue(r is ResultadoEnvio.Rejeitado)
    }
}
