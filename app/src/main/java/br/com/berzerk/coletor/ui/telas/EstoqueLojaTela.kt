package br.com.berzerk.coletor.ui.telas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.berzerk.coletor.api.EstoqueLojaDto
import br.com.berzerk.coletor.api.VarianteLojaDto
import br.com.berzerk.coletor.data.ModeloResumo
import br.com.berzerk.coletor.ui.comum.BotaoGrande
import br.com.berzerk.coletor.ui.comum.FotoProduto
import br.com.berzerk.coletor.ui.comum.TileTamanho
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Cartao as CorCartao
import br.com.berzerk.coletor.ui.theme.Fundo
import br.com.berzerk.coletor.ui.theme.Muted
import br.com.berzerk.coletor.ui.theme.RaioCartao
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho

/** Estados da tela "Estoque loja × picking × RFID" (vive dentro da leitura, sem rota própria). */
sealed interface LojaUi {
    data object Carregando : LojaUi
    /** [aviso]: mensagem do topo (estoque mudou, erro ao substituir…). */
    data class Pronto(val dto: EstoqueLojaDto, val aviso: String? = null) : LojaUi
    data class Erro(val mensagem: String) : LojaUi
    data class Substituindo(val dto: EstoqueLojaDto) : LojaUi
    data object Substituido : LojaUi
}

