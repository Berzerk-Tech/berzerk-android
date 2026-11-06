package br.com.berzerk.coletor.rfid

import android.content.Context
import android.util.Log
import br.com.berzerk.coletor.impressora.bluetoothLigado
import br.com.berzerk.coletor.impressora.temPermissaoBluetooth
import com.zebra.rfid.api3.BEEPER_VOLUME
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE
import com.zebra.rfid.api3.InvalidUsageException
import com.zebra.rfid.api3.OperationFailureException
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.Readers
import com.zebra.rfid.api3.RfidEventsListener
import com.zebra.rfid.api3.RfidReadEvents
import com.zebra.rfid.api3.RfidStatusEvents
import com.zebra.rfid.api3.START_TRIGGER_TYPE
import com.zebra.rfid.api3.STATUS_EVENT_TYPE
import com.zebra.rfid.api3.STOP_TRIGGER_TYPE
import com.zebra.rfid.api3.TriggerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Sled Zebra RFD40 pelo SDK oficial (RFIDAPI3, `com.zebra.rfid.api3`), por Bluetooth clássico:
 * o RFD40 é pareado no Bluetooth do Android (TC22) e `Readers.GetAvailableRFIDReaderList` lista
 * os pareados; `RFIDReader.connect()` abre a sessão. Tags chegam por `eventReadNotify` +
 * `Actions.getReadTags`; o gatilho do sled chega como `HANDHELD_TRIGGER_EVENT` (apertar liga a
 * leitura, soltar é ignorado — mesmo contrato do Urovo: parar é pela tela).
 *
 * Tudo que fala com o sled bloqueia (Bluetooth), então roda numa thread própria ([exec]).
 */
class ZebraLeitor(private val eventos: EventosRfid) : LeitorRfid, RfidEventsListener {
    override val tipo = TipoLeitor.ZEBRA

    data class Aparelho(val nome: String, val endereco: String)

    private val _aparelhos = MutableStateFlow<List<Aparelho>>(emptyList())
    /** Leitores Zebra pareados no Android (atualizada a cada tentativa de conexão). */
    val aparelhos: StateFlow<List<Aparelho>> = _aparelhos

    private val _conectado = MutableStateFlow<Aparelho?>(null)
    val conectado: StateFlow<Aparelho?> = _conectado

