package br.com.berzerk.coletor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelosTest {
    @Test fun tresSegmentos() {
        assertEquals("Oversized" to "Alfa", parseNomeItag("Oversized - Alfa - G", "G"))
    }

    @Test fun doisSegmentosMantemOModelo() {
        assertEquals("Cropped" to "Beta", parseNomeItag("Cropped - Beta", "M"))
    }

    @Test fun quatroSegmentosComTamanhoNoFim() {
        assertEquals("Oversized" to "A Gigante - Sereia Branca", parseNomeItag("Oversized - A Gigante - Sereia Branca - GG", "gg"))
        // tamanho do cadastro diferente, mas último segmento está na régua
        assertEquals("Oversized" to "Alfa", parseNomeItag("Oversized - Alfa - XG", null))
    }

    @Test fun tamanhoNoMeio() {
        assertEquals("Oversized" to "A Gigante Sereia - Branca", parseNomeItag("Oversized - A Gigante Sereia - G - Branca", "G"))
        // sem o tamanho do cadastro: a régua resolve
        assertEquals("Oversized" to "A Gigante Sereia - Branca", parseNomeItag("Oversized - A Gigante Sereia - GG - Branca", null))
    }

    @Test fun tamanhoNoFimComCor() {
        assertEquals("Oversized" to "A Gigante Sereia - Branca", parseNomeItag("Oversized - A Gigante Sereia - Branca - M", "M"))
    }

    @Test fun semTamanhoNoNome() {
        assertEquals("Oversized" to "A Gigante Sereia - Branca", parseNomeItag("Oversized - A Gigante Sereia - Branca", "G"))
    }

    @Test fun soTamanhoRestandoViraSemNome() {
        assertEquals("Cropped" to "Sem nome", parseNomeItag("Cropped - M", "M"))
    }

    @Test fun semSeparador() {
        assertEquals("Outros" to "Camiseta lisa", parseNomeItag("  Camiseta lisa ", "P"))
    }

    @Test fun nuloOuVazio() {
        assertEquals("Outros" to "Sem nome", parseNomeItag(null, null))
        assertEquals("Outros" to "Sem nome", parseNomeItag("  ", "P"))
    }

    @Test fun categoriaNormalizada() {
        assertEquals("Poliamida Tech" to "Zeta", parseNomeItag("POLIAMIDA tech - Zeta - P", "P"))
    }

    private fun linha(cat: String, mod: String, tam: String, ean: String, qtd: Int = 1) = ModeloEntity(cat, mod, tam, ean, qtd)

    @Test fun montarResumosEansTamanhosEImagem() {
        val linhas = listOf(
            linha("Oversized", "Alfa", "G", "789003", 5),
            linha("Oversized", "Alfa", "P", "789001", 2),
            linha("Oversized", "Alfa", "P", "789011", 1),
            linha("Oversized", "Alfa", "M", "789002", 3),
            linha("Cropped", "Beta", "", "", 4),
        )
        val r = montarResumos(linhas, mapOf("789002" to "http://img/2", "789003" to "http://img/3"))
        assertEquals(listOf("Alfa", "Beta"), r.map { it.modelo })
        val alfa = r.first()
        assertEquals("oversized|alfa", alfa.chave)
        assertEquals(listOf("P", "M", "G"), alfa.tamanhos)
        assertEquals(listOf("789001", "789011", "789002", "789003"), alfa.eans)
        assertEquals(listOf("789001", "789011"), alfa.eanPorTamanho["P"])
        assertEquals(11, alfa.totalEpcs)
        assertEquals("http://img/2", alfa.imagemUrl)
        val beta = r.last()
        assertEquals(emptyList<String>(), beta.tamanhos)
        assertNull(beta.imagemUrl)
    }
}
