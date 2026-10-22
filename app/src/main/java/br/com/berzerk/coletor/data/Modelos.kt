package br.com.berzerk.coletor.data

import br.com.berzerk.coletor.ui.comum.ehTamanhoDaRegua
import br.com.berzerk.coletor.ui.comum.ordenarTamanhos
import java.text.Collator
import java.util.Locale

/**
 * Parse do `nome` da iTag: `"Oversized - Alfa - G - Branca"` = `categoria - modelo - tamanho - cor`
 * (o tamanho pode estar no meio ou no fim; todo segmento igual ao tamanho ou da régua sai do modelo).
 * Devolve (categoria normalizada pra exibição, modelo). Sem " - " → ("Outros", nome); vazio → ("Outros", "Sem nome").
 */
fun parseNomeItag(nome: String?, tamanho: String?): Pair<String, String> {
    val limpo = nome?.trim().orEmpty()
    if (limpo.isEmpty()) return "Outros" to "Sem nome"
    if (!limpo.contains(" - ")) return "Outros" to limpo
    val partes = limpo.split(" - ").map { it.trim() }
    val categoria = capitalizarCategoria(partes[0]).ifEmpty { "Outros" }
    val tam = tamanho?.trim().orEmpty()
    // O tamanho pode estar no meio (`categoria - modelo - TAMANHO - cor`): sai de qualquer posição.
    val resto = partes.drop(1).filter { seg -> !(seg.equals(tam, ignoreCase = true) && tam.isNotEmpty()) && !ehTamanhoDaRegua(seg) }
    val modelo = resto.joinToString(" - ").ifEmpty { "Sem nome" }
    return categoria to modelo
}

/** Versão do índice `modelos` (regra de `parseNomeItag`); mudou → reindexa. */
const val VERSAO_INDICE = 2

/** `"POLIAMIDA tech"` → `"Poliamida Tech"`. */
fun capitalizarCategoria(texto: String): String =
    texto.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .joinToString(" ") { p -> p.replaceFirstChar { it.titlecase(Locale("pt", "BR")) } }

/** Chave estável do modelo (ignora caixa): `"categoria|modelo"` em minúsculas. */
fun chaveModelo(categoria: String, modelo: String): String = "${categoria.trim().lowercase()}|${modelo.trim().lowercase()}"

data class ModeloResumo(
    val chave: String,
    val categoria: String,
    val modelo: String,
    /** EANs distintos, na ordem dos tamanhos. */
    val eans: List<String>,
    /** Tamanhos conhecidos (sem o vazio), na ordem da régua. */
    val tamanhos: List<String>,
    val eanPorTamanho: Map<String, List<String>>,
    val totalEpcs: Int,
    val imagemUrl: String?,
) {
    /** Prefixos de 12 dígitos (o EAN sem o último dígito, que separa os tamanhos) — é o que a lista mostra. */
    val prefixos: List<String> get() = eans.map { if (it.length >= 13) it.dropLast(1) else it }.distinct()
}

/** Agrupa as linhas de `modelos` por (categoria, modelo) ignorando caixa; ordena por modelo (pt-BR). */
fun montarResumos(linhas: List<ModeloEntity>, imagens: Map<String, String>): List<ModeloResumo> {
    val collator = Collator.getInstance(Locale("pt", "BR")).apply { strength = Collator.PRIMARY }
    return linhas.groupBy { chaveModelo(it.categoria, it.modelo) }.map { (chave, grupo) ->
        val primeira = grupo.first()
        val tamanhosTodos = ordenarTamanhos(grupo.map { it.tamanho }.filter { it.isNotEmpty() })
        val porTamanho = LinkedHashMap<String, MutableList<String>>()
        (tamanhosTodos + "").forEach { t ->
            val eansT = grupo.filter { it.tamanho == t && it.ean13.isNotEmpty() }.map { it.ean13 }.distinct().sorted()
            if (eansT.isNotEmpty()) porTamanho[t] = eansT.toMutableList()
        }
        val eans = porTamanho.values.flatten().distinct()
        ModeloResumo(
            chave = chave,
            categoria = primeira.categoria,
            modelo = primeira.modelo,
            eans = eans,
            tamanhos = tamanhosTodos,
            eanPorTamanho = porTamanho,
            totalEpcs = grupo.sumOf { it.qtd },
            imagemUrl = eans.firstNotNullOfOrNull { imagens[it] },
        )
    }.sortedWith { a, b -> collator.compare(a.modelo, b.modelo) }
}

/** Converte a agregação de `epcs` em linhas de `modelos`, somando as que caem na mesma chave. */
fun indexarLinhas(agregadas: List<LinhaAgregada>): List<ModeloEntity> {
    val mapa = LinkedHashMap<List<String>, Int>()
    agregadas.forEach { l ->
        val (cat, mod) = parseNomeItag(l.nome, l.tamanho)
        val k = listOf(cat, mod, l.tamanho?.trim()?.uppercase().orEmpty(), l.ean13?.trim().orEmpty())
        mapa[k] = (mapa[k] ?: 0) + l.qtd
    }
    return mapa.map { (k, qtd) -> ModeloEntity(k[0], k[1], k[2], k[3], qtd) }
}
