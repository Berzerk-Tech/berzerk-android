package br.com.berzerk.coletor.ui.telas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.berzerk.coletor.R
import br.com.berzerk.coletor.core.Config
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.ui.comum.BotaoGrande
import br.com.berzerk.coletor.ui.comum.Rotulo
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Vermelho
import kotlinx.coroutines.launch

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

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.berzerk_logo), contentDescription = "Berzerk", modifier = Modifier.fillMaxWidth(0.7f))
        Spacer(Modifier.height(12.dp))
        Rotulo("Coletor RFID")
        Spacer(Modifier.height(56.dp))
        if (carregando) {
            CircularProgressIndicator()
        } else {
            BotaoGrande("Entrar com @berzerk", onClick = {
                erro = null
                runCatching { launcher.launch(Graph.auth.intentDeLogin()) }
                    .onFailure { erro = "Não há navegador no aparelho pra abrir o login (${it.message})." }
            })
        }
        Spacer(Modifier.height(16.dp))
        Text("Acesso restrito a contas @berzerk.com.br", color = TextoFraco, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (erro != null) {
            Spacer(Modifier.height(16.dp))
            Text(erro!!, color = Vermelho, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(48.dp))
        Text("Berzerk Coletor ${Config.versao}", color = TextoFraco, style = MaterialTheme.typography.labelSmall)
    }
}
