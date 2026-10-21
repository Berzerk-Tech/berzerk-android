package br.com.berzerk.coletor.upload

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import br.com.berzerk.coletor.api.NexusApi
import br.com.berzerk.coletor.api.UploadResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Envia EPCs lidos pro Nexus: texto puro, um EPC por linha, `POST
 * /revisao-rfid/leituras` AUTENTICADO. O aparelho se identifica pelo
 * `ANDROID_ID` (estável por app+aparelho) e o Nexus cadastra o coletor sozinho
 * na primeira leitura (`rfid_coletores.dispositivo_id`) — ninguém digita token.
 * Quem leu fica na trilha pelo usuário do JWT.
 */
class UploadService(context: Context, private val api: () -> NexusApi) {
    @SuppressLint("HardwareIds")
    val dispositivoId: String = run {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.takeIf { it.isNotBlank() } ?: "sem-id"
        "android-$id".replace(Regex("[^A-Za-z0-9._:-]"), "")
    }
    val modelo: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().replace(Regex("[^\\x20-\\x7E]"), "").take(80).ifBlank { "Android" }

    suspend fun enviar(epcs: Collection<String>): UploadResponse {
        val corpo = epcs.joinToString("\n").toRequestBody("text/plain; charset=utf-8".toMediaType())
        return api().leituras(dispositivoId, modelo, corpo)
    }
}
