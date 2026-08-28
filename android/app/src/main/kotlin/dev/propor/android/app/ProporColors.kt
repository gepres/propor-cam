package dev.propor.android.app

import androidx.compose.ui.graphics.Color

/**
 * Los tokens de color de PROPOR.
 *
 * Casi monocromo a proposito: un producto de fotografia que compita en color con las fotos ha
 * perdido. El color se reserva para significar, y en el visor **solo pueden coincidir dos
 * colores que no sean blanco o gris**. Mas es ruido, y el ruido compite con la escena.
 *
 * Provisional hasta H7.1, que los generara desde `design/tokens.json` para las dos plataformas.
 * Mientras tanto viven aqui juntos y no esparcidos por las pantallas, que es lo que hace que una
 * app se descuadre al sexto mes.
 */
object ProporColors {
    /** Negro puro: OLED, contraste real y menos bateria en un visor que esta siempre abierto. */
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF0E0E10)
    val SurfaceRaised = Color(0xFF16161A)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA0A0A8)

    /** Guias. La opacidad es parte del token: a plena opacidad tapan la escena. */
    val Guide = Color(0x8CFFFFFF)

    /** Ancla sugerida por el coach. */
    val Anchor = Color(0xFFFFD84D)

    val Good = Color(0xFF4ADE80)
    val Adjust = Color(0xFFFBBF24)
    val Attention = Color(0xFFF87171)

    /** Marca y Pro. */
    val Accent = Color(0xFF7C5CFF)

    /** Modo astro: solo rojo, para no romper la adaptacion del ojo a la oscuridad. */
    val NightRed = Color(0xFFFF3B30)
}
