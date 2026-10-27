package br.com.berzerk.coletor.impressora

/** Falha de impressão/Bluetooth com mensagem já pronta pra mostrar ao operador (pt-BR). */
class ImpressoraException(mensagem: String, causa: Throwable? = null) : Exception(mensagem, causa)

/** Ids de comando do protocolo Niimbot (`commands_full.ts` do niimbluelib). TX = app → impressora, IN_* = resposta. */
object Cmd {
    const val CONNECT = 0xC1
    const val PRINTER_STATUS_DATA = 0xA5
    const val PRINTER_INFO = 0x40
    const val HEARTBEAT = 0xDC
    const val SET_DENSITY = 0x21
    const val SET_LABEL_TYPE = 0x23
    const val PRINT_START = 0x01
    const val PRINT_CLEAR = 0x20
    const val PAGE_START = 0x03
    const val SET_PAGE_SIZE = 0x13
    const val PRINT_QUANTITY = 0x15
    const val PRINT_BITMAP_ROW = 0x85
    const val PRINT_BITMAP_ROW_INDEXED = 0x83
    const val PRINT_EMPTY_ROW = 0x84
    const val PAGE_END = 0xE3
    const val PRINT_END = 0xF3
    const val PRINT_STATUS = 0xA3

    const val IN_NOT_SUPPORTED = 0x00
    const val IN_CONNECT = 0xC2
    const val IN_PRINTER_STATUS_DATA = 0xB5
    const val IN_HEARTBEAT_ADVANCED1 = 0xDD
    const val IN_HEARTBEAT_BASIC = 0xDE
    const val IN_HEARTBEAT_PRINTER_INFO = 0xDF
    const val IN_HEARTBEAT_ADVANCED2 = 0xD9
    const val IN_SET_DENSITY = 0x31
    const val IN_SET_LABEL_TYPE = 0x33
    const val IN_PRINT_START = 0x02
    const val IN_PRINT_CLEAR = 0x30
    const val IN_PAGE_START = 0x04
    const val IN_SET_PAGE_SIZE = 0x14
    const val IN_PRINT_QUANTITY = 0x16
    const val IN_PAGE_END = 0xE4
    const val IN_PRINT_END = 0xF4
    const val IN_PRINT_STATUS = 0xB3
    const val IN_PRINT_ERROR = 0xDB

    /** `PrinterInfo` responde com um id diferente por tipo (0x41..0x4F); todos valem. */
    val IN_PRINTER_INFO: IntArray = IntArray(0x4F - 0x41 + 1) { 0x41 + it }

    /** Respostas esperadas por comando (`commandsMap`); `null` = uma via (sem resposta). */
    fun respostas(cmd: Int): IntArray? = when (cmd) {
        PRINT_BITMAP_ROW, PRINT_BITMAP_ROW_INDEXED, PRINT_EMPTY_ROW -> null
        CONNECT -> intArrayOf(IN_CONNECT)
        PRINTER_STATUS_DATA -> intArrayOf(IN_PRINTER_STATUS_DATA)
        PRINTER_INFO -> IN_PRINTER_INFO
        HEARTBEAT -> intArrayOf(IN_HEARTBEAT_BASIC, IN_HEARTBEAT_PRINTER_INFO, IN_HEARTBEAT_ADVANCED1, IN_HEARTBEAT_ADVANCED2)
        SET_DENSITY -> intArrayOf(IN_SET_DENSITY)
        SET_LABEL_TYPE -> intArrayOf(IN_SET_LABEL_TYPE)
        PRINT_START -> intArrayOf(IN_PRINT_START)
        PRINT_CLEAR -> intArrayOf(IN_PRINT_CLEAR)
        PAGE_START -> intArrayOf(IN_PAGE_START)
        SET_PAGE_SIZE -> intArrayOf(IN_SET_PAGE_SIZE)
        PRINT_QUANTITY -> intArrayOf(IN_PRINT_QUANTITY)
        PAGE_END -> intArrayOf(IN_PAGE_END)
        PRINT_END -> intArrayOf(IN_PRINT_END)
        PRINT_STATUS -> intArrayOf(IN_PRINT_STATUS)
        else -> throw IllegalArgumentException("comando sem mapa de resposta: $cmd")
    }
}

