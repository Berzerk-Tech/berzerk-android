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
