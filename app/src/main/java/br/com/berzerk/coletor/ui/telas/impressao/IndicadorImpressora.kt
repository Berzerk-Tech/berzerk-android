package br.com.berzerk.coletor.ui.telas.impressao

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.impressora.EstadoImpressora
import br.com.berzerk.coletor.ui.comum.Ponto
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Muted
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco
import br.com.berzerk.coletor.ui.theme.Verde
import br.com.berzerk.coletor.ui.theme.Vermelho

/** `B31-A1F2` fica como está; nome comprido com hífen mostra só o que vem depois dele. */
internal fun nomeCurto(nome: String): String =
    if (nome.length > 10 && '-' in nome) nome.substringAfterLast('-') else nome

/**
 * Ação do `Cabecalho` das telas de impressão: pílula com o estado da impressora (toque reconecta; sem
 * impressora salva leva aos ajustes) e, se [comEngrenagem], a engrenagem dos ajustes.
 */
@Composable
fun IndicadorImpressora(irAjustes: () -> Unit, comEngrenagem: Boolean = true) {
    val estado by Graph.impressora.estado.collectAsState()
    val (cor, texto) = when (val e = estado) {
        EstadoImpressora.Desconectada -> TextoFraco to "Conectar"
        EstadoImpressora.Conectando -> Ambar to "Conectando…"
        is EstadoImpressora.Conectada -> Verde to nomeCurto(e.nome)
        is EstadoImpressora.Imprimindo -> Verde to "Imprimindo (${e.fila})"
        is EstadoImpressora.Erro -> Vermelho to "Erro"
    }
    val forma = RoundedCornerShape(18.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.height(36.dp).clip(forma).background(Muted, forma).border(1.dp, Borda, forma)
                .clickable {
                    if (estado is EstadoImpressora.Desconectada || estado is EstadoImpressora.Erro) {
                        if (Graph.prefs.impressoraMac == null) irAjustes() else Graph.impressora.conectar()
                    }
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ponto(cor)
            Spacer(Modifier.width(6.dp))
            Text(texto, style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp), color = Texto, maxLines = 1)
        }
        if (comEngrenagem) {
            IconButton(onClick = irAjustes) { Icon(Icons.Outlined.Settings, contentDescription = "Ajustes da impressão", tint = Texto) }
        }
    }
}
