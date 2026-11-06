package br.com.berzerk.coletor.rfid

import android.content.Context
import android.util.Log
import com.ubx.usdk.RFIDSDKManager
import com.ubx.usdk.rfid.RfidManager
import com.ubx.usdk.bean.enums.InventorySceneMode
import com.ubx.usdk.rfid.aidl.IRfidCallback

/**
 * Ajustes finos do módulo UHF do DT50 (Config → Leitor → "Ajustes do DT50"), espelho do
 * [AjustesZebra]. O SDK da Urovo tem modos de cena prontos (`InventorySceneMode`) e um modo
 * personalizado com sessão/alvo/Q. `cena` nula = não mexe no que o módulo já tem (padrão, pra
 * não mudar o comportamento dos DT50 em produção sem alguém escolher).
 */
data class AjustesUrovo(
    /** `InventorySceneMode.value` (0 bateria, 1 inventário completo, 2 releitura, 3 equilibrado, 4 contagem cíclica, 5 alcance máximo, 6 personalizado); nulo = como está. */
    val cena: Int? = null,
    /** Só no personalizado: sessão Gen2 0..3. */
    val sessao: Int = 1,
    /** Só no personalizado: peças esperadas no campo; vira o Q inicial (2^Q ≈ população). */
    val populacao: Int = 100,
    /** Só no personalizado: alvo 0 = A, 1 = B, 2 = A/B alternando. */
    val alvo: Int = 0,
) {
    val personalizado get() = cena == CENA_PERSONALIZADA

    companion object {
        const val CENA_PERSONALIZADA = 6
        /** Modos de cena do SDK, na ordem do `value`, com rótulo e dica pro operador. */
        val CENAS = listOf(
            0 to ("Bateria" to "Menos potência de processamento; dura mais, lê menos."),
            1 to ("Inventário completo" to "Prioriza achar todas as peças de uma pilha grande."),
            2 to ("Releitura" to "Repete a mesma tag o tempo todo; bom pra localizar uma peça."),
            3 to ("Equilibrado" to "Meio-termo do fabricante."),
            4 to ("Contagem cíclica" to "Cada peça uma vez por rodada; bom pra contar."),
            5 to ("Alcance máximo" to "Puxa o máximo de distância."),
            CENA_PERSONALIZADA to ("Personalizado" to "Você escolhe sessão, alvo e população."),
        )
        /** Q inicial a partir da população esperada (2^Q ≈ tags no campo). */
        fun qDe(populacao: Int): Int = when {
            populacao <= 30 -> 5
            populacao <= 100 -> 7
            populacao <= 300 -> 8
            else -> 10
        }
    }
}

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

    /** Ajustes finos em vigor (`Prefs.ajustesUrovo`): aplicados no init e em [aplicarAjustes]. */
    @Volatile var ajustes = AjustesUrovo()

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
                Log.i(TAG, "urovo de fábrica: cena=${runCatching { m.inventorySceneMode }.getOrNull()} sessao=${runCatching { m.inventoryWithSession }.getOrNull()} q=${runCatching { m.inventoryWithStartQvalue }.getOrNull()} alvo=${runCatching { m.inventoryWithTarget }.getOrNull()}")
                aplicarAjustesAgora(m, ajustes)
                eventos.estado(this, RfidEstado.Pronto)
                Log.i(TAG, "urovo pronto: fw=${runCatching { m.firmwareVersion }.getOrNull()} max=$potenciaMax dBm")
            }
        } catch (e: Throwable) {
            iniciando = false
            Log.e(TAG, "init", e)
            eventos.estado(this, RfidEstado.Erro("Falha ao iniciar o SDK da Urovo: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /** Troca os ajustes finos e aplica no módulo se ele já estiver de pé. */
    fun aplicarAjustes(a: AjustesUrovo) {
        ajustes = a
        val m = manager ?: return
        aplicarAjustesAgora(m, a)
    }

    private fun aplicarAjustesAgora(m: RfidManager, a: AjustesUrovo) {
        val cena = a.cena ?: return
        val modo = runCatching { InventorySceneMode.fromValue(cena) }.getOrNull() ?: return
        val r = runCatching { m.setInventorySceneMode(modo) }.getOrDefault(-1)
        if (r != 0) Log.w(TAG, "setInventorySceneMode($modo)=$r")
        if (a.personalizado) {
            val q = AjustesUrovo.qDe(a.populacao)
            val rs = runCatching { m.setInventoryWithSession(a.sessao.coerceIn(0, 3)) }.getOrDefault(-1)
            val rq = runCatching { m.setInventoryWithStartQvalue(q) }.getOrDefault(-1)
            val rt = runCatching { m.setInventoryWithTarget(a.alvo.coerceIn(0, 2)) }.getOrDefault(-1)
            if (rs != 0 || rq != 0 || rt != 0) Log.w(TAG, "personalizado: sessao=$rs q=$rq alvo=$rt")
        }
        Log.i(TAG, "urovo ajustes: cena=$modo" + if (a.personalizado) " sessao=S${a.sessao} pop=${a.populacao} (q=${AjustesUrovo.qDe(a.populacao)}) alvo=${a.alvo}" else "")
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
