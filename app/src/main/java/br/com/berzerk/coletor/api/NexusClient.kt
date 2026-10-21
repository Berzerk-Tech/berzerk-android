package br.com.berzerk.coletor.api

import android.util.Log
import br.com.berzerk.coletor.BuildConfig
import br.com.berzerk.coletor.auth.AuthManager
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
 * web); refresh recusado derruba a sessão. 426 vira `AppDesatualizadoErro`
 * publicado em `atualizacaoObrigatoria`.
 */
class NexusClient(private val auth: AuthManager) {
    val atualizacaoObrigatoria = MutableSharedFlow<AppDesatualizadoErro>(replay = 1)

    private val versaoInterceptor = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header(Config.VERSAO_HEADER, Config.versao).build())
    }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val semAuth = original.header(NexusApi.SEM_AUTH) != null
        val builder = original.newBuilder().removeHeader(NexusApi.SEM_AUTH)
        if (!semAuth) {
            val token = runBlocking { auth.idTokenValido() }
            if (token != null) builder.header("Authorization", "Bearer $token")
        }
        chain.proceed(builder.build())
    }

    private val retryAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.request.header(NexusApi.SEM_AUTH) != null) return null
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
        throw ApiException(resposta.code, codigo, corpo, chain.request().url.toString())
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(versaoInterceptor)
        .addInterceptor(authInterceptor)
        .authenticator(retryAuthenticator)
        // Depois do authenticator na cadeia: o 401 primeiro tenta renovar; só o
        // 401 definitivo (ou qualquer outro erro) vira ApiException.
        .addInterceptor(errosInterceptor)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor { Log.d("Nexus", it) }.apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
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

/** Converte o `HttpException` do Retrofit (quando escapa) na nossa `ApiException`. */
fun Throwable.comoApi(): Throwable = when (this) {
    is HttpException -> ApiException(code(), ApiException.codigoDe(response()?.errorBody()?.string()), null, response()?.raw()?.request?.url?.toString() ?: "")
    else -> this
}
