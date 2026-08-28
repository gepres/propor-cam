package dev.propor.core.testing

import dev.propor.core.domain.port.ClockPort

/**
 * Reloj controlado por el test.
 *
 * Permite reproducir una sesion de coach de tres minutos en microsegundos y siempre con el
 * mismo resultado. Ningun test del throttler llama a `sleep()`: es criterio de aceptacion.
 */
class FakeClock(private var now: Long = 0L) : ClockPort {
    override fun nowMillis(): Long = now

    fun advance(millis: Long): FakeClock {
        require(millis >= 0) { "el reloj es monotono: no puede retroceder" }
        now += millis
        return this
    }

    /** Avanza un frame a 30 fps. Azucar para simular sesiones de visor. */
    fun tick(): FakeClock = advance(33)
}
