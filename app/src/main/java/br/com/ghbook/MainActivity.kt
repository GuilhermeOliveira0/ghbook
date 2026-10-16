package br.com.ghbook

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import br.com.ghbook.ui.AppNavigation
import br.com.ghbook.ui.theme.GhBookTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** Eventos vindos de fora da interface (intents de "Abrir com", notificação do player). */
object IncomingEvents {
    val pdf = MutableStateFlow<Uri?>(null)
    val openPlayer = MutableStateFlow(false)
}

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Barras transparentes; ícones claros ou escuros conforme o tema do sistema (claro/escuro).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // Só na criação inicial: ao recriar a activity (rotação, retorno após o processo ser encerrado) o Android
        // entrega de novo a intent original, o que reimportaria o PDF de "Abrir com" / reabriria o player.
        // Aberta pelos Recentes, a intent antiga também é reentregue (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY).
        val firstCreate = savedInstanceState == null
        if (firstCreate && ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            handleIntent(intent)
        }
        if (firstCreate && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Necessária para exibir os controles de mídia e o progresso da geração de áudio.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            GhBookTheme {
                AppNavigation()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppGraph.playback.connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            IncomingEvents.openPlayer.value = true
            intent.removeExtra(EXTRA_OPEN_PLAYER)
        }
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) {
            IncomingEvents.pdf.value = uri
            intent.action = null
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
