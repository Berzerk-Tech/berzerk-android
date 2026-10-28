package br.com.berzerk.coletor.ui.telas

import androidx.compose.runtime.Composable

/** Leitura de UM modelo (Revisão RFID): tela única de leitura com o alvo `Modelos` com uma chave. */
@Composable
fun RevisaoModeloScreen(chave: String, voltar: () -> Unit) = LeituraColetorScreen(AlvoLeitura.Modelos.de(listOf(chave)), voltar)
