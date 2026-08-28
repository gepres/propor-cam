package dev.propor.core.domain.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeometryTest {

    @Test
    fun normalized_fueraDeRango_falla() {
        assertFailsWith<IllegalArgumentException> { Normalized(1.2f) }
        assertFailsWith<IllegalArgumentException> { Normalized(-0.1f) }
    }

    @Test
    fun normalized_clamp_recortaEnVezDeFallar() {
        assertEquals(1f, Normalized.clamp(3f).value)
        assertEquals(0f, Normalized.clamp(-3f).value)
    }

    @Test
    fun normRect_sujetoDentro_noEstaRecortado() {
        val r = NormRect.of(left = 0.2f, top = 0.2f, width = 0.3f, height = 0.4f)
        assertFalse(r.isClipped)
        assertEquals(1f, r.visibleFraction(), absoluteTolerance = 1e-5f)
    }

    @Test
    fun normRect_sujetoQueSaleDelBorde_seDetectaYSeMideLoVisible() {
        // La mitad derecha del sujeto queda fuera del encuadre.
        val r = NormRect.of(left = 0.8f, top = 0.2f, width = 0.4f, height = 0.4f)
        assertTrue(r.isClipped)
        assertEquals(0.5f, r.visibleFraction(), absoluteTolerance = 1e-5f)
    }

    @Test
    fun aspectRatio_rotadoInvierteOrientacion() {
        assertTrue(AspectRatio.R16_9.rotated().isPortrait)
        assertFalse(AspectRatio.R16_9.isPortrait)
        assertTrue(AspectRatio.R1_1.isSquare)
    }

    @Test
    fun confidence_umbralDelCoachEsCeroSetentaYCinco() {
        assertEquals(0.75f, Confidence.COACH_THRESHOLD.value)
    }
}
