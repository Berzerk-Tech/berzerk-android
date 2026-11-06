package br.com.berzerk.coletor.ui.comum

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import br.com.berzerk.coletor.impressora.PedirPermissaoBluetooth
import br.com.berzerk.coletor.rfid.RfidEstado
import br.com.berzerk.coletor.rfid.RfidReader
import br.com.berzerk.coletor.rfid.TipoLeitor
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Cartao
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho

/** Texto curto do estado do leitor pra Home/Config ("Leitor Zebra pronto", "RFD40 não conectou: …"). */
fun descreverLeitor(estado: RfidEstado, tipo: TipoLeitor): Pair<androidx.compose.ui.graphics.Color, String> = when (estado) {
    RfidEstado.Pronto, RfidEstado.Lendo -> Verde to "Leitor ${tipo.curto} pronto"
    RfidEstado.Iniciando -> Ambar to if (tipo == TipoLeitor.ZEBRA) "Conectando no RFD40…" else "Iniciando o leitor…"
    RfidEstado.Desligado -> TextoFraco to "Leitor ${tipo.curto} desligado"
    is RfidEstado.Erro -> Vermelho to estado.motivo
}

/**
 * Diálogo "Qual leitor você vai usar?": Urovo (interno) × Zebra RFD40 (Bluetooth). No Zebra,
 * pede a permissão de Bluetooth, lista os sleds pareados e deixa escolher um. A escolha vale
 * na hora e fica salva no aparelho.
 */
@Composable
fun SeletorLeitorDialog(aoFechar: () -> Unit) {
    val contexto = LocalContext.current
    val tipo by RfidReader.tipo.collectAsState()
    val estado by RfidReader.estado.collectAsState()
    val aparelhos by RfidReader.zebra.aparelhos.collectAsState()
    val conectado by RfidReader.zebra.conectado.collectAsState()
    val (cor, texto) = descreverLeitor(estado, tipo)

    AlertDialog(
        onDismissRequest = aoFechar,
        containerColor = Cartao,
        titleContentColor = Texto,
        textContentColor = Texto,
        title = { Text("Qual leitor você vai usar?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TipoLeitor.entries.forEach { t ->
                    val marcado = t == tipo
                    Row(
                        Modifier.fillMaxWidth().clickable { RfidReader.selecionar(t, contexto) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = marcado, onClick = { RfidReader.selecionar(t, contexto) }, colors = RadioButtonDefaults.colors(selectedColor = Verde, unselectedColor = TextoFraco))
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(t.rotulo, color = Texto, style = MaterialTheme.typography.bodyLarge)
                            Text(t.descricao, color = TextoFraco, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ponto(cor)
                    Spacer(Modifier.width(8.dp))
                    Text(texto, color = cor, style = MaterialTheme.typography.bodySmall)
                }
                if (tipo == TipoLeitor.ZEBRA) {
                    PedirPermissaoBluetooth(aoConceder = { RfidReader.iniciar(contexto) }, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(8.dp))
                    Rotulo("RFD40 pareados")
                    if (aparelhos.isEmpty()) {
                        Text("Nenhum. Pareie o sled nas configurações de Bluetooth do Android (ele aparece como RFD40…).", color = TextoFraco, style = MaterialTheme.typography.bodySmall)
                    }
                    aparelhos.forEach { a ->
                        val marcado = a.endereco == conectado?.endereco
                        Row(
                            Modifier.fillMaxWidth().clickable { RfidReader.selecionarZebra(a.endereco, contexto) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = marcado, onClick = { RfidReader.selecionarZebra(a.endereco, contexto) }, colors = RadioButtonDefaults.colors(selectedColor = Verde, unselectedColor = TextoFraco))
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(a.nome, color = Texto)
                                Text(a.endereco, color = TextoFraco, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { RfidReader.reconectar(contexto) }, enabled = estado !is RfidEstado.Iniciando) { Text("Procurar de novo", color = Texto) }
                        TextButton(onClick = {
                            runCatching { contexto.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("Bluetooth do Android", color = TextoFraco) }
                    }
                } else if (estado is RfidEstado.Erro) {
                    TextButton(onClick = { RfidReader.reconectar(contexto) }) { Text("Tentar de novo", color = Texto) }
                }
            }
        },
        confirmButton = { TextButton(onClick = aoFechar) { Text("Pronto", color = Verde) } },
    )
}
