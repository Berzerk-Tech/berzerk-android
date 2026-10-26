package br.com.berzerk.coletor.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.com.berzerk.coletor.auth.SessaoEstado
import br.com.berzerk.coletor.core.Graph
import java.util.concurrent.TimeUnit

/**
 * Manda o que ficou com `enviadoEm = null` (rede caiu, app fechou com leitura não salva). Enfileirado
 * com constraint de rede, uma só vez por vez (`KEEP` na chave [CHAVE]). `Graph` é inicializado no
 * `Application`, então o worker já tem o cliente com Bearer.
 */
class EnvioPendentesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Sem conta do aparelho logada não há Bearer: o pendente espera o próximo login (o app reenfileira ao abrir).
        if (Graph.auth.sessao.value !is SessaoEstado.Logado) return Result.success()
        return if (Graph.upload.enviarTudoPendente()) Result.success() else Result.retry()
    }

    companion object {
        const val CHAVE = "upload-pendentes"

        fun enfileirar(context: Context) {
            val pedido = OneTimeWorkRequestBuilder<EnvioPendentesWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            runCatching { WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(CHAVE, ExistingWorkPolicy.KEEP, pedido) }
        }
    }
}
