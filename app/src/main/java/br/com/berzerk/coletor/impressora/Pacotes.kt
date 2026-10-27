package br.com.berzerk.coletor.impressora

/** Tipos de `PrinterInfo` (`PrinterInfoType`). */
object TipoInfo {
    const val DENSITY = 1
    const val SPEED = 2
    const val LABEL_TYPE = 3
    const val AUTO_SHUTDOWN_TIME = 7
    const val PRINTER_MODEL_ID = 8
    const val SOFTWARE_VERSION = 9
    const val BATTERY_CHARGE_LEVEL = 10
    const val SERIAL_NUMBER = 11
}

/** Tipos de `Heartbeat` (`HeartbeatType`). */
object TipoHeartbeat {
    const val ADVANCED1 = 1
    const val BASIC = 2
    const val PRINTER_INFO = 3
    const val ADVANCED2 = 4
}

/** Tipos de papel (`LabelType`) que a B31 aceita. */
object TipoPapel {
    const val COM_GAP = 1
    const val MARCA_PRETA = 2
    const val CONTINUO = 3
    const val TRANSPARENTE = 5

    /** (id, rótulo) na ordem do dropdown. */
    val opcoes: List<Pair<Int, String>> = listOf(
        COM_GAP to "Padrão da B31 (com destaque / gap)",
        MARCA_PRETA to "Marca preta",
        CONTINUO to "Contínuo",
        TRANSPARENTE to "Transparente",
    )
}

/** Contagem de pixels pretos que vai no pacote de bitmap (`PixelCountResult`). */
class ContagemPixels(val total: Int, val partes: IntArray)

/** Port de `packet_generator.ts` (só o que a impressão de etiqueta usa). */
object Pacotes {
    /** u16 big-endian. */
    fun u16(n: Int): ByteArray = byteArrayOf(((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte())

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun mapeado(cmd: Int, dados: ByteArray = byteArrayOf(1)): NiimbotPacote =
        NiimbotPacote(cmd, dados, Cmd.respostas(cmd) ?: IntArray(0))

    fun conectar() = mapeado(Cmd.CONNECT)
    fun statusDaImpressora() = mapeado(Cmd.PRINTER_STATUS_DATA)
    /** A resposta de `PrinterInfo` é `0x40 + tipo`: espera exatamente ela, pra uma resposta atrasada de outro tipo não ser aceita. */
    fun infoImpressora(tipo: Int) = NiimbotPacote(Cmd.PRINTER_INFO, bytes(tipo), intArrayOf(Cmd.PRINTER_INFO + tipo))
    fun heartbeat(tipo: Int) = mapeado(Cmd.HEARTBEAT, bytes(tipo))
    fun densidade(v: Int) = mapeado(Cmd.SET_DENSITY, bytes(v))
    fun tipoPapel(v: Int) = mapeado(Cmd.SET_LABEL_TYPE, bytes(v))

    fun printStart1b() = mapeado(Cmd.PRINT_START)
    fun printStart7b(totalPaginas: Int, cor: Int = 0) =
        mapeado(Cmd.PRINT_START, u16(totalPaginas) + bytes(0, 0, 0, 0, cor))
    fun printStart9b(totalPaginas: Int, cor: Int = 0, velocidade: Int = 0, flag: Int = 0) =
        mapeado(Cmd.PRINT_START, u16(totalPaginas) + bytes(0, 0, 0, 0, cor, velocidade, flag))

    fun printClear() = mapeado(Cmd.PRINT_CLEAR)
    fun pageStart() = mapeado(Cmd.PAGE_START)
    fun pageSize4b(linhas: Int, colunas: Int) = mapeado(Cmd.SET_PAGE_SIZE, u16(linhas) + u16(colunas))
    fun pageSize6b(linhas: Int, colunas: Int, copias: Int) = mapeado(Cmd.SET_PAGE_SIZE, u16(linhas) + u16(colunas) + u16(copias))
    fun pageSize13b(
        linhas: Int, colunas: Int, copias: Int,
        cutHeight: Int = 0, cutType: Int = 0, sendAll: Int = 0, partHeight: Int = 0,
    ) = mapeado(
        Cmd.SET_PAGE_SIZE,
        u16(linhas) + u16(colunas) + u16(copias) + u16(cutHeight) + bytes(cutType, 0x00, sendAll) + u16(partHeight),
    )

    fun quantidade(n: Int) = mapeado(Cmd.PRINT_QUANTITY, u16(n))
    fun pageEnd() = mapeado(Cmd.PAGE_END)
    fun printEnd() = mapeado(Cmd.PRINT_END)
    fun printStatus() = mapeado(Cmd.PRINT_STATUS)

    /** Bitmap e linha vazia não têm resposta ([NiimbotPacote.umaVia]). */
    fun linhaVazia(pos: Int, repeticoes: Int) = mapeado(Cmd.PRINT_EMPTY_ROW, u16(pos) + bytes(repeticoes))

    fun linhaBitmap(pos: Int, repeticoes: Int, dados: ByteArray, cabecaPx: Int): NiimbotPacote {
        val c = contarPixels(dados, cabecaPx)
        return mapeado(Cmd.PRINT_BITMAP_ROW, u16(pos) + bytes(c.partes[0], c.partes[1], c.partes[2], repeticoes) + dados)
    }

    /** Só com ≤ 6 pixels pretos (a impressora desliga com mais); dados = índice u16 de cada pixel preto. */
    fun linhaBitmapIndexada(pos: Int, repeticoes: Int, dados: ByteArray, cabecaPx: Int): NiimbotPacote {
        val c = contarPixels(dados, cabecaPx)
        if (c.total > 6) throw IllegalArgumentException("mais de 6 pixels pretos (${c.total})")
        return mapeado(
            Cmd.PRINT_BITMAP_ROW_INDEXED,
            u16(pos) + bytes(c.partes[0], c.partes[1], c.partes[2], repeticoes) + indexarPixels(dados),
        )
    }

    /** `ImageEncoder.indexPixels`: índice (u16) de cada bit ligado, do bit mais significativo pro menos. */
    fun indexarPixels(dados: ByteArray): ByteArray {
        val saida = ArrayList<Byte>()
        for (pos in dados.indices) {
            val b = dados[pos].toInt() and 0xFF
            for (bit in 0 until 8) {
                if (b and (1 shl (7 - bit)) != 0) saida += u16(pos * 8 + bit).toList()
            }
        }
        return saida.toByteArray()
    }

    /**
     * `countPixelsForBitmapPacket` modo "auto": o buffer cabe em 3 pedaços de `cabecaPx/8/3` bytes → conta
     * por terço; senão `[0, baixo, alto]` do total (little-endian, como no niimbluelib).
     */
    fun contarPixels(dados: ByteArray, cabecaPx: Int): ContagemPixels {
        val chunk = cabecaPx / 8 / 3
        val dividir = dados.size <= chunk * 3
        var total = 0
        val partes = IntArray(3)
        for ((i, valor) in dados.withIndex()) {
            val pretos = Integer.bitCount(valor.toInt() and 0xFF)
            total += pretos
            if (dividir) {
                val idx = if (chunk > 0) i / chunk else 0
                if (idx <= 2) partes[idx] += pretos // estouro de 255 não é tratado nem no original
            }
        }
        if (dividir) return ContagemPixels(total, partes)
        return ContagemPixels(total, intArrayOf(0, total and 0xFF, (total shr 8) and 0xFF))
    }
}
