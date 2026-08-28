package dev.propor.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier

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
                    ViewfinderScreen()
                }
            }
        }
    }
}
