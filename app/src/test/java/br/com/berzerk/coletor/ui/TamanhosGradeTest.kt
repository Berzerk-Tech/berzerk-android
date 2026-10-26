package br.com.berzerk.coletor.ui

import br.com.berzerk.coletor.ui.comum.TAMANHOS_GRADE
import br.com.berzerk.coletor.ui.comum.tamanhosDaGrade
import org.junit.Assert.assertEquals
import org.junit.Test

class TamanhosGradeTest {
    @Test
    fun `ordem fixa mesmo sem leitura`() {
        assertEquals(listOf("P", "M", "G", "GG", "XG", "G1", "G2", "G3", "U"), TAMANHOS_GRADE)
        assertEquals(TAMANHOS_GRADE, tamanhosDaGrade(emptyList()))
    }

    @Test
    fun `tamanho fora da grade entra depois e o ponto de interrogacao por ultimo`() {
        assertEquals(TAMANHOS_GRADE + listOf("PP", "EG", "?"), tamanhosDaGrade(listOf("?", "EG", "m", "PP")))
    }
}