    /** Endereço (mac) que o operador escolheu; sem escolha, o primeiro pareado. */
    @Volatile var enderecoPreferido: String? = null

    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "zebra-rfid").apply { isDaemon = true } }
    @Volatile private var readers: Readers? = null
    @Volatile private var reader: RFIDReader? = null
    @Volatile private var ocupado = false
    @Volatile private var liberado = false
    /** Níveis de potência do leitor, na unidade do SDK (RFD40: décimos de dBm). */
    @Volatile private var niveis: IntArray = IntArray(0)
    @Volatile private var escala = 10

    override fun iniciar(context: Context) {
        liberado = false
        val app = context.applicationContext
        exec.execute { conectar(app) }
    }

    private fun conectar(ctx: Context) {
        if (ocupado || liberado) return
        if (reader?.isConnected == true) { eventos.estado(this, RfidEstado.Pronto); return }
        ocupado = true
        eventos.estado(this, RfidEstado.Iniciando)
        try {
            if (!temPermissaoBluetooth(ctx)) {
                eventos.estado(this, RfidEstado.Erro("Sem permissão de Bluetooth pra falar com o RFD40."))
                return
            }
            if (!bluetoothLigado(ctx)) {
                eventos.estado(this, RfidEstado.Erro("Ligue o Bluetooth do aparelho pra conectar o RFD40."))
                return
            }
            val rs = readers ?: Readers(ctx, ENUM_TRANSPORT.ALL).also { readers = it }
            val lista = runCatching { rs.GetAvailableRFIDReaderList() }.getOrElse { emptyList() }.orEmpty()
            _aparelhos.value = lista.map { Aparelho(it.name?.takeIf { n -> n.isNotBlank() } ?: it.address.orEmpty(), it.address.orEmpty()) }
            if (lista.isEmpty()) {
                eventos.estado(this, RfidEstado.Erro("Nenhum RFD40 pareado. Pareie o sled no Bluetooth do Android e tente de novo."))
                return
            }
            val dev = lista.firstOrNull { it.address == enderecoPreferido } ?: lista.first()
            val r = dev.rfidReader
            Log.i(TAG, "zebra conectando em ${dev.name}")
            if (!r.isConnected) r.connect()
            if (liberado) { runCatching { r.disconnect() }; return } // trocaram de leitor no meio da conexão
            reader = r
            configurar(r)
            _conectado.value = Aparelho(dev.name ?: dev.address, dev.address)
            eventos.estado(this, RfidEstado.Pronto)
            Log.i(TAG, "zebra pronto: ${dev.name} níveis=${niveis.size} max=${niveis.lastOrNull()}")
        } catch (e: OperationFailureException) {
            Log.e(TAG, "zebra connect: ${e.vendorMessage} ${e.statusDescription}", e)
            eventos.estado(this, RfidEstado.Erro("RFD40 não conectou: ${e.vendorMessage ?: e.statusDescription ?: e.results?.toString() ?: "falha"}. O sled está ligado?"))
        } catch (e: InvalidUsageException) {
            Log.e(TAG, "zebra connect: ${e.info}", e)
            eventos.estado(this, RfidEstado.Erro("RFD40: ${e.vendorMessage ?: e.info ?: "uso inválido do SDK"}"))
        } catch (e: Throwable) {
            Log.e(TAG, "zebra connect", e)
            eventos.estado(this, RfidEstado.Erro("RFD40 não conectou: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            ocupado = false
        }
    }

    private fun configurar(r: RFIDReader) {
        r.Events.addEventsListener(this)
        r.Events.setHandheldEvent(true)
        r.Events.setTagReadEvent(true)
        r.Events.setAttachTagDataWithReadEvent(false)
        runCatching { r.Events.setInventoryStopEvent(true) }
        runCatching { r.Events.setInventoryStartEvent(true) }
        // Gatilho do sled no modo RFID (e não disparar o scanner de código de barras).
        runCatching { r.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true) }.onFailure { Log.w(TAG, "setTriggerMode", it) }
        // A leitura começa/para por comando nosso; o gatilho só avisa.
        val ti = TriggerInfo()
        ti.StartTrigger.triggerType = START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
        ti.StopTrigger.triggerType = STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE
        runCatching { r.Config.startTrigger = ti.StartTrigger; r.Config.stopTrigger = ti.StopTrigger }.onFailure { Log.w(TAG, "triggers", it) }
        // Potência: o SDK trabalha por índice numa tabela de níveis (RFD40: 100..300 = 10..30 dBm).
        niveis = runCatching { r.ReaderCapabilities.transmitPowerLevelValues }.getOrNull()?.takeIf { it.isNotEmpty() } ?: IntArray(0)
        val max = niveis.lastOrNull() ?: 0
        escala = when { max > 1000 -> 100; max > 100 -> 10; else -> 1 }
        if (max > 0) eventos.potenciaMax(this, (max / escala).coerceAtLeast(5))
        eventos.potenciaDesejada?.let { aplicarPotenciaAgora(r, it) }
        aplicarBeep(r, eventos.beepDesejado)
    }

    override fun comecar(): Boolean {
        val r = reader ?: return false
        if (!r.isConnected) return false
        exec.execute {
            try {
                r.Actions.Inventory.perform()
            } catch (e: Throwable) {
                Log.w(TAG, "inventory.perform: ${descrever(e)}")
                eventos.leituraTerminou(this)
            }
        }
        return true
    }

    override fun parar() {
        val r = reader ?: return
        exec.execute { runCatching { r.Actions.Inventory.stop() }.onFailure { Log.w(TAG, "inventory.stop: ${descrever(it)}") } }
    }

    override fun aplicarPotencia(dbm: Int) {
        val r = reader ?: return
        exec.execute { aplicarPotenciaAgora(r, dbm) }
    }

    private fun aplicarPotenciaAgora(r: RFIDReader, dbm: Int) {
        if (niveis.isEmpty()) return
        val alvo = dbm * escala
        // Maior nível que não passa do pedido (ou o menor da tabela).
        var idx = 0
        for (i in niveis.indices) if (niveis[i] <= alvo) idx = i
        runCatching {
            val cfg = r.Config.Antennas.getAntennaRfConfig(1)
            cfg.transmitPowerIndex = idx
            r.Config.Antennas.setAntennaRfConfig(1, cfg)
        }.onFailure { Log.w(TAG, "setAntennaRfConfig($idx): ${descrever(it)}") }
    }

    override fun setBeep(ligado: Boolean) {
        val r = reader ?: return
        exec.execute { aplicarBeep(r, ligado) }
    }

    private fun aplicarBeep(r: RFIDReader, ligado: Boolean) {
        runCatching { r.Config.beeperVolume = if (ligado) BEEPER_VOLUME.HIGH_BEEP else BEEPER_VOLUME.QUIET_BEEP }
            .onFailure { Log.w(TAG, "beeper: ${descrever(it)}") }
    }

    override fun potenciaAtual(): Int? {
        val r = reader ?: return null
        if (niveis.isEmpty()) return null
        val idx = runCatching { r.Config.Antennas.getAntennaRfConfig(1).transmitPowerIndex }.getOrNull() ?: return null
        return niveis.getOrNull(idx)?.let { it / escala }
    }

    override fun liberar() {
        liberado = true
        val r = reader
        val rs = readers
        reader = null
        readers = null
        _conectado.value = null
        exec.execute {
            runCatching { r?.Events?.removeEventsListener(this) }
            runCatching { r?.Actions?.Inventory?.stop() }
            runCatching { r?.disconnect() }
            runCatching { r?.Dispose() }
            runCatching { rs?.Dispose() }
        }
    }

    // ---- eventos do SDK (thread do SDK) ----

    override fun eventReadNotify(e: RfidReadEvents?) {
        val r = reader ?: return
        while (true) {
            val tags = runCatching { r.Actions.getReadTags(100) }.getOrNull() ?: break
            if (tags.isEmpty()) break
            for (t in tags) {
                val epc = t.tagID?.trim()?.uppercase() ?: continue
                if (epc.isEmpty()) continue
                eventos.leitura(this, epc, t.peakRSSI.toInt())
            }
            if (tags.size < 100) break
        }
    }

    override fun eventStatusNotify(ev: RfidStatusEvents?) {
        val dados = ev?.StatusEventData ?: return
        when (dados.statusEventType) {
            STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
                val tipo = dados.HandheldTriggerEventData?.handheldEvent
                if (tipo == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) eventos.gatilhoFisico(this, true)
            }
            STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT -> eventos.leituraTerminou(this)
            STATUS_EVENT_TYPE.DISCONNECTION_EVENT -> {
                Log.w(TAG, "zebra desconectou")
                _conectado.value = null
                val atual = reader
                reader = null
                runCatching { atual?.Events?.removeEventsListener(this) }
                eventos.estado(this, RfidEstado.Erro("RFD40 desconectou. Reconectando…"))
                // Uma tentativa automática; depois é pelo botão da tela.
                val ctx = contextoApp
                if (ctx != null && !liberado) exec.schedule({ conectar(ctx) }, 3, TimeUnit.SECONDS)
            }
            else -> Unit
        }
    }

    @Volatile private var contextoApp: Context? = null

    /** Guarda o contexto pra reconexão automática. Chamado pelo [RfidReader] antes de [iniciar]. */
    fun lembrarContexto(context: Context) { contextoApp = context.applicationContext }

    private fun descrever(e: Throwable): String = when (e) {
        is OperationFailureException -> e.vendorMessage ?: e.statusDescription ?: e.results?.toString() ?: "falha"
        is InvalidUsageException -> e.vendorMessage ?: e.info ?: "uso inválido"
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object { const val TAG = "Rfid" }
}
