package br.com.berzerk.coletor.data

import br.com.berzerk.coletor.api.LoteElegivelDto

/**
 * Último `lotes-elegiveis` em memória: a Conferência abre já com código, produto, cor, foto e
 * grade esperada do card enquanto o primeiro resumo do Nexus (1–2 s) não chega. Só prévia de
 * exibição — lido/divergência continuam vindo do resumo.
 */
object PreviaLotes {
    private val porId = HashMap<String, LoteElegivelDto>()

    @Synchronized fun guardar(lotes: List<LoteElegivelDto>) { lotes.forEach { porId[it.loteId] = it } }
    @Synchronized fun pegar(loteId: String): LoteElegivelDto? = porId[loteId]
}
