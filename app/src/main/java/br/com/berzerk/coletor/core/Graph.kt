package br.com.berzerk.coletor.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import br.com.berzerk.coletor.api.NexusApi
import br.com.berzerk.coletor.api.NexusClient
import br.com.berzerk.coletor.auth.AuthManager
import br.com.berzerk.coletor.auth.OperadorSessao
import br.com.berzerk.coletor.auth.SessaoEstado
import br.com.berzerk.coletor.data.ColetorDb
import br.com.berzerk.coletor.data.ItagSync
import br.com.berzerk.coletor.impressora.Impressora
import br.com.berzerk.coletor.update.UpdateManager
import br.com.berzerk.coletor.upload.UploadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Composição manual dos singletons do app (sem framework de DI). */
object Graph {
    lateinit var prefs: Prefs; private set
    lateinit var auth: AuthManager; private set
    lateinit var operador: OperadorSessao; private set
    lateinit var client: NexusClient; private set
    lateinit var db: ColetorDb; private set
    lateinit var itag: ItagSync; private set
    lateinit var update: UpdateManager; private set
    lateinit var upload: UploadService; private set
    lateinit var impressora: Impressora; private set
    lateinit var appContext: Context; private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Downloads fora do Nexus (S3 presignado, GitHub Release): SEM Bearer nem header de versão. */
    val httpExterno: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()

    val api: NexusApi get() = client.api

    @Volatile private var saidaPendente: Job? = null

    /**
     * "Sair da conta do aparelho": abre o `/logout` do Cognito num Custom Tab (apaga o cookie do
     * navegador) e SÓ limpa o estado local quando o callback `berzerkrfid://auth/logout` volta
     * ([concluirSaida], chamado pela `MainActivity`) ou após [SAIDA_TIMEOUT_MS]. Sem navegador
     * disponível, limpa na hora.
     */
    fun sairDoAparelho(context: Context) {
        if (saidaPendente?.isActive == true) return
        val uri: Uri = auth.urlDeLogout()
        val abriu = runCatching {
            val tab = CustomTabsIntent.Builder().build()
            tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(context, uri)
        }.onFailure { Log.w("Auth", "logout: sem navegador (${it.javaClass.simpleName})") }.isSuccess
        if (!abriu) { concluirSaida(); return }
        saidaPendente = scope.launch {
            delay(SAIDA_TIMEOUT_MS)
            concluirSaida()
        }
    }

    /** Fecha a saída: encerra o operador e apaga a sessão do Cognito. Idempotente. */
    fun concluirSaida() {
        saidaPendente?.cancel()
        saidaPendente = null
        operador.encerrar()
        // Pelo lock do AuthManager: espera um `renovar()` em andamento terminar antes de apagar os tokens.
        scope.launch { auth.limparSessaoLocal() }
    }

    /** Leituras gravadas e ainda não enviadas (bloqueiam "Sair da conta do aparelho"). */
    suspend fun leiturasNaoEnviadas(): Int = db.leituras().contarNaoEnviadas()

    private const val SAIDA_TIMEOUT_MS = 10_000L

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        prefs = Prefs(app)
        auth = AuthManager(app, prefs)
        operador = OperadorSessao(prefs, { client.api }, scope, logado = { auth.sessao.value is SessaoEstado.Logado })
        client = NexusClient(auth, operador)
        db = ColetorDb.abrir(app)
        itag = ItagSync(db, { client.api }, httpExterno, scope, app.cacheDir)
        update = UpdateManager({ client.api }, httpExterno)
        upload = UploadService(app, { client.api }, prefs, db.leituras())
        impressora = Impressora(app, prefs)
        scope.launch { client.atualizacaoObrigatoria.collect { update.marcarObrigatoria(it) } }
        scope.launch { itag.carregarStatus() }
        // Leitura já enviada há mais de 30 dias não precisa ficar no aparelho.
        scope.launch { runCatching { db.leituras().apagarEnviadasAntes(System.currentTimeMillis() - 30L * 24 * 3600_000) } }
    }
}
