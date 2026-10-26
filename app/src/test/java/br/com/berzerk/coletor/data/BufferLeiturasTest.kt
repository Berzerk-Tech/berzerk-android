package br.com.berzerk.coletor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferLeiturasTest {
    @Test
    fun `mesma tag lida varias vezes vira uma so linha`() {
        val b = BufferLeituras()
        repeat(50) { b.registrar("AAA") }
        b.registrar("BBB")
        assertEquals(51, b.leituras)
        assertEquals(listOf("AAA", "BBB"), b.drenar())
    }

    @Test
    fun `drenar esvazia e mantem a contagem de leituras`() {
        val b = BufferLeituras()
        b.registrar("A"); b.registrar("A")
        assertEquals(listOf("A"), b.drenar())
        assertTrue(b.drenar().isEmpty())
        assertEquals(2, b.leituras)
    }

    @Test
    fun `zerar limpa tudo`() {
        val b = BufferLeituras()
        b.registrar("A")
        b.zerar()
        assertTrue(b.drenar().isEmpty())
        assertEquals(0, b.leituras)
    }
}
