package dev.propor.core.domain.scene

import dev.propor.core.domain.geometry.Confidence
import dev.propor.core.domain.geometry.Degrees
import dev.propor.core.domain.port.DeviceTilt
import kotlin.math.abs

/** Umbrales de la fusion de horizonte. */
data class FusionConfig(
    /** Por encima de esta confianza, la lectura visual manda sobre el sensor. */
    val trustVisualAbove: Confidence = Confidence(0.70f),
    /**
     * Cuanto se acerca la salida al objetivo en cada frame, de 0 a 1.
     *
     * Es lo que evita el salto al cambiar de fuente. Sin esto, pasar de sensor a vision con
     * cuatro grados de diferencia haria brincar la linea del horizonte en pantalla y el usuario
     * no sabria a cual de las dos creer.
     */
    val blendPerFrame: Float = 0.25f,
    /** Diferencia a partir de la cual ya no se suaviza: el usuario giro el telefono de verdad. */
    val snapThresholdDeg: Float = 25f,
)

/**
 * Combina la inclinacion del sensor con el horizonte que ve la camara.
 *
 * Las dos fuentes miden cosas distintas y ninguna basta sola:
 *
 * - El **giroscopio** es preciso y siempre esta, pero mide el TELEFONO. En una foto de un barco
 *   escorado sobre un mar recto diria que todo esta bien.
 * - La **vision** mide la ESCENA, que es lo que de verdad importa, pero falla cuando no hay una
 *   linea clara: interiores, primeros planos, noche.
 *
 * Regla: con lectura visual de confianza alta manda la vision; sin ella, el sensor. Y **nunca se
 * alterna de golpe**, porque un horizonte que salta entre dos valores destruye la confianza en
 * el coach mas rapido que un consejo equivocado.
 *
 * Tiene estado (el valor suavizado), asi que vive con la sesion de visor y se reinicia con ella.
 */
class HorizonFusion(private val config: FusionConfig = FusionConfig()) {

    private var smoothed: Float? = null
    private var lastSource: HorizonSource? = null

    /**
     * @param sensor inclinacion inercial. Null si el dispositivo no tiene sensores utiles.
     * @param visual horizonte detectado por la camara. Null cuando la escena no tiene uno claro.
     */
    fun fuse(sensor: DeviceTilt?, visual: HorizonReading?): HorizonReading? {
        val trustVisual = visual != null && visual.confidence >= config.trustVisualAbove

        val target: Float
        val source: HorizonSource
        val confidence: Confidence

        when {
            trustVisual -> {
                target = visual!!.angle.value
                source = HorizonSource.FUSED
                confidence = visual.confidence
            }
            sensor != null -> {
                target = sensor.roll.value
                source = HorizonSource.SENSOR
                // Mientras el telefono se mueve, la lectura vale menos: el encuadre aun no es
                // el definitivo y opinar sobre el seria opinar sobre algo que va a cambiar.
                confidence = if (sensor.isStable) Confidence(0.9f) else Confidence(0.5f)
            }
            else -> return null
        }

        val previous = smoothed
        val next = when {
            previous == null -> target
            abs(target - previous) > config.snapThresholdDeg -> target // giro real, no se suaviza
            else -> previous + (target - previous) * config.blendPerFrame
        }

        smoothed = next
        lastSource = source

        return HorizonReading(
            angle = Degrees(next),
            y = if (trustVisual) visual?.y else null,
            source = source,
            confidence = confidence,
        )
    }

    /** Al reabrir el visor o cambiar de escena. */
    fun reset() {
        smoothed = null
        lastSource = null
    }
}
