package br.com.berzerk.coletor.ui.telas

import androidx.compose.runtime.Composable

/** "Ler tudo" (leitura livre, sem filtro de modelo): tela única de leitura com o alvo `Livre`. */
@Composable
fun LeituraScreen(voltar: () -> Unit) = LeituraColetorScreen(AlvoLeitura.Livre, voltar)
