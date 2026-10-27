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
open class ApiException(
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
            corpo?.let {
                runCatching {
                    val o = JsonCfg.json.parseToJsonElement(it) as? JsonObject
                    // Rotas antigas devolvem `error`; as de operador/facial devolvem `code`.
                    (o?.get("error") ?: o?.get("code"))?.jsonPrimitive?.content
                }.getOrNull()
            }
    }
}

/** Códigos `error` do Nexus que o app trata (pastas = sessões de contagem). */
object CodigoErro {
    /** 409 ao abrir pasta: já existe uma aberta com esse nome (Nexus novo, várias abertas). */
    const val PASTA_NOME_EM_USO = "pasta_nome_em_uso"

    /** 409 ao abrir pasta: Nexus velho, só uma aberta por vez; o corpo traz `sessaoId` da existente. */
    const val SESSAO_ABERTA_EXISTENTE = "sessao_aberta_existente"

    /** 409 ao enviar leitura: a pasta foi fechada no meio. */
    const val SESSAO_NAO_ACEITA_LEITURAS = "sessao_nao_aceita_leituras"
}

object JsonCfg {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }
}

/** 403 `operador_sessao_invalida`: a sessão de operador expirou/foi revogada. NÃO é 401: a conta do aparelho segue de pé. */
class OperadorSessaoInvalidaErro(corpo: String?, url: String) : ApiException(403, CODIGO, corpo, url) {
    companion object {
        const val CODIGO = "operador_sessao_invalida"
    }
}

/** Texto curto pra tela, a partir de qualquer erro de rede/API. */
fun Throwable.mensagemCurta(): String = when (this) {
    is ApiException -> mensagem ?: when (status) {
        401 -> "Sessão expirada. Entre de novo."
        403 -> if (this is OperadorSessaoInvalidaErro) "Sessão do operador expirou. Identifique-se de novo." else "Sem permissão (${codigo ?: "403"})."
        409 -> when (codigo) {
            CodigoErro.PASTA_NOME_EM_USO -> "Já existe uma pasta aberta com esse nome."
            CodigoErro.SESSAO_ABERTA_EXISTENTE -> "O Nexus ainda só aceita uma pasta aberta por vez."
            CodigoErro.SESSAO_NAO_ACEITA_LEITURAS -> "A pasta foi fechada no Nexus. Escolha outra."
            else -> "Erro 409${codigo?.let { " ($it)" } ?: ""}."
        }
        404 -> "Não encontrado (${codigo ?: "404"})."
        426 -> "Atualização obrigatória."
        in 500..599 -> "Nexus indisponível ($status)."
        else -> "Erro $status${codigo?.let { " ($it)" } ?: ""}."
    }
    is java.net.UnknownHostException, is java.net.ConnectException -> "Sem conexão com o Nexus."
    is java.net.SocketTimeoutException -> "O Nexus demorou a responder."
    else -> message ?: javaClass.simpleName
}
