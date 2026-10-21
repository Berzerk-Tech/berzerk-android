package br.com.berzerk.coletor.upload

import br.com.berzerk.coletor.api.NexusApi
import br.com.berzerk.coletor.api.UploadResponse
import br.com.berzerk.coletor.core.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class SemTokenException : IllegalStateException("Token do coletor não configurado.")

/**
 * Envia EPCs lidos pro Nexus como o coletor MacroDroid do legado: texto puro,
 * um EPC por linha, `POST /api/rfid/upload` com `x-upload-token`. O Nexus
 * grava o upload, deduplica (mesmo conteúdo = `duplicado`) e distribui pelas
 * sessões abertas (conferência do lote, contagem de estoque).
 */
class UploadService(private val api: () -> NexusApi, private val prefs: Prefs) {
    suspend fun enviar(epcs: Collection<String>): UploadResponse {
        val token = prefs.coletorToken.value ?: throw SemTokenException()
        val corpo = epcs.joinToString("\n").toRequestBody("text/plain; charset=utf-8".toMediaType())
        return api().upload(token, corpo)
    }
}
