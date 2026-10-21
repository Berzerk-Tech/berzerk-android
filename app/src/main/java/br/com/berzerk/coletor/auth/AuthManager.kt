package br.com.berzerk.coletor.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import br.com.berzerk.coletor.core.Config
import br.com.berzerk.coletor.core.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

sealed interface SessaoEstado {
    data object Deslogado : SessaoEstado
    data class Logado(val email: String?, val nome: String?, val sub: String) : SessaoEstado
}

/**
 * Login "Entrar com @berzerk": Authorization Code + PKCE no Hosted UI do Cognito
 * (pool staff), direto pro Google (`identity_provider=Google`), callback
 * `berzerkrfid://auth/callback` — o MESMO app client da infra (#263). O id
 * token (1 h) vai no Bearer; o refresh token (30 d, revogável) renova em
 * silêncio 5 min antes de vencer, como o `session-refresh.ts` do web. Tudo
 * guardado em `Prefs` (cifrado); nunca em log.
 */
class AuthManager(context: Context, private val prefs: Prefs) {
    private val appContext = context.applicationContext
    private val service = AuthorizationService(appContext)
    private val config = AuthorizationServiceConfiguration(
        Uri.parse("https://${Config.cognitoDomain}/oauth2/authorize"),
        Uri.parse("https://${Config.cognitoDomain}/oauth2/token"),
    )
    private val lock = Mutex()
    private var state: AuthState = prefs.authStateJson?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() } ?: AuthState(config)

    private val _sessao = MutableStateFlow(estadoDe(state))
    val sessao: StateFlow<SessaoEstado> = _sessao

    fun intentDeLogin(): Intent {
        val request = AuthorizationRequest.Builder(config, Config.cognitoClientId, ResponseTypeValues.CODE, Uri.parse(Config.redirectUri))
            .setScopes("openid", "email", "profile")
            .setPrompt("select_account")
            .setAdditionalParameters(mapOf("identity_provider" to "Google"))
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** Recebe o resultado da Activity do AppAuth e troca o code por tokens. */
    suspend fun concluirLogin(data: Intent?): Result<SessaoEstado.Logado> {
        if (data == null) return Result.failure(IllegalStateException("Login cancelado."))
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        if (resp == null) return Result.failure(IllegalStateException(ex?.errorDescription ?: ex?.error ?: "Login cancelado."))
        val (tokenResp, tokenEx) = executar(resp.createTokenExchangeRequest())
        return lock.withLock {
            val novo = AuthState(resp, ex)
            novo.update(tokenResp, tokenEx)
            if (tokenResp == null || novo.idToken == null) {
                Result.failure(IllegalStateException(tokenEx?.errorDescription ?: "Não foi possível concluir o login."))
            } else {
                gravar(novo)
                Result.success(estadoDe(novo) as SessaoEstado.Logado)
            }
        }
    }

    /** Id token válido por mais de 5 min, renovando se preciso. `null` = sem sessão. */
    suspend fun idTokenValido(): String? {
        val atual = state.idToken ?: return null
        val exp = expDe(atual) ?: return atual
        val agora = System.currentTimeMillis() / 1000
        return if (exp - agora > MARGEM_S) atual else renovar() ?: atual
    }

    /** Renova pelo refresh token. Recusa definitiva (400 `invalid_grant`) encerra a sessão. */
    suspend fun renovar(): String? = lock.withLock {
        val req: TokenRequest = runCatching { state.createTokenRefreshRequest() }.getOrElse { return null }
        val (resp, ex) = executar(req)
        if (resp?.idToken != null) {
            state.update(resp, ex)
            gravar(state)
            return resp.idToken
        }
        val recusado = ex != null && ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR
        Log.w(TAG, "refresh falhou (recusado=$recusado): ${ex?.error}")
        if (recusado) sairLocal()
        return null
    }

    /** Apaga a sessão local. A URL devolvida encerra a sessão no Cognito (abrir no navegador é opcional). */
    fun sair(): Uri {
        sairLocal()
        return Uri.parse("https://${Config.cognitoDomain}/logout").buildUpon()
            .appendQueryParameter("client_id", Config.cognitoClientId)
            .appendQueryParameter("logout_uri", Config.logoutUri)
            .build()
    }

    private fun sairLocal() {
        state = AuthState(config)
        prefs.authStateJson = null
        _sessao.value = SessaoEstado.Deslogado
    }

    private fun gravar(novo: AuthState) {
        state = novo
        prefs.authStateJson = novo.jsonSerializeString()
        _sessao.value = estadoDe(novo)
    }

    private suspend fun executar(req: TokenRequest): Pair<TokenResponse?, AuthorizationException?> =
        suspendCoroutine { cont -> service.performTokenRequest(req) { r, e -> cont.resume(r to e) } }

    fun dispose() = service.dispose()

    companion object {
        private const val TAG = "Auth"
        private const val MARGEM_S = 5 * 60L

        fun claims(idToken: String): JSONObject? = runCatching {
            val payload = idToken.split('.').getOrNull(1) ?: return null
            JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        }.getOrNull()

        fun expDe(idToken: String): Long? = claims(idToken)?.optLong("exp")?.takeIf { it > 0 }

        private fun estadoDe(s: AuthState): SessaoEstado {
            val tok = s.idToken ?: return SessaoEstado.Deslogado
            val c = claims(tok) ?: return SessaoEstado.Deslogado
            return SessaoEstado.Logado(
                email = c.optString("email").takeIf { it.isNotBlank() },
                nome = c.optString("name").takeIf { it.isNotBlank() },
                sub = c.optString("sub"),
            )
        }
    }
}
