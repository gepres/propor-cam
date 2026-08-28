package dev.propor.core.domain.scene

import dev.propor.core.domain.geometry.Confidence
import dev.propor.core.domain.geometry.Degrees
import dev.propor.core.domain.geometry.NormPoint
import dev.propor.core.domain.geometry.NormRect
import dev.propor.core.domain.geometry.Normalized
import dev.propor.core.domain.geometry.Segment

/** Direcciones cardinales del encuadre. Se usa para la mirada y para los bordes. */
enum class Direction { LEFT, RIGHT, UP, DOWN }

/** De donde viene la lectura de horizonte. Determina cuanto se puede confiar en ella. */
enum class HorizonSource {
    /** Giroscopio: mide el telefono, no la escena. Preciso pero puede no ser lo relevante. */
    SENSOR,

    /** Vision: mide la escena, pero falla cuando no hay una linea clara. */
    VISION,

    /** Las dos fuentes combinadas. Es lo que se usa en produccion. */
    FUSED,
}

/** Tipo de escena detectado. Decide que reglas del coach aplican. */
enum class SceneType {
    UNKNOWN, PORTRAIT, LANDSCAPE, ARCHITECTURE, STREET, PRODUCT, FOOD, NATURE, NIGHT
}

/**
 * Un rostro detectado.
 *
 * Se guarda posicion y orientacion, jamas identidad, edad, genero ni atractivo. Ese limite
 * es de dominio, no de implementacion: si el dato no existe aqui, no puede filtrarse despues.
 */
data class FaceReading(
    val bounds: NormRect,
    val leftEye: NormPoint? = null,
    val rightEye: NormPoint? = null,
    /** Hacia donde mira. Alimenta la regla del espacio de mirada. */
    val gaze: Direction? = null,
    val confidence: Confidence = Confidence.CERTAIN,
) {
    /** Altura de los ojos en el encuadre. Null si no se detectaron. */
    val eyeLine: Normalized?
        get() = when {
            leftEye != null && rightEye != null ->
                Normalized.clamp((leftEye.y.value + rightEye.y.value) / 2f)
            leftEye != null -> leftEye.y
            rightEye != null -> rightEye.y
            else -> null
        }
}

/** Un cuerpo detectado. */
data class BodyReading(
    val bounds: NormRect,
    /** Linea de hombros: referencia de horizontalidad en retrato. */
    val shoulderLine: Segment? = null,
    val confidence: Confidence = Confidence.CERTAIN,
)

/**
 * Horizonte detectado.
 *
 * [angle] es la inclinacion respecto a la horizontal; positivo es giro horario.
 */
data class HorizonReading(
    val angle: Degrees,
    /** Altura del horizonte en el encuadre, si la vision pudo situarlo. */
    val y: Normalized? = null,
    val source: HorizonSource = HorizonSource.FUSED,
    val confidence: Confidence = Confidence.CERTAIN,
)

/**
 * Todo lo que la vision entiende de un frame. Inmutable y sin tipos de plataforma: es lo que
 * cruza la frontera del `SceneVisionPort` hacia el dominio.
 */
data class SceneReading(
    val faces: List<FaceReading> = emptyList(),
    val bodies: List<BodyReading> = emptyList(),
    val horizon: HorizonReading? = null,
    /** Region a la que va la atencion cuando no hay personas. */
    val salientRegion: NormRect? = null,
    val dominantLines: List<Segment> = emptyList(),
    /** Inclinacion de las verticales de la escena. Alimenta la regla de arquitectura. */
    val verticalConvergence: Degrees? = null,
    val sceneType: SceneType = SceneType.UNKNOWN,
    val confidence: Confidence = Confidence.CERTAIN,
    val timestampMs: Long = 0L,
) {
    /**
     * El sujeto principal, con la prioridad acordada: rostro grande, rostro pequeno, cuerpo,
     * region saliente. Devuelve null cuando no hay nada de lo que hablar.
     *
     * El seguimiento estable de esta eleccion es responsabilidad del adaptador de vision: si
     * el sujeto principal cambia de un frame a otro, el coach se vuelve erratico.
     */
    val mainSubject: NormRect?
        get() = faces.maxByOrNull { it.bounds.size.area }?.bounds
            ?: bodies.maxByOrNull { it.bounds.size.area }?.bounds
            ?: salientRegion

    val mainFace: FaceReading?
        get() = faces.maxByOrNull { it.bounds.size.area }

    val hasPeople: Boolean get() = faces.isNotEmpty() || bodies.isNotEmpty()
}
