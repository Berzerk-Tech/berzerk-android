package br.com.berzerk.coletor.upload

import br.com.berzerk.coletor.api.ApiException
import br.com.berzerk.coletor.api.UploadResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class RepoFalso(epcs: List<String>) : RepoLeituras {
    val pendentes = ArrayList(epcs)
    val enviados = ArrayList<String>()
    var marcadoEm: Long? = null

    override suspend fun pendentes(origem: String, referencia: String, limite: Int) = pendentes.take(limite)

    override suspend fun marcarEnviadas(origem: String, referencia: String, epcs: List<String>, em: Long) {
        pendentes.removeAll(epcs.toSet())
        enviados.addAll(epcs)
        marcadoEm = em
    }
}

class FilaEnvioTest {
    private val epcs = (1..5).map { "E$it" }

    @Test
    fun `2xx marca enviadoEm e manda em blocos`() = runBlocking {
        val repo = RepoFalso(epcs)
        val blocos = ArrayList<List<String>>()
        val fila = FilaEnvio(repo, { _, _, lote -> blocos.add(lote); UploadResponse(epcs = lote.size) }, { 42L }, bloco = 2)
        val r = fila.drenar("inventario", "s1")
        assertEquals(ResultadoEnvio.Ok(5), r)
        assertEquals(listOf(listOf("E1", "E2"), listOf("E3", "E4"), listOf("E5")), blocos)
        assertTrue(repo.pendentes.isEmpty())
        assertEquals(42L, repo.marcadoEm)
    }

    @Test
    fun `5xx nao marca nada e retenta ate subir`() = runBlocking {
        val repo = RepoFalso(epcs)
        var chamadas = 0
        val fila = FilaEnvio(repo, { _, _, _ ->
            chamadas++
            if (chamadas < 3) throw ApiException(503, null, null, "u")
            UploadResponse()
        })
        val falhas = ArrayList<String>()
        val r = fila.drenarComRetry("revisao", "m", backoffMs = listOf(0L), aoFalhar = { falhas.add(it.motivo) })
        assertEquals(ResultadoEnvio.Ok(5), r)
        assertEquals(3, chamadas)
        assertEquals(2, falhas.size)
        assertEquals(5, repo.enviados.size)
    }

    @Test
    fun `erro de rede fica pendente`() = runBlocking {
        val repo = RepoFalso(epcs)
        val fila = FilaEnvio(repo, { _, _, _ -> throw IOException("sem rede") })
        val r = fila.drenarComRetry("livre", "", backoffMs = listOf(0L), maxTentativas = 3)
        assertTrue(r is ResultadoEnvio.SemRede)
        assertEquals(5, repo.pendentes.size)
        assertTrue(repo.enviados.isEmpty())
    }

    @Test
    fun `4xx para sem retry e sem marcar`() = runBlocking {
        val repo = RepoFalso(epcs)
        var chamadas = 0
        val fila = FilaEnvio(repo, { _, _, _ -> chamadas++; throw ApiException(403, "coletor_revogado", null, "u") })
        val r = fila.drenarComRetry("inventario", "s1", backoffMs = listOf(0L))
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
    fun `envio bloqueado vira rejeitado e nao marca`() = runBlocking {
        val repo = RepoFalso(epcs)
        val fila = FilaEnvio(repo, { _, _, _ -> throw EnvioBloqueadoException("Inventário fechado no Nexus") })
        val r = fila.drenar("inventario", "s1")
        assertEquals(ResultadoEnvio.Rejeitado(0, "Inventário fechado no Nexus"), r)
        assertEquals(5, repo.pendentes.size)
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
        val fila = FilaEnvio(repo, { _, _, _ ->
            chamadas++
            if (chamadas == 1) throw IOException("rede")
            UploadResponse()
        })
        fila.drenarComRetry("livre", "", backoffMs = listOf(1L), exclusivo = { bloco ->
            travado = true
            try { bloco() } finally { travado = false }
        }, aoFalhar = { travadoNoDelay = travado })
        assertTrue(!travadoNoDelay) // aoFalhar roda fora do bloco exclusivo
        assertEquals(5, repo.enviados.size)
    }

    @Test
    fun `sem pendentes nao chama a API`() = runBlocking {
        var chamadas = 0
        val fila = FilaEnvio(RepoFalso(emptyList()), { _, _, _ -> chamadas++; UploadResponse() })
        assertEquals(ResultadoEnvio.Ok(0), fila.drenar("livre", ""))
        assertEquals(0, chamadas)
    }
}
