package br.com.berzerk.coletor.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Erro HTTP do Nexus já aberto: `status` + o `error` do corpo (`invalid_token`,
 * `conferencia_diverge`, `app_desatualizado`…) + o corpo cru pra quem precisa
 * dos detalhes (a divergência, por exemplo).
 */
class ApiException(
    val status: Int,
    val codigo: String?,
    val corpo: String?,
    val url: String,
) : IOException("HTTP $status ${codigo ?: ""} em $url") {
    val corpoJson: JsonObject? by lazy {
        corpo?.let { runCatching { JsonCfg.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    }

    val mensagem: String?
        get() = corpoJson?.get("mensagem")?.jsonPrimitive?.content
            ?: corpoJson?.get("message")?.jsonPrimitive?.content

    inline fun <reified T> corpoComo(): T? = corpo?.let { runCatching { JsonCfg.json.decodeFromString<T>(it) }.getOrNull() }

    fun ehAuth(): Boolean = status == 401
    fun ehAtualizacao(): Boolean = status == 426

    companion object {
        fun codigoDe(corpo: String?): String? =
            corpo?.let { runCatching { (JsonCfg.json.parseToJsonElement(it) as? JsonObject)?.get("error")?.jsonPrimitive?.content }.getOrNull() }
    }
}

object JsonCfg {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }
}

/** Texto curto pra tela, a partir de qualquer erro de rede/API. */
fun Throwable.mensagemCurta(): String = when (this) {
    is ApiException -> mensagem ?: when (status) {
        401 -> "Sessão expirada. Entre de novo."
        403 -> "Sem permissão (${codigo ?: "403"})."
        404 -> "Não encontrado (${codigo ?: "404"})."
        426 -> "Atualização obrigatória."
        in 500..599 -> "Nexus indisponível ($status)."
        else -> "Erro $status${codigo?.let { " ($it)" } ?: ""}."
    }
    is java.net.UnknownHostException, is java.net.ConnectException -> "Sem conexão com o Nexus."
    is java.net.SocketTimeoutException -> "O Nexus demorou a responder."
    else -> message ?: javaClass.simpleName
}
