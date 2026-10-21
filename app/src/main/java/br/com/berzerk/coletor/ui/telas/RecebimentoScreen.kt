package br.com.berzerk.coletor.ui.telas

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import br.com.berzerk.coletor.api.LoteElegivelDto
import br.com.berzerk.coletor.api.mensagemCurta
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.Cartao
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho
import kotlinx.coroutines.delay

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
            .onSuccess { lotes = it.lotes; erro = null }
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
                Cartao(Modifier.clickable { abrirLote(lote.loteId) }) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(lote.codigo, style = MaterialTheme.typography.headlineSmall, color = Texto)
                            Text("${lote.esperadoTotal} pç", style = MaterialTheme.typography.titleMedium, color = TextoFraco)
                        }
                        Text(listOfNotNull(lote.produto, lote.cor).joinToString(" · ").ifEmpty { "—" }, color = Texto)
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(lote.oficina ?: "", color = TextoFraco, style = MaterialTheme.typography.bodyMedium)
                            Text(lote.statusNome, color = if (lote.aguardandoRecebimento) Verde else Ambar, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
