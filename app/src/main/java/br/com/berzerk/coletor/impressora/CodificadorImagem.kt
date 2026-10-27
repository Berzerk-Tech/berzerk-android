package br.com.berzerk.coletor.impressora

import android.graphics.Bitmap
import android.graphics.Color

enum class TipoLinha { VAZIA, PIXELS }

/** Linha (ou `repeticoes` linhas iguais seguidas) já em bits, MSB = pixel mais à esquerda. */
class LinhaImagem(val tipo: TipoLinha, val numero: Int, var repeticoes: Int, val pretos: Int, val dados: ByteArray?)

class ImagemCodificada(val linhas: Int, val colunas: Int, val rows: List<LinhaImagem>)

/** Port de `ImageEncoder.encode` com `printDirection = "top"` (sem rotação) do niimbluelib. */
object CodificadorImagem {
    /** Um byte só pra repetição no pacote: o niimbluelib estoura (módulo 256), aqui a sequência é quebrada. */
    private const val MAX_REPETICOES = 255

    /** Pixel preto = luminância < 128 (o bitmap da etiqueta já vem só preto e branco). */
    fun codificar(bitmap: Bitmap): ImagemCodificada {
        val l = bitmap.width
        val a = bitmap.height
        val px = IntArray(l * a)
        bitmap.getPixels(px, 0, l, 0, 0, l, a)
        return codificar(l, a) { x, y ->
            val c = px[y * l + x]
            (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) < 128
        }
    }

    /** Núcleo sem Android (testável): [preto] diz se o pixel (x, y) é preto. */
    fun codificar(largura: Int, altura: Int, preto: (x: Int, y: Int) -> Boolean): ImagemCodificada {
        val colunas = (largura + 7) / 8 * 8
        val linhas = ArrayList<LinhaImagem>()
        for (y in 0 until altura) {
            val bytes = ByteArray(colunas / 8)
            var pretos = 0
            for (x in 0 until largura) {
                if (preto(x, y)) {
                    bytes[x / 8] = (bytes[x / 8].toInt() or (1 shl (7 - x % 8))).toByte()
                    pretos++
                }
            }
            val vazia = pretos == 0
            val ultima = linhas.lastOrNull()
            val igual = ultima != null && ultima.repeticoes < MAX_REPETICOES &&
                ultima.tipo == (if (vazia) TipoLinha.VAZIA else TipoLinha.PIXELS) &&
                (vazia || ultima.dados!!.contentEquals(bytes))
            if (igual) ultima!!.repeticoes++
            else linhas += LinhaImagem(if (vazia) TipoLinha.VAZIA else TipoLinha.PIXELS, y, 1, pretos, if (vazia) null else bytes)
        }
        return ImagemCodificada(altura, colunas, linhas)
    }

    /** `writeImageDataSingleColor` (sem linha `check`). */
    fun pacotesDaImagem(img: ImagemCodificada, cabecaPx: Int): List<NiimbotPacote> = img.rows.map { l ->
        when {
            l.tipo == TipoLinha.PIXELS && l.pretos <= 6 -> Pacotes.linhaBitmapIndexada(l.numero, l.repeticoes, l.dados!!, cabecaPx)
            l.tipo == TipoLinha.PIXELS -> Pacotes.linhaBitmap(l.numero, l.repeticoes, l.dados!!, cabecaPx)
            else -> Pacotes.linhaVazia(l.numero, l.repeticoes)
        }
    }
}
