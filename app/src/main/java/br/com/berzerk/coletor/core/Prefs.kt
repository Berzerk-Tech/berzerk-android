package br.com.berzerk.coletor.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Preferências do aparelho, cifradas (AES-256, chave no Keystore do Android):
 * sessão do Cognito, token do coletor e ajustes do leitor. Nada disso sai do
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

    private val _coletorToken = MutableStateFlow(prefs.getString(K_COLETOR_TOKEN, null)?.takeIf { it.isNotBlank() })
    val coletorToken: StateFlow<String?> = _coletorToken
    fun setColetorToken(token: String?) {
        val limpo = token?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().putString(K_COLETOR_TOKEN, limpo).apply()
        _coletorToken.value = limpo
    }

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

    companion object {
        private const val K_AUTH_STATE = "auth_state"
        private const val K_COLETOR_TOKEN = "coletor_token"
        private const val K_POTENCIA = "potencia_dbm"
        private const val K_BEEP = "beep"
        const val POTENCIA_PADRAO = 26
    }
}
