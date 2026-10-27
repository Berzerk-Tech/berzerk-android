package br.com.berzerk.coletor.impressora

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Resultado do `Connect` (`ConnectResult`). */
object ResultadoConexao {
    const val DESCONECTADO = 0
    const val CONECTADO = 1
    const val CONECTADO_NOVO = 2
    const val CONECTADO_V3 = 3
    const val ERRO_FIRMWARE = 90
}

class Negociacao(val resultado: Int, val versaoProtocolo: Int)

/** [modeloId] da B31 = 5632; [versaoSoftware] no formato do niimbluelib (`"1.02/1.02"`); [bateria] em %. */
class InfoImpressora(val modeloId: Int?, val versaoSoftware: String?, val bateria: Int?)

class StatusImpressao(val pagina: Int, val progressoImpressao: Int, val progressoAvanco: Int, val erro: Int)

/** Camada de pacotes (`protocol.ts`): um pedido por vez, esperando a resposta certa. */
class NiimbotProtocolo(private val ble: NiimbotBle) {
    private val mutex = Mutex()

    /** Preenchidos por [negociar] / [infoImpressora]; o modo AUTO da impressão depende deles. */
    @Volatile var versaoProtocolo: Int = 0; private set
    @Volatile var modeloId: Int? = null; private set

    /**
     * Envia [p]; se for de uma via, retorna `null` na hora; senão espera a resposta (id mapeado em
     * [NiimbotPacote.respostas]). Erro da impressora (0xDB) ou "não suportado" (0x00) viram [ImpressoraException].
     */
    suspend fun enviar(p: NiimbotPacote, timeoutMs: Long = 1000): NiimbotPacote? = mutex.withLock {
        if (p.umaVia) {
            ble.escrever(p.bytes())
            return@withLock null
        }
        coroutineScope {
            // Assina o fluxo ANTES de escrever (a resposta pode chegar antes de o write voltar); o relógio só corre depois.
            val espera = async(start = CoroutineStart.UNDISPATCHED) {
                ble.pacotes.first { it.cmd in p.respostas || it.cmd == Cmd.IN_PRINT_ERROR || it.cmd == Cmd.IN_NOT_SUPPORTED }
            }
            // Queda da impressora no meio da espera encerra na hora, em vez de esperar o timeout.
            val queda = async(start = CoroutineStart.UNDISPATCHED) { ble.conectado.first { !it } }
            val r = try {
                ble.escrever(p.bytes())
                withTimeoutOrNull(timeoutMs) {
                    select<NiimbotPacote?> {
                        espera.onAwait { it }
                        queda.onAwait { null }
                    }
                }
            } finally {
                espera.cancel()
                queda.cancel()
            }
            if (r == null) {
                throw ImpressoraException(if (ble.conectado.value) "Sem resposta da impressora" else "Impressora desconectou")
            }
            when (r.cmd) {
                Cmd.IN_PRINT_ERROR -> throw ImpressoraException("Impressora recusou: ${descreverErro(r.dados.firstOrNull()?.toInt()?.and(0xFF))}")
                Cmd.IN_NOT_SUPPORTED -> throw ImpressoraException("Impressora recusou: comando não suportado")
            }
            r
        }
    }

    /**
     * Envia em sequência; [progresso] recebe (atual, total) antes de cada pacote. Com [agrupar], pacotes de
     * uma via consecutivos (linhas da etiqueta) vão juntos numa escrita BLE até o MTU: a impressora lê um
     * fluxo de bytes, não uma escrita por pacote, e isso corta o número de idas ao rádio em ~3×.
     */
    suspend fun enviarTodos(ps: List<NiimbotPacote>, timeoutMs: Long = 1000, progresso: ((Int, Int) -> Unit)? = null, agrupar: Boolean = false) {
        var i = 0
        while (i < ps.size) {
            progresso?.invoke(i + 1, ps.size)
            val p = ps[i]
            if (!agrupar || !p.umaVia) {
                enviar(p, timeoutMs)
                i++
                continue
            }
            val teto = ble.bytesPorEscrita
            var lote = p.bytes()
            i++
            while (i < ps.size && ps[i].umaVia) {
                val proximo = ps[i].bytes()
                if (lote.size + proximo.size > teto) break
                lote += proximo
                i++
            }
            mutex.withLock { ble.escrever(lote) }
        }
    }

