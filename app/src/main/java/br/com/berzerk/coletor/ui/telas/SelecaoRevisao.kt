package br.com.berzerk.coletor.ui.telas

import br.com.berzerk.coletor.core.Graph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Chaves dos modelos escolhidos na lista da Revisão (não vão na URL: podem ser muitas/longas).
 * Conjuntos com mais de um modelo também ficam em `Prefs` (referência → chaves), pra a leitura pendente
 * (`multi:<hash>`) poder ser retomada depois de sair sem salvar ou de o processo morrer.
 */
object SelecaoRevisao {
    const val MAX_CONJUNTOS = 50

    @Volatile var chaves: List<String> = emptyList()

    fun guardar(c: List<String>) {
        chaves = c
        if (c.size < 2) return
        val ref = AlvoLeitura.Modelos.de(c).referencia
        val mapa = lerMapa().toMutableMap()
        mapa.remove(ref) // reinsere no fim: o descarte é dos mais antigos
        mapa[ref] = AlvoLeitura.Modelos.de(c).chaves
        Graph.prefs.revisaoConjuntos = serializar(limitar(mapa))
        Graph.prefs.revisaoUltimaReferencia = ref
    }

    fun chavesDe(referencia: String): List<String>? = lerMapa()[referencia]

    fun ultimaReferencia(): String? = Graph.prefs.revisaoUltimaReferencia

    fun esquecer(referencia: String) {
        val mapa = lerMapa()
        if (referencia !in mapa) return
        Graph.prefs.revisaoConjuntos = serializar(mapa - referencia)
    }

    private fun lerMapa(): Map<String, List<String>> = desserializar(Graph.prefs.revisaoConjuntos)

    internal fun limitar(mapa: Map<String, List<String>>): Map<String, List<String>> =
        if (mapa.size <= MAX_CONJUNTOS) mapa else mapa.entries.toList().takeLast(MAX_CONJUNTOS).associate { it.key to it.value }

    /** Ordem de inserção preservada. */
    internal fun serializar(mapa: Map<String, List<String>>): String =
        JsonObject(mapa.mapValues { (_, v) -> JsonArray(v.map { JsonPrimitive(it) }) }).toString()

    /** JSON nulo, vazio ou corrompido = mapa vazio. */
    internal fun desserializar(json: String?): Map<String, List<String>> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val out = LinkedHashMap<String, List<String>>()
            Json.parseToJsonElement(json).jsonObject.forEach { (k, v) ->
                out[k] = v.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            }
            out
        }.getOrDefault(emptyMap())
    }
}
