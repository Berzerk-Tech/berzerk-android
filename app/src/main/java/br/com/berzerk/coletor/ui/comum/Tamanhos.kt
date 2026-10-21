package br.com.berzerk.coletor.ui.comum

/** Mesma régua de `@berzerk/contracts` `tamanhos.ts`, só a ordem (o app nunca inventa tamanho). */
private val ORDEM = listOf("PP", "P", "M", "G", "GG", "XG", "G1", "G2", "G3", "XGG", "EG", "EGG", "U", "TU")

fun ordenarTamanhos(tamanhos: Collection<String>): List<String> =
    tamanhos.distinct().sortedWith(compareBy({ ORDEM.indexOf(it.uppercase()).let { i -> if (i < 0) 999 else i } }, { it }))
