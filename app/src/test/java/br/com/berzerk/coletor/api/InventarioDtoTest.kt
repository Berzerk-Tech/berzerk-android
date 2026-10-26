package br.com.berzerk.coletor.api

import br.com.berzerk.coletor.ui.comum.formatarDataHora
import br.com.berzerk.coletor.ui.comum.formatarInteiro
import br.com.berzerk.coletor.ui.telas.categoriaDe
import br.com.berzerk.coletor.ui.telas.linhaDeTamanhos
import br.com.berzerk.coletor.ui.telas.ordenarSessoes
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InventarioDtoTest {
    private val json = JsonCfg.json

    @Test
    fun `resposta de leituras da sessao traz totalSessao e continua lendo a antiga`() {
        val nova = json.decodeFromString<UploadResponse>("""{"ok":true,"coletorId":"c1","epcs":10,"novos":4,"duplicado":false,"totalSessao":123,"extra":1}""")
        assertEquals(123, nova.totalSessao)
        assertEquals("c1", nova.coletorId)
        val antiga = json.decodeFromString<UploadResponse>("""{"ok":true,"epcs":3}""")
        assertNull(antiga.totalSessao)
        assertNull(antiga.coletorId)
    }

    @Test
    fun `resposta do registro do aparelho tem epcs 0 e coletorId`() {
        val r = br.com.berzerk.coletor.upload.interpretarResposta(
            "application/json; charset=utf-8",
            """{"ok":true,"coletor":"Urovo DT50","coletorId":"11111111-1111-1111-1111-111111111111","epcs":0,"novos":0}""",
        )
        assertEquals(0, r.epcs)
        assertEquals("11111111-1111-1111-1111-111111111111", r.coletorId)
    }

    @Test
    fun `lista de sessoes`() {
        val r = json.decodeFromString<ListarSessoesResponse>(
            """{"sessoes":[{"id":"a","tipo":"contagem","nome":"Black Friday","status":"aberta","abertaEm":"2026-10-20T14:03:00.000Z","fechadaEm":null,"coletorIds":["c1"],"totalEpcs":3851,"semCampoNovo":true},
               {"id":"b","tipo":"contagem","nome":"Abril","status":"fechada","abertaEm":"2026-04-01T10:00:00.000Z","fechadaEm":"2026-04-02T10:00:00.000Z","totalEpcs":10,"totalPecas":12}]}""",
        )
        assertTrue(r.sessoes[0].aberta)
        assertEquals(3851, r.sessoes[0].pecas)
        assertEquals(12, r.sessoes[1].pecas) // totalPecas vence totalEpcs
        assertEquals(listOf("a", "b"), ordenarSessoes(r.sessoes.reversed()).map { it.id })
    }

    @Test
    fun `body de abrir sessao leva tipo e coletorIds`() {
        assertEquals("""{"tipo":"contagem","nome":"BF","coletorIds":["c1"]}""", json.encodeToString(AbrirSessaoBody("contagem", "BF", listOf("c1"))))
    }

    @Test
    fun `inventario da sessao e chips`() {
        val inv = json.decodeFromString<InventarioSessaoDto>(
            """{"sessaoId":"a","referenciaEm":"x","calculadoEm":"y",
              "totais":{"contado":3851,"jaSaiu":1,"reservado":2,"reservadoNaoLiberado":3,"disponivel":9,"modelos":4,"semCadastro":5,"reservadoSemVinculo":0},
              "produtos":[{"chave":"k","produtoId":null,"nome":"Alfa","cor":"Branca","imagemUrl":null,"categoriaLoja":{"id":"i","nome":"Oversized"},"situacaoLoja":"fica",
                "contado":1374,"jaSaiu":0,"reservado":0,"reservadoNaoLiberado":0,"disponivel":1374,
                "tamanhos":[{"tamanho":"M","sku":null,"ean13":null,"contado":299,"jaSaiu":0,"reservado":0,"reservadoNaoLiberado":0,"disponivel":299},
                            {"tamanho":"P","sku":null,"ean13":null,"contado":334,"jaSaiu":0,"reservado":0,"reservadoNaoLiberado":0,"disponivel":334},
                            {"tamanho":"G","sku":null,"ean13":null,"contado":0,"jaSaiu":0,"reservado":0,"reservadoNaoLiberado":0,"disponivel":0},
                            {"tamanho":null,"sku":null,"ean13":null,"contado":2,"jaSaiu":0,"reservado":0,"reservadoNaoLiberado":0,"disponivel":2}]},
               {"chave":"z","nome":null,"categoriaLoja":null,"contado":1,"tamanhos":[]}]}""",
        )
        assertEquals(3851, inv.totais.contado)
        assertEquals(4, inv.totais.modelos)
        val p = inv.produtos[0]
        assertEquals("Oversized", categoriaDe(p))
        assertEquals("Sem categoria", categoriaDe(inv.produtos[1]))
        assertEquals("P 334 · M 299 · ? 2", linhaDeTamanhos(p))
        // volta pelo cache (encode → decode) sem perder nada
        assertEquals(inv, json.decodeFromString<InventarioSessaoDto>(json.encodeToString(inv)))
    }

    @Test
    fun `formatos pt-BR`() {
        assertEquals("3.851", formatarInteiro(3851))
        assertEquals("20/10/2026 às 11:03", formatarDataHora("2026-10-20T14:03:00.000Z"))
        assertEquals("lixo", formatarDataHora("lixo"))
    }
}
