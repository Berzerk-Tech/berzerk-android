package br.com.berzerk.coletor.impressora

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import br.com.berzerk.coletor.ui.comum.Cartao
import br.com.berzerk.coletor.ui.theme.Ambar
import br.com.berzerk.coletor.ui.theme.Texto

/** API ≥ 31: SCAN + CONNECT; até a 30 (o DT50 é Android 11) a varredura BLE exige a localização. */
fun permissoesBluetooth(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

fun temPermissaoBluetooth(c: Context): Boolean =
    permissoesBluetooth().all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

/** `false` também quando o aparelho não tem Bluetooth. */
fun bluetoothLigado(c: Context): Boolean =
    (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

/** Lembra, no processo, que o diálogo do sistema já apareceu (não repetir a cada tela de impressão). */
object EstadoPermissaoBluetooth {
    @Volatile var jaPediu = false
}

/**
 * Pede as permissões de Bluetooth ao entrar (se faltarem) e avisa quando faltar permissão ou o adaptador
 * estiver desligado (o app não liga o Bluetooth sozinho). [aoConceder] roda quando está tudo certo,
 * inclusive ao voltar das configurações do Android.
 */
@Composable
fun PedirPermissaoBluetooth(aoConceder: () -> Unit, modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tem by remember { mutableStateOf(temPermissaoBluetooth(contexto)) }
    var ligado by remember { mutableStateOf(bluetoothLigado(contexto)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tem = temPermissaoBluetooth(contexto) }

    LaunchedEffect(Unit) { if (!tem && !EstadoPermissaoBluetooth.jaPediu) { EstadoPermissaoBluetooth.jaPediu = true; launcher.launch(permissoesBluetooth()) } }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { tem = temPermissaoBluetooth(contexto); ligado = bluetoothLigado(contexto) }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val pronto = tem && ligado
    LaunchedEffect(pronto) { if (pronto) aoConceder() }

    if (!tem) {
        Cartao(modifier) {
            Column {
                Text("Sem permissão de Bluetooth. Libere em Configurações do Android.", color = Ambar)
                TextButton(onClick = { EstadoPermissaoBluetooth.jaPediu = false; launcher.launch(permissoesBluetooth()) }, modifier = Modifier.padding(top = 4.dp)) { Text("Pedir de novo", color = Texto) }
            }
        }
    } else if (!ligado) {
        Cartao(modifier) { Text("Ligue o Bluetooth do aparelho.", color = Ambar) }
    }
}
