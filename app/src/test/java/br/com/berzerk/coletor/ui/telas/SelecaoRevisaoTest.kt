package br.com.berzerk.coletor.ui.telas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelecaoRevisaoTest {
    @Test fun idaEVoltaPreservaOrdemEChaves() {
        val mapa = linkedMapOf("multi:b" to listOf("x|y", "z"), "multi:a" to listOf("k"))
        val volta = SelecaoRevisao.desserializar(SelecaoRevisao.serializar(mapa))
        assertEquals(mapa, volta)
        assertEquals(listOf("multi:b", "multi:a"), volta.keys.toList())
    }

    @Test fun nuloVazioOuCorrompidoViraMapaVazio() {
        assertTrue(SelecaoRevisao.desserializar(null).isEmpty())
        assertTrue(SelecaoRevisao.desserializar("").isEmpty())
        assertTrue(SelecaoRevisao.desserializar("{não é json").isEmpty())
    }

    @Test fun limiteDescartaOsMaisAntigos() {
        val mapa = (1..60).associate { "multi:$it" to listOf("a") }
        val l = SelecaoRevisao.limitar(mapa)
        assertEquals(SelecaoRevisao.MAX_CONJUNTOS, l.size)
        assertTrue("multi:10" !in l && "multi:11" in l && "multi:60" in l)
    }
}
