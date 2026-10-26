package br.com.berzerk.coletor.api

import br.com.berzerk.coletor.core.Config
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Rotas do Nexus que o coletor chama. Base = `Config.apiBaseUrl` (já com `/api/`). */
interface NexusApi {
    @GET("separacao/me")
    suspend fun me(): MeDto

    // --- conferência de lote (revisao-rfid:conferir) ---
    @GET("revisao-rfid/conferencia-lote/lotes-elegiveis")
    suspend fun lotesElegiveis(@Query("busca") busca: String? = null, @Query("limite") limite: Int = 50): LotesElegiveisResponse

    @GET("revisao-rfid/conferencia-lote/supervisores")
    suspend fun supervisores(): SupervisoresResponse

    @POST("revisao-rfid/conferencia-lote/lotes/{loteId}/conferir")
    suspend fun conferir(@Path("loteId") loteId: String): ConferirResponse

    @GET("revisao-rfid/sessoes/{sessaoId}/conferencia")
    suspend fun conferencia(@Path("sessaoId") sessaoId: String): ConferenciaResumoDto

    @POST("revisao-rfid/sessoes/{sessaoId}/lotes/{loteId}/confirmar")
    suspend fun confirmar(
        @Path("sessaoId") sessaoId: String,
        @Path("loteId") loteId: String,
        @Body body: ConfirmarBody,
    ): ConfirmarResponse

    // --- base offline (iTag) ---
    @GET("revisao-rfid/itag/epcs")
    suspend fun itagEpcs(
        @Query("desde") desde: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limite") limite: Int = 20_000,
    ): ItagEpcsResponse

    @GET("revisao-rfid/itag/imagens")
    suspend fun itagImagens(): ItagImagensResponse

    @GET("revisao-rfid/itag/snapshot")
    suspend fun itagSnapshot(): ItagSnapshotDto

    // --- leituras do coletor (autenticado; o Nexus cadastra o aparelho pelo id) ---
    @Headers("Content-Type: text/plain; charset=utf-8")
    @POST("revisao-rfid/leituras")
    suspend fun leituras(
        @Header("x-coletor-dispositivo") dispositivoId: String,
        @Header("x-coletor-modelo") modelo: String,
        @Body corpo: RequestBody,
    ): Response<ResponseBody>

    /** Inventário: mesma assinatura do `/leituras`, mas já inclui o aparelho na sessão. 404 = Nexus ainda sem a rota. */
    @Headers("Content-Type: text/plain; charset=utf-8")
    @POST("revisao-rfid/sessoes/{sessaoId}/leituras")
    suspend fun leiturasSessao(
        @Path("sessaoId") sessaoId: String,
        @Header("x-coletor-dispositivo") dispositivoId: String,
        @Header("x-coletor-modelo") modelo: String,
        @Body corpo: RequestBody,
    ): Response<ResponseBody>

    // --- inventário (sessões de contagem) ---
    @GET("revisao-rfid/sessoes")
    suspend fun listarSessoes(@Query("tipo") tipo: String? = null): ListarSessoesResponse

    @POST("revisao-rfid/sessoes")
    suspend fun abrirSessao(@Body body: AbrirSessaoBody): RfidSessaoDto

    @GET("revisao-rfid/sessoes/{sessaoId}/cabecalho")
    suspend fun cabecalhoSessao(@Path("sessaoId") sessaoId: String): CabecalhoSessaoDto

    @GET("revisao-rfid/sessoes/{sessaoId}/inventario")
    suspend fun inventarioSessao(@Path("sessaoId") sessaoId: String): InventarioSessaoDto

    // --- operador (reconhecimento facial; Bearer do aparelho, sem token de operador ainda) ---
    @Multipart
    @POST("android/operador/identificar")
    suspend fun identificarOperador(
        @Part foto: MultipartBody.Part,
        @Header("x-coletor-dispositivo") dispositivoId: String,
        @Header("x-coletor-modelo") modelo: String,
    ): FacialIdentificarResponse

    /** Revoga a sessão do token passado (explícito: o interceptor só põe o do estado atual). */
    @DELETE("android/operador/sessao")
    suspend fun encerrarSessaoOperador(@Header(Config.OPERADOR_HEADER) token: String): Response<Unit>

    // --- atualização (pública) ---
    @Headers("$SEM_AUTH: 1")
    @GET("android/release")
    suspend fun androidRelease(): AndroidReleaseDto

    companion object {
        /** Header interno: o interceptor não põe Bearer e o remove antes de enviar. */
        const val SEM_AUTH = "X-Berzerk-Sem-Auth"
    }
}
