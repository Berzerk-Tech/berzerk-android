package br.com.berzerk.coletor.impressora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun hex(s: String): ByteArray = s.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

class NiimbotProtocoloTest {
    @Test
    fun `conectar tem o prefixo 03 e densidade nao`() {
        assertArrayEquals(hex("03 55 55 c1 01 01 c1 aa aa"), Pacotes.conectar().bytes())
        assertArrayEquals(hex("55 55 21 01 03 23 aa aa"), Pacotes.densidade(3).bytes())
    }

    @Test
    fun `deBytes le um pacote valido e recusa checksum, cabeca e cauda ruins`() {
        // PrintStatus: pagina 1, 100%, 100%: cmd b3, len 4, dados 00 01 64 64; checksum = b6
        val p = NiimbotPacote.deBytes(hex("55 55 b3 04 00 01 64 64 b6 aa aa"))
        assertEquals(0xB3, p.cmd)
        assertArrayEquals(hex("00 01 64 64"), p.dados)
        for (ruim in listOf("55 55 b3 04 00 01 64 64 b7 aa aa", "54 55 b3 04 00 01 64 64 b6 aa aa", "55 55 b3 04 00 01 64 64 b6 aa ab")) {
            try { NiimbotPacote.deBytes(hex(ruim)); fail("devia lançar: $ruim") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun `dois pacotes colados num buffer saem os dois`() {
        val r = RemontadorPacotes()
        val a = hex("55 55 b3 04 00 01 64 64 b6 aa aa")
        val b = hex("55 55 c2 01 03 c0 aa aa")
        val saida = r.alimentar(a + b)
        assertEquals(listOf(0xB3, 0xC2), saida.map { it.cmd })
    }

    @Test
    fun `pacote partido em dois pedacos so sai quando completa`() {
        val r = RemontadorPacotes()
        val a = hex("55 55 b3 04 00 01 64 64 b6 aa aa")
        assertTrue(r.alimentar(a.copyOfRange(0, 6)).isEmpty())
        val saida = r.alimentar(a.copyOfRange(6, a.size))
        assertEquals(1, saida.size)
        assertArrayEquals(hex("00 01 64 64"), saida[0].dados)
        // e o resto de um pacote seguinte fica guardado
        val b = hex("55 55 c2 01 03 c0 aa aa")
        assertEquals(1, r.alimentar(a + b.copyOfRange(0, 3)).size)
        assertEquals(listOf(0xC2), r.alimentar(b.copyOfRange(3, b.size)).map { it.cmd })
    }

    @Test
    fun `lixo sem cabeca e descartado`() {
        val r = RemontadorPacotes()
        assertTrue(r.alimentar(hex("01 02 03")).isEmpty())
        assertEquals(1, r.alimentar(hex("55 55 c2 01 03 c0 aa aa")).size)
    }

    @Test
    fun `bitmap de 16 por 3 vira vazia, indexada e bitmap`() {
        // linha 0 branca; linha 1 com 2 pretos (x=1 e x=9); linha 2 toda preta
        val img = CodificadorImagem.codificar(16, 3) { x, y -> (y == 1 && (x == 1 || x == 9)) || y == 2 }
        assertEquals(16, img.colunas)
        assertEquals(3, img.linhas)
        assertEquals(listOf(TipoLinha.VAZIA, TipoLinha.PIXELS, TipoLinha.PIXELS), img.rows.map { it.tipo })
        assertArrayEquals(hex("40 40"), img.rows[1].dados)
        assertArrayEquals(hex("ff ff"), img.rows[2].dados)

        val ps = CodificadorImagem.pacotesDaImagem(img, 600)
        assertEquals(listOf(Cmd.PRINT_EMPTY_ROW, Cmd.PRINT_BITMAP_ROW_INDEXED, Cmd.PRINT_BITMAP_ROW), ps.map { it.cmd })
        assertArrayEquals(hex("00 00 01"), ps[0].dados)
        // indexada: pos 1, terços 2/0/0 (chunk = 25 bytes, tudo cai no primeiro), repete 1, índices 1 e 9 (u16)
        assertArrayEquals(hex("00 01 02 00 00 01 00 01 00 09"), ps[1].dados)
        // bitmap: pos 2, 16 pretos no primeiro terço, repete 1, os 2 bytes
        assertArrayEquals(hex("00 02 10 00 00 01 ff ff"), ps[2].dados)
        assertTrue(ps.all { it.umaVia })
    }

    @Test
    fun `linhas iguais seguidas viram repeticoes e param em 255`() {
        val img = CodificadorImagem.codificar(8, 300) { _, _ -> false }
        assertEquals(listOf(255, 45), img.rows.map { it.repeticoes })
        assertEquals(listOf(0, 255), img.rows.map { it.numero })
    }

    @Test
    fun `contagem por tercos para cabeca de 600 px`() {
        // 75 bytes = 600 px; chunk = 25 bytes. 1 bit no byte 0, 8 no byte 25, 2 no byte 74.
        val dados = ByteArray(75)
        dados[0] = 0x01
        dados[25] = 0xFF.toByte()
        dados[74] = 0x03
        val c = Pacotes.contarPixels(dados, 600)
        assertEquals(11, c.total)
        assertArrayEquals(intArrayOf(1, 8, 2), c.partes)
    }

    @Test
    fun `buffer maior que tres chunks usa o total little endian`() {
        val dados = ByteArray(76) { 0xFF.toByte() } // 608 pretos = 0x0260
        val c = Pacotes.contarPixels(dados, 600)
        assertEquals(608, c.total)
        assertArrayEquals(intArrayOf(0, 0x60, 0x02), c.partes)
    }

    @Test
    fun `pacotes de pagina e inicio batem com o niimbluelib`() {
        assertArrayEquals(hex("55 55 13 0d 03 20 02 58 00 02 00 00 00 00 00 00 00 65 aa aa"), Pacotes.pageSize13b(800, 600, 2).bytes().let { it })
        assertArrayEquals(hex("00 02 00 00 00 00 00 01 00"), Pacotes.printStart9b(2, 0, 1).dados)
        assertArrayEquals(hex("00 01 00 00 00 00 00"), Pacotes.printStart7b(1).dados)
    }

    @Test
    fun `dimensoes em pixels`() {
        assertEquals(600 to 800, dimensoesPx(80, 100))
        assertEquals(400 to 400, dimensoesPx(50, 50))
        assertEquals(600 to 1200, dimensoesPx(100, 300))
    }
}
