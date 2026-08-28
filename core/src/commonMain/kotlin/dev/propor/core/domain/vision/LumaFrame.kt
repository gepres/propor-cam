package dev.propor.core.domain.vision

/**
 * Un frame en escala de grises, listo para analizar.
 *
 * **Es la unica puerta por la que entran pixeles al nucleo, y esta acotada a proposito.** La
 * regla de coordenadas normalizadas (ADR-003) sigue en pie: lo que este tipo transporta es la
 * ENTRADA de un algoritmo, y todo lo que sale de el vuelve a estar normalizado.
 *
 * A cambio de esta concesion, el detector de lineas es Kotlin puro y **funciona igual en Android
 * y en iOS**. La alternativa era escribirlo dos veces, una por plataforma, y aceptar que las dos
 * vieran lineas ligeramente distintas en la misma escena.
 *
 * Solo luminancia: el plano Y del YUV que ya entrega la camara, sin conversion ninguna. El color
 * no aporta nada a la deteccion de bordes y triplicaria el trabajo.
 *
 * @param data luminancia de 8 bits, fila por fila.
 * @param rowStride bytes por fila. Suele ser mayor que [width]: las camaras alinean las filas.
 */
class LumaFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int = width,
) {
    init {
        require(width > 0 && height > 0) { "frame vacio: ${width}x$height" }
        require(rowStride >= width) { "rowStride $rowStride menor que el ancho $width" }
        require(data.size >= rowStride * (height - 1) + width) {
            "buffer insuficiente: ${data.size} bytes para ${width}x$height (stride $rowStride)"
        }
    }

    /** Luminancia en [0, 255]. Sin comprobacion de limites: se llama millones de veces. */
    fun luma(x: Int, y: Int): Int = data[y * rowStride + x].toInt() and 0xFF

    companion object {
        /**
         * Construye un frame de prueba con una linea recta de angulo conocido.
         *
         * Vive aqui y no en los tests porque es lo que permite probar el detector **sin camara**
         * y de forma determinista en CI: se genera una escena con la respuesta conocida y se
         * comprueba que el algoritmo la encuentra.
         *
         * @param angleDeg inclinacion de la linea respecto a la horizontal, positiva en sentido
         *   horario en un sistema con la Y hacia abajo.
         */
        fun withLine(
            width: Int,
            height: Int,
            angleDeg: Float,
            thickness: Int = 2,
            background: Int = 30,
            foreground: Int = 220,
        ): LumaFrame {
            val data = ByteArray(width * height) { background.toByte() }
            val slope = kotlin.math.tan(angleDeg * kotlin.math.PI / 180.0)
            val centerX = width / 2.0
            val centerY = height / 2.0

            // Con anti-aliasing. Pintar la linea a base de pixeles enteros crea escalones que
            // no existen en ninguna escena real, y esos escalones generan bordes horizontales
            // falsos que enganan al detector justo en los angulos suaves.
            val span = thickness + 1
            for (x in 0 until width) {
                val yExact = centerY + (x - centerX) * slope
                for (py in (yExact - span).toInt()..(yExact + span).toInt()) {
                    if (py !in 0 until height) continue
                    val distance = kotlin.math.abs(py - yExact)
                    val coverage = (thickness + 0.5 - distance).coerceIn(0.0, 1.0)
                    if (coverage <= 0.0) continue
                    val value = background + (foreground - background) * coverage
                    val current = data[py * width + x].toInt() and 0xFF
                    if (value > current) data[py * width + x] = value.toInt().toByte()
                }
            }
            return LumaFrame(data, width, height)
        }

        /** Frame uniforme, sin un solo borde. El detector no debe inventarse nada. */
        fun flat(width: Int, height: Int, value: Int = 128): LumaFrame =
            LumaFrame(ByteArray(width * height) { value.toByte() }, width, height)
    }
}
