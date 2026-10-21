package br.com.berzerk.coletor.ui.telas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.berzerk.coletor.api.CausaDivergencia
import br.com.berzerk.coletor.api.DivergenciaErro
import br.com.berzerk.coletor.rfid.RfidEstado
import br.com.berzerk.coletor.rfid.RfidReader
import br.com.berzerk.coletor.ui.comum.BotaoGrande
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.Cartao
import br.com.berzerk.coletor.ui.comum.Rotulo
import br.com.berzerk.coletor.ui.comum.ordenarTamanhos
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Preto
import br.com.berzerk.coletor.ui.theme.SuperficieAlta
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho

@Composable
fun ConferenciaScreen(loteId: String, voltar: () -> Unit, irConfig: () -> Unit) {
    val vm: ConferenciaViewModel = viewModel(key = loteId) { ConferenciaViewModel(loteId) }
    val ui by vm.ui.collectAsState()
    val rfid by RfidReader.estado.collectAsState()
    val lote = ui.lote
    val lendo = rfid is RfidEstado.Lendo

    Column(Modifier.fillMaxSize()) {
        Cabecalho(lote?.codigo ?: "Conferência", listOfNotNull(lote?.produto, lote?.cor).joinToString(" · ").ifEmpty { null }, aoVoltar = voltar)

        if (ui.carregando) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            return@Column
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.erro != null) Cartao { Text(ui.erro!!, color = Vermelho) }
            if (ui.semToken) Cartao {
                Column {
                    Text("Token do coletor não configurado: as leituras não estão chegando ao Nexus.", color = Ambar)
                    TextButton(onClick = irConfig) { Text("Configurar", color = Texto) }
                }
            }
            if (lote != null) {
                // Totais
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Column {
                        Rotulo("Lido")
                        Text("${lote.lidoTotal}", style = MaterialTheme.typography.displayLarge, color = corDaDiferenca(lote.lidoTotal - lote.esperadoTotal))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Rotulo("Esperado" + (lote.esperadoFonte?.let { " ($it)" } ?: ""))
                        Text("${lote.esperadoTotal}", style = MaterialTheme.typography.headlineMedium, color = TextoFraco)
                        val dif = lote.lidoTotal - lote.esperadoTotal
                        Text(if (dif == 0) "bateu" else (if (dif > 0) "+$dif" else "$dif"), color = corDaDiferenca(dif), fontWeight = FontWeight.Bold)
                    }
                }
                // Grade
                val tamanhos = ordenarTamanhos(lote.esperadoPorTamanho.keys + lote.lidoPorTamanho.keys)
                LazyVerticalGrid(columns = GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(((tamanhos.size + 2) / 3 * 92).dp)) {
                    items(tamanhos) { t ->
                        val lido = lote.lidoPorTamanho[t] ?: 0
                        val esperado = lote.esperadoPorTamanho[t] ?: 0
                        val dif = lido - esperado
                        Column(Modifier.background(SuperficieAlta, RoundedCornerShape(8.dp)).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(t, style = MaterialTheme.typography.labelLarge, color = TextoFraco)
                            Text("$lido", style = MaterialTheme.typography.headlineMedium, color = corDaDiferenca(dif))
                            Text("de $esperado" + (if (dif != 0) "  (${if (dif > 0) "+" else ""}$dif)" else ""), style = MaterialTheme.typography.labelSmall, color = TextoFraco)
                        }
                    }
                }
                if (lote.totalEpcsOutroLote > 0 || lote.totalEpcsDesconhecidos > 0) {
                    Text(
                        listOfNotNull(
                            lote.totalEpcsOutroLote.takeIf { it > 0 }?.let { "$it de outro lote" },
                            lote.totalEpcsDesconhecidos.takeIf { it > 0 }?.let { "$it sem cadastro" },
                        ).joinToString(" · "),
                        color = Ambar, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (lote.diverge && lote.exigePin == true) Text("Divergência acima da tolerância: a confirmação exige PIN de supervisor.", color = Ambar, style = MaterialTheme.typography.bodyMedium)
            }
            // Leitura local
            Cartao {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Rotulo("Neste aparelho"); Text("${ui.lidosAqui} etiquetas", color = Texto, fontWeight = FontWeight.SemiBold) }
                    Column(horizontalAlignment = Alignment.End) {
                        Rotulo("Envio")
                        Text(
                            when {
                                ui.enviando -> "enviando…"
                                ui.pendentesEnvio > 0 -> "${ui.pendentesEnvio} a enviar"
                                ui.ultimoEnvio != null -> "ok (${ui.ultimoEnvio})"
                                else -> "—"
                            },
                            color = if (ui.pendentesEnvio > 0) Ambar else Verde,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // Ações fixas embaixo
        Column(Modifier.fillMaxWidth().background(Preto).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.concluido) {
                Text(ui.mensagem ?: "Conferido.", color = Verde, fontWeight = FontWeight.SemiBold)
                BotaoGrande("Voltar à lista", onClick = voltar)
            } else {
                val estadoRfid = rfid
                BotaoGrande(
                    texto = if (lendo) "■  Parar" else "▶  Ler (ou segure o gatilho)",
                    onClick = { if (lendo) RfidReader.gatilho(false) else RfidReader.gatilho(true) },
                    cor = if (lendo) Vermelho else Texto,
                    corTexto = if (lendo) Texto else Preto,
                    habilitado = estadoRfid is RfidEstado.Pronto || estadoRfid is RfidEstado.Lendo,
                )
                if (estadoRfid is RfidEstado.Erro) Text(estadoRfid.motivo, color = Vermelho, style = MaterialTheme.typography.bodyMedium)
                BotaoGrande(
                    texto = if (ui.confirmando) "Confirmando…" else "✓  Confirmar conferência",
                    onClick = { vm.confirmar() },
                    cor = Verde, corTexto = Preto,
                    habilitado = !ui.confirmando && lote != null && !lendo,
                )
            }
        }
    }

    when (val p = ui.pergunta) {
        is Pergunta.Divergencia -> DialogoDivergencia(p.erro, aoFechar = vm::fecharPergunta, aoAceitar = { vm.aceitarDivergencia(p.erro) })
        is Pergunta.Justificativa -> DialogoJustificativa(p.erro, aoFechar = vm::fecharPergunta, aoEnviar = { c, m -> vm.justificar(p.erro, c, m) })
        is Pergunta.Pin -> DialogoPin(p, aoFechar = vm::fecharPergunta, aoEnviar = { s, pin, mot, c, mj -> vm.liberar(p.erro, s, pin, mot, c, mj) })
        null -> Unit
    }
}

private fun corDaDiferenca(dif: Int): Color = when {
    dif == 0 -> Verde
    dif < 0 -> Vermelho
    else -> Ambar
}

@Composable
private fun TabelaDiferenca(erro: DivergenciaErro) {
    val tamanhos = ordenarTamanhos(erro.esperadoPorTamanho.keys + erro.lidoPorTamanho.keys)
    Column(Modifier.fillMaxWidth().background(SuperficieAlta, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Tam.", Modifier.weight(1f), color = TextoFraco, style = MaterialTheme.typography.labelSmall)
            Text("Esperado", Modifier.weight(1f), color = TextoFraco, style = MaterialTheme.typography.labelSmall)
            Text("Lido", Modifier.weight(1f), color = TextoFraco, style = MaterialTheme.typography.labelSmall)
            Text("Dif.", Modifier.weight(1f), color = TextoFraco, style = MaterialTheme.typography.labelSmall)
        }
        tamanhos.forEach { t ->
            val e = erro.esperadoPorTamanho[t] ?: 0
            val l = erro.lidoPorTamanho[t] ?: 0
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(t, Modifier.weight(1f), color = Texto)
                Text("$e", Modifier.weight(1f), color = Texto)
                Text("$l", Modifier.weight(1f), color = Texto)
                Text("${if (l - e > 0) "+" else ""}${l - e}", Modifier.weight(1f), color = corDaDiferenca(l - e), fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("Total", Modifier.weight(1f), color = TextoFraco)
            Text("${erro.esperadoTotal}", Modifier.weight(1f), color = Texto)
            Text("${erro.lidoTotal}", Modifier.weight(1f), color = Texto)
            Text("${erro.lidoTotal - erro.esperadoTotal}", Modifier.weight(1f), color = corDaDiferenca(erro.lidoTotal - erro.esperadoTotal), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DialogoDivergencia(erro: DivergenciaErro, aoFechar: () -> Unit, aoAceitar: () -> Unit) {
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text("Lido ≠ esperado") },
        text = { Column { TabelaDiferenca(erro); Spacer(Modifier.height(8.dp)); Text("Confirmar com essa diferença?", color = TextoFraco) } },
        confirmButton = { TextButton(onClick = aoAceitar) { Text("Aceitar e continuar") } },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Voltar a ler", color = TextoFraco) } },
    )
}

@Composable
private fun SeletorCausa(causa: CausaDivergencia?, aoEscolher: (CausaDivergencia) -> Unit) {
    Column {
        Rotulo("Causa")
        Spacer(Modifier.height(4.dp))
        CausaDivergencia.entries.chunked(2).forEach { linha ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                linha.forEach { c -> FilterChip(selected = causa == c, onClick = { aoEscolher(c) }, label = { Text(c.rotulo) }) }
            }
        }
    }
}

@Composable
private fun DialogoJustificativa(erro: DivergenciaErro, aoFechar: () -> Unit, aoEnviar: (String, String) -> Unit) {
    var causa by remember { mutableStateOf<CausaDivergencia?>(null) }
    var motivo by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text("Justificar a divergência") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TabelaDiferenca(erro)
                SeletorCausa(causa) { causa = it }
                OutlinedTextField(value = motivo, onValueChange = { motivo = it }, label = { Text("Motivo (mín. 3 letras)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        },
        confirmButton = { TextButton(enabled = causa != null && motivo.trim().length >= 3, onClick = { aoEnviar(causa!!.chave, motivo) }) { Text("Confirmar") } },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar", color = TextoFraco) } },
    )
}

@Composable
private fun DialogoPin(p: Pergunta.Pin, aoFechar: () -> Unit, aoEnviar: (String, String, String, String?, String?) -> Unit) {
    var supervisor by remember { mutableStateOf(p.supervisores.firstOrNull()?.id) }
    var pin by remember { mutableStateOf("") }
    var motivo by remember { mutableStateOf("") }
    var causa by remember { mutableStateOf<CausaDivergencia?>(null) }
    var motivoJust by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text("Liberação do supervisor") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val fora = p.erro.tamanhosForaDaTolerancia
                Text(
                    buildString {
                        append("Divergência de ${p.erro.divergenciaPct?.let { "%.1f".format(it) } ?: "?"}% (tolerância ${p.erro.toleranciaPct?.let { "%.0f".format(it) } ?: "?"}%)")
                        if (fora.isNotEmpty()) append(". Fora da régua: " + fora.joinToString { "${it.tamanho} ${"%.0f".format(it.pct)}%" })
                    },
                    color = Ambar,
                )
                TabelaDiferenca(p.erro)
                Rotulo("Supervisor")
                if (p.supervisores.isEmpty()) Text("Nenhum supervisor com PIN cadastrado.", color = Vermelho)
                p.supervisores.chunked(2).forEach { linha ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        linha.forEach { s -> FilterChip(selected = supervisor == s.id, onClick = { supervisor = s.id }, label = { Text(s.nome) }) }
                    }
                }
                OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = motivo, onValueChange = { motivo = it }, label = { Text("Motivo da liberação") }, modifier = Modifier.fillMaxWidth())
                SeletorCausa(causa) { causa = it }
                OutlinedTextField(value = motivoJust, onValueChange = { motivoJust = it }, label = { Text("Justificativa da divergência") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = supervisor != null && pin.length >= 4 && motivo.trim().length >= 3 && causa != null && motivoJust.trim().length >= 3,
                onClick = { aoEnviar(supervisor!!, pin, motivo, causa?.chave, motivoJust) },
            ) { Text("Liberar e confirmar") }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar", color = TextoFraco) } },
    )
}
