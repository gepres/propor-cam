package dev.propor.core.domain.guide

import dev.propor.core.domain.geometry.AspectRatio
import dev.propor.core.domain.geometry.NormPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests de tabla de las guias de R1.
 *
 * Criterio de aceptacion PCA (H3.1): las 10 guias generan geometria correcta en 4:3, 3:2,
 * 16:9 y 1:1, en horizontal y en vertical.
 */
class GuideGeometryFactoryTest {

    private val formats: List<AspectRatio> =
        AspectRatio.ALL + AspectRatio.ALL.map { it.rotated() }

    @Test
    fun lasDiezGuiasDeR1_generanGeometriaEnTodosLosFormatos() {
        for (kind in GuideKind.R1) {
            for (aspect in formats) {
                val g = GuideGeometryFactory.geometryFor(kind, aspect)
                assertTrue(
                    !g.isEmpty,
                    "La guia $kind quedo vacia en ${aspect.width}:${aspect.height}",
                )
                assertEquals(kind, g.kind)
            }
        }
    }

    @Test
    fun sonExactamenteDiezGuiasEnR1() {
        assertEquals(10, GuideKind.R1.size)
        assertEquals(GuideKind.R1.size, GuideKind.R1.distinct().size)
    }

    // ------------------------------------------------------------------ aurea vs tercios

    /**
     * El error de bulto que este test existe para impedir: tratar la proporcion aurea como
     * si fuera la regla de tercios. Son 0,382 / 0,618 frente a 0,333 / 0,667.
     */
    @Test
    fun proporcionAurea_noEsLaReglaDeTercios() {
        val golden = GuideGeometryFactory.geometryFor(GuideKind.GOLDEN_RATIO)
        val thirds = GuideGeometryFactory.geometryFor(GuideKind.THIRDS)

        val goldenXs = golden.segments.filter { it.from.x == it.to.x }.map { it.from.x.value }.sorted()
        val thirdsXs = thirds.segments.filter { it.from.x == it.to.x }.map { it.from.x.value }.sorted()

        assertEquals(2, goldenXs.size)
        assertEquals(0.381966f, goldenXs[0], absoluteTolerance = 1e-5f)
        assertEquals(0.618034f, goldenXs[1], absoluteTolerance = 1e-5f)

        assertEquals(1f / 3f, thirdsXs[0], absoluteTolerance = 1e-5f)
        assertEquals(2f / 3f, thirdsXs[1], absoluteTolerance = 1e-5f)

        assertNotEquals(goldenXs, thirdsXs)
        // La separacion entre ambas convenciones es de casi cinco puntos de encuadre:
        // suficiente para que se vea, que es justo por lo que no se pueden confundir.
        assertTrue(abs(goldenXs[0] - thirdsXs[0]) > 0.04f)
    }

    @Test
    fun aureaCumpleLaDefinicion() {
        // 1/phi y su complemento.
        assertEquals(1f / GuideGeometryFactory.PHI, GuideGeometryFactory.GOLDEN_FAR, absoluteTolerance = 1e-5f)
        assertEquals(1f - GuideGeometryFactory.GOLDEN_FAR, GuideGeometryFactory.GOLDEN_NEAR, absoluteTolerance = 1e-5f)
    }

    // ------------------------------------------------------------------ anclas

    @Test
    fun tercios_tieneCuatroAnclasEnLasIntersecciones() {
        val g = GuideGeometryFactory.geometryFor(GuideKind.THIRDS)
        assertEquals(4, g.anchors.size)
        assertTrue(g.anchors.any { close(it, 1f / 3f, 1f / 3f) })
        assertTrue(g.anchors.any { close(it, 2f / 3f, 2f / 3f) })
    }

    /**
     * Tercios y rejilla 3x3 dibujan lo mismo, pero solo tercios sugiere donde poner al sujeto.
     * La rejilla es referencia; los tercios son una opinion. Esa diferencia es semantica y
     * vive en las anclas, no en las lineas.
     */
    @Test
    fun rejilla3x3_dibujaIgualQueTerciosPeroNoSugiereNada() {
        val thirds = GuideGeometryFactory.geometryFor(GuideKind.THIRDS)
        val grid = GuideGeometryFactory.geometryFor(GuideKind.GRID_3X3)

        assertEquals(thirds.segments, grid.segments)
        assertEquals(4, thirds.anchors.size)
        assertEquals(0, grid.anchors.size)
    }

    @Test
    fun rejilla4x4_tieneSeisLineas() {
        val g = GuideGeometryFactory.geometryFor(GuideKind.GRID_4X4)
        assertEquals(6, g.segments.size)
    }

    // ------------------------------------------------------------------ triangulos

    /**
     * En un cuadrado, la perpendicular desde la esquina cae exactamente en el centro.
     * Es el unico formato donde eso ocurre, asi que sirve de comprobacion analitica.
     */
    @Test
    fun triangulos_enCuadradoElPieCaeEnElCentro() {
        val g = GuideGeometryFactory.geometryFor(GuideKind.TRIANGLES, AspectRatio.R1_1)
        assertEquals(2, g.anchors.size)
        g.anchors.forEach { assertTrue(close(it, 0.5f, 0.5f), "ancla inesperada: $it") }
    }

