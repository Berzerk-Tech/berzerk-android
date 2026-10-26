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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextOverflow
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
import br.com.berzerk.coletor.ui.comum.ConsoleLeituras
import br.com.berzerk.coletor.ui.comum.Contador
import br.com.berzerk.coletor.ui.comum.FotoProduto
import br.com.berzerk.coletor.ui.comum.RodapeLeitura
import br.com.berzerk.coletor.ui.comum.TileTamanho
import br.com.berzerk.coletor.ui.comum.Rotulo
import br.com.berzerk.coletor.ui.comum.ordenarTamanhos
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Fundo
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

    Column(Modifier.fillMaxSize().background(Fundo)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 1. Topo (igual ao da Revisão por modelo)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Enquanto o primeiro resumo não chega, a prévia do card da lista preenche o topo e a grade esperada.
                val previa = ui.previa
                FotoProduto(lote?.imagemUrl ?: previa?.imagemUrl, 56.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SeloCategoria(lote?.statusNome ?: previa?.statusNome ?: "Lote")
                        Spacer(Modifier.width(8.dp))
                        Text("LOTE ${lote?.codigo ?: previa?.codigo ?: "…"}", style = MaterialTheme.typography.headlineSmall, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val sub = listOfNotNull(lote?.produto ?: previa?.produto, lote?.cor ?: previa?.cor).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = TextoFraco, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = voltar, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextoFraco)
                }
            }

            if (ui.erro != null) Text(ui.erro!!, color = Vermelho, style = MaterialTheme.typography.bodyMedium)
            if (ui.carregando) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TextoFraco)
                    Text("Carregando as leituras do Nexus…", style = MaterialTheme.typography.bodySmall, color = TextoFraco)
                }
            }

            val previa = ui.previa
            if (lote == null && previa != null && previa.esperadoPorTamanho.isNotEmpty()) {
                // Grade esperada do card, sem lido ainda (cinza): some quando o resumo chega.
                ordenarTamanhos(previa.esperadoPorTamanho.keys).chunked(3).forEach { linha ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        linha.forEach { t ->
                            TileTamanho(t, 0, "", Modifier.weight(1f), rodape = "de ${previa.esperadoPorTamanho[t] ?: 0}", corNumero = TextoFraco)
                        }
                        repeat(3 - linha.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            if (lote != null) {
                // 2. Grade por tamanho: o número grande é o que ESTE aparelho leu agora (zera com Zerar);
                // esperado e EAN vêm do Nexus. O acumulado do Nexus (que decide a confirmação) fica na linha abaixo.
                val tamanhos = ordenarTamanhos(lote.esperadoPorTamanho.keys + ui.porTamanhoLocal.keys + lote.variantes.map { it.tamanho })
                tamanhos.chunked(3).forEach { linha ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        linha.forEach { t ->
                            val lido = ui.porTamanhoLocal[t] ?: 0
                            val esperado = lote.esperadoPorTamanho[t] ?: 0
                            val dif = lido - esperado
                            TileTamanho(
                                t, lido, lote.variantes.firstOrNull { it.tamanho == t }?.ean13.orEmpty(), Modifier.weight(1f),
                                rodape = "de $esperado" + if (dif != 0) " (${if (dif > 0) "+" else ""}$dif)" else "",
                                corNumero = corDaDiferenca(dif, esperado, lote.toleranciaTamanhoPct),
                            )
                        }
                        repeat(3 - linha.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                // 3. Contadores locais (mesma classificação da Revisão por modelo)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Contador("Do lote", ui.doLote, Verde, Modifier.weight(1f))
                    Contador("Outro lote", ui.outros, TextoFraco, Modifier.weight(1f))
                    Contador("Sem cadastro", ui.semCadastro, Vermelho, Modifier.weight(1f))
                    Contador("Total lidas", ui.unicos, Texto, Modifier.weight(1f))
                }
                // Acumulado do Nexus na sessão (é com ESTE lido que ele compara ao confirmar; inclui o que já subiu antes).
                val dif = lote.lidoTotal - lote.esperadoTotal
                Text(
                    "Nexus: esperado ${lote.esperadoTotal}" + (lote.esperadoFonte?.let { " ($it)" } ?: "") + " · lido ${lote.lidoTotal} · " +
                        (if (dif == 0) "bateu" else if (dif > 0) "+$dif" else "$dif"),
                    style = MaterialTheme.typography.bodySmall, color = corDaDiferenca(dif, lote.esperadoTotal, lote.toleranciaPct),
                )
                if (lote.diverge && lote.exigePin == true) Text("Divergência acima da tolerância: a confirmação exige PIN de supervisor.", color = Ambar, style = MaterialTheme.typography.bodyMedium)
            }

            // 4. Console local
            ConsoleLeituras(ui.console, ui.unicos, ui.leituras)

            // 5. Envio
            Text(
                "Neste aparelho ${ui.lidosAqui} etiquetas · Envio: " + when {
                    ui.enviando -> "enviando…"
                    ui.pendentesEnvio > 0 -> "${ui.pendentesEnvio} a enviar"
                    ui.ultimoEnvio != null -> "ok (${ui.ultimoEnvio})"
                    else -> "—"
                },
                style = MaterialTheme.typography.bodySmall, color = if (ui.pendentesEnvio > 0) Ambar else TextoFraco,
            )
            val estadoRfid = rfid
            if (estadoRfid is RfidEstado.Erro) Text(estadoRfid.motivo, color = Vermelho, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
        }

        // 6. Rodapé fixo
        if (ui.concluido) {
            Column(Modifier.fillMaxWidth().background(Fundo).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ui.mensagem ?: "Conferido.", color = Verde, fontWeight = FontWeight.SemiBold)
                BotaoGrande("Voltar à lista", onClick = voltar)
            }
        } else {
            RodapeLeitura(
                lendo = lendo, habilitado = !ui.confirmando && (rfid is RfidEstado.Pronto || rfid is RfidEstado.Lendo),
                aoZerar = vm::zerar, aoLer = { RfidReader.gatilho(!lendo) },
                zerarHabilitado = !ui.confirmando && !ui.enviando,
            ) {
                BotaoGrande(
                    texto = if (ui.confirmando) "Confirmando…" else "✓  Confirmar conferência",
                    onClick = { vm.confirmar() },
                    cor = Verde, corTexto = Preto,
                    habilitado = !ui.confirmando && lote != null,
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

/**
 * Verde = bateu; âmbar = perto (dentro da tolerância que o Nexus manda, pra mais ou pra menos);
 * vermelho = longe. Sem tolerância (API velha) a régua visual é 10 %. É só cor: quem decide divergência é o Nexus.
 */
private fun corDaDiferenca(dif: Int, esperado: Int, toleranciaPct: Double?): Color {
    if (dif == 0) return Verde
    val pct = if (esperado > 0) Math.abs(dif) * 100.0 / esperado else 100.0
    return if (pct <= (toleranciaPct ?: 10.0)) Ambar else Vermelho
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
                Text("${if (l - e > 0) "+" else ""}${l - e}", Modifier.weight(1f), color = corDaDiferenca(l - e, e, erro.toleranciaTamanhoPct), fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("Total", Modifier.weight(1f), color = TextoFraco)
            Text("${erro.esperadoTotal}", Modifier.weight(1f), color = Texto)
            Text("${erro.lidoTotal}", Modifier.weight(1f), color = Texto)
            Text("${erro.lidoTotal - erro.esperadoTotal}", Modifier.weight(1f), color = corDaDiferenca(erro.lidoTotal - erro.esperadoTotal, erro.esperadoTotal, erro.toleranciaPct), fontWeight = FontWeight.Bold)
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
