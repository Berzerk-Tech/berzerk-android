package br.com.berzerk.coletor.data

import br.com.berzerk.coletor.api.ApiException
import br.com.berzerk.coletor.api.CodigoErro
import br.com.berzerk.coletor.api.RfidSessaoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class PastasTest {
    private fun s(id: String, aberta: Boolean, em: String) =
        RfidSessaoDto(id = id, tipo = "contagem", nome = "Pasta $id", status = if (aberta) "aberta" else "fechada", abertaEm = em)

    private val todas = listOf(
        s("a", true, "2026-10-20T10:00:00Z"),
        s("b", true, "2026-10-26T10:00:00Z"),
        s("c", false, "2026-10-27T10:00:00Z"),
        s("d", true, "2026-10-22T10:00:00Z"),
    )

    @Test
    fun `seletor so tem abertas por abertaEm desc`() {
        assertEquals(listOf("b", "d", "a"), Pastas.paraSalvar(todas, null).map { it.id })
    }

    @Test
    fun `pasta da rota vem no topo`() {
        assertEquals(listOf("a", "b", "d"), Pastas.paraSalvar(todas, "a").map { it.id })
    }

    @Test
    fun `pre selecao fechada ou ausente e ignorada`() {
        assertEquals(listOf("b", "d", "a"), Pastas.paraSalvar(todas, "c").map { it.id })
        assertEquals(listOf("b", "d", "a"), Pastas.paraSalvar(todas, "zzz").map { it.id })
    }

    @Test
    fun `escolha inicial prefere a atual, depois a da rota, depois a primeira`() {
        val lista = Pastas.paraSalvar(todas, "a")
        assertEquals("d", Pastas.escolhaInicial(lista, "a", atual = "d"))
        assertEquals("a", Pastas.escolhaInicial(lista, "a", atual = "fechada-agora"))
        assertEquals("a", Pastas.escolhaInicial(lista, "a"))
        assertEquals("a", Pastas.escolhaInicial(lista, "nao-existe")) // primeira da lista
        assertNull(Pastas.escolhaInicial(emptyList(), "a"))
    }

    @Test
    fun `409 ao criar vira nome em uso ou uma por vez`() {
        assertEquals(ResultadoCriar.NomeEmUso, Pastas.interpretarErroCriar(ApiException(409, CodigoErro.PASTA_NOME_EM_USO, null, "u")))
        val r = Pastas.interpretarErroCriar(ApiException(409, CodigoErro.SESSAO_ABERTA_EXISTENTE, """{"error":"sessao_aberta_existente","sessaoId":"xyz"}""", "u"))
        assertEquals(ResultadoCriar.JaHaUma("xyz"), r)
        assertTrue(Pastas.interpretarErroCriar(ApiException(500, null, null, "u")) is ResultadoCriar.Erro)
    }

    @Test
    fun `migration 4 para 5 adiciona pastaId`() {
        val sqls = ArrayList<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java)) { _, m, args ->
            if (m.name == "execSQL") sqls.add(args[0] as String)
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        val m = ColetorDb.MIGRACAO_4_5
        assertEquals(4, m.startVersion)
        assertEquals(5, m.endVersion)
        m.migrate(db)
        assertEquals(2, sqls.size)
        assertEquals("ALTER TABLE leituras ADD COLUMN pastaId TEXT", sqls[0])
        // inventário legado já tem destino; revisão/livre esperam o SALVAR
        assertEquals("UPDATE leituras SET pastaId = referencia WHERE origem = 'inventario' AND referencia <> '' AND enviadoEm IS NULL", sqls[1])
    }
}
