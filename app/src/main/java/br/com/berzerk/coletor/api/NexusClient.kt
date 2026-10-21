package br.com.berzerk.coletor.api

import android.util.Log
import br.com.berzerk.coletor.BuildConfig
import br.com.berzerk.coletor.auth.AuthManager
import br.com.berzerk.coletor.auth.OperadorSessao
import br.com.berzerk.coletor.core.Config
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP do Nexus. Em toda request: header de versão (gate 426) e, salvo
 * nas rotas marcadas `SEM_AUTH`, `Authorization: Bearer <id token>` com
 * renovação silenciosa. Um 401 renova UMA vez e repete (como o api client do
 * web); refresh recusado derruba a sessão. Com operador identificado, leva também
 * `x-berzerk-operador`; 403 `operador_sessao_invalida` vira `OperadorSessaoInvalidaErro`
 * e limpa SÓ a sessão de operador (a do aparelho fica). 426 vira `AppDesatualizadoErro`
 * publicado em `atualizacaoObrigatoria`.
 */
class NexusClient(private val auth: AuthManager, private val operador: OperadorSessao) {
    val atualizacaoObrigatoria = MutableSharedFlow<AppDesatualizadoErro>(replay = 1)

    private val versaoInterceptor = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header(Config.VERSAO_HEADER, Config.versao).build())
    }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val semAuth = original.header(NexusApi.SEM_AUTH) != null
        val builder = original.newBuilder().removeHeader(NexusApi.SEM_AUTH)
        // O header some antes de sair; a tag fica na Request pros outros interceptors e pro authenticator.
        if (semAuth) builder.tag(SemAuth::class.java, SemAuth)
        if (!semAuth) {
            val token = runBlocking { auth.idTokenValido() }
            if (token != null) builder.header("Authorization", "Bearer $token")
        }
        chain.proceed(builder.build())
    }

    private val operadorInterceptor = Interceptor { chain ->
        val original = chain.request()
        // Header já posto à mão (DELETE da sessão) ou rota SEM_AUTH: não mexe.
        if (original.header(Config.OPERADOR_HEADER) != null || original.tag(SemAuth::class.java) != null) {
            return@Interceptor chain.proceed(original)
        }
        val token = operador.tokenValido()
        val req = if (token != null) original.newBuilder().header(Config.OPERADOR_HEADER, token).build() else original
        chain.proceed(req)
    }

    private val retryAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.request.tag(SemAuth::class.java) != null) return null
            // Só uma tentativa por request (o OkHttp encadeia `priorResponse`).
            if (response.priorResponse?.code == 401) return null
            val novo = runBlocking { auth.renovar() } ?: return null
            return response.request.newBuilder().header("Authorization", "Bearer $novo").build()
        }
    }

    private val errosInterceptor = Interceptor { chain ->
        val resposta = chain.proceed(chain.request())
        if (resposta.isSuccessful) return@Interceptor resposta
        val corpo = resposta.peekBody(256 * 1024).string()
        val codigo = ApiException.codigoDe(corpo)
        if (resposta.code == 426) {
            runCatching { JsonCfg.json.decodeFromString<AppDesatualizadoErro>(corpo) }.getOrNull()
                ?.let { atualizacaoObrigatoria.tryEmit(it) }
        }
        if (resposta.code == 403 && codigo == OperadorSessaoInvalidaErro.CODIGO) {
            // Só derruba se o token recusado ainda é o atual (request antiga não derruba operador novo).
            operador.encerrarSe(chain.request().header(Config.OPERADOR_HEADER))
            throw OperadorSessaoInvalidaErro(corpo, chain.request().url.toString())
        }
        throw ApiException(resposta.code, codigo, corpo, chain.request().url.toString())
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(versaoInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(operadorInterceptor)
        .authenticator(retryAuthenticator)
        // Depois do authenticator na cadeia: o 401 primeiro tenta renovar; só o
        // 401 definitivo (ou qualquer outro erro) vira ApiException.
        .addInterceptor(errosInterceptor)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor { Log.d("Nexus", it) }.apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                    redactHeader(Config.OPERADOR_HEADER)
                    redactHeader("x-upload-token")
                })
            }
        }
        .build()

    val api: NexusApi = Retrofit.Builder()
        .baseUrl(Config.apiBaseUrl)
        .client(http)
        .addConverterFactory(JsonCfg.json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(NexusApi::class.java)
}

/** Tag de Request marcando rota `SEM_AUTH` (o header é removido antes de sair; a tag não vai pro fio). */
private object SemAuth

/** Converte o `HttpException` do Retrofit (quando escapa) na nossa `ApiException`. */
fun Throwable.comoApi(): Throwable = when (this) {
    is HttpException -> ApiException(code(), ApiException.codigoDe(response()?.errorBody()?.string()), null, response()?.raw()?.request?.url?.toString() ?: "")
    else -> this
}
