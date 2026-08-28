package dev.propor.core.domain.geometry

import kotlin.jvm.JvmInline
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Coordenada normalizada dentro del encuadre: 0 es el borde inicial y 1 el final.
 *
 * El dominio nunca ve pixeles (ADR-003). Trabajar en fracciones del encuadre hace que la
 * misma regla funcione en un visor 4:3, en un recorte 16:9 y en una foto ya capturada,
 * sin conversiones repartidas por el codigo. La conversion a pixeles ocurre solo en la
 * frontera de los adaptadores.
 */
@JvmInline
value class Normalized(val value: Float) {
    init {
        require(value in 0f..1f) { "Normalized fuera de rango: $value" }
    }

    operator fun compareTo(other: Normalized): Int = value.compareTo(other.value)

    companion object {
        val ZERO = Normalized(0f)
        val HALF = Normalized(0.5f)
        val ONE = Normalized(1f)

        /** Recorta al rango valido en vez de fallar. Para entradas de sensores ruidosos. */
        fun clamp(raw: Float): Normalized = Normalized(raw.coerceIn(0f, 1f))
    }
}

fun Float.normalized(): Normalized = Normalized(this)

/** Punto dentro del encuadre. Origen arriba a la izquierda. */
data class NormPoint(val x: Normalized, val y: Normalized) {

    /** Distancia euclidea en espacio normalizado. Util para "que tan lejos del ancla". */
    fun distanceTo(other: NormPoint): Float =
        hypot(x.value - other.x.value, y.value - other.y.value)

    companion object {
        /**
         * Constructor comodo desde Float. No puede ser un constructor secundario: al
         * compilar, `Normalized` se borra a `float` y las dos firmas chocarian en la JVM.
         */
        fun of(x: Float, y: Float): NormPoint = NormPoint(Normalized(x), Normalized(y))

        /** Igual que [of] pero recortando al rango valido en vez de fallar. */
        fun clamped(x: Float, y: Float): NormPoint =
            NormPoint(Normalized.clamp(x), Normalized.clamp(y))

        val CENTER = of(0.5f, 0.5f)
    }
}

/** Tamano normalizado. Puede exceder el encuadre si un sujeto se sale, de ahi el Float crudo. */
data class NormSize(val width: Float, val height: Float) {
    init {
        require(width >= 0f && height >= 0f) { "NormSize negativo: $width x $height" }
    }

    val area: Float get() = width * height
}

/** Rectangulo normalizado. Se permite que sobresalga del encuadre: asi se detecta un recorte. */
data class NormRect(val origin: NormPoint, val size: NormSize) {
    val left: Float get() = origin.x.value
    val top: Float get() = origin.y.value
    val right: Float get() = left + size.width
    val bottom: Float get() = top + size.height

    val center: NormPoint
        get() = NormPoint.clamped(left + size.width / 2f, top + size.height / 2f)

    /** True si alguna parte queda fuera del encuadre: base de la senal EDGE. */
    val isClipped: Boolean get() = left < 0f || top < 0f || right > 1f || bottom > 1f

    /** Fraccion del rectangulo que queda dentro del encuadre, de 0 a 1. */
    fun visibleFraction(): Float {
        val w = (minOf(right, 1f) - maxOf(left, 0f)).coerceAtLeast(0f)
        val h = (minOf(bottom, 1f) - maxOf(top, 0f)).coerceAtLeast(0f)
        return if (size.area == 0f) 0f else (w * h) / size.area
    }

    companion object {
        fun of(left: Float, top: Float, width: Float, height: Float): NormRect =
            NormRect(NormPoint.clamped(left, top), NormSize(width, height))
    }
}

/** Angulo en grados. Positivo es giro horario desde la horizontal. */
@JvmInline
value class Degrees(val value: Float) {
    val absolute: Degrees get() = Degrees(abs(value))
    operator fun minus(other: Degrees): Degrees = Degrees(value - other.value)
    operator fun compareTo(other: Degrees): Int = value.compareTo(other.value)

    companion object {
        val ZERO = Degrees(0f)
    }
}

/** Confianza de una lectura de vision, de 0 a 1. */
@JvmInline
value class Confidence(val value: Float) {
    init {
        require(value in 0f..1f) { "Confidence fuera de rango: $value" }
    }

    operator fun compareTo(other: Confidence): Int = value.compareTo(other.value)

    companion object {
        val NONE = Confidence(0f)
        val CERTAIN = Confidence(1f)

        /** Umbral por debajo del cual el coach nunca habla (nota 05, seccion B.1). */
        val COACH_THRESHOLD = Confidence(0.75f)
    }
}

/** Segmento recto entre dos puntos del encuadre. Unidad de dibujo de las guias. */
data class Segment(val from: NormPoint, val to: NormPoint) {
    val length: Float get() = from.distanceTo(to)
}

/**
 * Relacion de aspecto del encuadre. Importa para las guias que se construyen sobre la
 * forma del rectangulo (espiral aurea, diagonales, triangulos) y no para las que se
 * definen por fracciones (tercios, rejillas).
 */
data class AspectRatio(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "AspectRatio invalido: $width:$height" }
    }

    val ratio: Float get() = width.toFloat() / height.toFloat()
    val isPortrait: Boolean get() = height > width
    val isSquare: Boolean get() = width == height

    fun rotated(): AspectRatio = AspectRatio(height, width)

    companion object {
        val R4_3 = AspectRatio(4, 3)
        val R3_2 = AspectRatio(3, 2)
        val R16_9 = AspectRatio(16, 9)
        val R1_1 = AspectRatio(1, 1)

        /** Los cuatro formatos que toda guia debe soportar, en horizontal y en vertical. */
        val ALL = listOf(R4_3, R3_2, R16_9, R1_1)
    }
}
