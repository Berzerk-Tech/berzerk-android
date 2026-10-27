package br.com.berzerk.coletor.ui.comum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.berzerk.coletor.api.RfidSessaoDto
import br.com.berzerk.coletor.ui.theme.Fundo
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho
import br.com.berzerk.coletor.ui.theme.Ambar

/** Estado do seletor "Salvar em…". [lista] já vem na ordem do seletor (abertas, pré-selecionada no topo). */
data class PastasUi(
    val lista: List<RfidSessaoDto> = emptyList(),
    val carregando: Boolean = false,
    /** Sem rede e sem cache: não dá pra salvar. */
    val erro: String? = null,
    /** A lista veio do cache do aparelho (sem rede). */
    val doCache: Boolean = false,
    val escolhida: String? = null,
    val criando: Boolean = false,
    val erroCriar: String? = null,
) {
    val podeSalvar: Boolean get() = escolhida != null && lista.any { it.id == escolhida } && !criando
}

/** "Salvar em…": pastas abertas + "Nova pasta…" + "Salvar nesta pasta". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeletorPasta(
    ui: PastasUi,
    aoEscolher: (String) -> Unit,
    aoNovaPasta: () -> Unit,
    aoRecarregar: () -> Unit,
    aoSalvar: () -> Unit,
    aoFechar: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = aoFechar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = br.com.berzerk.coletor.ui.theme.Cartao) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Salvar em…", style = MaterialTheme.typography.headlineSmall, color = Texto)
            when {
                ui.carregando && ui.lista.isEmpty() -> Text("Carregando as pastas…", color = TextoFraco, modifier = Modifier.padding(vertical = 12.dp))
                ui.erro != null -> {
                    Text(ui.erro, color = Vermelho, modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = aoRecarregar) { Text("Tentar de novo", color = Texto) }
                }
                else -> {
                    if (ui.doCache) Text("Sem rede: mostrando as últimas pastas conhecidas.", color = Ambar, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    if (ui.lista.isEmpty()) Text("Nenhuma pasta aberta. Crie uma em “Nova pasta…”.", color = TextoFraco, modifier = Modifier.padding(vertical = 12.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(ui.lista, key = { it.id }) { p -> LinhaPasta(p, p.id == ui.escolhida) { aoEscolher(p.id) } }
                    }
                }
            }
            TextButton(onClick = aoNovaPasta, enabled = !ui.criando) { Text("＋ Nova pasta…", color = Verde) }
            androidx.compose.material3.Button(
                onClick = aoSalvar,
                enabled = ui.podeSalvar,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Verde, contentColor = Fundo),
            ) { Text("Salvar nesta pasta") }
        }
    }
}

@Composable
private fun LinhaPasta(p: RfidSessaoDto, escolhida: Boolean, aoToque: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = aoToque).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = escolhida, onClick = aoToque, colors = RadioButtonDefaults.colors(selectedColor = Verde, unselectedColor = TextoFraco))
        Column(Modifier.weight(1f)) {
            Text(p.nome, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text("aberta ${formatarHa(p.abertaEm)}".trim(), color = TextoFraco, style = MaterialTheme.typography.bodySmall)
        }
        Text("${formatarInteiro(p.pecas)} peças", color = if (escolhida) Verde else TextoFraco, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Diálogo "Nova pasta": só o nome. [erro] vem do Nexus (nome em uso, uma por vez…). */
@Composable
fun NovaPastaDialog(criando: Boolean, erro: String?, aoCriar: (String) -> Unit, aoCancelar: () -> Unit) {
    var nome by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!criando) aoCancelar() },
        title = { Text("Nova pasta") },
        text = {
            Column {
                OutlinedTextField(value = nome, onValueChange = { nome = it.take(120) }, singleLine = true, label = { Text("Nome (ex.: Black Friday)") }, enabled = !criando)
                if (erro != null) Text(erro, color = Vermelho, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = nome.isNotBlank() && !criando, onClick = { aoCriar(nome) }) { Text(if (criando) "Criando…" else "Criar", color = Verde) }
        },
        dismissButton = { TextButton(enabled = !criando, onClick = aoCancelar) { Text("Cancelar", color = Texto) } },
    )
}