    @Test
    fun triangulos_dependenDelFormato() {
        val cuadrado = GuideGeometryFactory.geometryFor(GuideKind.TRIANGLES, AspectRatio.R1_1)
        val panoramico = GuideGeometryFactory.geometryFor(GuideKind.TRIANGLES, AspectRatio.R16_9)
        assertNotEquals(cuadrado.anchors, panoramico.anchors)

        // 16:9 -> k = r^2/(r^2+1) con r = 16/9
        val r = AspectRatio.R16_9.ratio
        val k = (r * r) / (r * r + 1f)
        assertTrue(panoramico.anchors.any { close(it, k, k) })
        assertTrue(panoramico.anchors.any { close(it, 1f - k, 1f - k) })
    }

    @Test
    fun triangulos_lasPerpendicularesSonPerpendicularesEnPantalla() {
        // Se comprueba en espacio fisico, que es donde el angulo tiene sentido.
        for (aspect in formats) {
            val g = GuideGeometryFactory.geometryFor(GuideKind.TRIANGLES, aspect)
            val diagonal = g.segments[0]
            val perpendicular = g.segments[1]
            val r = aspect.ratio

            val dx = (diagonal.to.x.value - diagonal.from.x.value) * r
            val dy = diagonal.to.y.value - diagonal.from.y.value
            val px = (perpendicular.to.x.value - perpendicular.from.x.value) * r
            val py = perpendicular.to.y.value - perpendicular.from.y.value

            val dot = dx * px + dy * py
            val norm = kotlin.math.hypot(dx, dy) * kotlin.math.hypot(px, py)
            assertTrue(
                abs(dot / norm) < 1e-3f,
                "No son perpendiculares en ${aspect.width}:${aspect.height} (cos=${dot / norm})",
            )
        }
    }

    // ------------------------------------------------------------------ espiral aurea

    @Test
    fun espiral_seGeneraEnLasCuatroOrientaciones() {
        val curvas = SpiralCorner.entries.map { corner ->
            corner to GuideGeometryFactory.geometryFor(
                GuideKind.GOLDEN_SPIRAL, AspectRatio.R3_2, corner,
            )
        }

        curvas.forEach { (corner, g) ->
            assertTrue(g.curves.single().isNotEmpty(), "espiral vacia en $corner")
            assertEquals(1, g.anchors.size, "la espiral debe tener un unico punto de convergencia")
        }

        // Cuatro orientaciones, cuatro puntos de convergencia distintos.
        val anclas = curvas.map { it.second.anchors.single() }
        assertEquals(4, anclas.distinct().size)
    }

    @Test
    fun espiral_convergeEnLaEsquinaQueDaNombreALaOrientacion() {
        fun anchorOf(corner: SpiralCorner): NormPoint =
            GuideGeometryFactory.geometryFor(GuideKind.GOLDEN_SPIRAL, AspectRatio.R3_2, corner)
                .anchors.single()

        val br = anchorOf(SpiralCorner.BOTTOM_RIGHT)
        assertTrue(br.x.value > 0.5f && br.y.value > 0.5f, "BOTTOM_RIGHT convergio en $br")

        val tl = anchorOf(SpiralCorner.TOP_LEFT)
        assertTrue(tl.x.value < 0.5f && tl.y.value < 0.5f, "TOP_LEFT convergio en $tl")

        val tr = anchorOf(SpiralCorner.TOP_RIGHT)
        assertTrue(tr.x.value > 0.5f && tr.y.value < 0.5f, "TOP_RIGHT convergio en $tr")

        val bl = anchorOf(SpiralCorner.BOTTOM_LEFT)
        assertTrue(bl.x.value < 0.5f && bl.y.value > 0.5f, "BOTTOM_LEFT convergio en $bl")
    }

    /**
     * La curva no puede tener saltos: cada arco tiene que empezar donde termino el anterior.
     * Si la construccion por subdivision se desalinea, aparece un salto visible y este test
     * lo caza antes que el ojo.
     */
    @Test
    fun espiral_esUnaCurvaContinua() {
        val curve = GuideGeometryFactory
            .geometryFor(GuideKind.GOLDEN_SPIRAL, AspectRatio.R3_2)
            .curves.single()

        assertTrue(curve.size > 100, "la espiral deberia estar teselada: ${curve.size} puntos")

        var maxStep = 0f
        for (i in 1 until curve.size) {
            maxStep = maxOf(maxStep, curve[i - 1].distanceTo(curve[i]))
        }
        // El primer arco es el mayor y con 16 tramos ningun paso deberia pasar de ~0,07.
        assertTrue(maxStep < 0.12f, "salto en la espiral: $maxStep")
    }

    @Test
    fun espiral_cadaCuadradoEsMasPequenoQueElAnterior() {
        val g = GuideGeometryFactory.geometryFor(GuideKind.GOLDEN_SPIRAL, AspectRatio.R3_2)
        // Cuatro segmentos por cuadrado.
        val alturas = g.segments.chunked(4).map { lados ->
            lados.maxOf { abs(it.to.y.value - it.from.y.value) }
        }
        for (i in 1 until alturas.size) {
            assertTrue(
                alturas[i] <= alturas[i - 1] + 1e-4f,
                "el cuadrado $i no encoge: ${alturas[i]} tras ${alturas[i - 1]}",
            )
        }
    }

    // ------------------------------------------------------------------ util

    private fun close(p: NormPoint, x: Float, y: Float, tol: Float = 1e-4f): Boolean =
        abs(p.x.value - x) < tol && abs(p.y.value - y) < tol
}
