package br.com.berzerk.coletor.ui.telas

import br.com.berzerk.coletor.api.SubstituicaoHistoricoDto
import br.com.berzerk.coletor.api.VarianteHistoricoDto
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoricoEstoqueLojaTest {
    @Test fun resumoPulaTamanhosZeradosETrataTamanhoNulo() {
        val item = SubstituicaoHistoricoDto(variantes = listOf(
            VarianteHistoricoDto("P", 0, 36), VarianteHistoricoDto("M", 199, 261),
            VarianteHistoricoDto("G", 0, 0), VarianteHistoricoDto(null, 2, 1),
        ))
        assertEquals("P 0→36 · M 199→261 · ? 2→1", resumoVariantes(item))
    }

    @Test fun tendenciaPeloTotal() {
        assertEquals(Tendencia.SOBE, tendencia(SubstituicaoHistoricoDto(totalDe = 725, totalPara = 911)))
        assertEquals(Tendencia.DESCE, tendencia(SubstituicaoHistoricoDto(totalDe = 10, totalPara = 4)))
        assertEquals(Tendencia.IGUAL, tendencia(SubstituicaoHistoricoDto(totalDe = 5, totalPara = 5)))
    }
}

class HistoricoEstoqueLojaFiltroTest {
    private fun item(id: String, produtoId: String) =
        SubstituicaoHistoricoDto(id = id, produto = br.com.berzerk.coletor.api.ProdutoLojaDto(id = produtoId, nome = "Oversized - Alfa"))

    @Test fun filtraPorProdutoEIgnoraIdVazio() {
        val itens = listOf(item("1", "a"), item("2", "b"), item("3", "a"))
        assertEquals(listOf("1", "3"), filtrarPorProduto(itens, "a").map { it.id })
        assertEquals(itens, filtrarPorProduto(itens, null))
        assertEquals(itens, filtrarPorProduto(itens, ""))
    }

    @Test fun tendenciaPorTamanho() {
        assertEquals(Tendencia.SOBE, tendencia(0, 36))
        assertEquals(Tendencia.DESCE, tendencia(2, 1))
        assertEquals(Tendencia.IGUAL, tendencia(3, 3))
    }
}

class HistoricoEstoqueLojaResumoTest {
    private fun item(id: String, produtoId: String, em: String, vararg v: VarianteHistoricoDto, lidas: Int = 0, picking: Int = 0) =
        SubstituicaoHistoricoDto(
            id = id, em = em, produto = br.com.berzerk.coletor.api.ProdutoLojaDto(id = produtoId, nome = "Oversized - $produtoId"),
            lidas = lidas, picking = picking, variantes = v.toList(),
            totalDe = v.sumOf { it.de ?: 0 }, totalPara = v.sumOf { it.para ?: 0 },
        )

    @Test fun resumeSomandoPorTamanhoNaRegua() {
        val r = resumir(listOf(
            item("1", "a", "2026-11-04T12:00:00.000Z", VarianteHistoricoDto("M", 38, 179), VarianteHistoricoDto("P", 103, 202), lidas = 1886, picking = 382),
            item("2", "b", "2026-11-04T13:00:00.000Z", VarianteHistoricoDto("P", 0, 452), VarianteHistoricoDto(null, 0, 0), lidas = 1172),
        ))
        assertEquals(2, r.substituicoes); assertEquals(2, r.produtos)
        assertEquals(141, r.totalDe); assertEquals(833, r.totalPara)
        assertEquals(3058, r.lidas); assertEquals(382, r.picking)
        assertEquals(listOf("P", "M"), r.variantes.map { it.tamanho })
        assertEquals(listOf(103 to 654, 38 to 179), r.variantes.map { it.de to it.para })
    }

    @Test fun filtraPorDiaNoFusoDoAparelho() {
        val sp = java.time.ZoneId.of("America/Sao_Paulo")
        val itens = listOf(
            item("1", "a", "2026-11-05T01:30:00.000Z"), // 22:30 de 04/11 em SP
            item("2", "a", "2026-11-05T12:00:00.000Z"),
            item("3", "a", "lixo"),
        )
        assertEquals(listOf("1"), filtrarPorDia(itens, java.time.LocalDate.of(2026, 11, 4), sp).map { it.id })
        assertEquals(listOf("2"), filtrarPorDia(itens, java.time.LocalDate.of(2026, 11, 5), sp).map { it.id })
        assertEquals(3, filtrarPorDia(itens, null, sp).size)
    }

    @Test fun rotuloDoDiaEDelta() {
        val hoje = java.time.LocalDate.of(2026, 11, 4)
        assertEquals("Hoje · 04/11", rotuloDia(hoje, hoje))
        assertEquals("Ontem · 03/11", rotuloDia(hoje.minusDays(1), hoje))
        assertEquals("30/10", rotuloDia(hoje.minusDays(5), hoje))
        assertEquals("Todos os dias", rotuloDia(null, hoje))
        assertEquals("+1.112", delta(0, 1112)); assertEquals("−12", delta(20, 8)); assertEquals("0", delta(5, 5))
    }
}
