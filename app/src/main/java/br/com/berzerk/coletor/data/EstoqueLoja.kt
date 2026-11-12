package br.com.berzerk.coletor.data

import br.com.berzerk.coletor.api.ConsultaEstoqueLojaBody
import br.com.berzerk.coletor.api.EstoqueLojaDto
import br.com.berzerk.coletor.api.LidaEanDto
import br.com.berzerk.coletor.api.OrigemSubstituicaoDto
import br.com.berzerk.coletor.api.PorVarianteLojaDto
import br.com.berzerk.coletor.api.QuantidadeLojaDto
import br.com.berzerk.coletor.api.SubstituirEstoqueLojaBody

/** Margem de segurança que o Nexus desconta do novo estoque (a UI só exibe; a conta é do Nexus). Padrão ao abrir a tela. */
const val MARGEM_PCT_LOJA = 5

/** Margens que o operador pode escolher na tela da loja (o Nexus aceita 0–50; aqui só as combinadas). */
val MARGENS_PCT_LOJA = 2..5

/** Body da consulta: TODOS os EANs do modelo, `qtd` = EPCs únicos lidos com aquele EAN (0 quando nenhum). */
fun montarConsultaLoja(resumo: ModeloResumo, lidasPorEan: Map<String, Int>, margemPct: Int = MARGEM_PCT_LOJA): ConsultaEstoqueLojaBody {
    val eans = (resumo.eans + resumo.eanPorTamanho.values.flatten()).filter { it.isNotEmpty() }.distinct()
    return ConsultaEstoqueLojaBody(eans.map { LidaEanDto(it, lidasPorEan[it] ?: 0) }, margemPct)
}

/** Body do substituir: uma entrada por variante COM vínculo na Shopify; `de` = o estoque que a tela mostrou. */
fun montarSubstituirLoja(dto: EstoqueLojaDto, dispositivo: String): SubstituirEstoqueLojaBody =
    SubstituirEstoqueLojaBody(
        quantidades = dto.variantes.mapNotNull { v -> v.estoqueLoja?.let { QuantidadeLojaDto(v.varianteId, v.novo, it) } },
        origem = OrigemSubstituicaoDto(
            lidas = dto.totais.lidas, picking = dto.totais.picking, margemPct = dto.margemPct, dispositivo = dispositivo,
            // Mesmas variantes de `quantidades`: é o que deixa o Nexus recalcular esta substituição depois sem reler.
            porVariante = dto.variantes.filter { it.estoqueLoja != null }.map { PorVarianteLojaDto(it.varianteId, it.lidas, it.picking) },
            recalculoDe = dto.recalculo?.auditoriaId?.takeIf { it.isNotBlank() },
        ),
    )
