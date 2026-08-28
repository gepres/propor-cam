package dev.propor.core.domain.advice

import dev.propor.core.domain.geometry.Degrees
import dev.propor.core.domain.geometry.NormPoint
import dev.propor.core.domain.scene.Direction

/** Cuanto molesta el problema. Ordena que se dice antes. */
enum class Severity { INFO, MINOR, MAJOR }

/**
 * Cuanto cuesta corregirlo, en esfuerzo fisico real.
 *
 * Existe porque la prioridad no puede ser solo la gravedad: enderezar el horizonte es un giro
 * de muneca y recomponer entero es mover los pies. Ante gravedad parecida, primero lo barato
 * (criterio de aceptacion de H5.1). Un consejo que exige tres pasos atras rara vez se atiende.
 */
enum class CorrectionCost { WRIST, ARM, FEET }

/**
 * Identidad del tipo de consejo, independiente de sus valores.
 *
 * El throttler agrupa por esta clave: dos avisos de horizonte con distinto angulo son el mismo
 * consejo insistiendo, no dos consejos. Y los descartes del usuario se cuentan por clave.
 */
enum class AdviceKey {
    TILT_HORIZON,
    SUBJECT_CENTERED,
    EYES_OFF_UPPER_THIRD,
    TOO_MUCH_HEADROOM,
    LOOKING_ROOM,
    SUBJECT_CLIPPED,
    CONVERGING_VERTICALS,
}

/**
 * Lo que el coach ha detectado. **Sin una sola cadena de texto** (ADR-004).
 *
 * El dominio decide QUE esta mal; la presentacion decide COMO se dice y, sobre todo, SI se dice
 * o solo se vibra. Meter aqui un texto ya formado romperia a la vez la i18n, la accesibilidad y
 * el principio de que nunca se lee mientras se compone.
 */
sealed interface Advice {
    val key: AdviceKey
    val severity: Severity
    val cost: CorrectionCost

    /**
     * Cuan grave es, de 0 a 1, dentro de su propio tipo.
     *
     * Sirve para dos cosas: modular la intensidad de la haptica DRIFT, y detectar que el
     * usuario ya esta corrigiendo (la magnitud baja) para callarse.
     */
    val magnitude: Float

    /** El horizonte esta torcido. Giro de muneca. */
    data class TiltHorizon(val degrees: Degrees, override val magnitude: Float) : Advice {
        override val key = AdviceKey.TILT_HORIZON
        override val severity = if (degrees.absolute.value > 5f) Severity.MAJOR else Severity.MINOR
        override val cost = CorrectionCost.WRIST
    }

    /** El sujeto esta en el centro cuando la guia activa pide tercios. */
    data class SubjectCentered(
        val current: NormPoint,
        val suggested: NormPoint,
        override val magnitude: Float,
    ) : Advice {
        override val key = AdviceKey.SUBJECT_CENTERED
        override val severity = Severity.MINOR
        override val cost = CorrectionCost.ARM
    }

    /** En retrato, los ojos deberian caer cerca del tercio superior. */
    data class EyesOffUpperThird(
        val eyeLine: Float,
        val target: Float,
        override val magnitude: Float,
    ) : Advice {
        override val key = AdviceKey.EYES_OFF_UPPER_THIRD
        override val severity = Severity.MINOR
        override val cost = CorrectionCost.ARM
    }

    /** Demasiado aire sobre la cabeza. */
    data class TooMuchHeadroom(val excess: Float, override val magnitude: Float) : Advice {
        override val key = AdviceKey.TOO_MUCH_HEADROOM
        override val severity = Severity.MINOR
        override val cost = CorrectionCost.ARM
    }

    /** El sujeto mira hacia el borde cercano en vez de hacia el espacio libre. */
    data class LookingRoomOnWrongSide(
        val gaze: Direction,
        override val magnitude: Float,
    ) : Advice {
        override val key = AdviceKey.LOOKING_ROOM
        override val severity = Severity.MINOR
        override val cost = CorrectionCost.ARM
    }

    /** Algo importante se sale del encuadre. Es lo unico que casi siempre es un error. */
    data class SubjectClipped(
        val edge: Direction,
        val visibleFraction: Float,
        override val magnitude: Float,
    ) : Advice {
        override val key = AdviceKey.SUBJECT_CLIPPED
        override val severity = Severity.MAJOR
        override val cost = CorrectionCost.ARM
    }

    /** Verticales inclinadas: el edificio se cae hacia atras. */
    data class ConvergingVerticals(
        val degrees: Degrees,
        override val magnitude: Float,
    ) : Advice {
        override val key = AdviceKey.CONVERGING_VERTICALS
        override val severity = Severity.MINOR
        override val cost = CorrectionCost.FEET
    }
}

/**
 * Orden de emision: primero lo mas grave y, a igual gravedad, lo mas barato de corregir.
 * Como desempate final, la magnitud.
 */
val AdviceComparator: Comparator<Advice> = compareByDescending<Advice> { it.severity.ordinal }
    .thenBy { it.cost.ordinal }
    .thenByDescending { it.magnitude }
