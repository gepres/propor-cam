package dev.propor.core.domain.advice

import dev.propor.core.domain.geometry.Confidence
import dev.propor.core.domain.geometry.Degrees
import dev.propor.core.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Los criterios de aceptacion de H5.2, ejecutables.
 *
 * Toda la suite corre con [FakeClock]: ni un solo `sleep()` ni espera real.
 */
class AdviceThrottlerTest {

    private val certain = Confidence.CERTAIN

    private fun tilt(degrees: Float, magnitude: Float) =
        listOf(Advice.TiltHorizon(Degrees(degrees), magnitude))

    private fun headroom(magnitude: Float) =
        listOf(Advice.TooMuchHeadroom(excess = 0.1f, magnitude = magnitude))

    /** Lleva al throttler hasta el punto de emitir su primer consejo de horizonte. */
    private fun AdviceThrottler.warmUpWithTilt(clock: FakeClock, magnitude: Float = 0.6f): CoachOutput {
        evaluate(tilt(5f, magnitude), certain)          // arranca la ventana de estabilidad
        clock.advance(450)                              // supera los 400 ms
        return evaluate(tilt(5f, magnitude), certain)   // aqui ya deberia hablar
    }

    // ------------------------------------------------------ caso de prueba del tablero (1)

    /**
     * "El coach se calla mientras el usuario ya esta corrigiendo".
     *
     * Insistir mientras alguien ya esta arreglando el encuadre es el fallo que mas molesta y
     * el que hace que se apague el coach.
     */
    @Test
    fun seCallaMientrasElUsuarioYaEstaCorrigiendo() {
        val clock = FakeClock()
        val throttler = AdviceThrottler(clock)

        val first = throttler.warmUpWithTilt(clock, magnitude = 0.6f)
        assertIs<CoachOutput.Speak>(first, "deberia haber avisado del horizonte")

        // 5 -> 4 -> 3 -> 2,5 grados: la magnitud baja en cada paso.
        val correcting = listOf(0.45f, 0.30f, 0.15f)
        correcting.forEach { magnitude ->
            clock.advance(100)
            val out = throttler.evaluate(tilt(4f, magnitude), certain)
            assertIs<CoachOutput.Silent>(out, "no debe insistir mientras se corrige")
            assertEquals(SilenceReason.USER_IS_CORRECTING, out.reason)
        }
    }

    @Test
    fun cuandoElProblemaSeResuelve_confirmaConLock() {
        val clock = FakeClock()
        val throttler = AdviceThrottler(clock)
        throttler.warmUpWithTilt(clock)

        clock.advance(200)
        val out = throttler.evaluate(emptyList(), certain)

        assertIs<CoachOutput.Speak>(out)
        assertEquals(HapticSignal.Lock, out.signal)

        // Y no se queda repitiendo la confirmacion.
        clock.advance(200)
        val next = throttler.evaluate(emptyList(), certain)
        assertIs<CoachOutput.Silent>(next)
        assertEquals(SilenceReason.NOTHING_TO_SAY, next.reason)
    }

    // ------------------------------------------------------ caso de prueba del tablero (2)

    /**
     * "DRIFT no convierte el telefono en una alarma".
     *
     * Error constante durante diez segundos: la senal continua tiene que cortarse a los tres.
     */
    @Test
    fun driftSeCortaALosTresSegundos() {
        val clock = FakeClock()
        val throttler = AdviceThrottler(clock)
        throttler.warmUpWithTilt(clock, magnitude = 0.8f)

        var emisiones = 1
        var ultimoOutput: CoachOutput? = null

        // Diez segundos a 30 fps con el mismo error, sin mejora ninguna.
        repeat(300) {
            clock.tick()
            val out = throttler.evaluate(tilt(5f, 0.8f), certain)
            if (out is CoachOutput.Speak) emisiones++
            ultimoOutput = out
        }

        val fin = ultimoOutput
        assertIs<CoachOutput.Silent>(fin)
        assertEquals(SilenceReason.DRIFT_TIMEOUT, fin.reason)

        // A 30 fps, tres segundos son unos 91 frames. Nunca los diez segundos completos.
        assertTrue(emisiones in 60..110, "emisiones fuera de lo razonable: $emisiones")
    }

    // ------------------------------------------------------ confianza y estabilidad

    @Test
    fun noHablaConConfianzaBaja() {
        val clock = FakeClock()
        val throttler = AdviceThrottler(clock)

        val out = throttler.evaluate(tilt(8f, 0.9f), Confidence(0.5f))

        assertIs<CoachOutput.Silent>(out)
        assertEquals(SilenceReason.LOW_CONFIDENCE, out.reason)
    }

