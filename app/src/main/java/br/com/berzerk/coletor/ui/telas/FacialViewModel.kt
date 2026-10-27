package br.com.berzerk.coletor.ui.telas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.berzerk.coletor.api.ApiException
import br.com.berzerk.coletor.api.FacialCodigos
import br.com.berzerk.coletor.core.Graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

sealed interface FacialEstado {
    /** Nenhum rosto utilizável ainda; `aviso` explica o porquê. */
    data class Procurando(val aviso: String = AVISO_PROCURANDO) : FacialEstado
    /** Rosto de frente e perto: esperando a piscada (os dois olhos). */
    data object Pisque : FacialEstado
    data object Capturando : FacialEstado
    data object Enviando : FacialEstado
    data class Sucesso(val nome: String) : FacialEstado
    data class Erro(val mensagem: String) : FacialEstado

    companion object {
        const val AVISO_PROCURANDO = "Posicione o rosto no quadro"
    }
}

/**
 * Identificação do operador por rosto. Liveness simples (uma piscada com os dois
 * olhos, com o rosto de frente) sobre a classificação de olho aberto que o ML Kit
 * entrega por frame; depois captura UM jpeg em memória e manda pro Nexus. A foto
 * nunca vai pra disco nem pra log.
 */
class FacialViewModel : ViewModel() {
    private val _estado = MutableStateFlow<FacialEstado>(FacialEstado.Procurando())
    val estado: StateFlow<FacialEstado> = _estado

    /** Viu os dois olhos fechados (e quando): a piscada completa quando reabrem logo depois. */
    private var olhosFechados = false
    private var fechouEm = 0L
    private var desde = agora()
    private var vigia: Job? = null

