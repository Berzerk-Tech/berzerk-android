package br.com.berzerk.coletor.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import br.com.berzerk.coletor.auth.OperadorEstado
import br.com.berzerk.coletor.auth.SessaoEstado
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.ui.telas.AtualizacaoOverlay
import br.com.berzerk.coletor.ui.telas.ConferenciaScreen
import br.com.berzerk.coletor.ui.telas.ConfigScreen
import br.com.berzerk.coletor.ui.telas.FacialScreen
import br.com.berzerk.coletor.ui.telas.AlvoLeitura
import br.com.berzerk.coletor.ui.telas.HomeScreen
import br.com.berzerk.coletor.ui.telas.InventarioListaScreen
import br.com.berzerk.coletor.ui.telas.InventarioSessaoScreen
import br.com.berzerk.coletor.ui.telas.LeituraColetorScreen
import br.com.berzerk.coletor.ui.telas.PreviaSessoes
import br.com.berzerk.coletor.upload.EnvioPendentesWorker
import br.com.berzerk.coletor.ui.telas.LeituraScreen
import br.com.berzerk.coletor.ui.telas.LoginScreen
import br.com.berzerk.coletor.ui.telas.RecebimentoScreen
import br.com.berzerk.coletor.ui.telas.RevisaoModeloScreen
import br.com.berzerk.coletor.ui.telas.SelecaoRevisao
import br.com.berzerk.coletor.ui.telas.RevisaoScreen
import br.com.berzerk.coletor.ui.telas.impressao.ImpressaoAjustesScreen
import br.com.berzerk.coletor.ui.telas.impressao.ImpressaoListaScreen
import br.com.berzerk.coletor.ui.telas.impressao.ImpressaoProdutoScreen
import br.com.berzerk.coletor.ui.theme.Preto
import kotlinx.coroutines.delay

object Rotas {
    const val LOGIN = "login"
    const val FACIAL = "facial"
    const val HOME = "home"
    const val RECEBIMENTO = "recebimento"
    const val CONFERENCIA = "conferencia/{loteId}"
    const val REVISAO = "revisao"
    const val REVISAO_VARIOS = "revisao-varios"
    const val REVISAO_MODELO = "revisao/{chave}"
    const val LEITURA = "leitura"
    const val CONFIG = "config"
    const val IMPRESSAO = "impressao"
    const val IMPRESSAO_PRODUTO = "impressao/p/{chave}"
    const val IMPRESSAO_AJUSTES = "impressao/ajustes"
    const val INVENTARIO = "inventario"
    const val INVENTARIO_SESSAO = "inventario/{sessaoId}"
    const val INVENTARIO_LEITURA = "inventario/{sessaoId}/ler?nome={nome}"
    fun inventarioSessao(sessaoId: String) = "inventario/$sessaoId"
    fun inventarioLeitura(sessaoId: String, nome: String) = "inventario/$sessaoId/ler?nome=${Uri.encode(nome)}"
    fun conferencia(loteId: String) = "conferencia/$loteId"
    fun revisaoModelo(chave: String) = "revisao/${Uri.encode(chave)}"
    fun impressaoProduto(chave: String) = "impressao/p/${Uri.encode(chave)}"
}

