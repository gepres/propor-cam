package dev.propor.android.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ANDAMIAJE DE LA PRUEBA DE CAMPO. **No forma parte del producto.**
 *
 * El diseno de PROPOR dice explicitamente "cero pantallas de bienvenida: se entra al visor".
 * Esta pantalla lo contradice a proposito, y solo mientras dure la prueba con participantes
 * externos: alguien que recibe un APK sin contexto necesita saber que esta probando y que se
 * espera de el, o su opinion valdra para poco.
 *
 * Se quita poniendo [ProporFlags.SHOW_TEST_GUIDE] a false y borrando este archivo. Que sea
 * facil de borrar es parte del diseno: el andamiaje que no se puede quitar acaba quedandose.
 */
@Composable
fun TestGuideScreen(onStart: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ProporColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 26.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(28.dp))

            Text(
                text = "PROPOR",
                color = ProporColors.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "versión de prueba",
                color = ProporColors.Accent,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(26.dp))

            Paragraph(
                "Es una cámara que te avisa cuando el encuadre no está bien, " +
                    "antes de que dispares.",
            )
            Paragraph(
                "Lo hace sin decirte nada: no hay textos ni voz. Te avisa con una " +
                    "vibración y con una línea ámbar en el borde derecho. La idea es que " +
                    "no apartes la vista de lo que estás fotografiando.",
            )

            Section("Qué vas a ver")

            Bullet(
                "Las líneas blancas",
                "Son guías de composición, fijas. Puedes cambiarlas abajo. No reaccionan a nada.",
            )
            Bullet(
                "La línea ámbar del borde derecho",
                "Esto sí es el aviso. Aparece cuando algo no está bien y desaparece al corregirlo.",
            )
            Bullet(
                "La vibración",
                "Crece cuanto peor está el encuadre. Se corta sola a los tres segundos.",
            )
            Bullet(
                "El chip COACH, arriba a la derecha",
                "Apaga y enciende todos los avisos. Verde encendido, gris apagado.",
            )

            Section("Qué queremos que pruebes")

            Numbered(1, "Úsala como usarías cualquier cámara. No la fuerces.")
            Numbered(
                2,
                "Si un aviso te parece absurdo, toca la línea ámbar para descartarlo. " +
                    "Es lo más útil que puedes hacer: así aprende qué no decirte.",
            )
            Numbered(
                3,
                "Si te harta, apaga el COACH y sigue usándola. " +
                    "Apagarlo NO es hacerlo mal: es justo lo que necesitamos saber.",
            )
            Numbered(
                4,
                "Haz al menos una foto de un horizonte de verdad (la calle, el mar, una ventana) " +
                    "y una de un edificio mirando hacia arriba.",
            )

            Section("Lo que todavía NO tiene")

            Paragraph(
                "Para que no pierdas el tiempo reportándolo: no hay galería dentro de la app, " +
                    "ni edición, ni filtros, ni controles manuales, ni flash, ni zoom, ni cámara " +
                    "frontal. Las fotos se guardan en tu galería normal, en la carpeta PROPOR.",
            )

            Section("Qué contarnos al final")

            Paragraph("Solo dos cosas, y no hace falta que apuntes nada mientras usas la app:")
            Bullet(
                "Un momento en que acertó",
                "Algo que te señaló y que no habrías visto tú solo.",
            )
            Bullet(
                "Un momento en que estorbó",
                "Un aviso pesado, equivocado o que llegó cuando no tocaba.",
            )

            Spacer(Modifier.height(20.dp))

            Paragraph(
                "Si algo se rompe o se cierra, cuéntalo también: es una versión de prueba y " +
                    "eso es exactamente lo que buscamos.",
            )

            Spacer(Modifier.height(28.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 22.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ProporColors.Accent)
                .clickable(onClick = onStart)
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Empezar",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(30.dp))
    Text(
        text = title.uppercase(),
        color = ProporColors.Accent,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.2.sp,
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text = text,
        color = ProporColors.TextSecondary,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun Bullet(title: String, body: String) {
    Row(
        modifier = Modifier.padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(ProporColors.Anchor),
        )
        Column {
            Text(
                text = title,
                color = ProporColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = body,
                color = ProporColors.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }
    }
}

@Composable
private fun Numbered(index: Int, text: String) {
    Row(
        modifier = Modifier.padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$index",
            color = ProporColors.Accent,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = text,
            color = ProporColors.TextSecondary,
            fontSize = 15.sp,
            lineHeight = 23.sp,
        )
    }
}
