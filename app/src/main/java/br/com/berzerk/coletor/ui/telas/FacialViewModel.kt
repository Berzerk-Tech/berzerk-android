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
    data object VireOLado : FacialEstado
    data object OlheParaFrente : FacialEstado
    data object Capturando : FacialEstado
    data object Enviando : FacialEstado
    data class Sucesso(val nome: String) : FacialEstado
    data class Erro(val mensagem: String) : FacialEstado

    companion object {
        const val AVISO_PROCURANDO = "Posicione o rosto no quadro"
    }
}

/**
 * Identificação do operador por rosto. Liveness simples (virar o rosto e voltar
 * pra frente) sobre o que o ML Kit entrega por frame; depois captura UM jpeg em
 * memória e manda pro Nexus. A foto nunca vai pra disco nem pra log.
 */
class FacialViewModel : ViewModel() {
    private val _estado = MutableStateFlow<FacialEstado>(FacialEstado.Procurando())
    val estado: StateFlow<FacialEstado> = _estado

    private var framesDeFrente = 0
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

    private fun ativo(e: FacialEstado) = e is FacialEstado.Procurando || e is FacialEstado.VireOLado || e is FacialEstado.OlheParaFrente

    /** Troca de estado; só reinicia o relógio dos 30 s quando muda a FASE (aviso novo não conta como avanço). */
    private fun mudar(novo: FacialEstado) {
        val antigo = _estado.value
        if (antigo::class != novo::class) desde = agora()
        _estado.value = novo
    }

    /**
     * Resultado da detecção de UM frame (thread do analisador).
     * `larguraRelativa` = largura da bounding box ÷ largura do frame (0..1).
     */
    @Synchronized
    fun aoDeteccao(rostos: Int, larguraRelativa: Float, yaw: Float, roll: Float) {
        val atual = _estado.value
        if (!ativo(atual)) return
        when {
            rostos == 0 -> {
                framesDeFrente = 0
                mudar(FacialEstado.Procurando())
            }
            rostos > 1 -> {
                framesDeFrente = 0
                mudar(FacialEstado.Procurando("Só uma pessoa no quadro"))
            }
            atual is FacialEstado.Procurando -> {
                if (larguraRelativa < LARGURA_MIN) mudar(FacialEstado.Procurando("Aproxime-se")) else mudar(FacialEstado.VireOLado)
            }
            atual is FacialEstado.VireOLado -> {
                // Independe do sinal do ângulo: vale virar pra qualquer lado.
                if (abs(yaw) >= YAW_VIRADO) {
                    framesDeFrente = 0
                    mudar(FacialEstado.OlheParaFrente)
                }
            }
            atual is FacialEstado.OlheParaFrente -> {
                if (abs(yaw) <= YAW_FRENTE && abs(roll) <= ROLL_MAX) {
                    framesDeFrente++
                    if (framesDeFrente >= FRAMES_FRENTE) mudar(FacialEstado.Capturando)
                } else {
                    framesDeFrente = 0
                }
            }
        }
    }

    @Synchronized
    fun tentarDeNovo() {
        framesDeFrente = 0
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
        private const val YAW_VIRADO = 25f
        private const val YAW_FRENTE = 12f
        private const val ROLL_MAX = 15f
        private const val FRAMES_FRENTE = 3
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
