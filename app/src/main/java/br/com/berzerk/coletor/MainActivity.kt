package br.com.berzerk.coletor

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import br.com.berzerk.coletor.core.Graph
import br.com.berzerk.coletor.rfid.RfidReader
import br.com.berzerk.coletor.ui.App
import br.com.berzerk.coletor.ui.theme.BerzerkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Coletor de mão: a tela nunca apaga enquanto o app está na frente (o operador lê sem tocar na tela).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        RfidReader.setPotencia(Graph.prefs.potencia.value)
        RfidReader.setBeep(Graph.prefs.beep.value)
        RfidReader.iniciar(this)
        setContent { BerzerkTheme { App() } }
        // Só numa abertura nova: recriação (rotação, processo morto) reentregaria o mesmo intent.
        if (savedInstanceState == null) tratarLogout(intent)
    }

    /** Callback do `/logout` do Cognito (`berzerkrfid://auth/logout`) volta pra cá: aí sim limpa a sessão local. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tratarLogout(intent)
    }

    private fun tratarLogout(intent: Intent?) {
        val uri = intent?.data ?: return
        // Reabertura pelo histórico reentrega o intent antigo: não é um callback novo.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val logout = uri.scheme == "berzerkrfid" && uri.host == "auth" && uri.path == "/logout"
        intent.data = null // consumido: não dispara de novo
        if (logout) Graph.concluirSaida()
    }

    override fun onResume() {
        super.onResume()
        RfidReader.iniciar(this)
    }

    /** Toque na tela conta como atividade do operador (inatividade de 15 min encerra a sessão). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Graph.operador.registrarAtividade()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Gatilho físico do Urovo: apertar LIGA a leitura e soltar não faz nada; parar é só pelo botão
     * PARAR da tela (pedido do Lucas: ler uma arara inteira sem ficar segurando). Só o primeiro
     * evento (sem auto-repeat); apertar de novo enquanto lê é ignorado.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Graph.operador.registrarAtividade()
        if (keyCode in RfidReader.KEYCODES_GATILHO) {
            if (event.repeatCount == 0) RfidReader.gatilho(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Soltar o gatilho não para a leitura; o evento é consumido pra não virar tecla pro Compose. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in RfidReader.KEYCODES_GATILHO) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        if (isFinishing) RfidReader.liberar()
        super.onDestroy()
    }
}
