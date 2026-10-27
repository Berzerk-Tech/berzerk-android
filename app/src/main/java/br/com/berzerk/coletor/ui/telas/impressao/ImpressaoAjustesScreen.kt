package br.com.berzerk.coletor.ui.telas.impressao

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.berzerk.coletor.core.Config
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.impressora.AparelhoBle
import br.com.berzerk.coletor.impressora.EstadoImpressora
import br.com.berzerk.coletor.impressora.EtiquetaRender
import br.com.berzerk.coletor.impressora.ImpressoraException
import br.com.berzerk.coletor.impressora.ModoImpressao
import br.com.berzerk.coletor.impressora.PedirPermissaoBluetooth
import br.com.berzerk.coletor.impressora.TipoPapel
import br.com.berzerk.coletor.impressora.TrabalhoImpressao
import br.com.berzerk.coletor.impressora.dimensoesPx
import br.com.berzerk.coletor.ui.comum.BotaoGrande
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.ChipCategoria
import br.com.berzerk.coletor.ui.comum.Cartao
import br.com.berzerk.coletor.ui.comum.Rotulo
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Cartao as CorCartao
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Vermelho
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LARG_MIN_MM = 30
private const val ALT_MIN_MM = 50

@Composable
fun ImpressaoAjustesScreen(voltar: () -> Unit) {
    val escopo = rememberCoroutineScope()
    val prefs = Graph.prefs
    val estado by Graph.impressora.estado.collectAsState()
    val ultima by Graph.impressora.ultimaImpressaoEm.collectAsState()

    var mac by remember { mutableStateOf(prefs.impressoraMac) }
    var nome by remember { mutableStateOf(prefs.impressoraNome) }
    var buscando by remember { mutableStateOf(false) }
    var achados by remember { mutableStateOf<List<AparelhoBle>?>(null) }
    var erroBusca by remember { mutableStateOf<String?>(null) }

    var largura by remember { mutableStateOf(prefs.etiquetaLarguraMm.toString()) }
    var altura by remember { mutableStateOf(prefs.etiquetaAlturaMm.toString()) }
    var papel by remember { mutableStateOf(prefs.impressoraPapel) }
    var textoEmCima by remember { mutableStateOf(prefs.etiquetaTextoEmCima) }
    var agrupar by remember { mutableStateOf(prefs.impressoraAgruparLinhas) }
    var densidade by remember { mutableFloatStateOf(prefs.impressoraDensidade.toFloat()) }
    var modo by remember { mutableStateOf(runCatching { ModoImpressao.valueOf(prefs.impressoraModo) }.getOrDefault(ModoImpressao.AUTO)) }

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Ajustes", aoVoltar = voltar, acao = { IndicadorImpressora(irAjustes = {}, comEngrenagem = false) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PedirPermissaoBluetooth(aoConceder = {})

            Cartao {
                Column {
                    Rotulo("Impressora")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (mac == null) "Nenhuma impressora pareada" else "${nome ?: "Impressora"} · $mac",
                        color = Texto,
                    )
                    Spacer(Modifier.height(10.dp))
                    BotaoGrande("Buscar impressora", contorno = true, habilitado = !buscando, onClick = {
                        buscando = true; erroBusca = null; achados = null
                        escopo.launch {
                            try {
                                achados = Graph.impressora.buscar()
                            } catch (e: ImpressoraException) {
                                erroBusca = e.message
                            } finally {
                                buscando = false
                            }
                        }
                    })
                    if (buscando) {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Texto, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Procurando…", color = TextoFraco)
                        }
                    }
                    erroBusca?.let { Text(it, color = Vermelho, modifier = Modifier.padding(top = 8.dp)) }
                    achados?.let { lista ->
                        if (lista.isEmpty()) {
                            Text("Nenhuma impressora encontrada. Ligue a impressora e a Localização do aparelho, e chegue perto.", style = MaterialTheme.typography.bodySmall, color = Ambar, modifier = Modifier.padding(top = 8.dp))
                        }
                        lista.forEach { a ->
                            Column(
                                Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, Borda, RoundedCornerShape(8.dp))
                                    .clickable {
                                        Graph.impressora.conectar(a.mac, a.nome)
                                        mac = a.mac; nome = a.nome; achados = null
                                    }
                                    .padding(12.dp),
                            ) {
                                Text(a.nome, color = Texto, style = MaterialTheme.typography.titleSmall)
                                Text("${a.mac} · ${a.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = TextoFraco)
                            }
                        }
                    }
                    if (estado is EstadoImpressora.Conectada || estado is EstadoImpressora.Imprimindo) {
                        TextButton(onClick = { Graph.impressora.desconectar() }) { Text("Desconectar", color = Texto) }
                    }
                    if (mac != null) {
                        TextButton(onClick = { Graph.impressora.esquecer(); mac = null; nome = null }) { Text("Esquecer", color = Vermelho) }
                    }
                }
            }

            Cartao {
                Column {
                    Rotulo("Etiqueta")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CampoMm("Largura (mm)", largura, LARG_MIN_MM, Modifier.weight(1f)) { largura = it; it.toIntOrNull()?.takeIf { v -> v in LARG_MIN_MM..300 }?.let { v -> prefs.etiquetaLarguraMm = v } }
                        CampoMm("Altura (mm)", altura, ALT_MIN_MM, Modifier.weight(1f)) { altura = it; it.toIntOrNull()?.takeIf { v -> v in ALT_MIN_MM..300 }?.let { v -> prefs.etiquetaAlturaMm = v } }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Nome e EAN", color = TextoFraco, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChipCategoria("Embaixo", !textoEmCima) { textoEmCima = false; prefs.etiquetaTextoEmCima = false }
                        ChipCategoria("Em cima", textoEmCima) { textoEmCima = true; prefs.etiquetaTextoEmCima = true }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Papel", color = TextoFraco, style = MaterialTheme.typography.bodySmall)
                    Dropdown((TipoPapel.opcoes.firstOrNull { it.first == papel } ?: TipoPapel.opcoes[0]).second, TipoPapel.opcoes.map { it.second }) { i ->
                        papel = TipoPapel.opcoes[i].first; prefs.impressoraPapel = papel
                    }
                    Text("Se sair em branco, teste os outros tipos de papel.", style = MaterialTheme.typography.bodySmall, color = TextoFraco)
                    Spacer(Modifier.height(12.dp))
                    Text("Densidade: ${densidade.toInt()}", color = Texto)
                    Slider(
                        value = densidade, valueRange = 1f..5f, steps = 3,
                        onValueChange = { densidade = it },
                        onValueChangeFinished = { prefs.impressoraDensidade = densidade.toInt() },
                    )
                    Text("Modo de impressão", color = TextoFraco, style = MaterialTheme.typography.bodySmall)
                    Dropdown(modo.rotulo, ModoImpressao.entries.map { it.rotulo }) { i ->
                        modo = ModoImpressao.entries[i]; prefs.impressoraModo = modo.name
                    }
                    Text("Se a B31 não imprimir ou sair em branco, teste os outros modos.", style = MaterialTheme.typography.bodySmall, color = TextoFraco)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Envio rápido (várias linhas por escrita Bluetooth)", color = Texto, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = agrupar, onCheckedChange = { agrupar = it; prefs.impressoraAgruparLinhas = it })
                    }
                    Text("Desligue se a etiqueta sair com linhas faltando.", style = MaterialTheme.typography.bodySmall, color = TextoFraco)
                }
            }

            val lMm = largura.toIntOrNull()?.takeIf { it in LARG_MIN_MM..300 } ?: prefs.etiquetaLarguraMm
            val aMm = altura.toIntOrNull()?.takeIf { it in ALT_MIN_MM..300 } ?: prefs.etiquetaAlturaMm
            BotaoGrande("Imprimir etiqueta de teste", onClick = {
                val (l, a) = dimensoesPx(lMm, aMm)
                Graph.impressora.enfileirar(TrabalhoImpressao("teste", "M", "7890000000000", "Etiqueta de teste $lMm×$aMm mm", l, a, 1))
            })
            Text(
                "Versão ${Config.versao} · " + (ultima?.let { "última impressão às ${SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(it))}." } ?: "ainda não imprimiu nesta sessão."),
                style = MaterialTheme.typography.bodySmall, color = TextoFraco,
            )

            Rotulo("Prévia da etiqueta de teste")
            val (lPx, aPx) = dimensoesPx(lMm, aMm)
            PreviaEtiqueta("teste-$lMm-$aMm", lPx, aPx, textoEmCima) { EtiquetaRender.etiquetaDeTeste(lMm, aMm, textoEmCima) }
        }
    }
}

