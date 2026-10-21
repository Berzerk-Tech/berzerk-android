package br.com.berzerk.coletor.ui.comum

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Preto
import br.com.berzerk.coletor.ui.theme.Superficie
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco

@Composable
fun Cabecalho(titulo: String, subtitulo: String? = null, aoVoltar: (() -> Unit)? = null, acao: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Preto).statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (aoVoltar != null) {
            IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Texto) }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.titleLarge, color = Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitulo != null) Text(subtitulo, style = MaterialTheme.typography.bodyMedium, color = TextoFraco, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        acao?.invoke()
    }
}

@Composable
fun Cartao(modifier: Modifier = Modifier, conteudo: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Superficie, RoundedCornerShape(10.dp))
            .border(1.dp, Borda, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) { conteudo() }
}

@Composable
fun BotaoGrande(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, cor: Color = Texto, corTexto: Color = Preto, habilitado: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = cor, contentColor = corTexto, disabledContainerColor = Borda, disabledContentColor = TextoFraco),
    ) { Text(texto, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun Rotulo(texto: String) {
    Text(texto.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextoFraco)
}

@Composable
fun LinhaInfo(rotulo: String, valor: String, corValor: Color = Texto) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(rotulo, color = TextoFraco, style = MaterialTheme.typography.bodyMedium)
        Text(valor, color = corValor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Ponto(cor: Color) {
    Box(Modifier.size(10.dp).background(cor, RoundedCornerShape(50)))
}
