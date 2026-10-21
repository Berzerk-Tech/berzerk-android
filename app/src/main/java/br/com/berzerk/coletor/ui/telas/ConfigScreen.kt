package br.com.berzerk.coletor.ui.telas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import br.com.berzerk.coletor.core.Config
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.rfid.RfidReader
import br.com.berzerk.coletor.ui.comum.BotaoGrande
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.Cartao
import br.com.berzerk.coletor.ui.comum.LinhaInfo
import br.com.berzerk.coletor.ui.comum.Rotulo
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConfigScreen(voltar: () -> Unit) {
    val prefs = Graph.prefs
    val token by prefs.coletorToken.collectAsState()
    val potencia by prefs.potencia.collectAsState()
    val beep by prefs.beep.collectAsState()
    val potenciaMax by RfidReader.potenciaMax.collectAsState()
    val sync by Graph.itag.status.collectAsState()
    var tokenDigitado by remember { mutableStateOf("") }
    var mostrarToken by remember { mutableStateOf(false) }
    val fmt = NumberFormat.getIntegerInstance(Locale("pt", "BR"))

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Configurações", aoVoltar = voltar, rotulo = "Berzerk Nexus")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Cartao {
                Column {
                    Rotulo("Token do coletor")
                    Text("Gerado no Nexus em Revisão RFID → Coletores. Identifica este aparelho nos envios.", color = TextoFraco)
                    Spacer(Modifier.height(8.dp))
                    LinhaInfo("Estado", if (token == null) "não configurado" else "configurado", if (token == null) Ambar else Verde)
                    OutlinedTextField(
                        value = tokenDigitado, onValueChange = { tokenDigitado = it }, singleLine = true,
                        label = { Text(if (token == null) "Colar token" else "Trocar token") },
                        visualTransformation = if (mostrarToken) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { mostrarToken = !mostrarToken }) { Text(if (mostrarToken) "Ocultar" else "Mostrar", color = TextoFraco) }
                        TextButton(enabled = tokenDigitado.isNotBlank(), onClick = { prefs.setColetorToken(tokenDigitado); tokenDigitado = "" }) { Text("Salvar", color = Texto) }
                        if (token != null) TextButton(onClick = { prefs.setColetorToken(null) }) { Text("Remover", color = Vermelho) }
                    }
                }
            }
            Cartao {
                Column {
                    Rotulo("Leitor")
                    Spacer(Modifier.height(8.dp))
                    Text("Potência: $potencia dBm (máx. $potenciaMax)", color = Texto)
                    Slider(
                        value = potencia.toFloat(), valueRange = 5f..potenciaMax.toFloat(), steps = (potenciaMax - 5 - 1).coerceAtLeast(0),
                        onValueChange = { prefs.setPotencia(it.toInt()) },
                        onValueChangeFinished = { RfidReader.setPotencia(prefs.potencia.value) },
                    )
                    Text("Menos potência = lê só o que está perto (bom pra conferir uma caixa). Mais = alcance maior.", color = TextoFraco)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Bipe ao ler", color = Texto)
                        Switch(checked = beep, onCheckedChange = { prefs.setBeep(it); RfidReader.setBeep(it) })
                    }
                }
            }
            Cartao {
                Column {
                    Rotulo("Base de etiquetas (iTag)")
                    Spacer(Modifier.height(8.dp))
                    LinhaInfo("No aparelho", if (sync.totalLocal == 0L) "nada" else "${fmt.format(sync.totalLocal)} EPCs")
                    LinhaInfo("Imagens", if (sync.imagens == 0L) "nenhuma" else fmt.format(sync.imagens))
                    LinhaInfo("Última atualização", sync.concluidoEm?.let { SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(it)) } ?: "nunca")
                    if (sync.rodando) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Text("Baixando… ${fmt.format(sync.baixadosNestaRodada)} nesta rodada (${sync.paginas} páginas)", color = TextoFraco)
                        TextButton(onClick = { Graph.itag.cancelar() }) { Text("Pausar", color = TextoFraco) }
                    } else {
                        if (sync.erro != null) Text(sync.erro!!, color = Vermelho)
                        Spacer(Modifier.height(6.dp))
                        BotaoGrande(if (sync.totalLocal == 0L) "Baixar base" else "Atualizar base", onClick = { Graph.itag.iniciar() })
                        Text("A primeira carga é a base inteira (alguns minutos no Wi-Fi). Depois só o que mudou.", color = TextoFraco)
                    }
                }
            }
            Cartao {
                Column {
                    Rotulo("Sobre")
                    LinhaInfo("Versão", Config.versao)
                    LinhaInfo("Nexus", Config.apiBaseUrl.removeSuffix("/api/").removePrefix("https://"))
                }
            }
        }
    }
}
