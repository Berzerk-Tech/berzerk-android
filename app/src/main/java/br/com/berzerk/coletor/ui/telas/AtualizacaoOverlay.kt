package br.com.berzerk.coletor.ui.telas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.berzerk.coletor.core.Config
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.ui.comum.BotaoGrande
import br.com.berzerk.coletor.ui.theme.Preto
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Vermelho
import br.com.berzerk.coletor.update.Atualizacao
import kotlinx.coroutines.launch

/**
 * Atualização obrigatória = tela cheia, sem saída (como o Berzerk Client no
 * 426). Disponível = diálogo com "Atualizar"/"Depois".
 */
@Composable
fun AtualizacaoOverlay() {
    val estado by Graph.update.estado.collectAsState()
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()

    when (val e = estado) {
        Atualizacao.Nenhuma -> Unit
        is Atualizacao.Disponivel -> AlertDialog(
            onDismissRequest = { Graph.update.ignorarDisponivel() },
            title = { Text("Versão ${e.release.versao} disponível") },
            text = { Text(e.release.notas ?: "Há uma versão nova do Berzerk Coletor.", color = TextoFraco) },
            confirmButton = { TextButton(onClick = { escopo.launch { Graph.update.baixar(ctx, e.release, obrigatoria = false) } }) { Text("Atualizar") } },
            dismissButton = { TextButton(onClick = { Graph.update.ignorarDisponivel() }) { Text("Depois", color = TextoFraco) } },
        )
        is Atualizacao.Obrigatoria -> TelaBloqueio(
            titulo = "Atualização obrigatória",
            texto = e.erro.mensagem.ifBlank { "Este coletor está na versão ${Config.versao} e a mínima é ${e.erro.versaoMinima}." },
            botao = if (e.release?.apk != null) "Baixar versão ${e.release.versao}" else "Procurar atualização",
            aoClicar = { escopo.launch { if (e.release?.apk != null) Graph.update.baixar(ctx, e.release, obrigatoria = true) else Graph.update.verificar() } },
        )
        is Atualizacao.Baixando -> if (e.obrigatoria) TelaBloqueio("Baixando ${e.release.versao}…", "${e.pct}%", null, {}, progresso = e.pct) else AlertDialog(
            onDismissRequest = {},
            title = { Text("Baixando ${e.release.versao}…") },
            text = { LinearProgressIndicator(progress = { e.pct / 100f }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        is Atualizacao.Baixado -> {
            val instalar = { if (!Graph.update.instalar(ctx, e.arquivo)) Unit }
            if (e.obrigatoria) TelaBloqueio("Pronto pra instalar", "Versão ${e.release.versao} baixada. O Android vai pedir pra confirmar a instalação; se pedir pra permitir \"fontes desconhecidas\", permita e volte.", "Instalar", instalar)
            else AlertDialog(
                onDismissRequest = { Graph.update.ignorarDisponivel() },
                title = { Text("Instalar ${e.release.versao}") },
                text = { Text("O Android vai pedir pra confirmar a instalação.", color = TextoFraco) },
                confirmButton = { TextButton(onClick = instalar) { Text("Instalar") } },
                dismissButton = { TextButton(onClick = { Graph.update.ignorarDisponivel() }) { Text("Depois", color = TextoFraco) } },
            )
        }
        is Atualizacao.Erro -> if (e.obrigatoria) TelaBloqueio("Atualização obrigatória", "Falhou: ${e.mensagem}", "Tentar de novo", { escopo.launch { e.release?.let { Graph.update.baixar(ctx, it, true) } ?: Graph.update.verificar() } }) else AlertDialog(
            onDismissRequest = { Graph.update.ignorarDisponivel() },
            title = { Text("Atualização falhou") },
            text = { Text(e.mensagem, color = Vermelho) },
            confirmButton = { TextButton(onClick = { Graph.update.ignorarDisponivel() }) { Text("Fechar") } },
        )
    }
}

@Composable
private fun TelaBloqueio(titulo: String, texto: String, botao: String?, aoClicar: () -> Unit, progresso: Int? = null) {
    Column(
        Modifier.fillMaxSize().background(Preto).systemBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(titulo, style = MaterialTheme.typography.headlineMedium, color = Texto, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(texto, color = TextoFraco, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        if (progresso != null) LinearProgressIndicator(progress = { progresso / 100f }, modifier = Modifier.fillMaxWidth())
        if (botao != null) BotaoGrande(botao, onClick = aoClicar)
    }
}
