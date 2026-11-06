package br.com.berzerk.coletor.rfid

import android.content.Context
import android.os.Build

/** Qual hardware lê as tags. O operador escolhe na Home/Config; fica em `Prefs.leitorTipo`. */
enum class TipoLeitor(val chave: String, val rotulo: String, val curto: String, val descricao: String) {
    UROVO("urovo", "Urovo DT50 (leitor interno)", "Urovo", "Módulo UHF do próprio coletor. Gatilho lateral liga a leitura."),
    ZEBRA("zebra", "Zebra RFD40 (Bluetooth)", "Zebra", "Sled RFD40 pareado no Bluetooth do Android (TC22). Gatilho do sled liga a leitura."),
    ;

    companion object {
        fun de(chave: String?): TipoLeitor? = entries.firstOrNull { it.chave == chave }

        /** Sem escolha salva: Zebra num aparelho da Zebra, Urovo no resto. */
        fun padraoDoAparelho(): TipoLeitor =
            if (Build.MANUFACTURER.orEmpty().contains("zebra", ignoreCase = true)) ZEBRA else UROVO
    }
}

/**
 * Um driver de leitor. Só o [RfidReader] fala com ele; as telas nunca veem esta interface.
 * Todas as chamadas podem vir da main thread: quem bloqueia (Bluetooth) despacha pra própria thread.
 */
interface LeitorRfid {
    val tipo: TipoLeitor
    fun iniciar(context: Context)
    /** `true` = leitura pedida (o estado vira [RfidEstado.Lendo]). */
    fun comecar(): Boolean
    fun parar()
    fun aplicarPotencia(dbm: Int)
    fun setBeep(ligado: Boolean)
    fun potenciaAtual(): Int?
    fun liberar()
}

/** O que o driver devolve pro [RfidReader]. `origem` é ignorada quando o driver já foi trocado. */
interface EventosRfid {
    val potenciaDesejada: Int?
    val beepDesejado: Boolean
    fun estado(origem: LeitorRfid, e: RfidEstado)
    fun leitura(origem: LeitorRfid, epc: String, rssi: Int)
    fun potenciaMax(origem: LeitorRfid, dbm: Int)
    /** Gatilho físico do hardware (só o apertar interessa; soltar não para). */
    fun gatilhoFisico(origem: LeitorRfid, apertado: Boolean)
    /** O hardware encerrou a varredura sozinho (fim de ciclo / stop confirmado). */
    fun leituraTerminou(origem: LeitorRfid)
}
