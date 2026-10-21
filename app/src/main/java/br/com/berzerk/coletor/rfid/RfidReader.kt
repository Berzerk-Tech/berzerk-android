package br.com.berzerk.coletor.rfid

import android.content.Context
import android.util.Log
import com.ubx.usdk.RFIDSDKManager
import com.ubx.usdk.rfid.RfidManager
import com.ubx.usdk.rfid.aidl.IRfidCallback
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface RfidEstado {
    data object Desligado : RfidEstado
    data object Iniciando : RfidEstado
    data object Pronto : RfidEstado
    data object Lendo : RfidEstado
    data class Erro(val motivo: String) : RfidEstado
}

data class Leitura(val epc: String, val rssi: Int, val em: Long = System.currentTimeMillis())

/**
 * O módulo UHF do DT50 pelo SDK oficial da Urovo (`URFIDLibrary`, `com.ubx.usdk`):
 * `RFIDSDKManager.init` → `RfidManager.registerCallback` → `startInventory`/
 * `stopInventory`. Uma instância por processo; as telas só consomem `leituras`.
 *
 * O gatilho físico chega como KeyEvent na Activity (keycodes 523 e 515 nos
 * aparelhos da Urovo) e vira `gatilho(true/false)` aqui.
 */
object RfidReader {
    private const val TAG = "Rfid"
    /** Keycodes do gatilho/lateral nos Urovo (doc v2.25 §2.2.1): 523 = gatilho, 515 = PTT/esquerda. */
    val KEYCODES_GATILHO = setOf(523, 515, 520, 521, 522)

    private val _estado = MutableStateFlow<RfidEstado>(RfidEstado.Desligado)
    val estado: StateFlow<RfidEstado> = _estado

    /** Toda tag lida, sem dedupe (o SDK repete a mesma tag várias vezes por segundo). */
    private val _leituras = MutableSharedFlow<Leitura>(extraBufferCapacity = 8192, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val leituras: SharedFlow<Leitura> = _leituras

    private val _potenciaMax = MutableStateFlow(33)
    val potenciaMax: StateFlow<Int> = _potenciaMax

    @Volatile private var gatilhoApertado = false
    private var potenciaDesejada: Int? = null
    private var beepDesejado = true

    private val callback = object : IRfidCallback {
        override fun onInventoryTag(epc: String?, data: String?, rssi: Int) {
            val limpo = epc?.trim()?.uppercase() ?: return
            if (limpo.isEmpty()) return
            _leituras.tryEmit(Leitura(limpo, rssi))
        }
        override fun onInventoryTagEnd() {
            if (_estado.value is RfidEstado.Lendo && !gatilhoApertado) _estado.value = RfidEstado.Pronto
        }
    }

    private val manager: RfidManager? get() = runCatching { RFIDSDKManager.getInstance().rfidManager }.getOrNull()

    fun iniciar(context: Context) {
        val atual = _estado.value
        if (atual is RfidEstado.Iniciando || atual is RfidEstado.Pronto || atual is RfidEstado.Lendo) return
        _estado.value = RfidEstado.Iniciando
        try {
            RFIDSDKManager.getInstance().init(context.applicationContext) { ok ->
                if (!ok) {
                    _estado.value = RfidEstado.Erro("Módulo RFID não respondeu. Este aparelho é um Urovo com UHF?")
                    return@init
                }
                val m = manager
                if (m == null) {
                    _estado.value = RfidEstado.Erro("SDK iniciou sem RfidManager.")
                    return@init
                }
                runCatching { m.registerCallback(callback) }.onFailure { Log.w(TAG, "registerCallback", it) }
                runCatching { m.getSupportMaxOutputPower().takeIf { it > 0 }?.let { _potenciaMax.value = it } }
                potenciaDesejada?.let { aplicarPotencia(it) }
                runCatching { m.setBeepEnable(beepDesejado) }
                _estado.value = RfidEstado.Pronto
                Log.i(TAG, "pronto: fw=${runCatching { m.firmwareVersion }.getOrNull()} max=${_potenciaMax.value} dBm")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "init", e)
            _estado.value = RfidEstado.Erro("Falha ao iniciar o SDK: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Gatilho físico ou botão da tela. */
    fun gatilho(apertado: Boolean) {
        gatilhoApertado = apertado
        if (apertado) comecar() else parar()
    }

    fun comecar(): Boolean {
        val m = manager ?: return false
        if (_estado.value !is RfidEstado.Pronto && _estado.value !is RfidEstado.Lendo) return false
        val r = runCatching { m.startInventory() }.getOrDefault(-1)
        if (r == 0) {
            _estado.value = RfidEstado.Lendo
            return true
        }
        Log.w(TAG, "startInventory=$r")
        return false
    }

    fun parar() {
        val m = manager ?: return
        runCatching { m.stopInventory() }
        if (_estado.value is RfidEstado.Lendo) _estado.value = RfidEstado.Pronto
    }

    fun setPotencia(dbm: Int) {
        potenciaDesejada = dbm
        if (_estado.value is RfidEstado.Pronto || _estado.value is RfidEstado.Lendo) aplicarPotencia(dbm)
    }

    fun setBeep(ligado: Boolean) {
        beepDesejado = ligado
        runCatching { manager?.setBeepEnable(ligado) }
    }

    private fun aplicarPotencia(dbm: Int) {
        val m = manager ?: return
        val alvo = dbm.coerceIn(5, _potenciaMax.value)
        val r = runCatching { m.setOutputPower(alvo) }.getOrDefault(-1)
        if (r != 0) Log.w(TAG, "setOutputPower($alvo)=$r")
    }

    fun potenciaAtual(): Int? = runCatching { manager?.outputPower }.getOrNull()?.takeIf { it > 0 }

    fun liberar() {
        parar()
        runCatching { manager?.unregisterCallback() }
        runCatching { RFIDSDKManager.getInstance().release() }
        _estado.value = RfidEstado.Desligado
    }
}
