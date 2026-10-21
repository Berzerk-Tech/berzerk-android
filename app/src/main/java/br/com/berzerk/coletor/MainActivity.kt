package br.com.berzerk.coletor

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
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
        enableEdgeToEdge()
        RfidReader.setPotencia(Graph.prefs.potencia.value)
        RfidReader.setBeep(Graph.prefs.beep.value)
        RfidReader.iniciar(this)
        setContent { BerzerkTheme { App() } }
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

    /** Gatilho físico do Urovo: apertou lê, soltou para. Só o primeiro evento (sem auto-repeat). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Graph.operador.registrarAtividade()
        if (keyCode in RfidReader.KEYCODES_GATILHO) {
            if (event.repeatCount == 0) RfidReader.gatilho(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in RfidReader.KEYCODES_GATILHO) {
            RfidReader.gatilho(false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        if (isFinishing) RfidReader.liberar()
        super.onDestroy()
    }
}
