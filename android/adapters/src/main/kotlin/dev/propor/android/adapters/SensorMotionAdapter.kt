package dev.propor.android.adapters

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.propor.core.domain.geometry.Degrees
import dev.propor.core.domain.port.DeviceTilt
import dev.propor.core.domain.port.MotionSensorPort
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs

/**
 * Inclinacion del dispositivo a partir del vector de gravedad.
 *
 * Se usa el sensor de gravedad y no el acelerometro crudo porque el primero ya viene filtrado
 * por el sistema: el acelerometro mezcla gravedad con el temblor de la mano, y ese temblor se
 * traduciria en un horizonte que baila y en un coach que avisa y se desdice.
 *
 * Si el dispositivo no tiene sensor de gravedad —los hay—, se cae al acelerometro con un filtro
 * paso bajo propio.
 */
class SensorMotionAdapter(
    context: Context,
    /**
     * Constante del filtro exponencial, de 0 a 1. Mas bajo es mas suave y mas lento.
     * 0,15 responde en unos pocos frames sin que el valor tiemble.
     */
    private val smoothing: Float = 0.15f,
) : MotionSensorPort {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val sensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /**
     * Buffer de 1 con descarte del mas antiguo: al coach solo le sirve la inclinacion de ahora.
     * Encolar lecturas de sensor solo produciria latencia acumulada.
     */
    private val _tilt = MutableSharedFlow<DeviceTilt>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val tilt: Flow<DeviceTilt> = _tilt.asSharedFlow()

    private var smoothedX = 0f
    private var smoothedY = 0f
    private var smoothedZ = 0f
    private var initialized = false

    /** Roll de la lectura anterior, para saber si el telefono se esta moviendo. */
    private var previousRoll = 0f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = filter(event.values[0], event.values[1], event.values[2])

            // Roll: giro alrededor del eje de la vista. Es el que tuerce el horizonte.
            val roll = Math.toDegrees(kotlin.math.atan2(x.toDouble(), y.toDouble())).toFloat()

            // Pitch: cabeceo. Alimenta la deteccion de verticales convergentes.
            val magnitude = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            val pitch = if (magnitude == 0f) {
                0f
            } else {
                Math.toDegrees(kotlin.math.asin((z / magnitude).coerceIn(-1f, 1f).toDouble()))
                    .toFloat()
            }

            // Estable = el usuario ya no esta moviendo el telefono. Sin esto, el coach opinaria
            // sobre encuadres que aun estan cambiando.
            val stable = abs(roll - previousRoll) < STABILITY_THRESHOLD_DEG
            previousRoll = roll

            _tilt.tryEmit(
                DeviceTilt(
                    roll = Degrees(-roll),
                    pitch = Degrees(pitch),
                    isStable = stable,
                ),
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override suspend fun start() {
        val manager = sensorManager ?: return
        val target = sensor ?: return
        manager.registerListener(listener, target, SensorManager.SENSOR_DELAY_GAME)
    }

    override suspend fun stop() {
        sensorManager?.unregisterListener(listener)
        initialized = false
    }

    /** Filtro exponencial. La primera muestra se toma tal cual para no arrancar desde cero. */
    private fun filter(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        if (!initialized) {
            smoothedX = x
            smoothedY = y
            smoothedZ = z
            initialized = true
        } else {
            smoothedX += smoothing * (x - smoothedX)
            smoothedY += smoothing * (y - smoothedY)
            smoothedZ += smoothing * (z - smoothedZ)
        }
        return Triple(smoothedX, smoothedY, smoothedZ)
    }

    private companion object {
        /** Por debajo de esto se considera que el telefono esta quieto. */
        const val STABILITY_THRESHOLD_DEG = 0.35f
    }
}
