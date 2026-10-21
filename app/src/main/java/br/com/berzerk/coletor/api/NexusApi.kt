package br.com.berzerk.coletor.api

import okhttp3.RequestBody
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

    // --- leituras do coletor (token de coletor, sem JWT) ---
    @Headers("$SEM_AUTH: 1", "Content-Type: text/plain; charset=utf-8")
    @POST("rfid/upload")
    suspend fun upload(@Header("x-upload-token") token: String, @Body corpo: RequestBody): UploadResponse

    // --- atualização (pública) ---
    @Headers("$SEM_AUTH: 1")
    @GET("android/release")
    suspend fun androidRelease(): AndroidReleaseDto

    companion object {
        /** Header interno: o interceptor não põe Bearer e o remove antes de enviar. */
        const val SEM_AUTH = "X-Berzerk-Sem-Auth"
    }
}
