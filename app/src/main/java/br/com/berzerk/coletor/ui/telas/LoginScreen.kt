package br.com.berzerk.coletor.ui.telas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.berzerk.coletor.core.Config
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.ui.comum.BotaoGrande
import br.com.berzerk.coletor.ui.comum.Chip
import br.com.berzerk.coletor.ui.comum.Rotulo
import br.com.berzerk.coletor.ui.comum.Simbolo
import br.com.berzerk.coletor.ui.comum.gradeDeFundo
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Destrutivo
import br.com.berzerk.coletor.ui.theme.Fundo
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import kotlinx.coroutines.launch

/**
 * A mesma tela de login do Nexus (`packages/auth/src/login-screen.tsx`), no
 * formato de uma coluna do coletor: painel institucional em cima (grade, rótulo
 * "Berzerk Nexus", nome do app em display, marca d'água do símbolo) e o painel
 * de acesso embaixo ("Entrar" / "Acesso corporativo" / chip de acesso restrito /
 * botão "Entrar com @berzerk").
 */
@Composable
fun LoginScreen(aoEntrar: () -> Unit) {
    val escopo = rememberCoroutineScope()
    var carregando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        escopo.launch {
            carregando = true
            Graph.auth.concluirLogin(resultado.data)
                .onSuccess { aoEntrar() }
                .onFailure { erro = it.message }
            carregando = false
        }
    }

    Column(Modifier.fillMaxSize().background(Fundo)) {
        // Painel institucional
        Box(Modifier.fillMaxWidth().weight(0.95f).gradeDeFundo()) {
            Simbolo(
                Modifier.size(300.dp).align(Alignment.BottomEnd).offset(x = 70.dp, y = 90.dp),
                cor = Texto.copy(alpha = 0.05f),
            )
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Simbolo(Modifier.padding(top = 20.dp).height(36.dp).width(33.dp))
                Column(Modifier.padding(bottom = 28.dp)) {
                    Rotulo("Berzerk Nexus")
                    Spacer(Modifier.height(10.dp))
                    Text("COLETOR", style = MaterialTheme.typography.displayLarge, color = Texto)
                    Spacer(Modifier.height(10.dp))
                    Text("Recebimento e leitura RFID no coletor do CD.", style = MaterialTheme.typography.bodyMedium, color = TextoFraco)
                }
            }
        }
        HorizontalDivider(color = Borda)

        // Painel de acesso
        Column(Modifier.fillMaxWidth().weight(1.05f).padding(horizontal = 28.dp, vertical = 28.dp)) {
            Rotulo("Entrar")
            Spacer(Modifier.height(8.dp))
            Text("ACESSO CORPORATIVO", style = MaterialTheme.typography.displaySmall, color = Texto)
            Spacer(Modifier.height(12.dp))
            Chip("Acesso restrito", "@berzerk.com.br")
            Spacer(Modifier.height(28.dp))
            if (carregando) {
                Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Texto, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            } else {
                BotaoGrande("Entrar com @berzerk", onClick = {
                    erro = null
                    runCatching { launcher.launch(Graph.auth.intentDeLogin()) }
                        .onFailure { erro = "Não há navegador no aparelho pra abrir o login (${it.message})." }
                })
            }
            Spacer(Modifier.height(12.dp))
            Text("Entre com sua conta Google @berzerk.com.br.", style = MaterialTheme.typography.bodySmall, color = TextoFraco)
            if (erro != null) {
                Spacer(Modifier.height(12.dp))
                Text(erro!!, style = MaterialTheme.typography.bodySmall, color = Destrutivo)
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = Borda)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Problemas pra entrar? Fale com o time de tecnologia.", style = MaterialTheme.typography.bodySmall, color = TextoFraco, modifier = Modifier.weight(1f))
                Text(Config.versao, style = MaterialTheme.typography.bodySmall, color = TextoFraco)
            }
        }
    }
}
