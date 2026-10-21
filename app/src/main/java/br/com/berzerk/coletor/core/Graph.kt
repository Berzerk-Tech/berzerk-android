package br.com.berzerk.coletor.core

import android.content.Context
import br.com.berzerk.coletor.api.NexusApi
import br.com.berzerk.coletor.api.NexusClient
import br.com.berzerk.coletor.auth.AuthManager
import br.com.berzerk.coletor.auth.OperadorSessao
import br.com.berzerk.coletor.auth.SessaoEstado
import br.com.berzerk.coletor.data.ColetorDb
import br.com.berzerk.coletor.data.ItagSync
import br.com.berzerk.coletor.update.UpdateManager
import br.com.berzerk.coletor.upload.UploadService
import kotlinx.coroutines.CoroutineScope
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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Downloads fora do Nexus (S3 presignado, GitHub Release): SEM Bearer nem header de versão. */
    val httpExterno: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()

    val api: NexusApi get() = client.api

    fun init(context: Context) {
        val app = context.applicationContext
        prefs = Prefs(app)
        auth = AuthManager(app, prefs)
        operador = OperadorSessao(prefs, { client.api }, scope, logado = { auth.sessao.value is SessaoEstado.Logado })
        client = NexusClient(auth, operador)
        db = ColetorDb.abrir(app)
        itag = ItagSync(db, { client.api }, httpExterno, scope, app.cacheDir)
        update = UpdateManager({ client.api }, httpExterno)
        upload = UploadService(app) { client.api }
        scope.launch { client.atualizacaoObrigatoria.collect { update.marcarObrigatoria(it) } }
        scope.launch { itag.carregarStatus() }
    }
}
