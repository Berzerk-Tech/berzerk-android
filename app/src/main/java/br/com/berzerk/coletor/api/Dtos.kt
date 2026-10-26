package br.com.berzerk.coletor.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Espelho dos contratos zod de `@berzerk/contracts` (packages/contracts/src/
// revisao-rfid.ts, separacao.ts, android.ts). Todo campo opcional tem default:
// a API pode ganhar campo antes do app (e vice-versa) sem quebrar o parse.

@Serializable
data class OperadorDto(val id: String = "", val nome: String = "", val username: String = "")

@Serializable
data class FacialIdentificarResponse(
    val token: String = "",
    val expiraEm: String = "",
    val operador: OperadorDto = OperadorDto(),
)

/** `code` dos erros de `POST android/operador/identificar` e do 403 de sessão. */
object FacialCodigos {
    const val ROSTO_NAO_DETECTADO = "rosto_nao_detectado"
    const val VARIOS_ROSTOS = "varios_rostos"
    const val ROSTO_NAO_RECONHECIDO = "rosto_nao_reconhecido"
    const val OPERADOR_INATIVO = "operador_inativo"
    const val SESSAO_INVALIDA = "operador_sessao_invalida"
}

@Serializable
data class MeDto(
    val actorId: String,
    val email: String? = null,
    val nome: String? = null,
    val permissions: List<String> = emptyList(),
    val postos: List<String> = emptyList(),
) {
    fun tem(permissao: String): Boolean = permissions.contains("*") || permissions.contains(permissao)
    val rotulo: String get() = nome?.takeIf { it.isNotBlank() } ?: email ?: actorId
}

@Serializable
data class LoteElegivelDto(
    val loteId: String,
    val codigo: String,
    val produto: String? = null,
    val cor: String? = null,
    val oficina: String? = null,
    val estampa: String? = null,
    val statusKey: String = "",
    val statusNome: String = "",
    val aguardandoRecebimento: Boolean = false,
    val esperadoTotal: Int = 0,
    // Opcionais: o Nexus novo manda; app velho × API velha segue parseando.
    val imagemUrl: String? = null,
    val esperadoPorTamanho: Map<String, Int> = emptyMap(),
    val galpaoCheckinEm: String? = null,
)

@Serializable
data class LotesElegiveisResponse(val lotes: List<LoteElegivelDto> = emptyList())

@Serializable
data class SupervisorDto(val id: String, val nome: String, val temPin: Boolean = false)

@Serializable
data class SupervisoresResponse(val supervisores: List<SupervisorDto> = emptyList())

@Serializable
data class ConferirResponse(val sessaoId: String)

/** Outro lote visto na sessão (`conferenciaLoteOutroLoteDtoSchema`) — objeto, não string. */
@Serializable
data class OutroLoteDto(val loteId: String = "", val codigo: String = "", val produto: String? = null, val cor: String? = null, val total: Int = 0)

/** Produto sem lote visto na sessão (`conferenciaLoteOutroProdutoDtoSchema`). */
@Serializable
data class OutroProdutoDto(val sku: String? = null, val ean13: String? = null, val nome: String? = null, val total: Int = 0)

@Serializable
data class TamanhoForaDto(val tamanho: String, val pct: Double = 0.0)

@Serializable
data class VarianteDto(val tamanho: String, val ean13: String, val sku: String? = null)

@Serializable
data class ConferenciaLoteItemDto(
    val loteId: String,
    val codigo: String,
    val produto: String? = null,
    val cor: String? = null,
    val status: String = "pendente",
    val statusKey: String? = null,
    val statusNome: String? = null,
    val confirmadoEm: String? = null,
    val confirmadoPor: String? = null,
    val esperadoPorTamanho: Map<String, Int> = emptyMap(),
    val etiquetadoPorTamanho: Map<String, Int> = emptyMap(),
    val lidoPorTamanho: Map<String, Int> = emptyMap(),
    val diferencaPorTamanho: Map<String, Int> = emptyMap(),
    val cortePorTamanho: Map<String, Int>? = null,
    val costuraPorTamanho: Map<String, Int>? = null,
    val esperadoTotal: Int = 0,
    val etiquetadoTotal: Int = 0,
    val lidoTotal: Int = 0,
    val lidoNaSessaoTotal: Int = 0,
    val lidoDentroDoEsperado: Int = 0,
    val corteTotal: Int = 0,
    val silkTotal: Int = 0,
    val defeitosSilk: Int = 0,
    val costuraTotal: Int? = null,
    val diverge: Boolean = false,
    val divergenciaPct: Double? = null,
    val toleranciaPct: Double? = null,
    val exigePin: Boolean? = null,
    val toleranciaTamanhoPct: Double? = null,
    val tamanhosForaDaTolerancia: List<TamanhoForaDto>? = null,
    val divergenciaCausa: String? = null,
    val divergenciaMotivo: String? = null,
    val divergenciaLiberadaPor: String? = null,
    val primeiraLeituraEm: String? = null,
    val ultimaLeituraEm: String? = null,
    val totalEpcsOutroLote: Int = 0,
    val outrosLotes: List<OutroLoteDto> = emptyList(),
    val totalEpcsDesconhecidos: Int = 0,
    val outrosProdutos: List<OutroProdutoDto> = emptyList(),
    val totalEpcsSemCadastro: Int = 0,
    val esperadoFonte: String? = null,
    val galpaoCheckinEm: String? = null,
    val imagemUrl: String? = null,
    val variantes: List<VarianteDto> = emptyList(),
) {
    val confirmado: Boolean get() = status == "confirmado"
}

@Serializable
data class ConferenciaTotaisDto(
    val esperado: Int = 0,
    val etiquetado: Int = 0,
    val lido: Int = 0,
    val lotesConfirmados: Int = 0,
    val lotesPendentes: Int = 0,
)

@Serializable
data class ConferenciaResumoDto(
    val sessao: JsonElement? = null,
    val lotes: List<ConferenciaLoteItemDto> = emptyList(),
    val totais: ConferenciaTotaisDto = ConferenciaTotaisDto(),
)

/** Causas fechadas de `justificativa.causa` (contrato `revisao-rfid.ts`). */
enum class CausaDivergencia(val chave: String, val rotulo: String) {
    ETIQUETA_DANIFICADA("etiqueta_danificada", "Etiqueta danificada"),
    PECA_FALTANDO("peca_faltando", "Peça faltando"),
    ERRO_SILK("erro_silk", "Erro de silk"),
    ERRO_COSTURA("erro_costura", "Erro de costura"),
    ERRO_LEITURA("erro_leitura", "Erro de leitura"),
    OUTRO("outro", "Outro"),
}

@Serializable
data class JustificativaDto(val causa: String, val motivo: String)

@Serializable
data class LiberacaoDto(val supervisorId: String, val pin: String, val motivo: String)

@Serializable
data class ConfirmarBody(
    val aceitarDivergencia: Boolean? = null,
    val divergenciaVista: Map<String, Int>? = null,
    val justificativa: JustificativaDto? = null,
    val liberacao: LiberacaoDto? = null,
)

@Serializable
data class ConfirmarResponse(
    val lote: ConferenciaLoteItemDto,
    val sessaoConcluida: Boolean = false,
    val sessaoFechada: Boolean = false,
)

/** Corpo dos 422 `conferencia_diverge` / `conferencia_exige_pin` / `conferencia_exige_justificativa`. */
@Serializable
data class DivergenciaErro(
    val error: String,
    val loteId: String? = null,
    val esperadoPorTamanho: Map<String, Int> = emptyMap(),
    val etiquetadoPorTamanho: Map<String, Int> = emptyMap(),
    val lidoPorTamanho: Map<String, Int> = emptyMap(),
    val diferencaPorTamanho: Map<String, Int> = emptyMap(),
    val esperadoTotal: Int = 0,
    val lidoTotal: Int = 0,
    val divergenciaPct: Double? = null,
    val toleranciaPct: Double? = null,
    val toleranciaTamanhoPct: Double? = null,
    val tamanhosForaDaTolerancia: List<TamanhoForaDto> = emptyList(),
    val desbloqueiaEm: String? = null,
    val message: String? = null,
)

@Serializable
data class ItagEpcDto(
    val epc: String,
    val ean13: String? = null,
    val nome: String? = null,
    val tamanho: String? = null,
    val cor: String? = null,
    val referencia: String? = null,
    val codigoItag: Int? = null,
    val dataGerado: String,
)

@Serializable
data class ItagEpcsResponse(val epcs: List<ItagEpcDto> = emptyList(), val proximoCursor: String? = null)

@Serializable
data class ItagImagemDto(val ean13: String, val sku: String? = null, val imagemUrl: String)

@Serializable
data class ItagImagensResponse(val imagens: List<ItagImagemDto> = emptyList())

@Serializable
data class ApkDto(val url: String, val nomeArquivo: String = "", val sha256: String? = null)

/** `GET /android/release` (contrato `android.ts`, nexus). */
@Serializable
data class AndroidReleaseDto(
    val versao: String,
    val versionCode: Int? = null,
    val publicadoEm: String? = null,
    val notas: String? = null,
    val apk: ApkDto? = null,
    val paginaReleases: String = "",
)

/** `426 Upgrade Required` — `appDesatualizadoErrorSchema`. */
@Serializable
data class AppDesatualizadoErro(
    val error: String = "app_desatualizado",
    val versaoMinima: String,
    val versaoAtual: String? = null,
    val mensagem: String = "",
)

/** `POST /revisao-rfid/leituras` (mesmo shape do upload legado): `coletor` = NOME do aparelho que o Nexus cadastrou/achou, `coletorId` o uuid. */
@Serializable
data class UploadResponse(
    val ok: Boolean = true,
    val aceito: Boolean? = null,
    val uploadId: String? = null,
    val coletor: String? = null,
    val coletorId: String? = null,
    val epcs: Int = 0,
    val novos: Int? = null,
    val duplicado: Boolean? = null,
    /** Só em `POST revisao-rfid/sessoes/{id}/leituras`: total de EPCs da sessão depois deste envio. */
    val totalSessao: Int? = null,
)

/** `GET /revisao-rfid/itag/snapshot`: 200 com `url` presignada (15 min) ou 202 `{ gerando: true }`. */
@Serializable
data class ItagSnapshotDto(
    val gerando: Boolean = false,
    val url: String? = null,
    val geradoEm: String? = null,
    val maxDataGerado: String? = null,
    val linhas: Long? = null,
    val bytes: Long? = null,
    val sha256: String? = null,
)

// --- Inventário (sessões `contagem`) --- contratos `revisao-rfid.ts` e `revisao-rfid-inventario.ts` ---

/** `rfidSessaoDtoSchema` (só o que o coletor usa). `status`: `aberta` | `fechada`. */
@Serializable
data class RfidSessaoDto(
    val id: String = "",
    val tipo: String = "",
    val nome: String = "",
    val status: String = "",
    val abertaEm: String = "",
    val fechadaEm: String? = null,
    val coletorIds: List<String> = emptyList(),
    val totalEpcs: Int = 0,
    /** Ainda não existe no Nexus; quando vier, vence `totalEpcs` na tela. */
    val totalPecas: Int? = null,
) {
    val aberta: Boolean get() = status == "aberta"
    val pecas: Int get() = totalPecas ?: totalEpcs
}

@Serializable
data class ListarSessoesResponse(val sessoes: List<RfidSessaoDto> = emptyList())

/** `abrirSessaoBodySchema`: sem defaults de propósito (o json do app não envia campo igual ao default). */
@Serializable
data class AbrirSessaoBody(val tipo: String, val nome: String, val coletorIds: List<String>)

@Serializable
data class InventarioTamanhoDto(
    val tamanho: String? = null,
    val sku: String? = null,
    val ean13: String? = null,
    val contado: Int = 0,
    val jaSaiu: Int = 0,
    val reservado: Int = 0,
    val reservadoNaoLiberado: Int = 0,
    val disponivel: Int = 0,
)

@Serializable
data class CategoriaLojaDto(val id: String = "", val nome: String = "")

@Serializable
data class InventarioProdutoDto(
    val chave: String = "",
    val produtoId: String? = null,
    val nome: String? = null,
    val cor: String? = null,
    val imagemUrl: String? = null,
    val categoriaLoja: CategoriaLojaDto? = null,
    val situacaoLoja: String? = null,
    val contado: Int = 0,
    val jaSaiu: Int = 0,
    val reservado: Int = 0,
    val reservadoNaoLiberado: Int = 0,
    val disponivel: Int = 0,
    val tamanhos: List<InventarioTamanhoDto> = emptyList(),
)

@Serializable
data class InventarioTotaisDto(
    val contado: Int = 0,
    val jaSaiu: Int = 0,
    val reservado: Int = 0,
    val reservadoNaoLiberado: Int = 0,
    val disponivel: Int = 0,
    val modelos: Int = 0,
    val semCadastro: Int = 0,
    val reservadoSemVinculo: Int = 0,
    val jaSaiuSemLinha: Int? = null,
)

/** `GET revisao-rfid/sessoes/{id}/inventario` (`inventarioSessaoDtoSchema`). */
@Serializable
data class InventarioSessaoDto(
    val sessaoId: String = "",
    val referenciaEm: String = "",
    val calculadoEm: String = "",
    val totais: InventarioTotaisDto = InventarioTotaisDto(),
    val produtos: List<InventarioProdutoDto> = emptyList(),
)

/** `GET revisao-rfid/sessoes/{id}/cabecalho` (fatia leve do resumo): só a sessão interessa ao coletor. */
@Serializable
data class CabecalhoSessaoDto(val sessao: RfidSessaoDto = RfidSessaoDto())
