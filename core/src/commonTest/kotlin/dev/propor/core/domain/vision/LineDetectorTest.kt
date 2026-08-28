package dev.propor.core.domain.vision

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El detector, probado con escenas sintéticas de angulo conocido.
 *
 * No hace falta camara ni dispositivo: se genera una imagen con una linea de exactamente N
 * grados y se comprueba que el detector devuelve N. Es la unica forma de tener una prueba
 * objetiva de un algoritmo de vision en CI.
 */
class LineDetectorTest {

    private val detector = LineDetector()

    @Test
    fun escenaLisa_noInventaLineas() {
        val result = detector.detect(LumaFrame.flat(320, 240))
        assertTrue(result.lines.isEmpty())
        assertNull(result.horizon)
    }

    @Test
    fun lineaHorizontal_seDetectaComoHorizonteANivel() {
        val result = detector.detect(LumaFrame.withLine(320, 240, angleDeg = 0f))

        val horizon = assertNotNull(result.horizon, "deberia haber encontrado el horizonte")
        assertTrue(
            abs(horizon.angle.value) < 3f,
            "una linea horizontal no puede salir a ${horizon.angle.value} grados",
        )
        assertTrue(result.lines.isNotEmpty())
    }

    /**
     * El caso que de verdad importa: si el detector encuentra la linea pero se equivoca en el
     * signo, el coach diria al usuario que gire justo hacia el lado contrario.
     */
    @Test
    fun elAnguloDetectadoCoincideConElReal_enAmbosSentidos() {
        listOf(-20f, -12f, -5f, 5f, 12f, 20f).forEach { expected ->
            val result = detector.detect(LumaFrame.withLine(320, 240, angleDeg = expected))
            val horizon = assertNotNull(result.horizon, "sin horizonte para $expected grados")

            assertTrue(
                abs(horizon.angle.value - expected) < 4f,
                "linea de $expected grados detectada como ${horizon.angle.value}",
            )
        }
    }

    @Test
    fun laConfianzaSubeConLaEvidencia() {
        // Una linea gruesa y contrastada deja mas bordes que una fina y tenue.
        val strong = detector.detect(
            LumaFrame.withLine(320, 240, angleDeg = 8f, thickness = 3, background = 10, foreground = 245),
        )
        val weak = detector.detect(
            LumaFrame.withLine(320, 240, angleDeg = 8f, thickness = 1, background = 110, foreground = 150),
        )

        val strongConfidence = strong.horizon?.confidence?.value ?: 0f
        val weakConfidence = weak.horizon?.confidence?.value ?: 0f
        assertTrue(
            strongConfidence >= weakConfidence,
            "mas evidencia deberia dar mas confianza: $strongConfidence frente a $weakConfidence",
        )
    }

    @Test
    fun elHorizonteVieneMarcadoComoDeteccionVisual() {
        val result = detector.detect(LumaFrame.withLine(320, 240, angleDeg = 6f))
        assertEquals(
            dev.propor.core.domain.scene.HorizonSource.VISION,
            assertNotNull(result.horizon).source,
        )
    }

    @Test
    fun unaLineaMuyInclinada_noSeConfundeConElHorizonte() {
        // 60 grados esta lejos de la horizontal: no es un horizonte torcido, es otra cosa.
        val result = detector.detect(LumaFrame.withLine(320, 240, angleDeg = 60f))
        assertNull(
            result.horizon,
            "una linea a 60 grados no puede pasar por horizonte",
        )
        assertTrue(result.lines.isNotEmpty(), "pero si deberia constar como linea dominante")
    }

    @Test
    fun losSegmentosDevueltosEstanNormalizados() {
        val result = detector.detect(LumaFrame.withLine(320, 240, angleDeg = 10f))
        result.lines.forEach { segment ->
            listOf(segment.from, segment.to).forEach { point ->
                assertTrue(point.x.value in 0f..1f && point.y.value in 0f..1f)
            }
        }
    }

    @Test
    fun unaSolaLineaNoProduceUnaConstelacionDePicos() {
        // Sin supresion de no-maximos, un unico borde genera decenas de lineas casi identicas.
        val result = detector.detect(LumaFrame.withLine(320, 240, angleDeg = 0f, thickness = 2))
        assertTrue(
            result.lines.size <= 4,
            "una linea recta no puede devolver ${result.lines.size} lineas",
        )
    }

    @Test
    fun esDeterminista() {
        val frame = LumaFrame.withLine(320, 240, angleDeg = 7f)
        val a = detector.detect(frame)
        val b = detector.detect(frame)
        assertEquals(a.horizon?.angle?.value, b.horizon?.angle?.value)
        assertEquals(a.lines.size, b.lines.size)
    }

    @Test
    fun soportaFramesConRelleno() {
        // Las camaras alinean las filas: el stride suele ser mayor que el ancho util.
        val width = 200
        val stride = 224
        val height = 150
        val data = ByteArray(stride * height) { 20 }
        for (x in 0 until width) {
            for (t in -1..1) {
                data[(height / 2 + t) * stride + x] = 230.toByte()
            }
        }

        val result = detector.detect(LumaFrame(data, width, height, stride))
        val horizon = assertNotNull(result.horizon, "el relleno de fila rompio la deteccion")
        assertTrue(abs(horizon.angle.value) < 3f)
    }
}
