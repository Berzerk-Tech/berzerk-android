package br.com.berzerk.coletor.ui

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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import br.com.berzerk.coletor.auth.OperadorEstado
import br.com.berzerk.coletor.auth.SessaoEstado
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.ui.telas.AtualizacaoOverlay
import br.com.berzerk.coletor.ui.telas.ConferenciaScreen
import br.com.berzerk.coletor.ui.telas.ConfigScreen
import br.com.berzerk.coletor.ui.telas.FacialScreen
import br.com.berzerk.coletor.ui.telas.HomeScreen
import br.com.berzerk.coletor.ui.telas.LeituraScreen
import br.com.berzerk.coletor.ui.telas.LoginScreen
import br.com.berzerk.coletor.ui.telas.RecebimentoScreen
import br.com.berzerk.coletor.ui.theme.Preto
import kotlinx.coroutines.delay

object Rotas {
    const val LOGIN = "login"
    const val FACIAL = "facial"
    const val HOME = "home"
    const val RECEBIMENTO = "recebimento"
    const val CONFERENCIA = "conferencia/{loteId}"
    const val LEITURA = "leitura"
    const val CONFIG = "config"
    fun conferencia(loteId: String) = "conferencia/$loteId"
}

@Composable
fun App() {
    val nav: NavHostController = rememberNavController()
    val sessao by Graph.auth.sessao.collectAsState()
    val operador by Graph.operador.estado.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) { Graph.update.verificar() }
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
    // Inatividade de 15 min: confere a cada 30 s e ao voltar pro app.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            if (Graph.operador.inativoOuExpirado()) Graph.operador.encerrar()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && Graph.operador.inativoOuExpirado()) Graph.operador.encerrar()
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
                    irLeitura = { nav.navigate(Rotas.LEITURA) },
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
            composable(Rotas.LEITURA) { LeituraScreen(voltar = { nav.popBackStack() }, irConfig = { nav.navigate(Rotas.CONFIG) }) }
            composable(Rotas.CONFIG) { ConfigScreen(voltar = { nav.popBackStack() }) }
        }
        AtualizacaoOverlay()
    }
}