    init {
        vigia = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                synchronized(this@FacialViewModel) {
                    if (ativo(_estado.value) && agora() - desde > TIMEOUT_MS) mudar(FacialEstado.Erro("Não consegui. Tente de novo."))
                }
            }
        }
    }

    private fun ativo(e: FacialEstado) = e is FacialEstado.Procurando || e is FacialEstado.Pisque

    /** Troca de estado; só reinicia o relógio dos 30 s quando muda a FASE (aviso novo não conta como avanço). */
    private fun mudar(novo: FacialEstado) {
        val antigo = _estado.value
        if (antigo::class != novo::class) desde = agora()
        _estado.value = novo
    }

    /**
     * Resultado da detecção de UM frame (thread do analisador).
     * `larguraRelativa` = largura da bounding box ÷ largura do frame (0..1);
     * `olhoEsq`/`olhoDir` = probabilidade de cada olho estar aberto (0..1), ou -1 quando o ML Kit não classificou.
     */
    @Synchronized
    fun aoDeteccao(rostos: Int, larguraRelativa: Float, yaw: Float, roll: Float, olhoEsq: Float, olhoDir: Float) {
        val atual = _estado.value
        if (!ativo(atual)) return
        val deFrente = abs(yaw) <= YAW_FRENTE && abs(roll) <= ROLL_MAX
        when {
            rostos == 0 -> {
                olhosFechados = false
                mudar(FacialEstado.Procurando())
            }
            rostos > 1 -> {
                olhosFechados = false
                mudar(FacialEstado.Procurando("Só uma pessoa no quadro"))
            }
            larguraRelativa < LARGURA_MIN -> {
                olhosFechados = false
                mudar(FacialEstado.Procurando("Aproxime-se"))
            }
            !deFrente -> {
                olhosFechados = false
                mudar(FacialEstado.Procurando("Olhe para a frente"))
            }
            atual is FacialEstado.Procurando -> {
                olhosFechados = false
                mudar(FacialEstado.Pisque)
            }
            atual is FacialEstado.Pisque -> {
                if (olhoEsq < 0f || olhoDir < 0f) return // frame sem classificação dos olhos
                val fechados = olhoEsq < OLHO_FECHADO && olhoDir < OLHO_FECHADO
                val abertos = olhoEsq > OLHO_ABERTO && olhoDir > OLHO_ABERTO
                if (!olhosFechados && fechados) {
                    olhosFechados = true
                    fechouEm = agora()
                } else if (olhosFechados && abertos) {
                    // Fechou e reabriu rápido = piscada; demorou demais (olhos fechados parado) = recomeça.
                    if (agora() - fechouEm <= PISCADA_MAX_MS) mudar(FacialEstado.Capturando) else olhosFechados = false
                }
            }
        }
    }

    @Synchronized
    fun tentarDeNovo() {
        olhosFechados = false
        desde = agora()
        _estado.value = FacialEstado.Procurando()
    }

    /** Falha ao capturar/preparar o jpeg (câmera). */
    @Synchronized
    fun falhaNaCaptura() {
        mudar(FacialEstado.Erro("Não consegui tirar a foto. Tente de novo."))
    }

    /** `bindToLifecycle` falhou (aparelho sem suporte a preview + análise + captura juntos, câmera ocupada). */
    @Synchronized
    fun falhaNaCamera() {
        mudar(FacialEstado.Erro("Câmera indisponível. Feche outros apps que usam a câmera e tente de novo."))
    }

    fun enviar(jpeg: ByteArray) {
        _estado.value = FacialEstado.Enviando
        viewModelScope.launch {
            val parte = MultipartBody.Part.createFormData("foto", "rosto.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            runCatching { Graph.api.identificarOperador(parte, Graph.upload.dispositivoId, Graph.upload.modelo) }
                .onSuccess {
                    if (Graph.operador.iniciar(it)) mudar(FacialEstado.Sucesso(it.operador.nome))
                    else mudar(FacialEstado.Erro("Resposta inválida do Nexus. Tente de novo."))
                }
                .onFailure {
                    Log.w(TAG, "identificação falhou: ${(it as? ApiException)?.let { e -> "${e.status} ${e.codigo}" } ?: it.javaClass.simpleName}")
                    mudar(FacialEstado.Erro(mensagemDe(it)))
                }
        }
    }

    private fun mensagemDe(e: Throwable): String = when ((e as? ApiException)?.codigo) {
        FacialCodigos.ROSTO_NAO_RECONHECIDO -> "Rosto não reconhecido. Peça pro supervisor cadastrar seu rosto no Nexus."
        FacialCodigos.ROSTO_NAO_DETECTADO -> "Não achei um rosto na foto. Tente de novo."
        FacialCodigos.VARIOS_ROSTOS -> "Mais de um rosto na foto."
        FacialCodigos.OPERADOR_INATIVO -> "Operador inativo. Fale com o supervisor."
        else -> "Não deu pra falar com o Nexus. Tente de novo."
    }

    private fun agora() = System.currentTimeMillis()

    companion object {
        private const val TAG = "Facial"
        private const val TIMEOUT_MS = 30_000L
        private const val LARGURA_MIN = 0.30f
        private const val YAW_FRENTE = 12f
        private const val ROLL_MAX = 15f
        /** Probabilidade de olho aberto do ML Kit: abaixo disso conta como fechado, acima como aberto (meio = ignora). */
        private const val OLHO_FECHADO = 0.3f
        private const val OLHO_ABERTO = 0.6f
        /** Tempo máximo entre fechar e reabrir pra contar como piscada. */
        private const val PISCADA_MAX_MS = 1_500L
        private const val LADO_MAX = 1024
        private const val QUALIDADE = 85

        /** JPEG da câmera → gira por `rotacao`, lado maior ≤ 1024 px, recomprime a 85. Só em memória. */
        fun prepararJpeg(bruto: ByteArray, rotacao: Int): ByteArray? {
            val original = BitmapFactory.decodeByteArray(bruto, 0, bruto.size) ?: return null
            var atual: Bitmap = original
            try {
                val escala = LADO_MAX.toFloat() / max(atual.width, atual.height)
                val m = Matrix()
                if (rotacao != 0) m.postRotate(rotacao.toFloat())
                if (escala < 1f) m.postScale(escala, escala)
                if (!m.isIdentity) {
                    atual = Bitmap.createBitmap(original, 0, 0, original.width, original.height, m, true)
                }
                val saida = ByteArrayOutputStream()
                atual.compress(Bitmap.CompressFormat.JPEG, QUALIDADE, saida)
                return saida.toByteArray()
            } finally {
                if (atual !== original) atual.recycle()
                original.recycle()
            }
        }
    }
}
