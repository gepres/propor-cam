package dev.propor.core.domain.guide

import dev.propor.core.domain.geometry.NormPoint
import dev.propor.core.domain.geometry.Segment

/**
 * Las guias de composicion que el visor puede dibujar.
 *
 * Las diez primeras son el alcance de R1. El resto queda declarado porque el catalogo
 * es parte del dominio y conviene que el `when` exhaustivo avise cuando se anada una.
 */
enum class GuideKind {
    /** Tercios: las cuatro intersecciones son puntos de interes. */
    THIRDS,

    /** Rejilla 2x2: un eje vertical y uno horizontal. Composicion simple. */
    GRID_2X2,

    /** Rejilla 3x3: misma geometria que tercios, sin anclas. Solo referencia. */
    GRID_3X3,

    /** Rejilla 4x4: arquitectura y producto. */
    GRID_4X4,

    /** Diagonales principales del encuadre. Dinamismo. */
    DIAGONALS,

    /** Triangulos aureos: una diagonal y dos perpendiculares. Depende del formato. */
    TRIANGLES,

    /** Rejilla aurea: lineas en 0,382 y 0,618. NO es la regla de tercios. */
    GOLDEN_RATIO,

    /** Espiral aurea. Depende del formato y admite cuatro orientaciones. */
    GOLDEN_SPIRAL,

    /** Ejes de simetria. */
    SYMMETRY,

    /** Reticula central para composicion simetrica. */
    CENTER,

    // --- Fuera de R1: dependen de la escena o llegan mas tarde ---
    FIBONACCI,
    LEADING_LINES,
    HORIZON,
    FRAME_IN_FRAME,
    PATTERN,
    NEGATIVE_SPACE,
    CUSTOM,
    ;

    /**
     * True si esta guia propone deliberadamente colocar el sujeto en el centro.
     *
     * Es una propiedad SEMANTICA, no geometrica: no se puede deducir mirando donde caen las
     * anclas. La simetria, por ejemplo, ancla en los puntos medios de los bordes para alinear
     * el eje de reflexion, y aun asi pide un sujeto centrado.
     *
     * El coach la consulta para no contradecir al usuario: avisar de "sujeto centrado" a quien
     * eligio la reticula central es la forma mas rapida de que apague el coach.
     */
    val encouragesCentering: Boolean
        get() = this == CENTER || this == SYMMETRY || this == GRID_2X2

    companion object {
        /** Las diez guias que entran en R1. */
        val R1: List<GuideKind> = listOf(
            THIRDS, GRID_2X2, GRID_3X3, GRID_4X4, DIAGONALS,
            TRIANGLES, GOLDEN_RATIO, GOLDEN_SPIRAL, SYMMETRY, CENTER,
        )
    }
}

/**
 * Geometria lista para dibujar, en coordenadas normalizadas.
 *
 * Se modela como una sola estructura con tres colecciones en vez de una jerarquia sellada:
 * varias guias son mixtas (la espiral tiene curva Y rectangulos; los tercios tienen lineas
 * Y anclas), asi que un `when` en el shader solo anadiria ramas sin evitar el caso compuesto.
 *
 * - [segments] lineas rectas.
 * - [curves] polilineas ya teseladas; el renderizador solo une puntos.
 * - [anchors] puntos de interes donde el coach sugiere colocar el sujeto.
 */
data class GuideGeometry(
    val kind: GuideKind,
    val segments: List<Segment> = emptyList(),
    val curves: List<List<NormPoint>> = emptyList(),
    val anchors: List<NormPoint> = emptyList(),
) {
    val isEmpty: Boolean get() = segments.isEmpty() && curves.isEmpty() && anchors.isEmpty()
}

/** Esquina de origen de la espiral aurea. Sus cuatro valores son las cuatro orientaciones. */
enum class SpiralCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
