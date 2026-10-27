package br.com.berzerk.coletor.impressora

import android.content.Context
import br.com.berzerk.coletor.core.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

sealed class EstadoImpressora {
    object Desconectada : EstadoImpressora()
    object Conectando : EstadoImpressora()
    data class Conectada(val nome: String, val bateria: Int?) : EstadoImpressora()
    data class Imprimindo(val nome: String, val fila: Int) : EstadoImpressora()
    data class Erro(val msg: String, val nome: String?) : EstadoImpressora()
}

/**
 * Uma etiqueta a imprimir: o render ([EtiquetaRender]) acontece no worker da fila, na ordem dos toques,
 * pra o toque ser instantâneo e a etiqueta não sumir se o operador sair da tela. [rotulo] = console ("G").
 */
data class TrabalhoImpressao(
    val rotulo: String,
    val tamanho: String,
    val ean: String,
    val nome: String,
    val larguraPx: Int,
    val alturaPx: Int,
    val copias: Int,
)

/**
 * A impressora térmica do coletor (Niimbot B31 por BLE): estado de conexão, fila serial de impressão e
 * console de eventos. Um singleton em `Graph.impressora`; a conexão sobrevive à troca de tela.
 */
class Impressora(ctx: Context, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ble = NiimbotBle(ctx.applicationContext)
    private val proto = NiimbotProtocolo(ble)

    private val _estado = MutableStateFlow<EstadoImpressora>(EstadoImpressora.Desconectada)
    val estado: StateFlow<EstadoImpressora> = _estado

    private val _eventos = MutableSharedFlow<String>(extraBufferCapacity = 32)
    /** Linhas pro console ("Imprimindo G ×1…", "Saiu G", "Erro: …"). */
    val eventos: SharedFlow<String> = _eventos

    private val _console = MutableStateFlow<List<String>>(emptyList())
    /** As últimas linhas de [eventos], pra tela montar o console mesmo depois de ter saído e voltado. */
    val console: StateFlow<List<String>> = _console

    private val _ultimaImpressaoEm = MutableStateFlow<Long?>(null)
    val ultimaImpressaoEm: StateFlow<Long?> = _ultimaImpressaoEm

    private val canal = Channel<TrabalhoImpressao>(Channel.UNLIMITED)
    private val pendentes = AtomicInteger(0)
    private val mutexConexao = Mutex()
    @Volatile private var bateria: Int? = null

    init {
        scope.launch { for (job in canal) processar(job) }
        scope.launch {
            ble.desconexoes.collect {
                if (_estado.value !is EstadoImpressora.Desconectada) {
                    _estado.value = EstadoImpressora.Erro("Impressora desconectou", nomeSalvo())
                    registrar("Erro: Impressora desconectou")
                }
            }
        }
    }

    private fun nomeSalvo(): String = prefs.impressoraNome ?: prefs.impressoraMac ?: "Impressora"

    private fun registrar(linha: String) {
        _console.value = (_console.value + linha).takeLast(20)
        _eventos.tryEmit(linha)
    }

    /** Varre 8 s atrás de Niimbot por perto. Lança [ImpressoraException] sem permissão/Bluetooth. */
    suspend fun buscar(): List<AparelhoBle> = ble.buscar()

    /** Conecta em [mac] (o salvo, por padrão) sem bloquear; no sucesso grava mac e nome nas prefs. */
    fun conectar(mac: String? = prefs.impressoraMac, nome: String? = null) {
        val alvo = mac ?: return
        if (_estado.value is EstadoImpressora.Conectando || _estado.value is EstadoImpressora.Imprimindo) return
        scope.launch { conectarAgora(alvo, nome) }
    }

    /** Reconexão ao entrar numa tela de impressão: só se há impressora salva e nada em andamento. */
    fun reconectarSeNecessario() {
        if (_estado.value is EstadoImpressora.Desconectada && prefs.impressoraMac != null) conectar()
    }

    fun desconectar() {
        ble.fechar()
        _estado.value = EstadoImpressora.Desconectada
    }

    /** "Esquecer": desconecta e apaga mac e nome salvos. */
    fun esquecer() {
        desconectar()
        prefs.impressoraMac = null
        prefs.impressoraNome = null
    }

    fun enfileirar(job: TrabalhoImpressao) {
        pendentes.incrementAndGet()
        canal.trySend(job)
    }

    private suspend fun conectarAgora(mac: String, nomeNovo: String?): Boolean = mutexConexao.withLock {
        val nome = nomeNovo ?: prefs.impressoraNome?.takeIf { prefs.impressoraMac == mac } ?: mac
        if (ble.conectado.value) {
            // Já conectada nesta impressora: só limpa um Erro antigo (ex.: tampa aberta) e segue.
            if (mac == prefs.impressoraMac) {
                _estado.value = EstadoImpressora.Conectada(nome, bateria)
                return@withLock true
            }
            // Trocou de impressora em Ajustes: derruba a atual e conecta na nova.
            ble.fechar()
        }
        _estado.value = EstadoImpressora.Conectando
        try {
            ble.conectar(mac)
            val neg = proto.negociar()
            val info = proto.infoImpressora()
            bateria = info.bateria
            prefs.impressoraMac = mac
            prefs.impressoraNome = nome
            registrar("Conectada: $nome (protocolo ${neg.versaoProtocolo}, modelo ${info.modeloId ?: "?"})")
            _estado.value = EstadoImpressora.Conectada(nome, info.bateria)
            true
        } catch (e: CancellationException) {
            ble.fechar()
            throw e
        } catch (e: Exception) {
            ble.fechar()
            val msg = (e as? ImpressoraException)?.message ?: "Falha ao conectar na impressora"
            _estado.value = EstadoImpressora.Erro(msg, nome)
            registrar("Erro: $msg")
            false
        }
    }

    private suspend fun processar(job: TrabalhoImpressao) {
        try {
            if (!ble.conectado.value) {
                val mac = prefs.impressoraMac
                if (mac == null || !conectarAgora(mac, null)) {
                    if (mac == null) {
                        _estado.value = EstadoImpressora.Erro("Nenhuma impressora pareada", null)
                        registrar("Erro: nenhuma impressora pareada")
                    }
                    // Sem impressora, o resto da fila também não sai: descarta tudo de uma vez em vez de
                    // tentar reconectar (10 s cada) etiqueta por etiqueta.
                    descartarFila(1)
                    return
                }
            }
            val nome = nomeSalvo()
            _estado.value = EstadoImpressora.Imprimindo(nome, pendentes.get())
            registrar("Imprimindo ${job.rotulo} ×${job.copias}…")
            val bitmap = EtiquetaRender.renderizar(job.tamanho, job.ean, job.nome, job.larguraPx, job.alturaPx)
            val img = try { CodificadorImagem.codificar(bitmap) } finally { bitmap.recycle() }
            TarefaImpressao.imprimir(
                proto, img, job.copias,
                densidade = prefs.impressoraDensidade,
                papel = prefs.impressoraPapel,
                modo = runCatching { ModoImpressao.valueOf(prefs.impressoraModo) }.getOrDefault(ModoImpressao.AUTO),
                cabecaPx = CABECA_PX,
            )
            registrar("Saiu ${job.rotulo}")
            _ultimaImpressaoEm.value = System.currentTimeMillis()
            if (ble.conectado.value && pendentes.get() <= 1) _estado.value = EstadoImpressora.Conectada(nome, bateria)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = (e as? ImpressoraException)?.message ?: "Falha ao imprimir"
            _estado.value = EstadoImpressora.Erro(msg, nomeSalvo())
            registrar("Erro: $msg")
        } finally {
            pendentes.decrementAndGet()
        }
    }

    /** Esvazia a fila (o trabalho atual, já fora do canal, conta em [jaContados]) e avisa no console. */
    private fun descartarFila(jaContados: Int) {
        var n = 0
        while (canal.tryReceive().isSuccess) n++
        if (n == 0) return
        pendentes.addAndGet(-n)
        registrar("Erro: $n etiqueta${if (n > 1) "s" else ""} descartada${if (n > 1) "s" else ""} (${n + jaContados} na fila sem impressora)")
    }
}
