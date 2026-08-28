package dev.propor.core.domain.guide

import dev.propor.core.domain.geometry.AspectRatio
import dev.propor.core.domain.geometry.NormPoint
import dev.propor.core.domain.geometry.Segment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Genera la geometria de cada guia. Servicio de dominio puro: sin estado, sin dependencias,
 * determinista y probado con tests de tabla en los cuatro formatos y las dos orientaciones.
 *
 * Anadir una guia nueva es anadir un caso aqui y su test. Cero cambios en la interfaz
 * (nota 03, seccion H).
 */
object GuideGeometryFactory {

    /** Proporcion aurea. */
    const val PHI: Float = 1.618034f

    /** 1 - 1/phi. La linea aurea cercana. NO es 1/3: confundirlas es un error de bulto. */
    const val GOLDEN_NEAR: Float = 0.381966f

    /** 1/phi. La linea aurea lejana. NO es 2/3. */
    const val GOLDEN_FAR: Float = 0.618034f

    private const val ONE_THIRD = 1f / 3f
    private const val TWO_THIRDS = 2f / 3f

    /** Puntos por cuarto de arco al teselar la espiral. Suficiente para que no se vean facetas. */
    private const val ARC_STEPS = 16

    /** Cuantos cuadrados de la espiral se dibujan antes de que dejen de verse. */
    private const val SPIRAL_STEPS = 9

    /**
     * Geometria de [kind] para un encuadre de proporcion [aspect].
     *
     * [aspect] solo altera el resultado en las guias que se construyen sobre la forma del
     * rectangulo (triangulos). Las que se definen por fracciones (tercios, rejillas, aurea)
     * son identicas en todos los formatos, que es justo lo que se espera de ellas.
     *
     * [corner] solo aplica a la espiral aurea.
     */
    fun geometryFor(
        kind: GuideKind,
        aspect: AspectRatio = AspectRatio.R4_3,
        corner: SpiralCorner = SpiralCorner.BOTTOM_RIGHT,
    ): GuideGeometry = when (kind) {
        GuideKind.THIRDS -> grid(
            kind = kind,
            xs = listOf(ONE_THIRD, TWO_THIRDS),
            ys = listOf(ONE_THIRD, TWO_THIRDS),
            withAnchors = true,
        )

        // Misma geometria que THIRDS, pero sin anclas: es referencia, no sugerencia.
        GuideKind.GRID_3X3 -> grid(
            kind = kind,
            xs = listOf(ONE_THIRD, TWO_THIRDS),
            ys = listOf(ONE_THIRD, TWO_THIRDS),
            withAnchors = false,
        )

        GuideKind.GRID_2X2 -> grid(
            kind = kind,
            xs = listOf(0.5f),
            ys = listOf(0.5f),
            withAnchors = true,
        )

        GuideKind.GRID_4X4 -> grid(
            kind = kind,
            xs = listOf(0.25f, 0.5f, 0.75f),
            ys = listOf(0.25f, 0.5f, 0.75f),
            withAnchors = false,
        )

        GuideKind.GOLDEN_RATIO -> grid(
            kind = kind,
            xs = listOf(GOLDEN_NEAR, GOLDEN_FAR),
            ys = listOf(GOLDEN_NEAR, GOLDEN_FAR),
            withAnchors = true,
        )

        GuideKind.DIAGONALS -> GuideGeometry(
            kind = kind,
            segments = listOf(
                Segment(NormPoint.of(0f, 0f), NormPoint.of(1f, 1f)),
                Segment(NormPoint.of(1f, 0f), NormPoint.of(0f, 1f)),
            ),
            anchors = listOf(NormPoint.CENTER),
        )

        GuideKind.TRIANGLES -> triangles(aspect)

        GuideKind.GOLDEN_SPIRAL -> goldenSpiral(corner)

        GuideKind.SYMMETRY -> GuideGeometry(
            kind = kind,
            segments = listOf(
                Segment(NormPoint.of(0.5f, 0f), NormPoint.of(0.5f, 1f)),
                Segment(NormPoint.of(0f, 0.5f), NormPoint.of(1f, 0.5f)),
            ),
            // Los puntos medios de los bordes sirven para alinear el eje de reflexion
            // del sujeto con el eje del encuadre.
            anchors = listOf(
                NormPoint.of(0.5f, 0f), NormPoint.of(0.5f, 1f),
                NormPoint.of(0f, 0.5f), NormPoint.of(1f, 0.5f),
            ),
        )

        GuideKind.CENTER -> centerReticle()

        // Fuera de R1: dependen de la escena o llegan mas tarde.
        else -> GuideGeometry(kind)
    }

    // ---------------------------------------------------------------- rejillas

    private fun grid(
        kind: GuideKind,
        xs: List<Float>,
        ys: List<Float>,
        withAnchors: Boolean,
    ): GuideGeometry {
        val segments = buildList {
            xs.forEach { x -> add(Segment(NormPoint.of(x, 0f), NormPoint.of(x, 1f))) }
            ys.forEach { y -> add(Segment(NormPoint.of(0f, y), NormPoint.of(1f, y))) }
        }
        val anchors = if (!withAnchors) emptyList() else buildList {
            xs.forEach { x -> ys.forEach { y -> add(NormPoint.of(x, y)) } }
        }
        return GuideGeometry(kind, segments = segments, anchors = anchors)
    }

    // ---------------------------------------------------------------- triangulos aureos