    @Test
    fun noReaccionaAUnFrameSuelto() {
        val clock = FakeClock()
        val throttler = AdviceThrottler(clock)

        // Aparece el error...
        val primero = throttler.evaluate(tilt(6f, 0.7f), certain)
        assertIs<CoachOutput.Silent>(primero)
        assertEquals(SilenceReason.NOT_STABLE_YET, primero.reason)

        // ...y a los 200 ms sigue sin ser suficiente.
        clock.advance(200)
        val segundo = throttler.evaluate(tilt(6f, 0.7f), certain)
        assertIs<CoachOutput.Silent>(segundo)
        assertEquals(SilenceReason.NOT_STABLE_YET, segundo.reason)

        // Pasados los 400 ms, ya se ha ganado el derecho a hablar.
        clock.advance(250)
        assertIs<CoachOutput.Speak>(throttler.evaluate(tilt(6f, 0.7f), certain))
    }

    @Test
    fun respetaLaSeparacionEntreConsejosDistintos() {
        val clock = FakeClock()
        val throttler = AdviceThrottler(clock)
        throttler.warmUpWithTilt(clock)

        // Cambia el problema: ahora sobra aire arriba. Se sostiene lo suficiente...
        clock.advance(100)
        throttler.evaluate(headroom(0.7f), certain)
        clock.advance(450)

        // ...pero aun no han pasado dos segundos desde el consejo anterior.
        val out = throttler.evaluate(headroom(0.7f), certain)
        assertIs<CoachOutput.Silent>(out)
        assertEquals(SilenceReason.COOLDOWN, out.reason)

        clock.advance(1_600)
        assertIs<CoachOutput.Speak>(throttler.evaluate(headroom(0.7f), certain))
    }

    @Test
    fun nuncaEmiteDosSenalesALaVez() {
        val clock = FakeClock()
        val throttler = AdviceThrottler(clock)

        // Dos problemas simultaneos, ya priorizados por el motor.
        val dos = tilt(7f, 0.8f) + headroom(0.9f)
        throttler.evaluate(dos, certain)
        clock.advance(450)
        val out = throttler.evaluate(dos, certain)

        assertIs<CoachOutput.Speak>(out)
        // Un solo consejo, y es el primero de la lista priorizada.
        assertEquals(AdviceKey.TILT_HORIZON, out.advice.key)
    }

    // ------------------------------------------------------ descartes

    @Test
    fun tresDescartesApaganLaReglaParaEsaPersona() {
        val clock = FakeClock()
        val throttler = AdviceThrottler(clock)

        repeat(3) { throttler.onDismissed(AdviceKey.TILT_HORIZON) }
        assertTrue(AdviceKey.TILT_HORIZON in throttler.mutedKeys)

        throttler.evaluate(tilt(9f, 1f), certain)
        clock.advance(2_000)
        val out = throttler.evaluate(tilt(9f, 1f), certain)

        assertIs<CoachOutput.Silent>(out)
        assertEquals(SilenceReason.MUTED, out.reason)
    }

    @Test
    fun dosDescartesNoBastan() {
        val clock = FakeClock()
        val throttler = AdviceThrottler(clock)

        repeat(2) { throttler.onDismissed(AdviceKey.TILT_HORIZON) }
        assertTrue(throttler.mutedKeys.isEmpty())
        assertIs<CoachOutput.Speak>(throttler.warmUpWithTilt(clock))
    }

    // ------------------------------------------------------ silencio saludable

    /**
     * La metrica que gobierna toda la clase, en su escenario adverso: un error que no se
     * corrige nunca durante un minuto entero de visor.
     *
     * Es el caso en el que una app mal disenada vibraria sin parar. Aqui el coach avisa,
     * insiste tres segundos y se calla.
     *
     * El suelo del 60 % es el que importa y es el que se comprueba: pasar de ahi significa
     * ser pesado. El techo del 80 % solo tiene sentido medido sobre uso real, no sintetico.
     */
    @Test
    fun enElPeorCaso_elCoachSigueCallandoLaMayorParteDelTiempo() {
        val clock = FakeClock()
        val throttler = AdviceThrottler(clock)

        repeat(1_800) { // 60 s a 30 fps
            clock.tick()
            throttler.evaluate(tilt(6f, 0.7f), certain)
        }

        val stats = throttler.stats
        assertEquals(1_800, stats.evaluations)
        assertTrue(
            stats.silenceRatio > 0.60f,
            "el coach hablo demasiado: silencio ${stats.silenceRatio}",
        )
        assertTrue(
            stats.silenceByReason[SilenceReason.DRIFT_TIMEOUT]!! > 1_500,
            "la senal continua deberia estar cortada casi todo el minuto",
        )
    }

    @Test
    fun enUnaSesionSinProblemas_elCoachNoDiceNada() {
        val clock = FakeClock()
        val throttler = AdviceThrottler(clock)

        repeat(600) {
            clock.tick()
            throttler.evaluate(emptyList(), certain)
        }

        assertEquals(0, throttler.stats.emissions)
        assertEquals(1f, throttler.stats.silenceRatio)
    }
}
