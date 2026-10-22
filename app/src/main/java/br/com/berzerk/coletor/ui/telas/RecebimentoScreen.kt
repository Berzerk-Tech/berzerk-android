package br.com.berzerk.coletor.ui.telas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.berzerk.coletor.api.LoteElegivelDto
import br.com.berzerk.coletor.data.PreviaLotes
import br.com.berzerk.coletor.api.mensagemCurta
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.Cartao
import br.com.berzerk.coletor.ui.comum.FotoProduto
import br.com.berzerk.coletor.ui.comum.ordenarTamanhos
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Muted
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale

@Composable
fun RecebimentoScreen(voltar: () -> Unit, abrirLote: (String) -> Unit) {
    var busca by remember { mutableStateOf("") }
    var lotes by remember { mutableStateOf<List<LoteElegivelDto>>(emptyList()) }
    var carregando by remember { mutableStateOf(true) }
    var erro by remember { mutableStateOf<String?>(null) }
    var versao by remember { mutableStateOf(0) }

    LaunchedEffect(busca, versao) {
        if (busca.isNotEmpty()) delay(350)
        carregando = true
        runCatching { Graph.api.lotesElegiveis(busca = busca.trim().ifEmpty { null }) }
            .onSuccess { lotes = it.lotes; erro = null; PreviaLotes.guardar(it.lotes) }
            .onFailure { erro = it.mensagemCurta() }
        carregando = false
    }

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Recebimento", "Lotes no galpão e a caminho", aoVoltar = voltar, rotulo = "Berzerk Nexus", acao = {
            TextButton(onClick = { versao++ }) { Text("Atualizar", color = TextoFraco) }
        })
        OutlinedTextField(
            value = busca,
            onValueChange = { busca = it },
            placeholder = { Text("Código do lote", color = TextoFraco) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (erro != null) Text(erro!!, color = Vermelho, modifier = Modifier.padding(16.dp))
        if (carregando && lotes.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        } else if (lotes.isEmpty() && erro == null) {
            Text(if (busca.isBlank()) "Nenhum lote aguardando recebimento." else "Nenhum lote com \"$busca\".", color = TextoFraco, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(lotes, key = { it.loteId }) { lote ->
                CartaoLote(lote) { abrirLote(lote.loteId) }
            }
        }
    }
}

private val FormatoCheckin = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))

/** ISO-8601 do Nexus → `dd/MM HH:mm` no fuso local; formato estranho → null (a linha some). */
private fun checkinLocal(iso: String): String? =
    runCatching { FormatoCheckin.format(Date.from(Instant.parse(iso))) }.getOrNull()
        ?: runCatching { FormatoCheckin.format(Date.from(OffsetDateTime.parse(iso).toInstant())) }.getOrNull()

/** Card do lote no estilo do Recebimento do Nexus web (versão compacta). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CartaoLote(lote: LoteElegivelDto, onClick: () -> Unit) {
    val formaChip = RoundedCornerShape(4.dp)
    Cartao(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.Top) {
            FotoProduto(lote.imagemUrl, 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("LOTE ${lote.codigo}", style = MaterialTheme.typography.headlineSmall, color = Texto)
                        if (lote.estampa != null) Text(lote.estampa.uppercase(), style = MaterialTheme.typography.headlineSmall, color = Texto)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${lote.esperadoTotal} pç", style = MaterialTheme.typography.titleMedium, color = TextoFraco)
                }
                if (lote.produto != null || lote.cor != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (lote.produto != null) { SeloCategoria(lote.produto); Spacer(Modifier.width(8.dp)) }
                        if (lote.cor != null) Text(lote.cor, style = MaterialTheme.typography.bodyMedium, color = Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (lote.esperadoPorTamanho.isNotEmpty()) {
                    val grade = ordenarTamanhos(lote.esperadoPorTamanho.keys).joinToString("  ") { "${lote.esperadoPorTamanho[it]}$it" }
                    Box(Modifier.background(Muted, formaChip).border(1.dp, Borda, formaChip).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(grade, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp), color = Texto)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (lote.oficina != null) {
                        Box(Modifier.weight(1f, fill = false).background(Muted, formaChip).border(1.dp, Verde.copy(alpha = 0.5f), formaChip).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text(lote.oficina.uppercase(), style = MaterialTheme.typography.labelSmall, color = Verde, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else Spacer(Modifier.width(1.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(lote.statusNome, color = if (lote.aguardandoRecebimento) Verde else Ambar, style = MaterialTheme.typography.bodyMedium)
                }
                lote.galpaoCheckinEm?.let { checkinLocal(it) }?.let {
                    Text("Check-in galpão em $it", style = MaterialTheme.typography.bodySmall, color = TextoFraco)
                }
            }
        }
    }
}
