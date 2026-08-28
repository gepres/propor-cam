package dev.propor.android.adapters

import android.os.SystemClock
import dev.propor.core.domain.port.ClockPort

/**
 * Reloj monotono del sistema.
 *
 * `elapsedRealtime` y no `currentTimeMillis`: el segundo es de calendario y puede saltar hacia
 * atras si el usuario cambia la hora o si la red ajusta el reloj. Un salto hacia atras haria
 * que el `AdviceThrottler` creyera que el ultimo consejo se emitio en el futuro y dejara de
 * hablar hasta recuperar la diferencia.
 */
object AndroidClockAdapter : ClockPort {
    override fun nowMillis(): Long = SystemClock.elapsedRealtime()
}