    /** `Connect` e, se vier V3, `PrinterStatusData` pra descobrir a versão do protocolo. */
    suspend fun negociar(): Negociacao {
        val r = enviar(Pacotes.conectar())!!
        val resultado = r.dados.firstOrNull()?.toInt()?.and(0xFF) ?: throw ImpressoraException("Resposta de conexão vazia")
        var versao = 0
        if (resultado == ResultadoConexao.CONECTADO_NOVO) {
            versao = 1
        } else if (resultado == ResultadoConexao.CONECTADO_V3) {
            val s = enviar(Pacotes.statusDaImpressora())!!.dados
            if (s.size >= 13) {
                val n = (s[11].toInt() and 0xFF) * 100 + (s[12].toInt() and 0xFF)
                versao = when {
                    n in 204..299 -> 3
                    n in 300..301 -> 4
                    n >= 302 -> 5
                    else -> 0
                }
            }
        }
        versaoProtocolo = versao
        return Negociacao(resultado, versao)
    }

    /** Modelo, versão do software e bateria; cada item que a impressora não responder fica `null`. */
    suspend fun infoImpressora(): InfoImpressora {
        val modelo = runCatching {
            val d = enviar(Pacotes.infoImpressora(TipoInfo.PRINTER_MODEL_ID))!!.dados
            when (d.size) {
                1 -> (d[0].toInt() and 0xFF) shl 8
                2 -> ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
                else -> null
            }
        }.getOrNull()
        modeloId = modelo
        val versao = runCatching {
            val d = enviar(Pacotes.infoImpressora(TipoInfo.SOFTWARE_VERSION))!!.dados
            if (d.size != 2) null else {
                val a = d[0].toInt() and 0xFF
                val b = d[1].toInt() and 0xFF
                "%.2f/%.2f".format(java.util.Locale.US, a + b / 100.0, (a * 256 + b) / 100.0)
            }
        }.getOrNull()
        val bateria = runCatching {
            val d = enviar(Pacotes.infoImpressora(TipoInfo.BATTERY_CHARGE_LEVEL))!!.dados
            if (d.size != 1) null else (d[0].toInt() and 0xFF).let { if (it <= 4) it * 25 else it }
        }.getOrNull()
        return InfoImpressora(modelo, versao, bateria)
    }

    /** `PrintStatus` (2 tentativas, como `getPrintStatus(2)`); erro ≠ 0 vira exceção. */
    suspend fun statusImpressao(timeoutMs: Long = 1000): StatusImpressao {
        val resposta = try {
            enviar(Pacotes.printStatus(), timeoutMs)!!
        } catch (e: ImpressoraException) {
            enviar(Pacotes.printStatus(), timeoutMs)!!
        }
        val d = resposta.dados
        if (d.size < 4) throw ImpressoraException("Resposta de status inválida")
        val pagina = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        val erro = if (d.size == 10) d[6].toInt() and 0xFF else 0
        if (erro != 0) throw ImpressoraException("Erro de impressão $erro")
        return StatusImpressao(pagina, d[2].toInt() and 0xFF, d[3].toInt() and 0xFF, erro)
    }

    suspend fun printEnd(): Boolean = enviar(Pacotes.printEnd())!!.dados.let { it.size == 1 && it[0].toInt() == 1 }

    companion object {
        /** Códigos de `PrinterErrorCode` mais comuns em pt-BR; os demais saem como "erro N". */
        fun descreverErro(codigo: Int?): String = when (codigo) {
            null -> "erro desconhecido"
            0x01 -> "tampa aberta"
            0x02 -> "sem papel"
            0x03 -> "bateria fraca"
            0x04 -> "falha na bateria"
            0x05 -> "cancelado"
            0x06 -> "dados inválidos"
            0x07 -> "superaquecimento"
            0x08 -> "falha na saída do papel"
            0x09 -> "impressora ocupada"
            0x0A -> "sem cabeça de impressão"
            0x0B -> "temperatura baixa"
            0x10 -> "papel incorreto"
            0x11 -> "falha ao definir o papel"
            0x13 -> "falha ao definir a densidade"
            0x32 -> "página inválida"
            0x34 -> "tempo esgotado recebendo dados"
            else -> "erro $codigo"
        }
    }
}
