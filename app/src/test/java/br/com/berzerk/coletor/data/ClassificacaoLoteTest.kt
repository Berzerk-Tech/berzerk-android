package br.com.berzerk.coletor.data

import br.com.berzerk.coletor.api.VarianteDto
import br.com.berzerk.coletor.ui.comum.TipoLeitura
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassificacaoLoteTest {
    private fun epc(ean: String?, nome: String? = "Oversized - Alfa - G") =
        EpcEntity("E1", ean, nome, "G", null, null, "2026-01-01")

    private val variantes = listOf(VarianteDto("P", "7890000000013"), VarianteDto("G", "7890000000020"))

    @Test fun semCadastro() {
        assertEquals(TipoLeitura.SEM_CADASTRO, classificarParaLote(null, variantes, "Alfa"))
        assertEquals(TipoLeitura.SEM_CADASTRO, classificarParaLote(epc("1", nome = " "), variantes, "Alfa"))
    }

    @Test fun eanExatoOuPrefixoDe12() {
        assertEquals(TipoLeitura.DO_ALVO, classificarParaLote(epc("7890000000020"), variantes, null))
        // mesmo prefixo de 12, 13º dígito diferente
        assertEquals(TipoLeitura.DO_ALVO, classificarParaLote(epc("7890000000028"), variantes, null))
        assertEquals(TipoLeitura.OUTRO, classificarParaLote(epc("1110000000000"), variantes, null))
    }

    @Test fun apiVelhaUsaNomeDoProduto() {
        assertEquals(TipoLeitura.DO_ALVO, classificarParaLote(epc(null, "Oversized - Ação - G"), emptyList(), "ACAO"))
        assertEquals(TipoLeitura.OUTRO, classificarParaLote(epc(null), emptyList(), "Beta"))
        assertEquals(TipoLeitura.OUTRO, classificarParaLote(epc(null), emptyList(), null))
    }
}
