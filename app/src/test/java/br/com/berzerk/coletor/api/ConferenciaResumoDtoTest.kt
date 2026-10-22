package br.com.berzerk.coletor.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regressão da v0.3.3/0.3.4: `outrosLotes`/`outrosProdutos` vêm como OBJETOS do Nexus
 * (`conferenciaLoteOutroLoteDtoSchema`); o app esperava `List<String>` e o parse quebrava
 * sempre que havia etiqueta de outro lote na sessão — o lote nunca carregava.
 */
class ConferenciaResumoDtoTest {
    @Test
    fun `parse do resumo com outros lotes e produtos como objetos`() {
        val json = """
        {"sessao":{"id":"s1","tipo":"conferencia_lote"},
         "lotes":[{"loteId":"11111111-1111-1111-1111-111111111111","codigo":"2709","produto":"Calça Moletom","cor":"marinho",
           "status":"pendente","esperadoPorTamanho":{"P":35,"M":105},"lidoPorTamanho":{"P":34},"diferencaPorTamanho":{"P":-1,"M":-105},
           "esperadoTotal":140,"lidoTotal":34,"diverge":true,"divergenciaPct":75.7,"toleranciaPct":5,
           "epcsOutroLote":[{"epc":"E2","loteId":"22222222-2222-2222-2222-222222222222","loteCodigo":"2678"}],
           "totalEpcsOutroLote":1,
           "outrosLotes":[{"loteId":"22222222-2222-2222-2222-222222222222","codigo":"2678","produto":"Calça Rux","cor":"branco","total":1}],
           "epcsDesconhecidos":["E3"],"totalEpcsDesconhecidos":1,
           "outrosProdutos":[{"sku":"X","ean13":"7897966649231","nome":"Dragão Azul","total":1}],
           "entradaEstoque":null,"leadTimeGalpaoMin":null,"imagemUrl":null,
           "variantes":[{"tamanho":"P","ean13":"7897966649213","sku":null}]}],
         "totais":{"esperado":140,"etiquetado":0,"lido":34,"lotesConfirmados":0,"lotesPendentes":1}}
        """.trimIndent()
        val r = JsonCfg.json.decodeFromString<ConferenciaResumoDto>(json)
        val lote = r.lotes.single()
        assertEquals("2709", lote.codigo)
        assertEquals("2678", lote.outrosLotes.single().codigo)
        assertEquals("Dragão Azul", lote.outrosProdutos.single().nome)
        assertEquals("7897966649213", lote.variantes.single().ean13)
        assertEquals(34, lote.lidoPorTamanho["P"])
    }
}