/** Pacote Niimbot: `55 55 cmd len dados checksum aa aa` (`packet.ts`). */
class NiimbotPacote(val cmd: Int, val dados: ByteArray = byteArrayOf(1), val respostas: IntArray = IntArray(0)) {
    /** Sem resposta depois do envio. */
    val umaVia get() = respostas.isEmpty()

    /** `cmd xor len xor cada byte de dados`. */
    val checksum: Int
        get() {
            var c = cmd xor dados.size
            for (b in dados) c = c xor (b.toInt() and 0xFF)
            return c and 0xFF
        }

    fun bytes(): ByteArray {
        require(dados.size <= 255) { "dados do pacote > 255 bytes" }
        val corpo = ByteArray(2 + 1 + 1 + dados.size + 1 + 2)
        corpo[0] = 0x55; corpo[1] = 0x55
        corpo[2] = cmd.toByte()
        corpo[3] = dados.size.toByte()
        dados.copyInto(corpo, 4)
        corpo[4 + dados.size] = checksum.toByte()
        corpo[5 + dados.size] = 0xAA.toByte(); corpo[6 + dados.size] = 0xAA.toByte()
        // Connect é o único que vai prefixado com 0x03 (igual ao niimbluelib).
        return if (cmd == Cmd.CONNECT) byteArrayOf(0x03) + corpo else corpo
    }

    companion object {
        private const val MIN = 2 + 1 + 1 + 1 + 2

        /** Valida cabeça, tamanho, cauda e checksum; lança [IllegalArgumentException] se inválido. */
        fun deBytes(b: ByteArray): NiimbotPacote {
            require(b.size >= MIN) { "pacote pequeno demais (${b.size})" }
            require(b[0] == 0x55.toByte() && b[1] == 0x55.toByte()) { "cabeça inválida" }
            val cmd = b[2].toInt() and 0xFF
            val len = b[3].toInt() and 0xFF
            require(b.size == MIN + len) { "tamanho inválido (${b.size} != ${MIN + len})" }
            val dados = b.copyOfRange(4, 4 + len)
            val checksum = b[4 + len].toInt() and 0xFF
            require(b[5 + len] == 0xAA.toByte() && b[6 + len] == 0xAA.toByte()) { "cauda inválida" }
            val p = NiimbotPacote(cmd, dados)
            require(p.checksum == checksum) { "checksum inválido (${p.checksum} != $checksum)" }
            return p
        }
    }
}

/**
 * Remonta pacotes a partir das notificações BLE (`processRawPacket` + `parsePacketBundle` do niimbluelib):
 * um pacote pode vir partido em várias notificações ou vários colados numa só.
 */
class RemontadorPacotes {
    private var buf = ByteArray(0)

    @Synchronized
    fun alimentar(pedaco: ByteArray): List<NiimbotPacote> {
        if (pedaco.isEmpty()) return emptyList()
        buf += pedaco
        val saida = mutableListOf<NiimbotPacote>()
        while (buf.isNotEmpty()) {
            // Lixo no começo (não é 55 55): descarta tudo, como o niimbluelib.
            if (buf.size > 1 && !(buf[0] == 0x55.toByte() && buf[1] == 0x55.toByte())) { buf = ByteArray(0); break }
            if (buf.size < 4) break // falta cmd/len
            val len = buf[3].toInt() and 0xFF
            val total = 2 + 1 + 1 + len + 1 + 2
            if (buf.size < total) break // incompleto: espera o resto
            val bruto = buf.copyOfRange(0, total)
            buf = buf.copyOfRange(total, buf.size)
            // Pacote com cauda/checksum ruim é descartado (niimbluelib travaria o buffer inteiro).
            runCatching { NiimbotPacote.deBytes(bruto) }.onSuccess { saida += it }
        }
        return saida
    }

    @Synchronized
    fun limpar() { buf = ByteArray(0) }
}
