package br.com.berzerk.coletor.impressora

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class AparelhoBle(val nome: String, val mac: String, val rssi: Int)

/**
 * Transporte BLE da Niimbot: varredura, conexão GATT, escrita sem resposta serial e remontagem dos
 * pacotes que chegam por notificação. Nunca loga nada além de nome/mac.
 */
@SuppressLint("MissingPermission") // toda chamada passa por [bt], que checa a permissão e trata SecurityException
@Suppress("DEPRECATION")
class NiimbotBle(private val ctx: Context) {
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _pacotes = MutableSharedFlow<NiimbotPacote>(extraBufferCapacity = 64)
    val pacotes: SharedFlow<NiimbotPacote> = _pacotes

    private val _conectado = MutableStateFlow(false)
    val conectado: StateFlow<Boolean> = _conectado

    /** Emite quando a impressora cai SEM o app ter pedido ([fechar] não emite). */
    private val _desconexoes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val desconexoes: SharedFlow<Unit> = _desconexoes

    private val remontador = RemontadorPacotes()
    private val mutexEscrita = Mutex()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var caracteristica: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23

    @Volatile private var aguardaConexao: CompletableDeferred<Unit>? = null
    @Volatile private var aguardaMtu: CompletableDeferred<Int>? = null
    @Volatile private var aguardaServicos: CompletableDeferred<Unit>? = null
    @Volatile private var aguardaDescritor: CompletableDeferred<Unit>? = null
    @Volatile private var aguardaEscrita: CompletableDeferred<Unit>? = null

    private fun exigirPronto(): BluetoothAdapter {
        val a = adapter ?: throw ImpressoraException("Este aparelho não tem Bluetooth.")
        if (!temPermissaoBluetooth(ctx)) throw ImpressoraException("Sem permissão de Bluetooth")
        if (!a.isEnabled) throw ImpressoraException("Ligue o Bluetooth do aparelho.")
        return a
    }

    private inline fun <T> bt(bloco: () -> T): T = try {
        bloco()
    } catch (e: SecurityException) {
        throw ImpressoraException("Sem permissão de Bluetooth", e)
    }

    private suspend fun <T> aguardar(d: CompletableDeferred<T>, ms: Long, msg: String): T =
        withTimeoutOrNull(ms) { d.await() } ?: throw ImpressoraException(msg)

    // ---- varredura ----

