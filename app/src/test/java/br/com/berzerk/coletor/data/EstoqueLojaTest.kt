package br.com.berzerk.coletor.data

import br.com.berzerk.coletor.api.EstoqueLojaDto
import br.com.berzerk.coletor.api.TotaisLojaDto
import br.com.berzerk.coletor.api.VarianteLojaDto
import org.junit.Assert.assertEquals
import org.junit.Test

class EstoqueLojaTest {
    private val resumo = ModeloResumo(
        chave = "oversized|chains", categoria = "Oversized", modelo = "Chains",
        eans = listOf("7890075929917", "7890075929924", "7890075929931"),
        tamanhos = listOf("M", "G"),
        eanPorTamanho = mapOf("M" to listOf("7890075929917", "7890075929924"), "G" to listOf("7890075929931")),
        totalEpcs = 10, imagemUrl = null,
    )

    @Test fun consultaLevaTodosOsEansComZeroQuandoNaoLido() {
        val b = montarConsultaLoja(resumo, mapOf("7890075929917" to 274, "7890075929931" to 3))
        assertEquals(5, b.margemPct)
        assertEquals(listOf("7890075929917" to 274, "7890075929924" to 0, "7890075929931" to 3), b.lidas.map { it.ean to it.qtd })
    }

    @Test fun substituirSoVariantesVinculadas() {
        val dto = EstoqueLojaDto(
            margemPct = 5,
            variantes = listOf(
                VarianteLojaDto(varianteId = "a", tamanho = "M", estoqueLoja = 10, novo = 260),
                VarianteLojaDto(varianteId = "b", tamanho = "G", estoqueLoja = null, novo = 5),
                VarianteLojaDto(varianteId = "c", tamanho = "GG", estoqueLoja = 0, novo = 7),
            ),
            totais = TotaisLojaDto(lidas = 380, picking = 4, novo = 272),
        )
        val b = montarSubstituirLoja(dto, "dev123")
        assertEquals(listOf(Triple("a", 260, 10), Triple("c", 7, 0)), b.quantidades.map { Triple(it.varianteId, it.quantidade, it.de) })
        assertEquals(380, b.origem.lidas)
        assertEquals(4, b.origem.picking)
        assertEquals("dev123", b.origem.dispositivo)
    }
}