    /**
     * Una diagonal principal y las perpendiculares a ella desde las otras dos esquinas.
     * Los pies de esas perpendiculares son los puntos fuertes.
     *
     * Es la unica guia de R1 cuya geometria depende del formato: la perpendicularidad hay que
     * calcularla en el espacio fisico (ancho = ratio, alto = 1) y traerla despues al espacio
     * normalizado. Calculada directamente en normalizado, las lineas no saldrian
     * perpendiculares en pantalla.
     */
    private fun triangles(aspect: AspectRatio): GuideGeometry {
        val r = aspect.ratio
        // Pie de la perpendicular desde (ratio, 0) sobre la diagonal (0,0)-(ratio,1),
        // ya convertido a normalizado: ambas componentes valen r^2 / (r^2 + 1).
        val k = (r * r) / (r * r + 1f)
        val footA = NormPoint.of(k, k)
        val footB = NormPoint.of(1f - k, 1f - k)
        return GuideGeometry(
            kind = GuideKind.TRIANGLES,
            segments = listOf(
                Segment(NormPoint.of(0f, 0f), NormPoint.of(1f, 1f)),
                Segment(NormPoint.of(1f, 0f), footA),
                Segment(NormPoint.of(0f, 1f), footB),
            ),
            anchors = listOf(footA, footB),
        )
    }

    // ---------------------------------------------------------------- reticula central

    private fun centerReticle(): GuideGeometry {
        val arm = 0.06f
        return GuideGeometry(
            kind = GuideKind.CENTER,
            segments = listOf(
                Segment(NormPoint.of(0.5f - arm, 0.5f), NormPoint.of(0.5f + arm, 0.5f)),
                Segment(NormPoint.of(0.5f, 0.5f - arm), NormPoint.of(0.5f, 0.5f + arm)),
            ),
            anchors = listOf(NormPoint.CENTER),
        )
    }

    // ---------------------------------------------------------------- espiral aurea

    /**
     * Espiral de Fibonacci construida por subdivision de un rectangulo aureo.
     *
     * Se genera en el espacio canonico (ancho phi, alto 1), donde los cuadrados son cuadrados
     * de verdad, y se lleva al encuadre por escala afin. Es lo que hacen las camaras
     * profesionales del mercado: la espiral se estira al formato en vez de inscribir un
     * rectangulo aureo dejando bandas vacias.
     *
     * [corner] nombra DONDE CONVERGE la espiral, que es donde va el sujeto. Es lo que le
     * importa al fotografo, no en que esquina empieza el dibujo.
     */
    private fun goldenSpiral(corner: SpiralCorner): GuideGeometry {
        var x = 0f
        var y = 0f
        var w = PHI
        var h = 1f

        val curve = mutableListOf<NormPoint>()
        val squares = mutableListOf<Segment>()

        fun push(px: Float, py: Float) {
            // De x en espacio canonico (0..phi) a normalizado (0..1).
            curve += reflect(NormPoint.clamped(px / PHI, py), corner)
        }

        fun pushSquare(sx: Float, sy: Float, side: Float) {
            val l = sx / PHI
            val r = (sx + side) / PHI
            val t = sy
            val b = sy + side
            listOf(
                Segment(NormPoint.clamped(l, t), NormPoint.clamped(r, t)),
                Segment(NormPoint.clamped(r, t), NormPoint.clamped(r, b)),
                Segment(NormPoint.clamped(r, b), NormPoint.clamped(l, b)),
                Segment(NormPoint.clamped(l, b), NormPoint.clamped(l, t)),
            ).forEach { squares += reflect(it, corner) }
        }

        for (i in 0 until SPIRAL_STEPS) {
            val side: Float
            val cx: Float
            val cy: Float
            val sqX: Float
            val sqY: Float

            when (i % 4) {
                0 -> { // se corta el cuadrado de la izquierda
                    side = h; sqX = x; sqY = y
                    cx = x + side; cy = y + side
                    x += side; w -= side
                }
                1 -> { // el de arriba
                    side = w; sqX = x; sqY = y
                    cx = x; cy = y + side
                    y += side; h -= side
                }
                2 -> { // el de la derecha
                    side = h; sqX = x + w - side; sqY = y
                    cx = sqX; cy = y
                    w -= side
                }
                else -> { // el de abajo
                    side = w; sqX = x; sqY = y + h - side
                    cx = x + side; cy = sqY
                    h -= side
                }
            }

            pushSquare(sqX, sqY, side)

            // Cuarto de arco. El angulo inicial avanza 90 grados en cada paso, de modo que
            // el final de un arco es exactamente el principio del siguiente.
            val start = (180f + i * 90f) * (PI.toFloat() / 180f)
            val sweep = PI.toFloat() / 2f
            for (s in 0..ARC_STEPS) {
                val a = (start + sweep * (s.toFloat() / ARC_STEPS)).toDouble()
                push(cx + side * cos(a).toFloat(), cy + side * sin(a).toFloat())
            }
        }

        return GuideGeometry(
            kind = GuideKind.GOLDEN_SPIRAL,
            segments = squares,
            curves = listOf(curve),
            // El ojo termina donde termina la espiral: ese es el ancla.
            anchors = listOfNotNull(curve.lastOrNull()),
        )
    }

    private fun reflect(p: NormPoint, corner: SpiralCorner): NormPoint {
        val flipX = corner == SpiralCorner.BOTTOM_LEFT || corner == SpiralCorner.TOP_LEFT
        val flipY = corner == SpiralCorner.TOP_RIGHT || corner == SpiralCorner.TOP_LEFT
        val nx = if (flipX) 1f - p.x.value else p.x.value
        val ny = if (flipY) 1f - p.y.value else p.y.value
        return NormPoint.clamped(nx, ny)
    }

    private fun reflect(s: Segment, corner: SpiralCorner): Segment =
        Segment(reflect(s.from, corner), reflect(s.to, corner))
}
