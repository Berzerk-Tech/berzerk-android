package br.com.berzerk.coletor.ui.telas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlvoLeituraTest {
    @Test fun umaChaveUsaAPropriaChave() {
        assertEquals("a", AlvoLeitura.Modelos.de(listOf("a")).referencia)
    }

    @Test fun ordemERepeticaoNaoMudamAReferencia() {
        val x = AlvoLeitura.Modelos.de(listOf("b", "a"))
        val y = AlvoLeitura.Modelos.de(listOf("a", "b", "a"))
        assertEquals(listOf("a", "b"), x.chaves)
        assertEquals(x.chaves, y.chaves)
        assertEquals(x.referencia, y.referencia)
    }

    @Test fun conjuntosDiferentesTemReferenciasDiferentes() {
        assertNotEquals(AlvoLeitura.Modelos.de(listOf("a", "b")).referencia, AlvoLeitura.Modelos.de(listOf("a", "c")).referencia)
    }

    @Test fun referenciaDeVariosTemPrefixoETamanho() {
        val r = AlvoLeitura.Modelos.de(listOf("a", "b")).referencia
        assertTrue(r.startsWith("multi:"))
        assertEquals(22, r.length)
    }
}