    /** Varre por [duracaoMs] e devolve as Niimbot achadas (nome B31/B21/B1/D110 ou serviço Niimbot), maior sinal primeiro. */
    suspend fun buscar(duracaoMs: Long = 8_000): List<AparelhoBle> {
        val scanner = exigirPronto().bluetoothLeScanner ?: throw ImpressoraException("Ligue o Bluetooth do aparelho.")
        val achados = LinkedHashMap<String, AparelhoBle>()
        var falha: Int? = null
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val nome = runCatching { r.scanRecord?.deviceName ?: r.device.name }.getOrNull().orEmpty()
                val anunciaServico = r.scanRecord?.serviceUuids?.any { it.uuid == SERVICO } == true
                if (!anunciaServico && PREFIXOS.none { nome.uppercase().startsWith(it) }) return
                synchronized(achados) { achados[r.device.address] = AparelhoBle(nome.ifEmpty { r.device.address }, r.device.address, r.rssi) }
            }
            override fun onScanFailed(erro: Int) { falha = erro }
        }
        bt { scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb) }
        try {
            delay(duracaoMs)
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
        if (falha != null && achados.isEmpty()) throw ImpressoraException("Não consegui procurar impressoras (erro $falha).")
        return synchronized(achados) { achados.values.sortedByDescending { it.rssi } }
    }

    // ---- conexão ----

    /**
     * Geração da conexão atual: cada [conectar]/[fechar] incrementa, e o callback de um GATT antigo
     * (que ainda pode disparar o DISCONNECTED depois do `disconnect()`) é ignorado em vez de
     * completar as esperas da conexão nova.
     */
    private val geracao = AtomicInteger(0)

    private fun novoCallback(minha: Int) = object : BluetoothGattCallback() {
        private fun velho(g: BluetoothGatt): Boolean {
            if (minha == geracao.get()) return false
            runCatching { g.close() }
            return true
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (velho(g)) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                aguardaConexao?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                abortarEsperas("Impressora desconectou")
                val estavaConectado = _conectado.value
                if (g === gatt) { gatt = null; caracteristica = null }
                _conectado.value = false
                runCatching { g.close() }
                if (estavaConectado) _desconexoes.tryEmit(Unit)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtuNovo: Int, status: Int) {
            if (velho(g)) return
            aguardaMtu?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtuNovo else 23)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (velho(g)) return
            if (status == BluetoothGatt.GATT_SUCCESS) aguardaServicos?.complete(Unit)
            else aguardaServicos?.completeExceptionally(ImpressoraException("Falha ao listar os serviços da impressora"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (velho(g)) return
            if (status == BluetoothGatt.GATT_SUCCESS) aguardaDescritor?.complete(Unit)
            else aguardaDescritor?.completeExceptionally(ImpressoraException("Falha ao ligar as notificações da impressora"))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (velho(g)) return
            if (status == BluetoothGatt.GATT_SUCCESS) aguardaEscrita?.complete(Unit)
            else aguardaEscrita?.completeExceptionally(ImpressoraException("Falha ao enviar dados à impressora"))
        }

        // API ≤ 32 chama a versão sem `value`; API 33+ chama as duas, então cada uma só vale na sua faixa.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || velho(g)) return
            val valor = c.value ?: return
            remontador.alimentar(valor.copyOf()).forEach { _pacotes.tryEmit(it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || velho(g)) return
            remontador.alimentar(value.copyOf()).forEach { _pacotes.tryEmit(it) }
        }
    }

    /** Derruba quem está esperando callback (conexão, MTU, serviços, descritor, escrita). */
    private fun abortarEsperas(motivo: String) {
        val erro = ImpressoraException(motivo)
        aguardaConexao?.completeExceptionally(erro)
        aguardaMtu?.completeExceptionally(erro)
        aguardaServicos?.completeExceptionally(erro)
        aguardaDescritor?.completeExceptionally(erro)
        aguardaEscrita?.completeExceptionally(erro)
    }

    /** Conecta no [mac], negocia MTU, acha a característica Niimbot e liga as notificações. */
    suspend fun conectar(mac: String) {
        val a = exigirPronto()
        fechar()
        remontador.limpar()
        val dispositivo = try { a.getRemoteDevice(mac) } catch (e: IllegalArgumentException) { throw ImpressoraException("Endereço da impressora inválido", e) }
        val minha = geracao.incrementAndGet()
        try {
            val conexao = CompletableDeferred<Unit>().also { aguardaConexao = it }
            val g = bt { dispositivo.connectGatt(ctx, false, novoCallback(minha), android.bluetooth.BluetoothDevice.TRANSPORT_LE) }
                ?: throw ImpressoraException("Não consegui conectar na impressora")
            gatt = g
            aguardar(conexao, 10_000, "Impressora não respondeu (ela está ligada e perto?)")

            // MTU maior é só otimização: se não vier, segue com os 23 bytes padrão.
            val pedidoMtu = CompletableDeferred<Int>().also { aguardaMtu = it }
            mtu = if (bt { g.requestMtu(247) }) (withTimeoutOrNull(5_000) { pedidoMtu.await() } ?: 23) else 23

            val servicos = CompletableDeferred<Unit>().also { aguardaServicos = it }
            if (!bt { g.discoverServices() }) throw ImpressoraException("Falha ao listar os serviços da impressora")
            aguardar(servicos, 5_000, "A impressora não listou os serviços")

            val c = escolherCaracteristica(g) ?: throw ImpressoraException("Esta impressora não tem o canal de impressão esperado")
            val descritor = c.getDescriptor(CCCD) ?: throw ImpressoraException("Esta impressora não tem o canal de impressão esperado")
            val liga = CompletableDeferred<Unit>().also { aguardaDescritor = it }
            bt { g.setCharacteristicNotification(c, true) }
            descritor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!bt { g.writeDescriptor(descritor) }) throw ImpressoraException("Falha ao ligar as notificações da impressora")
            aguardar(liga, 5_000, "A impressora não ligou as notificações")

            caracteristica = c
            _conectado.value = true
        } catch (e: Throwable) {
            fechar()
            throw e
        }
    }

    /** Primeira característica com WRITE_NO_RESPONSE e NOTIFY de um serviço de 128 bits (ignora os padrão de 16 bits). */
    private fun escolherCaracteristica(g: BluetoothGatt): BluetoothGattCharacteristic? {
        for (s in g.services.orEmpty()) {
            if (s.uuid.toString().endsWith(BASE_16_BITS)) continue
            for (c in s.characteristics) {
                val p = c.properties
                if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 && p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) return c
            }
        }
        return null
    }

    /** Quanto cabe numa escrita (`mtu - 3`); quem agrupa pacotes numa escrita usa isso como teto. */
    val bytesPorEscrita: Int get() = (mtu - 3).coerceAtLeast(20)

    /**
     * Escreve sem resposta, em pedaços de `mtu - 3`, esperando cada `onCharacteristicWrite` (o GATT é serial).
     * Sem pausa entre escritas: no Android a confirmação já faz o controle de fluxo (o niimbluelib espera 10 ms
     * porque o Web Bluetooth não tem isso, e eram centenas de linhas por etiqueta).
     */
    suspend fun escrever(bytes: ByteArray) = mutexEscrita.withLock {
        val g = gatt
        val c = caracteristica
        if (g == null || c == null || !_conectado.value) throw ImpressoraException("Impressora desconectada")
        val max = bytesPorEscrita
        var i = 0
        while (i < bytes.size) {
            val parte = bytes.copyOfRange(i, minOf(i + max, bytes.size))
            val feita = CompletableDeferred<Unit>().also { aguardaEscrita = it }
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            c.value = parte
            if (!bt { g.writeCharacteristic(c) }) throw ImpressoraException("Falha ao enviar dados à impressora")
            aguardar(feita, 5_000, "A impressora não confirmou o envio")
            i += max
        }
    }

    /** Fecha a conexão por pedido do app (não conta como queda). */
    fun fechar() {
        geracao.incrementAndGet() // o DISCONNECTED que vier deste gatt é ignorado
        val g = gatt
        gatt = null
        caracteristica = null
        _conectado.value = false
        abortarEsperas("Impressora desconectada")
        if (g != null) runCatching { g.disconnect(); g.close() }
    }

    companion object {
        val SERVICO: UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val BASE_16_BITS = "-0000-1000-8000-00805f9b34fb"
        private val PREFIXOS = listOf("B31", "B21", "B1", "D110")
    }
}
