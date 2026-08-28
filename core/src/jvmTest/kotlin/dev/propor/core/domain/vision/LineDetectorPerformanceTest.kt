package dev.propor.core.domain.vision

import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Canario de rendimiento del detector.
 *
 * No es el benchmark formal —ese llega con H1.5 y corre en dispositivo— pero avisa si alguien
 * convierte el algoritmo en algo cuadratico sin darse cuenta. El presupuesto real es de 33 ms
 * por frame para TODAS las senales de vision juntas, asi que el detector solo puede quedarse
 * con una fraccion.
 *
 * El umbral es deliberadamente holgado: en CI compiten otros procesos y un test de tiempo
 * estricto seria intermitente, que es peor que no tenerlo.
 */
class LineDetectorPerformanceTest {

    @Test
    fun detectarUnFrameDeVgaEsBarato() {
        val detector = LineDetector()
        val frame = LumaFrame.withLine(640, 480, angleDeg = 7f)

        // Calentamiento: la JVM necesita ver el codigo antes de compilarlo de verdad.
        repeat(20) { detector.detect(frame) }

        val runs = 50
        val nanos = measureNanoTime { repeat(runs) { detector.detect(frame) } }
        val millisPerFrame = nanos / runs / 1_000_000.0

        println("LineDetector: %.2f ms por frame de 640x480".format(millisPerFrame))

        assertTrue(
            millisPerFrame < 60.0,
            "el detector tarda %.2f ms: se ha vuelto demasiado caro".format(millisPerFrame),
        )
    }
}
