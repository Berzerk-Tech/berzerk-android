package br.com.berzerk.coletor.rfid

import android.content.Context
import android.util.Log
import com.ubx.usdk.RFIDSDKManager
import com.ubx.usdk.rfid.RfidManager
import com.ubx.usdk.rfid.aidl.IRfidCallback

/**
 * O módulo UHF do DT50 pelo SDK oficial da Urovo (`URFIDLibrary`, `com.ubx.usdk`):
 * `RFIDSDKManager.init` → `RfidManager.registerCallback` → `startInventory`/`stopInventory`.
 *
 * O gatilho físico chega como KeyEvent na Activity (keycodes 523 e 515 nos aparelhos da Urovo),
 * não por aqui: a `MainActivity` chama `RfidReader.gatilho(true)`.
 */
class UrovoLeitor(private val eventos: EventosRfid) : LeitorRfid {
    override val tipo = TipoLeitor.UROVO

    @Volatile private var potenciaMax = 33
    @Volatile private var iniciando = false

    private val callback = object : IRfidCallback {
        override fun onInventoryTag(epc: String?, data: String?, rssi: Int) {
            val limpo = epc?.trim()?.uppercase() ?: return
            if (limpo.isEmpty()) return
            eventos.leitura(this@UrovoLeitor, limpo, rssi)
        }
        override fun onInventoryTagEnd() {
            eventos.leituraTerminou(this@UrovoLeitor)
        }
    }

    private val manager: RfidManager? get() = runCatching { RFIDSDKManager.getInstance().rfidManager }.getOrNull()

    override fun iniciar(context: Context) {
        if (iniciando) return
        iniciando = true
        eventos.estado(this, RfidEstado.Iniciando)
        try {
            RFIDSDKManager.getInstance().init(context.applicationContext) { ok ->
                iniciando = false
                if (!ok) {
                    eventos.estado(this, RfidEstado.Erro("Módulo RFID da Urovo não respondeu. Se este aparelho não é um Urovo, troque o leitor."))
                    return@init
                }
                val m = manager
                if (m == null) {
                    eventos.estado(this, RfidEstado.Erro("SDK da Urovo iniciou sem RfidManager."))
                    return@init
                }
                runCatching { m.registerCallback(callback) }.onFailure { Log.w(TAG, "registerCallback", it) }
                runCatching { m.getSupportMaxOutputPower().takeIf { it > 0 }?.let { potenciaMax = it; eventos.potenciaMax(this, it) } }
                eventos.potenciaDesejada?.let { aplicarPotencia(it) }
                runCatching { m.setBeepEnable(eventos.beepDesejado) }
                eventos.estado(this, RfidEstado.Pronto)
                Log.i(TAG, "urovo pronto: fw=${runCatching { m.firmwareVersion }.getOrNull()} max=$potenciaMax dBm")
            }
        } catch (e: Throwable) {
            iniciando = false
            Log.e(TAG, "init", e)
            eventos.estado(this, RfidEstado.Erro("Falha ao iniciar o SDK da Urovo: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    override fun comecar(): Boolean {
        val m = manager ?: return false
        val r = runCatching { m.startInventory() }.getOrDefault(-1)
        if (r == 0) return true
        Log.w(TAG, "startInventory=$r")
        return false
    }

    override fun parar() {
        val m = manager ?: return
        runCatching { m.stopInventory() }
    }

    override fun aplicarPotencia(dbm: Int) {
        val m = manager ?: return
        val alvo = dbm.coerceIn(5, potenciaMax)
        val r = runCatching { m.setOutputPower(alvo) }.getOrDefault(-1)
        if (r != 0) Log.w(TAG, "setOutputPower($alvo)=$r")
    }

    override fun setBeep(ligado: Boolean) {
        runCatching { manager?.setBeepEnable(ligado) }
    }

    override fun potenciaAtual(): Int? = runCatching { manager?.outputPower }.getOrNull()?.takeIf { it > 0 }

    override fun liberar() {
        parar()
        runCatching { manager?.unregisterCallback() }
        runCatching { RFIDSDKManager.getInstance().release() }
        iniciando = false
    }

    private companion object { const val TAG = "Rfid" }
}
