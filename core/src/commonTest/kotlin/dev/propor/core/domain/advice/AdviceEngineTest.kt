package dev.propor.core.domain.advice

import dev.propor.core.domain.geometry.AspectRatio
import dev.propor.core.domain.geometry.Degrees
import dev.propor.core.domain.geometry.NormPoint
import dev.propor.core.domain.geometry.NormRect
import dev.propor.core.domain.geometry.Normalized
import dev.propor.core.domain.guide.GuideKind
import dev.propor.core.domain.scene.Direction
import dev.propor.core.domain.scene.FaceReading
import dev.propor.core.domain.scene.HorizonReading
import dev.propor.core.domain.scene.SceneReading
import dev.propor.core.domain.scene.SceneType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Las siete reglas de R1, cada una con sus casos limite: justo por debajo y justo por encima
 * del umbral. Es el criterio de aceptacion de H5.1.
 */
class AdviceEngineTest {

    private val engine = AdviceEngine()

    private fun face(
        left: Float, top: Float, w: Float, h: Float,
        gaze: Direction? = null,
        eyeY: Float? = null,
    ): FaceReading {
        val bounds = NormRect.of(left, top, w, h)
        val eyes = eyeY?.let { y ->
            Pair(NormPoint.of(left + w * 0.3f, y), NormPoint.of(left + w * 0.7f, y))
        }
        return FaceReading(
            bounds = bounds,
            leftEye = eyes?.first,
            rightEye = eyes?.second,
            gaze = gaze,
        )
    }

    // ------------------------------------------------------------------ 1. horizonte

    @Test
    fun horizonte_pordebajoDeDosGrados_noDiceNada() {
        val reading = SceneReading(horizon = HorizonReading(Degrees(1.9f)))
        assertTrue(engine.advise(reading, GuideKind.THIRDS).isEmpty())
    }

    @Test
    fun horizonte_porEncimaDeDosGrados_avisa() {
        val reading = SceneReading(horizon = HorizonReading(Degrees(4.5f)))
        val advice = engine.advise(reading, GuideKind.THIRDS).single()
        assertIs<Advice.TiltHorizon>(advice)
        assertEquals(4.5f, advice.degrees.value)
        assertTrue(advice.magnitude > 0f)
    }

    @Test
    fun horizonte_inclinacionGrande_esGraveYSaturaEnUno() {
        val reading = SceneReading(horizon = HorizonReading(Degrees(-15f)))
        val advice = engine.advise(reading, GuideKind.THIRDS).single()
        assertIs<Advice.TiltHorizon>(advice)
        assertEquals(Severity.MAJOR, advice.severity)
        assertEquals(1f, advice.magnitude)
        // Es lo mas barato de corregir: un giro de muneca.
        assertEquals(CorrectionCost.WRIST, advice.cost)
    }

    // ------------------------------------------------------------------ 2. sujeto centrado

    @Test
    fun sujetoCentrado_conGuiaDeTercios_sugiereUnAncla() {
        val reading = SceneReading(faces = listOf(face(0.45f, 0.45f, 0.10f, 0.10f)))
        val advice = engine.advise(reading, GuideKind.THIRDS)
            .filterIsInstance<Advice.SubjectCentered>().single()

        assertTrue(advice.suggested.distanceTo(NormPoint.CENTER) > 0.1f)
        assertTrue(advice.magnitude > 0f)
    }

    /**
     * Con la reticula central o la simetria, centrar es exactamente lo que se pide. Avisar ahi
     * seria contradecir al usuario, que es la forma mas rapida de que apague el coach.
     */
    @Test
    fun sujetoCentrado_conGuiaQuePideCentrar_noDiceNada() {
        val reading = SceneReading(faces = listOf(face(0.45f, 0.45f, 0.10f, 0.10f)))
        listOf(GuideKind.CENTER, GuideKind.GRID_2X2, GuideKind.SYMMETRY).forEach { guide ->
            val centered = engine.advise(reading, guide).filterIsInstance<Advice.SubjectCentered>()
            assertTrue(centered.isEmpty(), "no debe avisar de centrado con la guia $guide")
        }
    }

    @Test
    fun sujetoLejosDelCentro_noDiceNada() {
        val reading = SceneReading(faces = listOf(face(0.60f, 0.28f, 0.10f, 0.10f)))
        val centered = engine.advise(reading, GuideKind.THIRDS)
            .filterIsInstance<Advice.SubjectCentered>()
        assertTrue(centered.isEmpty())
    }

