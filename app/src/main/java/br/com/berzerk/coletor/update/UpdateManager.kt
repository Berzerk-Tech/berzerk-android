package br.com.berzerk.coletor.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import br.com.berzerk.coletor.api.AndroidReleaseDto
import br.com.berzerk.coletor.api.AppDesatualizadoErro
import br.com.berzerk.coletor.api.NexusApi
import br.com.berzerk.coletor.api.mensagemCurta
import br.com.berzerk.coletor.core.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

sealed interface Atualizacao {
    data object Nenhuma : Atualizacao
    /** Há versão mais nova publicada; o app continua funcionando. */
    data class Disponivel(val release: AndroidReleaseDto) : Atualizacao
    /** O Nexus respondeu 426: abaixo da mínima, só resta atualizar. */
    data class Obrigatoria(val erro: AppDesatualizadoErro, val release: AndroidReleaseDto?) : Atualizacao
    data class Baixando(val pct: Int, val release: AndroidReleaseDto, val obrigatoria: Boolean) : Atualizacao
    data class Baixado(val arquivo: File, val release: AndroidReleaseDto, val obrigatoria: Boolean) : Atualizacao
    data class Erro(val mensagem: String, val release: AndroidReleaseDto?, val obrigatoria: Boolean) : Atualizacao
}

/**
 * Atualização pelo Nexus, no molde do Berzerk Client: a versão mínima vem do
 * admin (426 `app_desatualizado` em toda rota operacional) e a release atual de
 * `GET /android/release` (que lê o `latest.json` do GitHub Release). O app baixa
 * o APK e abre o instalador do Android; o usuário confirma a instalação.
 */
class UpdateManager(private val api: () -> NexusApi, private val http: OkHttpClient) {
    private val _estado = MutableStateFlow<Atualizacao>(Atualizacao.Nenhuma)
    val estado: StateFlow<Atualizacao> = _estado

    suspend fun verificar() {
        val release = runCatching { api().androidRelease() }.getOrElse {
            Log.w(TAG, "release indisponível: ${it.mensagemCurta()}")
            return
        }
        val atual = _estado.value
        if (atual is Atualizacao.Obrigatoria) {
            _estado.value = atual.copy(release = release)
            return
        }
        if (atual is Atualizacao.Nenhuma || atual is Atualizacao.Disponivel) {
            _estado.value = if (compararSemver(release.versao, Config.versao) > 0 && release.apk != null) Atualizacao.Disponivel(release) else Atualizacao.Nenhuma
        }
    }

    fun marcarObrigatoria(erro: AppDesatualizadoErro) {
        val atual = _estado.value
        val release = when (atual) {
            is Atualizacao.Disponivel -> atual.release
            is Atualizacao.Obrigatoria -> atual.release
            is Atualizacao.Baixando -> return
            is Atualizacao.Baixado -> return
            is Atualizacao.Erro -> atual.release
            Atualizacao.Nenhuma -> null
        }
        _estado.value = Atualizacao.Obrigatoria(erro, release)
    }

    fun ignorarDisponivel() {
        if (_estado.value is Atualizacao.Disponivel) _estado.value = Atualizacao.Nenhuma
    }

    suspend fun baixar(context: Context, release: AndroidReleaseDto, obrigatoria: Boolean) = withContext(Dispatchers.IO) {
        val apk = release.apk ?: run {
            _estado.value = Atualizacao.Erro("A release ${release.versao} não tem APK publicado.", release, obrigatoria)
            return@withContext
        }
        try {
            _estado.value = Atualizacao.Baixando(0, release, obrigatoria)
            val pasta = File(context.cacheDir, "apk").apply { mkdirs() }
            pasta.listFiles()?.forEach { it.delete() }
            val destino = File(pasta, "berzerk-coletor-${release.versao}.apk")
            val resposta = http.newCall(Request.Builder().url(apk.url).build()).execute()
            if (!resposta.isSuccessful) throw IllegalStateException("HTTP ${resposta.code} ao baixar o APK")
            val corpo = resposta.body ?: throw IllegalStateException("resposta vazia")
            val total = corpo.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            corpo.byteStream().use { entrada ->
                destino.outputStream().use { saida ->
                    val buf = ByteArray(64 * 1024)
                    var lidos = 0L
                    while (true) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        saida.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        lidos += n
                        if (total > 0) _estado.value = Atualizacao.Baixando((lidos * 100 / total).toInt(), release, obrigatoria)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (apk.sha256 != null && !apk.sha256.equals(sha, ignoreCase = true)) {
                destino.delete()
                throw IllegalStateException("O APK baixado não bate com a assinatura publicada (sha256).")
            }
            _estado.value = Atualizacao.Baixado(destino, release, obrigatoria)
        } catch (e: Throwable) {
            Log.w(TAG, "download", e)
            _estado.value = Atualizacao.Erro(e.mensagemCurta(), release, obrigatoria)
        }
    }

    /** `true` se abriu o instalador; `false` se precisou mandar o usuário liberar "fontes desconhecidas". */
    fun instalar(context: Context, arquivo: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", arquivo)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        return true
    }

    companion object {
        private const val TAG = "Update"

        /** >0 se `a` é mais nova que `b`. Só major.minor.patch; sufixo é ignorado. */
        fun compararSemver(a: String, b: String): Int {
            fun partes(v: String) = v.substringBefore('-').removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            val pa = partes(a)
            val pb = partes(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }
    }
}