@Composable
fun App() {
    val nav: NavHostController = rememberNavController()
    val sessao by Graph.auth.sessao.collectAsState()
    val operador by Graph.operador.estado.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) { Graph.update.verificar() }
    // Base iTag: atualiza sozinha ao logar/abrir, ao voltar pro primeiro plano e a cada hora (régua de 24 h dentro do ItagSync).
    LaunchedEffect(sessao) { if (sessao is SessaoEstado.Logado) Graph.itag.atualizarSeVelha() }
    // Leitura que ficou sem enviar (rede caiu, app fechado): o worker manda quando houver rede.
    LaunchedEffect(sessao) { if (sessao is SessaoEstado.Logado) EnvioPendentesWorker.enfileirar(Graph.appContext) }
    LaunchedEffect(sessao) {
        // Conta do aparelho caiu (refresh recusado, "Sair da conta do aparelho"): leva a sessão de operador junto e volta pro login.
        if (sessao is SessaoEstado.Deslogado) {
            Graph.operador.encerrar()
            if (nav.currentDestination?.route != Rotas.LOGIN) nav.navigate(Rotas.LOGIN) { popUpTo(0) { inclusive = true } }
        }
    }
    LaunchedEffect(operador) {
        // Operador saiu (trocar, inatividade, expirou, 403): só a tela facial, a conta do aparelho segue.
        val rota = nav.currentDestination?.route
        if (operador is OperadorEstado.Nenhum && sessao is SessaoEstado.Logado && rota != null && rota != Rotas.LOGIN && rota != Rotas.FACIAL) {
            nav.navigate(Rotas.FACIAL) { popUpTo(0) { inclusive = true } }
        }
    }
    // Inatividade de 15 min: confere a cada 30 s e ao voltar pro app. A cada 120 voltas (1 h) confere a base também.
    LaunchedEffect(Unit) {
        var voltas = 0
        while (true) {
            delay(30_000)
            if (Graph.operador.inativoOuExpirado()) Graph.operador.encerrar()
            if (++voltas % 120 == 0 && Graph.auth.sessao.value is SessaoEstado.Logado) Graph.itag.atualizarSeVelha()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                if (Graph.operador.inativoOuExpirado()) Graph.operador.encerrar()
                if (Graph.auth.sessao.value is SessaoEstado.Logado) Graph.itag.atualizarSeVelha()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Calculado UMA vez: startDestination diferente recria o grafo e zera a pilha. Toda transição
    // depois disso é por navigate (callbacks das telas e os LaunchedEffect acima).
    val inicio = remember {
        when {
            Graph.auth.sessao.value !is SessaoEstado.Logado -> Rotas.LOGIN
            Graph.operador.estado.value is OperadorEstado.Ativo -> Rotas.HOME
            else -> Rotas.FACIAL
        }
    }

    Box(Modifier.fillMaxSize().background(Preto)) {
        NavHost(navController = nav, startDestination = inicio) {
            composable(Rotas.LOGIN) { LoginScreen(aoEntrar = { nav.navigate(Rotas.FACIAL) { popUpTo(Rotas.LOGIN) { inclusive = true } } }) }
            composable(Rotas.FACIAL) { FacialScreen(aoConcluir = { nav.navigate(Rotas.HOME) { popUpTo(Rotas.FACIAL) { inclusive = true } } }) }
            composable(Rotas.HOME) {
                HomeScreen(
                    irRecebimento = { nav.navigate(Rotas.RECEBIMENTO) },
                    irRevisao = { nav.navigate(Rotas.REVISAO) },
                    irInventario = { nav.navigate(Rotas.INVENTARIO) },
                    irImpressao = { nav.navigate(Rotas.IMPRESSAO) },
                    irConfig = { nav.navigate(Rotas.CONFIG) },
                )
            }
            composable(Rotas.RECEBIMENTO) {
                RecebimentoScreen(voltar = { nav.popBackStack() }, abrirLote = { nav.navigate(Rotas.conferencia(it)) })
            }
            composable(Rotas.CONFERENCIA) { entrada ->
                val loteId = entrada.arguments?.getString("loteId") ?: return@composable
                ConferenciaScreen(loteId = loteId, voltar = { nav.popBackStack() }, irConfig = { nav.navigate(Rotas.CONFIG) })
            }
            composable(Rotas.REVISAO) {
                RevisaoScreen(
                    voltar = { nav.popBackStack() },
                    irConfig = { nav.navigate(Rotas.CONFIG) },
                    irLeitura = { nav.navigate(Rotas.LEITURA) },
                    abrirModelo = { nav.navigate(Rotas.revisaoModelo(it)) },
                    lerSelecionados = { SelecaoRevisao.guardar(it); nav.navigate(Rotas.REVISAO_VARIOS) },
                )
            }
            composable(Rotas.REVISAO_VARIOS) {
                // Processo recriado: a seleção em memória se perdeu; tenta o último conjunto salvo.
                val chaves = SelecaoRevisao.chaves.ifEmpty { SelecaoRevisao.ultimaReferencia()?.let { SelecaoRevisao.chavesDe(it) }.orEmpty() }
                if (chaves.isEmpty()) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    LeituraColetorScreen(AlvoLeitura.Modelos.de(chaves), voltar = { nav.popBackStack() })
                }
            }
            composable(Rotas.REVISAO_MODELO) { entrada ->
                val chave = entrada.arguments?.getString("chave") ?: return@composable
                RevisaoModeloScreen(chave = chave, voltar = { nav.popBackStack() })
            }
            composable(Rotas.LEITURA) { LeituraScreen(voltar = { nav.popBackStack() }) }
            composable(Rotas.INVENTARIO) {
                InventarioListaScreen(
                    voltar = { nav.popBackStack() },
                    abrir = { PreviaSessoes.guardar(it); nav.navigate(Rotas.inventarioSessao(it.id)) },
                )
            }
            composable(Rotas.INVENTARIO_SESSAO) { entrada ->
                val sessaoId = entrada.arguments?.getString("sessaoId") ?: return@composable
                InventarioSessaoScreen(
                    sessaoId = sessaoId,
                    voltar = { nav.popBackStack() },
                    ler = { nav.navigate(Rotas.inventarioLeitura(it.id, it.nome)) },
                )
            }
            composable(
                Rotas.INVENTARIO_LEITURA,
                arguments = listOf(navArgument("nome") { type = NavType.StringType; defaultValue = "" }),
            ) { entrada ->
                val sessaoId = entrada.arguments?.getString("sessaoId") ?: return@composable
                val nome = entrada.arguments?.getString("nome").orEmpty()
                LeituraColetorScreen(
                    alvo = AlvoLeitura.Inventario(sessaoId, nome),
                    voltar = { nav.popBackStack() },
                )
            }
            composable(Rotas.IMPRESSAO) {
                ImpressaoListaScreen(
                    voltar = { nav.popBackStack() },
                    abrirProduto = { nav.navigate(Rotas.impressaoProduto(it)) },
                    irAjustes = { nav.navigate(Rotas.IMPRESSAO_AJUSTES) },
                )
            }
            composable(Rotas.IMPRESSAO_PRODUTO) { entrada ->
                val chave = entrada.arguments?.getString("chave") ?: return@composable
                ImpressaoProdutoScreen(chave = chave, voltar = { nav.popBackStack() }, irAjustes = { nav.navigate(Rotas.IMPRESSAO_AJUSTES) })
            }
            composable(Rotas.IMPRESSAO_AJUSTES) { ImpressaoAjustesScreen(voltar = { nav.popBackStack() }) }
            composable(Rotas.CONFIG) { ConfigScreen(voltar = { nav.popBackStack() }) }
        }
        AtualizacaoOverlay()
    }
}
