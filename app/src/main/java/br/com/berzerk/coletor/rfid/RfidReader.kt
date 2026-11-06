package br.com.berzerk.coletor.rfid

import android.content.Context
import android.util.Log
import br.com.berzerk.coletor.core.Graph
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
 * Fachada única do leitor RFID: as telas só consomem [estado] e [leituras] e chamam
 * [gatilho]/[parar]. Por baixo, um driver por hardware ([UrovoLeitor] = módulo interno do
 * DT50; [ZebraLeitor] = sled RFD40 por Bluetooth), escolhido pelo operador ([selecionar]) e
 * lembrado em `Prefs.leitorTipo`. Sem escolha salva, [TipoLeitor.padraoDoAparelho]. A potência é
 * por leitor (`Prefs.potencia(tipo)`): o Urovo roda a 26 dBm, o RFD40 no máximo dele.
 *
 * Gatilho físico: no Urovo chega como KeyEvent na Activity (keycodes 523 e 515); no Zebra
 * vem do próprio SDK. Nos dois, apertar vira `gatilho(true)` e soltar não faz nada — parar é
 * só pelo botão da tela (`gatilho(false)`).
 */
object RfidReader : EventosRfid {
    private const val TAG = "Rfid"
    /** Keycodes do gatilho/lateral nos Urovo (doc v2.25 §2.2.1): 523 = gatilho, 515 = PTT/esquerda. */
    val KEYCODES_GATILHO = setOf(523, 515, 520, 521, 522)

    private val _estado = MutableStateFlow<RfidEstado>(RfidEstado.Desligado)
    val estado: StateFlow<RfidEstado> = _estado

