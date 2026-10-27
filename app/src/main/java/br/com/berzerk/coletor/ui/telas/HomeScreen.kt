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
import br.com.berzerk.coletor.auth.OperadorEstado
import br.com.berzerk.coletor.auth.SessaoEstado
import br.com.berzerk.coletor.core.Config
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.rfid.RfidEstado
import br.com.berzerk.coletor.rfid.RfidReader
import br.com.berzerk.coletor.ui.comum.BotaoGrande
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.Cartao
import br.com.berzerk.coletor.ui.comum.formatarInteiro
import br.com.berzerk.coletor.ui.comum.Ponto
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho

/**
 * `GET /separacao/me` em memória de processo: permissões e nome de cadastro.
 * Vale só pra sessão de operador que o buscou (`chave` = id + expiração): trocou o operador, descarta.
 */
object MeCache {
    private var chave: String? = null
    private var me: MeDto? = null

    fun valor(chaveAtual: String?): MeDto? = me.takeIf { chave == chaveAtual && chaveAtual != null }

    fun guardar(chaveAtual: String?, valor: MeDto) {
        chave = chaveAtual
        me = valor
    }
}

@Composable
fun HomeScreen(irRecebimento: () -> Unit, irRevisao: () -> Unit, irInventario: () -> Unit, irConfig: () -> Unit) {
    val sessao by Graph.auth.sessao.collectAsState()
    val rfid by RfidReader.estado.collectAsState()
    val sync by Graph.itag.status.collectAsState()
    val operador by Graph.operador.estado.collectAsState()
    val ativo = operador as? OperadorEstado.Ativo
    val chave = ativo?.let { "${it.id}:${it.expiraEm}" }
    var me by remember(chave) { mutableStateOf(MeCache.valor(chave)) }
    var erroMe by remember(chave) { mutableStateOf<String?>(null) }

    LaunchedEffect(sessao, chave) {
        if (sessao is SessaoEstado.Logado && ativo != null && me == null) {
            runCatching { Graph.api.me() }
                .onSuccess { me = it; MeCache.guardar(chave, it); erroMe = null }
                .onFailure { erroMe = it.mensagemCurta() }
        }
    }
    val email = ativo?.username?.takeIf { it.isNotBlank() } ?: me?.rotulo ?: ativo?.nome?.takeIf { it.isNotBlank() } ?: "—"
    val podeConferir = me?.let { it.tem(Config.PERMISSAO_CONFERIR) || it.tem(Config.PERMISSAO_OPERATE) }

    Column(Modifier.fillMaxSize()) {
        Cabecalho(
            titulo = "Berzerk",
            subtitulo = email,
            acao = {
                IconButton(onClick = irConfig) { Icon(Icons.Default.Settings, contentDescription = "Configurações", tint = Texto) }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val (corRfid, textoRfid) = when (val e = rfid) {
                RfidEstado.Pronto, RfidEstado.Lendo -> Verde to "Leitor pronto"
                RfidEstado.Iniciando -> Ambar to "Iniciando o leitor…"
                RfidEstado.Desligado -> TextoFraco to "Leitor desligado"
                is RfidEstado.Erro -> Vermelho to e.motivo
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ponto(corRfid)
                Spacer(Modifier.width(8.dp))
                val base = if (sync.totalLocal == 0L) "base não baixada" else "base ${formatarInteiro(sync.totalLocal)}" + if (sync.rodando) " (atualizando…)" else ""
                Text(
                    "$textoRfid · ${formatarInteiro(sync.modelos)} modelos · $base",
                    style = MaterialTheme.typography.bodySmall, color = TextoFraco, maxLines = 2,
                )
            }
            if (erroMe != null) Text(erroMe!!, color = Vermelho)
            if (podeConferir == false) {
                Cartao { Text("Sua conta ainda não tem acesso ao recebimento. Peça ao admin o papel coletor-recebimento no Nexus.", color = Ambar) }
            }
            MenuItem("01", "Revisão RFID", "Escolha o modelo, dê play e veja por tamanho o que tem na sua frente", habilitado = true, onClick = irRevisao)
            val semPasta by remember { Graph.db.leituras().contarSemPastaTotal() }.collectAsState(0)
            val descPastas = "Contagens abertas e fechadas; a leitura entra na pasta que você escolhe no Salvar" +
                if (semPasta > 0) " · $semPasta leituras sem pasta" else ""
            MenuItem("02", "Pastas", descPastas, habilitado = true, onClick = irInventario)
            MenuItem("03", "Recebimento", "Conferir lotes no galpão e confirmar no Nexus", habilitado = podeConferir != false, onClick = irRecebimento)
            Spacer(Modifier.height(4.dp))
            BotaoGrande("Trocar operador", onClick = { Graph.operador.encerrar() }, contorno = true)
            Text("Versão ${Config.versao}", style = MaterialTheme.typography.bodySmall, color = TextoFraco)
        }
    }
}

/** Card numerado: todos com a mesma altura, número em verde e título em display. */
@Composable
private fun MenuItem(numero: String, titulo: String, descricao: String, habilitado: Boolean, onClick: () -> Unit) {
    Cartao(Modifier.height(116.dp).clickable(enabled = habilitado, onClick = onClick)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Text(numero, style = MaterialTheme.typography.displayMedium, color = if (habilitado) Verde else TextoFraco.copy(alpha = 0.4f))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(titulo.uppercase(), style = MaterialTheme.typography.headlineSmall, color = if (habilitado) Texto else TextoFraco)
                Text(descricao, style = MaterialTheme.typography.bodyMedium, color = TextoFraco, maxLines = 3)
            }
            Text("→", style = MaterialTheme.typography.titleLarge, color = TextoFraco)
        }
    }
}
