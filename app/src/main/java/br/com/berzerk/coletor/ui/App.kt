package br.com.berzerk.coletor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import br.com.berzerk.coletor.auth.SessaoEstado
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.ui.telas.AtualizacaoOverlay
import br.com.berzerk.coletor.ui.telas.ConferenciaScreen
import br.com.berzerk.coletor.ui.telas.ConfigScreen
import br.com.berzerk.coletor.ui.telas.HomeScreen
import br.com.berzerk.coletor.ui.telas.LeituraScreen
import br.com.berzerk.coletor.ui.telas.LoginScreen
import br.com.berzerk.coletor.ui.telas.RecebimentoScreen
import br.com.berzerk.coletor.ui.theme.Preto

object Rotas {
    const val LOGIN = "login"
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

    LaunchedEffect(Unit) { Graph.update.verificar() }
    LaunchedEffect(sessao) {
        // Sessão caiu (refresh recusado, "Trocar operador"): volta pro login limpando a pilha.
        if (sessao is SessaoEstado.Deslogado && nav.currentDestination?.route != Rotas.LOGIN) {
            nav.navigate(Rotas.LOGIN) { popUpTo(0) { inclusive = true } }
        }
    }

    Box(Modifier.fillMaxSize().background(Preto)) {
        NavHost(navController = nav, startDestination = if (sessao is SessaoEstado.Logado) Rotas.HOME else Rotas.LOGIN) {
            composable(Rotas.LOGIN) { LoginScreen(aoEntrar = { nav.navigate(Rotas.HOME) { popUpTo(Rotas.LOGIN) { inclusive = true } } }) }
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