    /** Toda tag lida, sem dedupe (os SDKs repetem a mesma tag várias vezes por segundo). */
    private val _leituras = MutableSharedFlow<Leitura>(extraBufferCapacity = 8192, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val leituras: SharedFlow<Leitura> = _leituras

    private val _potenciaMax = MutableStateFlow(33)
    val potenciaMax: StateFlow<Int> = _potenciaMax

    private val _tipo = MutableStateFlow(TipoLeitor.UROVO)
    /** Leitor em uso (o escolhido/salvo; resolvido no primeiro [iniciar]). */
    val tipo: StateFlow<TipoLeitor> = _tipo

    /** Driver do sled Zebra (lista de pareados / conectado), pro seletor de leitor. */
    val zebra: ZebraLeitor by lazy { ZebraLeitor(this) }
    private val urovo: UrovoLeitor by lazy { UrovoLeitor(this) }

    @Volatile private var driver: LeitorRfid? = null
    @Volatile private var gatilhoApertado = false
    @Volatile override var potenciaDesejada: Int? = null; private set
    @Volatile override var beepDesejado = true; private set

    private fun driverDe(t: TipoLeitor): LeitorRfid = when (t) {
        TipoLeitor.UROVO -> urovo
        TipoLeitor.ZEBRA -> zebra.also { z ->
            z.enderecoPreferido = Graph.prefs.leitorZebraEndereco
            z.ajustes = Graph.prefs.ajustesZebra.value
        }
    }

    fun iniciar(context: Context) {
        val d = driver ?: run {
            val t = TipoLeitor.de(Graph.prefs.leitorTipo) ?: TipoLeitor.padraoDoAparelho()
            _tipo.value = t
            potenciaDesejada = Graph.prefs.potencia(t).value
            driverDe(t).also { driver = it }
        }
        val atual = _estado.value
        if (atual is RfidEstado.Iniciando || atual is RfidEstado.Pronto || atual is RfidEstado.Lendo) return
        if (d is ZebraLeitor) d.lembrarContexto(context)
        d.iniciar(context)
    }

    /** Troca o hardware de leitura (e lembra a escolha). Mesmo tipo já ativo = só garante conexão. */
    fun selecionar(t: TipoLeitor, context: Context) {
        Graph.prefs.leitorTipo = t.chave
        val atual = driver
        if (atual != null && atual.tipo == t) { iniciar(context); return }
        Log.i(TAG, "trocando leitor ${atual?.tipo} → $t")
        atual?.liberar()
        gatilhoApertado = false
        _estado.value = RfidEstado.Desligado
        _potenciaMax.value = 33
        _tipo.value = t
        potenciaDesejada = Graph.prefs.potencia(t).value
        driver = driverDe(t)
        iniciar(context)
    }

    /** Ajustes finos do RFD40 (Config): salva e aplica no sled se ele for o leitor em uso. */
    fun ajustesZebra(a: AjustesZebra) {
        Graph.prefs.setAjustesZebra(a)
        if (driver?.tipo == TipoLeitor.ZEBRA) zebra.aplicarAjustes(a) else zebra.ajustes = a
    }

    /** Escolhe qual RFD40 pareado usar (endereço Bluetooth) e reconecta. */
    fun selecionarZebra(endereco: String, context: Context) {
        Graph.prefs.leitorZebraEndereco = endereco
        zebra.enderecoPreferido = endereco
        if (driver?.tipo == TipoLeitor.ZEBRA) reconectar(context) else selecionar(TipoLeitor.ZEBRA, context)
    }

    /** Derruba e sobe o driver atual (botão "Tentar de novo"). */
    fun reconectar(context: Context) {
        val d = driver ?: run { iniciar(context); return }
        d.liberar()
        gatilhoApertado = false
        _estado.value = RfidEstado.Desligado
        if (d is ZebraLeitor) d.lembrarContexto(context)
        d.iniciar(context)
    }

    /** Gatilho físico (só `true`) ou botão LER/PARAR da tela. Ligar com a leitura já ligada é no-op. */
    fun gatilho(apertado: Boolean) {
        gatilhoApertado = apertado
        if (apertado) comecar() else parar()
    }

    fun comecar(): Boolean {
        val d = driver ?: return false
        if (_estado.value is RfidEstado.Lendo) return true
        if (_estado.value !is RfidEstado.Pronto) return false
        if (!d.comecar()) return false
        _estado.value = RfidEstado.Lendo
        return true
    }

    fun parar() {
        driver?.parar()
        if (_estado.value is RfidEstado.Lendo) _estado.value = RfidEstado.Pronto
    }

    fun setPotencia(dbm: Int) {
        potenciaDesejada = dbm
        if (_estado.value is RfidEstado.Pronto || _estado.value is RfidEstado.Lendo) driver?.aplicarPotencia(dbm)
    }

    fun setBeep(ligado: Boolean) {
        beepDesejado = ligado
        driver?.setBeep(ligado)
    }

    fun potenciaAtual(): Int? = driver?.potenciaAtual()

    fun liberar() {
        driver?.liberar()
        gatilhoApertado = false
        _estado.value = RfidEstado.Desligado
    }

    // ---- EventosRfid (chamados pelos drivers, em qualquer thread) ----

    override fun estado(origem: LeitorRfid, e: RfidEstado) {
        if (origem !== driver) return
        _estado.value = e
    }

    override fun leitura(origem: LeitorRfid, epc: String, rssi: Int) {
        if (origem !== driver) return
        _leituras.tryEmit(Leitura(epc, rssi))
    }

    override fun potenciaMax(origem: LeitorRfid, dbm: Int) {
        if (origem !== driver) return
        _potenciaMax.value = dbm
    }

    override fun gatilhoFisico(origem: LeitorRfid, apertado: Boolean) {
        if (origem !== driver) return
        if (apertado) gatilho(true)
    }

    override fun leituraTerminou(origem: LeitorRfid) {
        if (origem !== driver) return
        if (_estado.value is RfidEstado.Lendo && !gatilhoApertado) _estado.value = RfidEstado.Pronto
    }
}
