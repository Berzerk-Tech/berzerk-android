package br.com.berzerk.coletor.ui.telas

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.berzerk.coletor.core.Config
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.ui.comum.BotaoGrande
import br.com.berzerk.coletor.ui.comum.Rotulo
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Fundo
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Vermelho
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Identificação do operador por rosto, depois do login da conta do aparelho.
 * Câmera frontal + ML Kit só pra guiar o "vire o rosto e volte" (liveness
 * simples); quem reconhece o rosto é o Nexus. Foto só em memória.
 */
@Composable
fun FacialScreen(aoConcluir: () -> Unit, vm: FacialViewModel = viewModel()) {
    val contexto = LocalContext.current
    val escopoSaida = androidx.compose.runtime.rememberCoroutineScope()
    var avisoSaida by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val estado by vm.estado.collectAsState()

    var temPermissao by remember { mutableStateOf(permissaoCamera(contexto)) }
    var pediu by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { temPermissao = it }

    LaunchedEffect(Unit) {
        if (!temPermissao && !pediu) { pediu = true; launcher.launch(Manifest.permission.CAMERA) }
    }
    // Voltou das configurações do app: reavalia a permissão.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) temPermissao = permissaoCamera(contexto) }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val captura = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    LaunchedEffect(estado) {
        when (val e = estado) {
            FacialEstado.Capturando -> {
                val jpeg = capturarJpeg(captura, ContextCompat.getMainExecutor(contexto))
                if (jpeg != null) vm.enviar(jpeg) else vm.falhaNaCaptura()
            }
            is FacialEstado.Sucesso -> { delay(1_000); aoConcluir() }
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize().background(Fundo).systemBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Rotulo("Identificação do operador")
        Spacer(Modifier.height(6.dp))
        Text(instrucao(estado).uppercase(), style = MaterialTheme.typography.displaySmall, color = Texto, minLines = 2, maxLines = 3)
        Spacer(Modifier.height(16.dp))

        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Borda, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (temPermissao) {
                CameraPreview(captura = captura, ativo = estado is FacialEstado.Procurando || estado is FacialEstado.VireOLado || estado is FacialEstado.OlheParaFrente, vm = vm)
            } else {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("O app precisa da câmera pra identificar o operador.", color = Texto, style = MaterialTheme.typography.bodyLarge)
                    BotaoGrande("Permitir", onClick = { abrirConfiguracoes(contexto) })
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.TopStart) {
            when (val e = estado) {
                is FacialEstado.Erro -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(e.mensagem, color = Vermelho, style = MaterialTheme.typography.bodyMedium)
                    BotaoGrande("Tentar de novo", onClick = vm::tentarDeNovo)
                }
                FacialEstado.Enviando, FacialEstado.Capturando -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Texto, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(12.dp))
                    Text("Identificando...", color = TextoFraco)
                }
                else -> Unit
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                if (avisoSaida != null) Text(avisoSaida!!, color = Vermelho, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    escopoSaida.launch {
                        val n = Graph.leiturasNaoEnviadas()
                        if (n > 0) avisoSaida = "Há $n leituras não enviadas. Envie ou zere antes de sair."
                        else { avisoSaida = null; Graph.sairDoAparelho(contexto) }
                    }
                }) { Text("Sair da conta do aparelho", color = TextoFraco, style = MaterialTheme.typography.bodySmall) }
            }
            Text(Config.versao, style = MaterialTheme.typography.bodySmall, color = TextoFraco)
        }
    }
}

private fun instrucao(e: FacialEstado): String = when (e) {
    is FacialEstado.Procurando -> e.aviso
    FacialEstado.VireOLado -> "Vire o rosto para um lado"
    FacialEstado.OlheParaFrente -> "Agora olhe para a frente"
    FacialEstado.Capturando, FacialEstado.Enviando -> "Identificando..."
    is FacialEstado.Sucesso -> "Olá, ${e.nome}"
    is FacialEstado.Erro -> "Não deu certo"
}

private fun permissaoCamera(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun abrirConfiguracoes(c: Context) {
    runCatching {
        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Preview + análise (ML Kit) + captura, presos ao ciclo de vida da tela. */
@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(captura: ImageCapture, ativo: Boolean, vm: FacialViewModel) {
    val contexto = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(contexto).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val analiseExecutor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.2f)
                .build(),
        )
    }
    // O analisador roda fora da composição: lê o flag mais recente por aqui.
    val ativoAgora = remember { java.util.concurrent.atomic.AtomicBoolean(ativo) }
    ativoAgora.set(ativo)

    DisposableEffect(lifecycleOwner) {
        var provider: ProcessCameraProvider? = null
        val futuro = ProcessCameraProvider.getInstance(contexto)
        futuro.addListener({
            val p = futuro.get().also { provider = it }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analise = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(480, 640))
                .build()
            analise.setAnalyzer(analiseExecutor) { proxy ->
                val midia = proxy.image
                if (midia == null || !ativoAgora.get()) { proxy.close(); return@setAnalyzer }
                val rotacao = proxy.imageInfo.rotationDegrees
                // A bounding box vem no sistema de coordenadas já girado.
                val larguraFrame = (if (rotacao % 180 == 0) proxy.width else proxy.height).toFloat()
                detector.process(InputImage.fromMediaImage(midia, rotacao))
                    .addOnSuccessListener { rostos ->
                        val r = rostos.firstOrNull()
                        vm.aoDeteccao(
                            rostos = rostos.size,
                            larguraRelativa = if (r != null) r.boundingBox.width() / larguraFrame else 0f,
                            yaw = r?.headEulerAngleY ?: 0f,
                            roll = r?.headEulerAngleZ ?: 0f,
                        )
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            val seletor = if (p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, seletor, preview, analise, captura)
            }.onFailure {
                Log.w("Facial", "câmera indisponível: ${it.javaClass.simpleName}")
                vm.falhaNaCamera()
            }
        }, ContextCompat.getMainExecutor(contexto))
        onDispose {
            provider?.unbindAll()
            analiseExecutor.shutdown()
            detector.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/**
 * Tira UMA foto em memória e devolve o jpeg já girado/reduzido (`null` = falhou). Nada vai pra disco.
 * O callback roda no executor principal (não é desligado com a tela, então o `ImageProxy` sempre
 * fecha); só a cópia dos bytes acontece lá e o decode/redimensionamento vai pra `Default`.
 */
private suspend fun capturarJpeg(captura: ImageCapture, executor: Executor): ByteArray? {
    val bruto: Pair<ByteArray, Int> = suspendCancellableCoroutine { cont ->
        captura.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val copia = try {
                    val buf = image.planes[0].buffer
                    ByteArray(buf.remaining()).also { buf.get(it) } to image.imageInfo.rotationDegrees
                } catch (_: Exception) {
                    null
                } finally {
                    image.close()
                }
                if (cont.isActive) cont.resume(copia)
            }

            override fun onError(exception: ImageCaptureException) {
                if (cont.isActive) cont.resume(null)
            }
        })
    } ?: return null
    return withContext(Dispatchers.Default) { runCatching { FacialViewModel.prepararJpeg(bruto.first, bruto.second) }.getOrNull() }
}
