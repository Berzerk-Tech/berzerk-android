package br.com.berzerk.coletor.data

import br.com.berzerk.coletor.api.AbrirSessaoBody
import br.com.berzerk.coletor.api.ApiException
import br.com.berzerk.coletor.api.CodigoErro
import br.com.berzerk.coletor.api.JsonCfg
import br.com.berzerk.coletor.api.ListarSessoesResponse
import br.com.berzerk.coletor.api.RfidSessaoDto
import br.com.berzerk.coletor.api.mensagemCurta
import br.com.berzerk.coletor.core.Graph
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive

/** Resultado de "Nova pasta…". */
sealed interface ResultadoCriar {
    data class Criada(val pasta: RfidSessaoDto) : ResultadoCriar

    /** 409 `pasta_nome_em_uso`. */
    data object NomeEmUso : ResultadoCriar

    /** 409 `sessao_aberta_existente` (Nexus velho: uma aberta por vez); [sessaoId] é a que já existe. */
    data class JaHaUma(val sessaoId: String?) : ResultadoCriar

    data class Erro(val motivo: String) : ResultadoCriar
}

/**
 * Pastas = sessões de contagem. O operador escolhe a pasta no SALVAR; aqui ficam a ordem do
 * seletor, a pré-seleção e a criação ("Nova pasta…"), compartilhadas pela tela de leitura e pela lista.
 */
object Pastas {
    const val MENSAGEM_NOME_EM_USO = "Já existe uma pasta aberta com esse nome"
    const val MENSAGEM_UMA_POR_VEZ = "O Nexus ainda só aceita uma pasta aberta por vez"
    const val MENSAGEM_SEM_REDE = "Sem rede pra listar as pastas"

    /** Só as abertas, `abertaEm desc` (a API não garante ordem). */
    fun abertas(sessoes: List<RfidSessaoDto>): List<RfidSessaoDto> = sessoes.filter { it.aberta }.sortedByDescending { it.abertaEm }

    /** Lista do seletor: abertas por `abertaEm desc`, com a pré-selecionada ([preId], a pasta da rota) no topo. */
    fun paraSalvar(sessoes: List<RfidSessaoDto>, preId: String?): List<RfidSessaoDto> {
        val lista = abertas(sessoes)
        val pre = preId?.let { id -> lista.firstOrNull { it.id == id } } ?: return lista
        return listOf(pre) + lista.filter { it.id != pre.id }
    }

    /** Escolha de partida do seletor: a atual se ainda existe, senão a pré-selecionada, senão a primeira. */
    fun escolhaInicial(lista: List<RfidSessaoDto>, preId: String?, atual: String? = null): String? =
        listOfNotNull(atual, preId).firstOrNull { id -> lista.any { it.id == id } } ?: lista.firstOrNull()?.id

    /** Erro de `POST revisao-rfid/sessoes` → resultado que a tela entende. */
    fun interpretarErroCriar(e: Throwable): ResultadoCriar {
        if (e is ApiException && e.status == 409) {
            when (e.codigo) {
                CodigoErro.PASTA_NOME_EM_USO -> return ResultadoCriar.NomeEmUso
                CodigoErro.SESSAO_ABERTA_EXISTENTE ->
                    return ResultadoCriar.JaHaUma(runCatching { e.corpoJson?.get("sessaoId")?.jsonPrimitive?.content }.getOrNull())
            }
        }
        return ResultadoCriar.Erro(e.mensagemCurta())
    }

    fun mensagem(r: ResultadoCriar): String? = when (r) {
        is ResultadoCriar.Criada -> null
        ResultadoCriar.NomeEmUso -> MENSAGEM_NOME_EM_USO
        is ResultadoCriar.JaHaUma -> MENSAGEM_UMA_POR_VEZ
        is ResultadoCriar.Erro -> r.motivo
    }

    /** Abre a pasta (precisa do id deste aparelho como coletor: `coletorIds` é obrigatório). */
    suspend fun criar(nome: String): ResultadoCriar {
        val coletorId = try {
            Graph.upload.garantirColetorId()
        } catch (e: Throwable) {
            return ResultadoCriar.Erro("Não consegui registrar este aparelho: ${e.mensagemCurta()}")
        }
        return try {
            ResultadoCriar.Criada(Graph.api.abrirSessao(AbrirSessaoBody("contagem", nome.trim(), listOf(coletorId))))
        } catch (e: Throwable) {
            interpretarErroCriar(e)
        }
    }

    /** Pastas guardadas da última listagem boa (seletor sem rede). */
    fun doCache(): List<RfidSessaoDto> =
        Graph.prefs.pastasJson?.let { runCatching { JsonCfg.json.decodeFromString<ListarSessoesResponse>(it).sessoes }.getOrNull() }.orEmpty()

    /** A pasta fechou no Nexus: sai do cache do seletor e as linhas dela podem ir pra outra pasta. */
    fun marcarFechada(id: String) {
        runCatching {
            Graph.prefs.pastasFechadas = Graph.prefs.pastasFechadas + id
            guardarCache(doCache().filter { it.id != id })
        }
    }

    fun guardarCache(sessoes: List<RfidSessaoDto>) {
        runCatching { Graph.prefs.pastasJson = JsonCfg.json.encodeToString(ListarSessoesResponse(sessoes)) }
    }
}