@Composable
fun EstoqueLojaTela(
    resumo: ModeloResumo,
    estado: LojaUi,
    aoAtualizar: () -> Unit,
    aoSubstituir: () -> Unit,
    aoFechar: () -> Unit,
    aoAbrirHistorico: () -> Unit = {},
) {
    var confirmando by remember { mutableStateOf(false) }
    BackHandler(onBack = aoFechar)

    Column(Modifier.fillMaxSize().background(Fundo)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FotoProduto(resumo.imagemUrl, 56.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SeloCategoria(resumo.categoria)
                        Spacer(Modifier.width(8.dp))
                        Text(resumo.modelo, style = MaterialTheme.typography.headlineSmall, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Estoque loja × picking × RFID", style = MaterialTheme.typography.bodySmall, color = TextoFraco)
                }
                IconButton(onClick = aoAbrirHistorico, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.History, contentDescription = "Histórico", tint = TextoFraco) }
                if (estado is LojaUi.Pronto || estado is LojaUi.Erro) {
                    IconButton(onClick = aoAtualizar, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = TextoFraco) }
                }
                IconButton(onClick = aoFechar, modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextoFraco) }
            }

            when (estado) {
                LojaUi.Carregando -> Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Texto) }
                is LojaUi.Erro -> Text(estado.mensagem, color = Vermelho, style = MaterialTheme.typography.bodyMedium)
                is LojaUi.Pronto -> Conteudo(estado.dto, estado.aviso)
                is LojaUi.Substituindo -> Conteudo(estado.dto, null)
                LojaUi.Substituido -> Unit
            }
            Spacer(Modifier.height(4.dp))
        }

        val dto = (estado as? LojaUi.Pronto)?.dto ?: (estado as? LojaUi.Substituindo)?.dto
        if (dto != null) {
            Column(Modifier.fillMaxWidth().background(Fundo).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BotaoGrande(
                    texto = if (estado is LojaUi.Substituindo) "Substituindo…" else "SUBSTITUIR ESTOQUE",
                    onClick = { confirmando = true },
                    cor = Color.White, corTexto = Fundo,
                    habilitado = estado is LojaUi.Pronto && dto.podeSubstituir && dto.variantes.any { it.estoqueLoja != null },
                )
                if (!dto.podeSubstituir) Text("Seu usuário não pode substituir o estoque da loja", style = MaterialTheme.typography.bodySmall, color = TextoFraco, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }

    if (confirmando && estado is LojaUi.Pronto) {
        DialogoSubstituir(resumo, estado.dto, aoCancelar = { confirmando = false }, aoConfirmar = { confirmando = false; aoSubstituir() })
    }
    if (estado == LojaUi.Substituido) Sucesso(aoFechar)
}

@Composable
private fun Conteudo(dto: EstoqueLojaDto, aviso: String?) {
    if (aviso != null) Text(aviso, color = Ambar, style = MaterialTheme.typography.bodyMedium)
    if (dto.maisDeUmaCor) Text("Produto com mais de uma cor na loja: confira os tamanhos antes de substituir", color = Ambar, style = MaterialTheme.typography.bodyMedium)
    if (dto.eansNaoCasados.isNotEmpty()) Text("${dto.eansNaoCasados.size} etiquetas sem variante na loja", color = TextoFraco, style = MaterialTheme.typography.bodySmall)
    if (dto.outrosProdutos.isNotEmpty()) Text("EANs de outro produto ignorados: ${dto.outrosProdutos.joinToString { it.nome }}", color = TextoFraco, style = MaterialTheme.typography.bodySmall)

    CartaoLoja("Estoque loja", "Nexus · estoque atual da Shopify", dto.totais.estoqueLoja, dto.variantes, { it.estoqueLoja }, Texto)
    CartaoLoja("Peças lidas", "Revisão RFID · esta leitura", dto.totais.lidas, dto.variantes, { it.lidas }, Ambar)
    CartaoLoja("Picking geral", "Nexus · picking do produto", dto.totais.picking, dto.variantes, { it.picking }, Texto, badge = "−")
    CartaoLoja(
        "Peças total", "Novo estoque · ${dto.margemPct}% de margem", dto.totais.novo, dto.variantes, { it.novo }, Verde,
        badge = "=", destaque = true, rodape = "peças lidas − picking geral − ${dto.margemPct}%",
    )
}

@Composable
private fun CartaoLoja(
    titulo: String,
    subtitulo: String,
    total: Int,
    variantes: List<VarianteLojaDto>,
    valor: (VarianteLojaDto) -> Int?,
    corNumero: Color,
    badge: String? = null,
    destaque: Boolean = false,
    rodape: String? = null,
) {
    val forma = RoundedCornerShape(RaioCartao)
    Column(
        Modifier.fillMaxWidth().background(CorCartao, forma).border(if (destaque) 2.dp else 1.dp, if (destaque) Verde else Borda, forma).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (badge != null) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (destaque) Verde else Muted), contentAlignment = Alignment.Center) {
                    Text(badge, color = if (destaque) Fundo else Texto, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(titulo.uppercase(), style = MaterialTheme.typography.titleSmall, color = Texto)
                Text(subtitulo, style = MaterialTheme.typography.bodySmall, color = TextoFraco)
            }
            Text("$total PÇS", style = MaterialTheme.typography.titleMedium, color = if (destaque) Verde else Texto)
        }
        variantes.chunked(5).forEach { linha ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                linha.forEach { v ->
                    val n = valor(v)
                    TileTamanho(v.tamanho ?: "?", n ?: 0, "", Modifier.weight(1f), corNumero = corNumero, compacto = true, textoValor = if (n == null) "—" else null)
                }
                repeat(5 - linha.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (rodape != null) Text(rodape, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextoFraco)
    }
}

@Composable
private fun DialogoSubstituir(resumo: ModeloResumo, dto: EstoqueLojaDto, aoCancelar: () -> Unit, aoConfirmar: () -> Unit) {
    val linhas = dto.variantes.filter { it.estoqueLoja != null }
    AlertDialog(
        onDismissRequest = aoCancelar,
        title = {
            Column {
                Text("Substituir estoque da loja?")
                Text("${resumo.categoria} - ${resumo.modelo} · Shopify", style = MaterialTheme.typography.bodySmall, color = TextoFraco)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("TAM.", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = TextoFraco)
                    Text("ATUAL → NOVO", style = MaterialTheme.typography.labelSmall, color = TextoFraco)
                }
                linhas.forEach { v ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(v.tamanho ?: "?", Modifier.weight(1f), color = Texto)
                        Text("${v.estoqueLoja} → ", color = TextoFraco)
                        Text("${v.novo}", color = Verde)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("Total", Modifier.weight(1f), color = Texto, style = MaterialTheme.typography.titleSmall)
                    Text("${linhas.sumOf { it.estoqueLoja ?: 0 }} → ", color = TextoFraco)
                    Text("${linhas.sumOf { it.novo }}", color = Verde, style = MaterialTheme.typography.titleSmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = aoConfirmar, colors = ButtonDefaults.buttonColors(containerColor = Verde, contentColor = Fundo)) { Text("CONFIRMAR") }
        },
        dismissButton = { TextButton(onClick = aoCancelar) { Text("Cancelar", color = Texto) } },
    )
}

@Composable
private fun Sucesso(aoOk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Fundo).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(Verde.copy(alpha = 0.18f)).border(2.dp, Verde, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Verde, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ShoppingBag, contentDescription = null, tint = TextoFraco, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Estoque substituído", style = MaterialTheme.typography.headlineSmall, color = Texto)
        }
        Text("A loja agora mostra o novo estoque.", style = MaterialTheme.typography.bodyMedium, color = TextoFraco)
        Spacer(Modifier.height(24.dp))
        BotaoGrande("OK", aoOk, cor = Verde, corTexto = Fundo)
    }
}
