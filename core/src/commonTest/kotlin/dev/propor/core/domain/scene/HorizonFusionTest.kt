package dev.propor.core.domain.scene

import dev.propor.core.domain.geometry.Confidence
import dev.propor.core.domain.geometry.Degrees
import dev.propor.core.domain.port.DeviceTilt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La fusion sensor + vision (H4.3).
 *
 * Importa mas en Android que en iOS: aqui no hay equivalente a `VNDetectHorizonRequest`, asi
 * que al principio el sensor sera la unica fuente en muchas escenas y la fusion tiene que
 * degradar con elegancia.
 */
class HorizonFusionTest {

    private fun tilt(roll: Float, stable: Boolean = true) =
        DeviceTilt(roll = Degrees(roll), pitch = Degrees.ZERO, isStable = stable)

    private fun visual(angle: Float, confidence: Float) =
        HorizonReading(angle = Degrees(angle), confidence = Confidence(confidence))

    @Test
    fun sinNingunaFuente_noHayHorizonte() {
        assertNull(HorizonFusion().fuse(sensor = null, visual = null))
    }

    @Test
    fun sinVision_mandaElSensor() {
        val result = HorizonFusion().fuse(sensor = tilt(4f), visual = null)!!
        assertEquals(HorizonSource.SENSOR, result.source)
        assertEquals(4f, result.angle.value, absoluteTolerance = 1e-4f)
    }

    /**
     * El caso del barco escorado sobre un mar recto: el sensor diria que todo esta bien, y hay
     * que corregir por lo que se ve, no por como se sujeta el telefono.
     */
    @Test
    fun conVisionFiable_mandaLaVision() {
        val fusion = HorizonFusion()
        val result = fusion.fuse(sensor = tilt(0f), visual = visual(angle = 6f, confidence = 0.9f))!!
        assertEquals(HorizonSource.FUSED, result.source)
        assertEquals(6f, result.angle.value, absoluteTolerance = 1e-4f)
    }

    @Test
    fun conVisionDudosa_seIgnoraYMandaElSensor() {
        val result = HorizonFusion()
            .fuse(sensor = tilt(3f), visual = visual(angle = 12f, confidence = 0.4f))!!
        assertEquals(HorizonSource.SENSOR, result.source)
        assertEquals(3f, result.angle.value, absoluteTolerance = 1e-4f)
    }

    /**
     * Cambiar de fuente no puede hacer brincar la linea del horizonte: el usuario no sabria a
     * cual de las dos creer, y un horizonte que salta destruye la confianza mas rapido que un
     * consejo equivocado.
     */
    @Test
    fun alCambiarDeFuente_laTransicionEsSuave() {
        val fusion = HorizonFusion()

        // Arranca con el sensor a 0 grados y se estabiliza.
        repeat(20) { fusion.fuse(sensor = tilt(0f), visual = null) }
        val before = fusion.fuse(sensor = tilt(0f), visual = null)!!.angle.value
        assertTrue(abs(before) < 0.5f)

        // Aparece un horizonte visual muy distinto: no puede llegar de un frame.
        val firstWithVision = fusion.fuse(
            sensor = tilt(0f),
            visual = visual(angle = 8f, confidence = 0.95f),
        )!!
        assertTrue(
            firstWithVision.angle.value < 4f,
            "salto brusco al cambiar de fuente: ${firstWithVision.angle.value}",
        )

        // Y en unos cuantos frames converge al valor de la vision.
        repeat(30) { fusion.fuse(sensor = tilt(0f), visual = visual(8f, 0.95f)) }
        val settled = fusion.fuse(sensor = tilt(0f), visual = visual(8f, 0.95f))!!
        assertEquals(8f, settled.angle.value, absoluteTolerance = 0.2f)
    }

    /**
     * Suavizar un giro real seria peor que no suavizar: el usuario ha puesto el telefono en
     * vertical y espera que la linea le siga de inmediato.
     */
    @Test
    fun anteUnGiroReal_noSeSuaviza() {
        val fusion = HorizonFusion()
        repeat(10) { fusion.fuse(sensor = tilt(0f), visual = null) }

        val turned = fusion.fuse(sensor = tilt(90f), visual = null)!!
        assertEquals(90f, turned.angle.value, absoluteTolerance = 1e-4f)
    }

    @Test
    fun conElTelefonoEnMovimiento_laLecturaValeMenos() {
        val moving = HorizonFusion().fuse(sensor = tilt(5f, stable = false), visual = null)!!
        val still = HorizonFusion().fuse(sensor = tilt(5f, stable = true), visual = null)!!
        assertTrue(moving.confidence < still.confidence)

        // Y por debajo del umbral del coach: mientras el encuadre cambia, no se opina sobre el.
        assertTrue(moving.confidence < Confidence.COACH_THRESHOLD)
    }

    @Test
    fun reset_olvidaElEstadoSuavizado() {
        val fusion = HorizonFusion()
        repeat(10) { fusion.fuse(sensor = tilt(10f), visual = null) }
        fusion.reset()
        val fresh = fusion.fuse(sensor = tilt(0f), visual = null)!!
        assertEquals(0f, fresh.angle.value, absoluteTolerance = 1e-4f)
    }
}
