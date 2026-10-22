package br.com.berzerk.coletor.data

import br.com.berzerk.coletor.api.VarianteDto
import br.com.berzerk.coletor.ui.comum.TipoLeitura
import br.com.berzerk.coletor.ui.telas.normalizarBusca

/**
 * Classificação LOCAL de um EPC lido na conferência (só pro console e contadores do aparelho;
 * quem decide lido × esperado é o Nexus). `epc == null` = fora da base local.
 *
 * Com `variantes` (Nexus novo): casa o EAN-13 ou o prefixo de 12 dígitos (o 13º só separa tamanho,
 * mesma regra de `ItagSync.imagemPor`). Sem elas (API velha): o `nome` da iTag contém o `produto`.
 */
fun classificarParaLote(epc: EpcEntity?, variantes: List<VarianteDto>, produto: String?): TipoLeitura {
    if (epc == null || epc.nome.isNullOrBlank()) return TipoLeitura.SEM_CADASTRO
    if (variantes.isNotEmpty()) {
        val ean = epc.ean13.orEmpty()
        if (ean.isEmpty()) return TipoLeitura.OUTRO
        val achou = variantes.any { it.ean13 == ean || it.ean13.take(12) == ean.take(12) }
        return if (achou) TipoLeitura.DO_ALVO else TipoLeitura.OUTRO
    }
    val alvo = normalizarBusca(produto.orEmpty())
    if (alvo.isEmpty()) return TipoLeitura.OUTRO
    return if (normalizarBusca(epc.nome).contains(alvo)) TipoLeitura.DO_ALVO else TipoLeitura.OUTRO
}