    // ------------------------------------------------------------------ 3. linea de ojos

    @Test
    fun ojos_cercaDelTercioSuperior_noDiceNada() {
        val reading = SceneReading(
            sceneType = SceneType.PORTRAIT,
            faces = listOf(face(0.35f, 0.20f, 0.30f, 0.35f, eyeY = 0.34f)),
        )
        assertTrue(
            engine.advise(reading, GuideKind.THIRDS)
                .filterIsInstance<Advice.EyesOffUpperThird>().isEmpty(),
        )
    }

    @Test
    fun ojos_demasiadoBajos_avisa() {
        val reading = SceneReading(
            sceneType = SceneType.PORTRAIT,
            faces = listOf(face(0.35f, 0.40f, 0.30f, 0.35f, eyeY = 0.55f)),
        )
        val advice = engine.advise(reading, GuideKind.THIRDS)
            .filterIsInstance<Advice.EyesOffUpperThird>().single()
        assertEquals(1f / 3f, advice.target, absoluteTolerance = 1e-5f)
        assertTrue(advice.eyeLine > advice.target)
    }

    @Test
    fun ojos_rostroPequeno_noAplica() {
        // Una persona diminuta en un paisaje no es un retrato: la regla no aplica.
        val reading = SceneReading(
            sceneType = SceneType.PORTRAIT,
            faces = listOf(face(0.48f, 0.60f, 0.04f, 0.05f, eyeY = 0.62f)),
        )
        assertTrue(
            engine.advise(reading, GuideKind.THIRDS)
                .filterIsInstance<Advice.EyesOffUpperThird>().isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 4. headroom

    @Test
    fun headroom_justoEnElLimite_noDiceNada() {
        val reading = SceneReading(faces = listOf(face(0.35f, 0.21f, 0.30f, 0.30f)))
        assertTrue(
            engine.advise(reading, GuideKind.THIRDS)
                .filterIsInstance<Advice.TooMuchHeadroom>().isEmpty(),
        )
    }

    @Test
    fun headroom_excesivo_avisa() {
        val reading = SceneReading(faces = listOf(face(0.35f, 0.45f, 0.30f, 0.30f)))
        val advice = engine.advise(reading, GuideKind.THIRDS)
            .filterIsInstance<Advice.TooMuchHeadroom>().single()
        assertTrue(advice.excess > 0f)
    }

    // ------------------------------------------------------------------ 5. espacio de mirada

    @Test
    fun mirada_conEspacioDelante_noDiceNada() {
        // Mira a la izquierda y tiene la izquierda libre: correcto.
        val reading = SceneReading(faces = listOf(face(0.55f, 0.30f, 0.20f, 0.25f, gaze = Direction.LEFT)))
        assertTrue(
            engine.advise(reading, GuideKind.THIRDS)
                .filterIsInstance<Advice.LookingRoomOnWrongSide>().isEmpty(),
        )
    }

    @Test
    fun mirada_contraElBorde_avisa() {
        // Mira a la izquierda pegado al borde izquierdo: la mirada choca contra el marco.
        val reading = SceneReading(faces = listOf(face(0.05f, 0.30f, 0.20f, 0.25f, gaze = Direction.LEFT)))
        val advice = engine.advise(reading, GuideKind.THIRDS)
            .filterIsInstance<Advice.LookingRoomOnWrongSide>().single()
        assertEquals(Direction.LEFT, advice.gaze)
    }

    // ------------------------------------------------------------------ 6. sujeto cortado

    @Test
    fun sujetoCortado_esLoMasGrave() {
        val reading = SceneReading(
            faces = listOf(FaceReading(bounds = NormRect.of(0.80f, 0.30f, 0.40f, 0.30f))),
        )
        val advice = engine.advise(reading, GuideKind.THIRDS)
            .filterIsInstance<Advice.SubjectClipped>().single()

        assertEquals(Direction.RIGHT, advice.edge)
        assertEquals(Severity.MAJOR, advice.severity)
        assertTrue(advice.visibleFraction < 1f)
    }

    @Test
    fun sujetoApenasRozandoElBorde_noEsUnProblema() {
        // Se sale un 2 %: por encima del umbral de visibilidad, no merece decirse.
        val reading = SceneReading(
            faces = listOf(FaceReading(bounds = NormRect.of(0.70f, 0.30f, 0.302f, 0.30f))),
        )
        assertTrue(
            engine.advise(reading, GuideKind.THIRDS)
                .filterIsInstance<Advice.SubjectClipped>().isEmpty(),
        )
    }

    // ------------------------------------------------------------------ 7. verticales

    @Test
    fun verticales_soloEnArquitectura() {
        val convergencia = Degrees(6f)

        val paisaje = SceneReading(sceneType = SceneType.LANDSCAPE, verticalConvergence = convergencia)
        assertTrue(engine.advise(paisaje, GuideKind.THIRDS).isEmpty())

        val edificio = SceneReading(sceneType = SceneType.ARCHITECTURE, verticalConvergence = convergencia)
        assertIs<Advice.ConvergingVerticals>(engine.advise(edificio, GuideKind.THIRDS).single())
    }

    @Test
    fun verticales_corregirlasCuestaMoverLosPies() {
        val reading = SceneReading(sceneType = SceneType.ARCHITECTURE, verticalConvergence = Degrees(7f))
        val advice = engine.advise(reading, GuideKind.THIRDS).single()
        assertEquals(CorrectionCost.FEET, advice.cost)
    }

    // ------------------------------------------------------------------ priorizacion

    /**
     * Criterio de aceptacion: a gravedad equivalente, primero lo mas barato de corregir.
     * Girar la muneca antes que mover los pies; un consejo que exige tres pasos atras rara
     * vez se atiende.
     */
    @Test
    fun aIgualGravedad_primeroLoMasBaratoDeCorregir() {
        val reading = SceneReading(
            sceneType = SceneType.ARCHITECTURE,
            horizon = HorizonReading(Degrees(3f)),          // MINOR, muneca
            verticalConvergence = Degrees(6f),               // MINOR, pies
        )
        val advice = engine.advise(reading, GuideKind.THIRDS)
        assertEquals(2, advice.size)
        assertEquals(AdviceKey.TILT_HORIZON, advice[0].key)
        assertEquals(AdviceKey.CONVERGING_VERTICALS, advice[1].key)
    }

    @Test
    fun loGraveVaAntesQueLoBarato() {
        val reading = SceneReading(
            horizon = HorizonReading(Degrees(3f)),                                  // MINOR
            faces = listOf(FaceReading(bounds = NormRect.of(0.80f, 0.30f, 0.40f, 0.30f))), // MAJOR
        )
        val advice = engine.advise(reading, GuideKind.THIRDS)
        assertEquals(AdviceKey.SUBJECT_CLIPPED, advice.first().key)
    }

    // ------------------------------------------------------------------ perfil

    @Test
    fun elPerfilPuedeApagarUnaRegla() {
        val reading = SceneReading(horizon = HorizonReading(Degrees(8f)))
        val perfil = CoachProfile.NEUTRAL.muting(AdviceKey.TILT_HORIZON)
        assertTrue(engine.advise(reading, GuideKind.THIRDS, AspectRatio.R4_3, perfil).isEmpty())
    }

    @Test
    fun elPerfilCambiaElOrdenAIgualGravedadYCoste() {
        val reading = SceneReading(
            faces = listOf(face(0.35f, 0.45f, 0.30f, 0.30f, gaze = Direction.LEFT, eyeY = 0.60f)),
        )
        val neutro = engine.advise(reading, GuideKind.THIRDS)
        assertTrue(neutro.size >= 2, "el escenario deberia producir varios consejos")

        val ultimo = neutro.last().key
        val perfil = CoachProfile(weights = mapOf(ultimo to 2f))
        val ponderado = engine.advise(reading, GuideKind.THIRDS, AspectRatio.R4_3, perfil)

        assertTrue(
            ponderado.indexOfFirst { it.key == ultimo } <= neutro.indexOfFirst { it.key == ultimo },
            "subir el peso de una debilidad deberia adelantarla",
        )
    }

    // ------------------------------------------------------------------ determinismo

    @Test
    fun esDeterminista() {
        val reading = SceneReading(
            sceneType = SceneType.PORTRAIT,
            horizon = HorizonReading(Degrees(3.7f)),
            faces = listOf(face(0.44f, 0.42f, 0.14f, 0.16f, gaze = Direction.RIGHT, eyeY = 0.52f)),
        )
        val a = engine.advise(reading, GuideKind.THIRDS)
        val b = engine.advise(reading, GuideKind.THIRDS)
        assertEquals(a, b)
    }

    @Test
    fun escenaVacia_noProduceConsejos() {
        assertTrue(engine.advise(SceneReading(), GuideKind.THIRDS).isEmpty())
    }
}
