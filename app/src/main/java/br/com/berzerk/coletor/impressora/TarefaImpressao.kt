package br.com.berzerk.coletor.impressora

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Variação do fluxo de impressão por família de modelo (`D110MV4PrintTask`, `B1PrintTask`, `D110PrintTask`). */
enum class ModoImpressao(val rotulo: String) {
    AUTO("Automático (D110M V4 na B31)"),
    D110M_V4("D110M V4"),
    B1("B1"),
    D110("D110"),
}

object TarefaImpressao {
    const val MODELO_B31 = 5632
    private const val TIMEOUT_PAGINA_MS = 10_000L
    private const val TIMEOUT_STATUS_MS = 5_000L
    private const val INTERVALO_STATUS_MS = 300L

    /** AUTO = D110M V4 se o protocolo for ≥ 4 ou o modelo for B31; senão B1. */
    fun resolverModo(modo: ModoImpressao, versaoProtocolo: Int, modeloId: Int?): ModoImpressao = when {
        modo != ModoImpressao.AUTO -> modo
        versaoProtocolo >= 4 || modeloId == MODELO_B31 -> ModoImpressao.D110M_V4
        else -> ModoImpressao.B1
    }

    suspend fun imprimir(
        proto: NiimbotProtocolo,
        img: ImagemCodificada,
        copias: Int,
        densidade: Int,
        papel: Int,
        modo: ModoImpressao,
        cabecaPx: Int = 600,
        progresso: ((Int, Int) -> Unit)? = null,
    ) {
        val efetivo = resolverModo(modo, proto.versaoProtocolo, proto.modeloId)
        val pacotesImagem = CodificadorImagem.pacotesDaImagem(img, cabecaPx)
        var concluiu = false
        try {
            when (efetivo) {
                ModoImpressao.D110M_V4 -> {
                    proto.enviarTodos(listOf(Pacotes.tipoPapel(papel), Pacotes.densidade(densidade), Pacotes.printStart9b(copias, 0, velocidade = 1)))
                    // A B21 Pro não responde ao primeiro pacote depois do PrintStart: o app original manda PrintStatus sem esperar.
                    proto.enviar(Pacotes.printStatus().umaViaCopia())
                    proto.enviarTodos(
                        listOf(Pacotes.pageSize13b(img.linhas, img.colunas, copias)) + pacotesImagem + Pacotes.pageEnd(),
                        TIMEOUT_PAGINA_MS, progresso,
                    )
                }
                ModoImpressao.B1 -> {
                    proto.enviarTodos(listOf(Pacotes.densidade(densidade), Pacotes.tipoPapel(papel), Pacotes.printStart7b(copias)))
                    proto.enviarTodos(
                        listOf(Pacotes.pageStart(), Pacotes.pageSize6b(img.linhas, img.colunas, copias)) + pacotesImagem + Pacotes.pageEnd(),
                        TIMEOUT_PAGINA_MS, progresso,
                    )
                }
                ModoImpressao.D110 -> {
                    proto.enviarTodos(listOf(Pacotes.densidade(densidade), Pacotes.tipoPapel(papel), Pacotes.printStart1b()))
                    proto.enviarTodos(
                        listOf(Pacotes.printClear(), Pacotes.pageStart(), Pacotes.pageSize4b(img.linhas, img.colunas), Pacotes.quantidade(copias)) +
                            pacotesImagem + Pacotes.pageEnd(),
                        TIMEOUT_PAGINA_MS, progresso,
                    )
                }
                ModoImpressao.AUTO -> error("AUTO já foi resolvido")
            }
            esperarTerminar(proto, copias)
            proto.printEnd()
            if (efetivo == ModoImpressao.D110M_V4) {
                // A B21 Pro perde o primeiro pacote depois do PrintEnd: o app original manda Heartbeat sem esperar.
                proto.enviar(Pacotes.heartbeat(TipoHeartbeat.ADVANCED1).umaViaCopia())
            }
            concluiu = true
        } finally {
            if (!concluiu) runCatching { proto.printEnd() }
        }
    }

    /**
     * Consulta o status a cada 300 ms até a página impressa == [copias]; erro da impressora sobe como exceção.
     * Folga de 15 s + 5 s por cópia: uma etiqueta de 100 mm leva alguns segundos pra sair e avançar.
     */
    private suspend fun esperarTerminar(proto: NiimbotProtocolo, copias: Int) {
        val ok = withTimeoutOrNull(15_000L + 5_000L * copias) {
            while (true) {
                delay(INTERVALO_STATUS_MS)
                if (proto.statusImpressao(TIMEOUT_STATUS_MS).pagina == copias) break
            }
            true
        }
        if (ok == null) throw ImpressoraException("A impressora não terminou a impressão a tempo")
    }

    /** Mesmo pacote sem esperar resposta (`oneWay = true` do niimbluelib). */
    private fun NiimbotPacote.umaViaCopia() = NiimbotPacote(cmd, dados, IntArray(0))
}
