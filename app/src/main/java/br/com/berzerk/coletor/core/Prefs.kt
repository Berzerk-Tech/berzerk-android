package br.com.berzerk.coletor.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Preferências do aparelho, cifradas (AES-256, chave no Keystore do Android):
 * sessão do Cognito, id do coletor e ajustes do leitor. Nada disso sai do
 * aparelho nem aparece em log.
 */
class Prefs(context: Context) {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "berzerk_coletor_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var authStateJson: String?
        get() = prefs.getString(K_AUTH_STATE, null)
        set(v) = prefs.edit().putString(K_AUTH_STATE, v).apply()

    /** Id (uuid) deste aparelho como coletor no Nexus, devolvido em toda resposta de leitura. Não é segredo. */
    var coletorId: String?
        get() = prefs.getString(K_COLETOR_ID, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString(K_COLETOR_ID, v).apply()

    /** Última lista de pastas (sessões de contagem) vinda do Nexus: o seletor do SALVAR sem rede. Não é segredo. */
    var pastasJson: String?
        get() = prefs.getString(K_PASTAS, null)
        set(v) = prefs.edit().putString(K_PASTAS, v).apply()

    /** Pastas que o Nexus recusou por fechadas (409): as linhas delas podem ser reatribuídas no SALVAR. */
    var pastasFechadas: Set<String>
        get() = prefs.getStringSet(K_PASTAS_FECHADAS, emptySet())?.toSet().orEmpty()
        set(v) = prefs.edit().putStringSet(K_PASTAS_FECHADAS, v).apply()

    /** Impressora Niimbot pareada (mac BLE e nome de exibição). Não são segredo. */
    var impressoraMac: String?
        get() = prefs.getString(K_IMP_MAC, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().apply { if (v == null) remove(K_IMP_MAC) else putString(K_IMP_MAC, v) }.apply()

    var impressoraNome: String?
        get() = prefs.getString(K_IMP_NOME, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().apply { if (v == null) remove(K_IMP_NOME) else putString(K_IMP_NOME, v) }.apply()

    /** Largura e altura do rolo de etiqueta, em mm (padrão 80×100). */
    var etiquetaLarguraMm: Int
        get() = prefs.getInt(K_ETQ_LARGURA, 80)
        set(v) = prefs.edit().putInt(K_ETQ_LARGURA, v).apply()

    var etiquetaAlturaMm: Int
        get() = prefs.getInt(K_ETQ_ALTURA, 100)
        set(v) = prefs.edit().putInt(K_ETQ_ALTURA, v).apply()

    /** Tipo de papel (`LabelType` da Niimbot; 1 = com gap, o padrão da B31). */
    var impressoraPapel: Int
        get() = prefs.getInt(K_IMP_PAPEL, 1)
        set(v) = prefs.edit().putInt(K_IMP_PAPEL, v).apply()

    /** Densidade 1..5. */
    var impressoraDensidade: Int
        get() = prefs.getInt(K_IMP_DENSIDADE, 3)
        set(v) = prefs.edit().putInt(K_IMP_DENSIDADE, v).apply()

    /** Nome de um `ModoImpressao`. */
    var impressoraModo: String
        get() = prefs.getString(K_IMP_MODO, "AUTO") ?: "AUTO"
        set(v) = prefs.edit().putString(K_IMP_MODO, v).apply()

    /** Cópias por toque num tamanho (1..20). */
    var impressaoQtdPorToque: Int
        get() = prefs.getInt(K_IMP_QTD, 1)
        set(v) = prefs.edit().putInt(K_IMP_QTD, v).apply()

    private val _potencia = MutableStateFlow(prefs.getInt(K_POTENCIA, POTENCIA_PADRAO))
    val potencia: StateFlow<Int> = _potencia
    fun setPotencia(dbm: Int) {
        prefs.edit().putInt(K_POTENCIA, dbm).apply()
        _potencia.value = dbm
    }

    private val _beep = MutableStateFlow(prefs.getBoolean(K_BEEP, true))
    val beep: StateFlow<Boolean> = _beep
    fun setBeep(v: Boolean) {
        prefs.edit().putBoolean(K_BEEP, v).apply()
        _beep.value = v
    }

    /** Sessão de operador gravada (tudo opcional: sem token = sem sessão). */
    data class OperadorSalvo(
        val token: String,
        val expiraEm: Long,
        val id: String,
        val nome: String,
        val username: String,
        val ultimaAtividade: Long,
    )

    fun operadorSalvo(): OperadorSalvo? {
        val token = prefs.getString(K_OP_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return OperadorSalvo(
            token = token,
            expiraEm = prefs.getLong(K_OP_EXPIRA, 0L),
            id = prefs.getString(K_OP_ID, "") ?: "",
            nome = prefs.getString(K_OP_NOME, "") ?: "",
            username = prefs.getString(K_OP_USERNAME, "") ?: "",
            ultimaAtividade = prefs.getLong(K_OP_ATIVIDADE, 0L),
        )
    }

    fun salvarOperador(o: OperadorSalvo) {
        prefs.edit()
            .putString(K_OP_TOKEN, o.token)
            .putLong(K_OP_EXPIRA, o.expiraEm)
            .putString(K_OP_ID, o.id)
            .putString(K_OP_NOME, o.nome)
            .putString(K_OP_USERNAME, o.username)
            .putLong(K_OP_ATIVIDADE, o.ultimaAtividade)
            .apply()
    }

    fun limparOperador() {
        prefs.edit().remove(K_OP_TOKEN).remove(K_OP_EXPIRA).remove(K_OP_ID).remove(K_OP_NOME).remove(K_OP_USERNAME).remove(K_OP_ATIVIDADE).apply()
    }

    fun atualizarAtividadeOperador(epochMs: Long) {
        prefs.edit().putLong(K_OP_ATIVIDADE, epochMs).apply()
    }

    companion object {
        private const val K_OP_TOKEN = "operador_token"
        private const val K_OP_EXPIRA = "operador_expira_em"
        private const val K_OP_ID = "operador_id"
        private const val K_OP_NOME = "operador_nome"
        private const val K_OP_USERNAME = "operador_username"
        private const val K_OP_ATIVIDADE = "operador_ultima_atividade"
        private const val K_AUTH_STATE = "auth_state"
        private const val K_COLETOR_ID = "coletor_id"
        private const val K_PASTAS = "pastas_json"
        private const val K_PASTAS_FECHADAS = "pastas_fechadas"
        private const val K_IMP_MAC = "impressora_mac"
        private const val K_IMP_NOME = "impressora_nome"
        private const val K_ETQ_LARGURA = "etiqueta_largura_mm"
        private const val K_ETQ_ALTURA = "etiqueta_altura_mm"
        private const val K_IMP_PAPEL = "impressora_papel"
        private const val K_IMP_DENSIDADE = "impressora_densidade"
        private const val K_IMP_MODO = "impressora_modo"
        private const val K_IMP_QTD = "impressao_qtd_por_toque"
        private const val K_POTENCIA = "potencia_dbm"
        private const val K_BEEP = "beep"
        const val POTENCIA_PADRAO = 26
    }
}