@Composable
private fun CampoMm(rotulo: String, valor: String, minimo: Int, modifier: Modifier, aoMudar: (String) -> Unit) {
    val invalido = valor.isNotEmpty() && (valor.toIntOrNull() ?: 0) !in minimo..300
    OutlinedTextField(
        value = valor,
        isError = invalido,
        supportingText = if (invalido) ({ Text("mín. $minimo mm", color = Vermelho) }) else null,
        onValueChange = { aoMudar(it.filter(Char::isDigit).take(3)) },
        modifier = modifier,
        label = { Text(rotulo, color = TextoFraco) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = CorCartao, unfocusedContainerColor = CorCartao,
            focusedBorderColor = Texto, unfocusedBorderColor = Borda,
            focusedTextColor = Texto, unfocusedTextColor = Texto, cursorColor = Texto,
        ),
    )
}

/** Seletor de uma linha: mostra [atual]; o toque abre a lista de [opcoes] e devolve o índice escolhido. */
@Composable
private fun Dropdown(atual: String, opcoes: List<String>, aoEscolher: (Int) -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    val forma = RoundedCornerShape(6.dp)
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).border(1.dp, Borda, forma).clickable { aberto = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(atual, color = Texto, maxLines = 1, modifier = Modifier.weight(1f))
            Text("▾", color = TextoFraco)
        }
        DropdownMenu(expanded = aberto, onDismissRequest = { aberto = false }) {
            opcoes.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o, color = Texto) }, onClick = { aberto = false; aoEscolher(i) }) }
        }
    }
}
