package dev.propor.android.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * Punto de entrada.
 *
 * Se abre directamente en el visor: sin pantalla de bienvenida, sin tour, sin cuenta y sin muro
 * de pago. El objetivo es visor visible en menos de 20 segundos y primera foto antes de los 60.
 */
class ProporActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = ProporColors.Background,
                    surface = ProporColors.Surface,
                    primary = ProporColors.Accent,
                ),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ProporColors.Background,
                ) {
                    val context = LocalContext.current
                    var showGuide by remember {
                        mutableStateOf(ProporFlags.SHOW_TEST_GUIDE && !context.guideSeen())
                    }

                    if (showGuide) {
                        TestGuideScreen(
                            onStart = {
                                context.markGuideSeen()
                                showGuide = false
                            },
                        )
                    } else {
                        ViewfinderScreen(onShowGuide = { showGuide = true })
                    }
                }
            }
        }
    }
}

// --- Andamiaje de la fase de prueba. Se va con ProporFlags.SHOW_TEST_GUIDE. ---

private const val PREFS = "propor_test"
private const val KEY_GUIDE_SEEN = "guideSeen"

private fun Context.guideSeen(): Boolean =
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GUIDE_SEEN, false)

private fun Context.markGuideSeen() {
    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_GUIDE_SEEN, true)
        .apply()
}
