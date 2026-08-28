package dev.propor.android.adapters

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.propor.core.domain.advice.HapticSignal
import dev.propor.core.domain.port.HapticPort

/**
 * La gramatica haptica sobre el motor de vibracion de Android.
 *
 * Aqui se decide como SUENA en la mano el coach silencioso, asi que es una de las clases que mas
 * afecta a la experiencia real del producto pese a tener poca logica.
 *
 * Tres niveles de calidad, de mejor a peor, segun lo que ofrezca el dispositivo:
 *
 * 1. **Primitivas de composicion** (API 30+, y solo si el hardware las soporta). Son curvas
 *    disenadas por el fabricante para su motor concreto: un CLICK se siente como un click de
 *    verdad, no como un zumbido. Es la unica via para que READY se perciba como una subida.
 * 2. **Waveform con amplitudes** (API 26+). Control de intensidad, sin el matiz de las curvas.
 * 3. **Vibracion simple**. Solo duracion. Suficiente para que la senal exista.
 *
 * El nivel 1 importa mas de lo que parece: con el motor de gama baja, DRIFT y TICK pueden llegar
 * a sentirse iguales, y entonces el vocabulario deja de ser un lenguaje.
 */
class VibratorHapticAdapter(context: Context) : HapticPort {

    private val vibrator: Vibrator? = resolveVibrator(context)

    /** El hardware soporta las primitivas que usa la gramatica. */
    private val supportsPrimitives: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator?.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            ) == true

    override val isAvailable: Boolean
        get() = vibrator?.hasVibrator() == true

    override fun play(signal: HapticSignal) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return

        when (signal) {
            HapticSignal.Tick -> tick(device)
            HapticSignal.Lock -> lock(device)
            is HapticSignal.Drift -> drift(device, signal.intensity)
            HapticSignal.Edge -> edge(device)
            HapticSignal.Ready -> ready(device)
            HapticSignal.Shutter -> shutter(device)
        }
    }

    override fun stop() {
        vibrator?.cancel()
    }

    // ------------------------------------------------------------------ senales

    /** Un golpe seco y ligero: cruzaste una linea de la guia. */
    private fun tick(device: Vibrator) {
        if (supportsPrimitives) {
            device.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f)
                    .compose(),
            )
        } else {
            device.vibrate(oneShot(durationMs = 12, amplitude = 90))
        }
    }

    /** Dos golpes rapidos: alineado. Es la unica confirmacion positiva del vocabulario. */
    private fun lock(device: Vibrator) {
        if (supportsPrimitives) {
            device.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 60)
                    .compose(),
            )
        } else {
            device.vibrate(waveform(longArrayOf(0, 18, 55, 22), intArrayOf(0, 140, 0, 190)))
        }
    }

    /**
     * Vibracion continua cuya intensidad crece con el error.
     *
     * **No se repite en bucle a proposito.** El corte lo decide el `AdviceThrottler` del dominio,
     * que la para a los tres segundos: si el bucle viviera aqui, la regla de producto dependeria
     * de dos sitios y acabaria divergiendo entre plataformas.
     */
    private fun drift(device: Vibrator, intensity: Float) {
        val amplitude = (40 + intensity.coerceIn(0f, 1f) * 150).toInt().coerceIn(1, 255)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && device.hasAmplitudeControl()) {
            device.vibrate(VibrationEffect.createOneShot(120, amplitude))
        } else {
            // Sin control de amplitud, la intensidad se traduce a duracion: es lo unico que queda.
            device.vibrate(oneShot(durationMs = (40 + intensity * 120).toLong(), amplitude = 255))
        }
    }

    /** Golpe fuerte: algo importante se sale del encuadre. */
    private fun edge(device: Vibrator) {
        if (supportsPrimitives) {
            device.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                    .compose(),
            )
        } else {
            device.vibrate(oneShot(durationMs = 45, amplitude = 255))
        }
    }

    /** Subida suave: todo alineado, dispara. */
    private fun ready(device: Vibrator) {
        if (supportsPrimitives) {
            device.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.8f)
                    .compose(),
            )
        } else {
            // Rampa a mano: sin primitivas es lo mas parecido a una subida.
            device.vibrate(
                waveform(
                    timings = longArrayOf(0, 40, 40, 40, 40),
                    amplitudes = intArrayOf(0, 60, 110, 170, 230),
                ),
            )
        }
    }

    /** Doble golpe seco: captura confirmada. */
    private fun shutter(device: Vibrator) {
        device.vibrate(waveform(longArrayOf(0, 14, 40, 14), intArrayOf(0, 200, 0, 200)))
    }

    // ------------------------------------------------------------------ util

    private fun oneShot(durationMs: Long, amplitude: Int): VibrationEffect =
        VibrationEffect.createOneShot(durationMs.coerceAtLeast(1), amplitude.coerceIn(1, 255))

    private fun waveform(timings: LongArray, amplitudes: IntArray): VibrationEffect =
        VibrationEffect.createWaveform(timings, amplitudes, -1)

    private fun resolveVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}

/** No hace nada. Para tests, para previews y para dispositivos sin motor de vibracion. */
object NoOpHapticAdapter : HapticPort {
    override val isAvailable: Boolean = false
    override fun play(signal: HapticSignal) = Unit
    override fun stop() = Unit
}
