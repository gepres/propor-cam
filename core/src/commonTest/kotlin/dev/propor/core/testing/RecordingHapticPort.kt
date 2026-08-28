package dev.propor.core.testing

import dev.propor.core.domain.advice.HapticSignal
import dev.propor.core.domain.port.HapticPort

/**
 * Motor haptico de mentira que apunta lo que se le pide.
 *
 * Permite comprobar en CI algo que de otro modo solo se podria verificar con el telefono en la
 * mano: que el coach vibra cuando debe, con la senal correcta, y **que se calla cuando debe**.
 */
class RecordingHapticPort(
    override val isAvailable: Boolean = true,
) : HapticPort {

    private val _played = mutableListOf<HapticSignal>()
    val played: List<HapticSignal> get() = _played.toList()

    var stopCount: Int = 0
        private set

    override fun play(signal: HapticSignal) {
        _played += signal
    }

    override fun stop() {
        stopCount++
    }

    fun clear() {
        _played.clear()
        stopCount = 0
    }
}
