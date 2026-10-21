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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.berzerk.coletor.api.MeDto
import br.com.berzerk.coletor.api.mensagemCurta
import br.com.berzerk.coletor.auth.SessaoEstado
import br.com.berzerk.coletor.core.Config
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.rfid.RfidEstado
import br.com.berzerk.coletor.rfid.RfidReader
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.Cartao
import br.com.berzerk.coletor.ui.comum.LinhaInfo
import br.com.berzerk.coletor.ui.comum.Ponto
import br.com.berzerk.coletor.ui.comum.Rotulo
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho
import java.text.NumberFormat
import java.util.Locale

/** `GET /separacao/me` em memória de processo: permissões e nome de cadastro. */
object MeCache {
    var me: MeDto? = null
    var erro: String? = null
}

@Composable
fun HomeScreen(irRecebimento: () -> Unit, irLeitura: () -> Unit, irConfig: () -> Unit) {
    val sessao by Graph.auth.sessao.collectAsState()
    val rfid by RfidReader.estado.collectAsState()
    val sync by Graph.itag.status.collectAsState()
    val token by Graph.prefs.coletorToken.collectAsState()
    var me by remember { mutableStateOf(MeCache.me) }
    var erroMe by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessao) {
        if (sessao is SessaoEstado.Logado && me == null) {
            runCatching { Graph.api.me() }
                .onSuccess { me = it; MeCache.me = it; erroMe = null }
                .onFailure { erroMe = it.mensagemCurta() }
        }
    }

    val logado = sessao as? SessaoEstado.Logado
    val nome = me?.rotulo ?: logado?.nome ?: logado?.email ?: "—"
    val podeConferir = me?.let { it.tem(Config.PERMISSAO_CONFERIR) || it.tem(Config.PERMISSAO_OPERATE) }

    Column(Modifier.fillMaxSize()) {
        Cabecalho(
            titulo = nome,
            subtitulo = logado?.email,
            acao = {
                TextButton(onClick = { Graph.auth.sair() }) { Text("Trocar operador", color = TextoFraco) }
                IconButton(onClick = irConfig) { Icon(Icons.Default.Settings, contentDescription = "Configurações", tint = Texto) }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (erroMe != null) Text(erroMe!!, color = Vermelho)
            if (podeConferir == false) {
                Cartao { Text("Sua conta ainda não tem acesso ao recebimento. Peça ao admin o papel coletor-recebimento no Nexus.", color = Ambar) }
            }
            MenuItem("01", "Recebimento", "Conferir lotes que chegaram do galpão", habilitado = podeConferir != false, onClick = irRecebimento)
            MenuItem("02", "Leitura livre", "Contar peças por produto e tamanho, enviar pro Nexus", habilitado = true, onClick = irLeitura)
            Spacer(Modifier.height(8.dp))
            Cartao {
                Column {
                    Rotulo("Aparelho")
                    Spacer(Modifier.height(6.dp))
                    val (corRfid, textoRfid) = when (val e = rfid) {
                        RfidEstado.Pronto, RfidEstado.Lendo -> Verde to "Leitor pronto"
                        RfidEstado.Iniciando -> Ambar to "Iniciando o leitor…"
                        RfidEstado.Desligado -> TextoFraco to "Leitor desligado"
                        is RfidEstado.Erro -> Vermelho to e.motivo
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Ponto(corRfid); Spacer(Modifier.width(8.dp)); Text(textoRfid, color = Texto) }
                    Spacer(Modifier.height(4.dp))
                    val fmt = NumberFormat.getIntegerInstance(Locale("pt", "BR"))
                    LinhaInfo("Base de etiquetas", if (sync.totalLocal == 0L) "não baixada" else "${fmt.format(sync.totalLocal)} EPCs" + if (sync.rodando) " (baixando…)" else "")
                    LinhaInfo("Token do coletor", if (token == null) "não configurado" else "ok", if (token == null) Ambar else Verde)
                    LinhaInfo("Versão", Config.versao)
                }
            }
        }
    }
}

@Composable
private fun MenuItem(numero: String, titulo: String, descricao: String, habilitado: Boolean, onClick: () -> Unit) {
    Cartao(Modifier.clickable(enabled = habilitado, onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(numero, style = MaterialTheme.typography.headlineMedium, color = if (habilitado) TextoFraco else TextoFraco.copy(alpha = 0.4f))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(titulo, style = MaterialTheme.typography.titleLarge, color = if (habilitado) Texto else TextoFraco)
                Text(descricao, style = MaterialTheme.typography.bodyMedium, color = TextoFraco)
            }
        }
    }
}
