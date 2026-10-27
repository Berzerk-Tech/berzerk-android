package br.com.berzerk.coletor.ui.telas.impressao

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.data.ModeloResumo
import br.com.berzerk.coletor.impressora.PedirPermissaoBluetooth
import br.com.berzerk.coletor.ui.comum.CampoBusca
import br.com.berzerk.coletor.ui.comum.Cabecalho
import br.com.berzerk.coletor.ui.comum.ChipCategoria
import br.com.berzerk.coletor.ui.comum.FotoProduto
import br.com.berzerk.coletor.ui.comum.ordenarTamanhos
import br.com.berzerk.coletor.ui.telas.RevisaoViewModel
import br.com.berzerk.coletor.ui.theme.Borda
import br.com.berzerk.coletor.ui.theme.Cartao as CorCartao
import br.com.berzerk.coletor.ui.theme.RaioCartao
import br.com.berzerk.coletor.ui.theme.Texto
import br.com.berzerk.coletor.ui.theme.TextoFraco

/**
 * Lista de produtos da impressão. A carga da base, a busca e os chips de categoria são os da Revisão
 * ([RevisaoViewModel], uma instância por tela); aqui muda só o card e o destino do toque.
 */
@Composable
fun ImpressaoListaScreen(voltar: () -> Unit, abrirProduto: (String) -> Unit, irAjustes: () -> Unit) {
    val vm: RevisaoViewModel = viewModel()
    val ui by vm.ui.collectAsState()
    val sync by Graph.itag.status.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Produtos", aoVoltar = voltar, acao = { IndicadorImpressora(irAjustes) })
        PedirPermissaoBluetooth(aoConceder = { Graph.impressora.reconectarSeNecessario() }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

        CampoBusca(ui.busca, vm::setBusca, "Buscar produto ou EAN…")

        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { ChipCategoria("Todas (${ui.resumos.size})", ui.categoriaSel == null) { vm.escolherCategoria(null) } }
            items(ui.categorias, key = { it.first }) { (nome, qtd) ->
                ChipCategoria("$nome ($qtd)", ui.categoriaSel.equals(nome, ignoreCase = true)) { vm.escolherCategoria(nome) }
            }
        }

        Text(
            "${ui.filtrados.size} produtos",
            style = MaterialTheme.typography.bodySmall, color = TextoFraco,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.filtrados.isEmpty() && !ui.carregando) {
                item {
                    val msg = when {
                        ui.busca.isNotBlank() -> "Nenhum produto com \"${ui.busca}\"."
                        sync.totalLocal == 0L || ui.resumos.isEmpty() -> "Nenhum produto na base. Sincronize a base iTag em Config."
                        else -> "Nenhum produto nesta categoria."
                    }
                    Text(msg, color = TextoFraco, modifier = Modifier.padding(top = 24.dp))
                }
            }
            items(ui.filtrados, key = { it.chave }) { m -> CartaoProduto(m) { abrirProduto(m.chave) } }
        }
    }
}

@Composable
private fun CartaoProduto(m: ModeloResumo, onClick: () -> Unit) {
    val forma = RoundedCornerShape(RaioCartao)
    Row(
        Modifier.fillMaxWidth().clip(forma).background(CorCartao, forma).border(1.dp, Borda, forma).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FotoProduto(m.imagemUrl, 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.modelo, style = MaterialTheme.typography.titleMedium, color = Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(m.categoria, style = MaterialTheme.typography.bodySmall, color = TextoFraco, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            ordenarTamanhos(m.eanPorTamanho.keys.filter { it.isNotEmpty() }).joinToString(" "),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp), color = TextoFraco, maxLines = 2,
            modifier = Modifier.width(88.dp),
        )
    }
}
