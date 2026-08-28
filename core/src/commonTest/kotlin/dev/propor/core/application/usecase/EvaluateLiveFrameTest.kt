package dev.propor.core.application.usecase

import dev.propor.core.domain.advice.AdviceEngine
import dev.propor.core.domain.advice.AdviceKey
import dev.propor.core.domain.advice.AdviceThrottler
import dev.propor.core.domain.advice.HapticSignal
import dev.propor.core.domain.geometry.Degrees
import dev.propor.core.domain.guide.GuideKind
import dev.propor.core.domain.scene.HorizonReading
import dev.propor.core.domain.scene.SceneReading
import dev.propor.core.testing.FakeClock
import dev.propor.core.testing.RecordingHapticPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * El bucle completo del coach, del frame a la vibracion.
 *
 * Es un test de integracion del nucleo: motor, throttler y haptica juntos, con reloj falso.
 * Toda una sesion de visor se reproduce sin camara y sin telefono.
 */
class EvaluateLiveFrameTest {

    private fun sceneWithTilt(degrees: Float) =
        SceneReading(horizon = HorizonReading(Degrees(degrees)))

    private class Fixture {
        val clock = FakeClock()
        val haptics = RecordingHapticPort()
        val throttler = AdviceThrottler(clock)
        val useCase = EvaluateLiveFrame(AdviceEngine(), throttler, haptics)
    }

    @Test
    fun cuandoElCoachHabla_elTelefonoVibra() {
        val f = Fixture()

        f.useCase(sceneWithTilt(6f), GuideKind.THIRDS)
        f.clock.advance(450)
        val feedback = f.useCase(sceneWithTilt(6f), GuideKind.THIRDS)

        assertTrue(feedback.isSpeaking)
        assertEquals(1, f.haptics.played.size)
        assertIs<HapticSignal.Drift>(f.haptics.played.single())
    }

    @Test
    fun conLaHapticaDesactivada_elCoachSigueOpinandoPeroNoVibra() {
        val f = Fixture()

        f.useCase(sceneWithTilt(6f), GuideKind.THIRDS, hapticsEnabled = false)
        f.clock.advance(450)
        val feedback = f.useCase(sceneWithTilt(6f), GuideKind.THIRDS, hapticsEnabled = false)

        // El consejo existe y la interfaz puede dibujarlo; simplemente no se siente.
        assertTrue(feedback.isSpeaking)
        assertTrue(f.haptics.played.isEmpty())
    }

    @Test
    fun enSilencio_noVibraNada() {
        val f = Fixture()

        repeat(60) {
            f.clock.tick()
            f.useCase(SceneReading(), GuideKind.THIRDS)
        }

        assertTrue(f.haptics.played.isEmpty())
    }

    /**
     * La senal de aprendizaje del producto: no basta con haber avisado, hace falta saber si el
     * usuario hizo caso. Es lo que alimenta el perfil del fotografo (E9) desde el sidecar.
     */
    @Test
    fun registraQueConsejosSeMostraronYCualesSeAtendieron() {
        val f = Fixture()

        f.useCase(sceneWithTilt(8f), GuideKind.THIRDS)
        f.clock.advance(450)
        f.useCase(sceneWithTilt(8f), GuideKind.THIRDS)

        // El usuario endereza hasta que el aviso desaparece.
        f.clock.advance(200)
        f.useCase(sceneWithTilt(4f), GuideKind.THIRDS)
        f.clock.advance(200)
        f.useCase(sceneWithTilt(1f), GuideKind.THIRDS)

        val (shown, accepted) = f.useCase.sessionAdvice()
        assertTrue(AdviceKey.TILT_HORIZON in shown)
        assertTrue(
            AdviceKey.TILT_HORIZON in accepted,
            "enderezar tras el aviso deberia contar como consejo atendido",
        )
    }

    @Test
    fun siElUsuarioIgnoraElConsejo_noCuentaComoAtendido() {
        val f = Fixture()

        f.useCase(sceneWithTilt(8f), GuideKind.THIRDS)
        f.clock.advance(450)
        f.useCase(sceneWithTilt(8f), GuideKind.THIRDS)
        repeat(10) {
            f.clock.advance(100)
            f.useCase(sceneWithTilt(8f), GuideKind.THIRDS)
        }

        val (shown, accepted) = f.useCase.sessionAdvice()
        assertTrue(AdviceKey.TILT_HORIZON in shown)
        assertTrue(accepted.isEmpty(), "sin correccion no hay consejo atendido")
    }

    @Test
    fun reset_dejaLaSesionLimpiaYCortaLaHaptica() {
        val f = Fixture()

        f.useCase(sceneWithTilt(8f), GuideKind.THIRDS)
        f.clock.advance(450)
        f.useCase(sceneWithTilt(8f), GuideKind.THIRDS)

        f.useCase.reset()

        val (shown, accepted) = f.useCase.sessionAdvice()
        assertTrue(shown.isEmpty() && accepted.isEmpty())
        assertEquals(1, f.haptics.stopCount)
    }

    /**
     * El ancla ambar solo se enciende cuando el consejo va DE la posicion del sujeto.
     *
     * Antes se destacaba siempre que habia un sujeto detectado, aunque el coach estuviera
     * callado. Un usuario lo vio y pregunto "ese punto que me quiere decir": si hay que
     * preguntarlo, el elemento no se explica solo y sobra.
     */
    @Test
    fun conElHorizonteTorcido_noSeSenalaNingunAncla() {
        val f = Fixture()

        f.useCase(sceneWithTilt(7f), GuideKind.THIRDS)
        f.clock.advance(450)
        val feedback = f.useCase(sceneWithTilt(7f), GuideKind.THIRDS)

        assertTrue(feedback.isSpeaking, "deberia estar avisando del horizonte")
        assertEquals(
            null,
            feedback.suggestedAnchor,
            "el problema es la inclinacion, no donde esta el sujeto: no hay nada que senalar",
        )
    }

    @Test
    fun enSilencio_tampocoSeSenalaNada() {
        val f = Fixture()
        val feedback = f.useCase(SceneReading(), GuideKind.THIRDS)
        assertTrue(!feedback.isSpeaking)
        assertEquals(null, feedback.suggestedAnchor)
    }

    @Test
    fun elAlineamientoEsUnoCuandoNoHaySujeto() {
        val f = Fixture()
        val feedback = f.useCase(sceneWithTilt(0f), GuideKind.THIRDS)
        assertEquals(1f, feedback.alignment)
        assertEquals(null, feedback.suggestedAnchor)
    }
}
