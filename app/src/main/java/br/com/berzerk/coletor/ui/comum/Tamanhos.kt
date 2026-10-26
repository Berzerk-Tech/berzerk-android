package br.com.berzerk.coletor.ui.comum

/** Mesma régua de `@berzerk/contracts` `tamanhos.ts`, só a ordem (o app nunca inventa tamanho). */
private val ORDEM = listOf("PP", "P", "M", "G", "GG", "XG", "G1", "G2", "G3", "XGG", "EG", "EGG", "U", "TU")

fun ordenarTamanhos(tamanhos: Collection<String>): List<String> =
    tamanhos.distinct().sortedWith(compareBy({ ORDEM.indexOf(it.uppercase()).let { i -> if (i < 0) 999 else i } }, { it }))

/** `true` se o texto é um tamanho da régua (PP, P, M, G, GG…). */
fun ehTamanhoDaRegua(texto: String): Boolean = ORDEM.contains(texto.trim().uppercase())

/** Grade fixa da tela de leitura (inventário e leitura livre): sempre estes tiles, com 0 quando não há leitura. */
val TAMANHOS_GRADE = listOf("P", "M", "G", "GG", "XG", "G1", "G2", "G3", "U")

/** [TAMANHOS_GRADE] + os tamanhos fora dela que apareceram (PP, EG…), na régua, e o `?` (sem tamanho) por último. */
fun tamanhosDaGrade(vistos: Collection<String>): List<String> {
    val extras = vistos.map { it.trim().uppercase() }.filter { it.isNotEmpty() && it !in TAMANHOS_GRADE }.distinct()
    val (semTamanho, comTamanho) = extras.partition { it == "?" }
    return TAMANHOS_GRADE + ordenarTamanhos(comTamanho) + semTamanho
}
