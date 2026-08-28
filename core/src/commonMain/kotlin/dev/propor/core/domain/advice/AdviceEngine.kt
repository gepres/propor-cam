package dev.propor.core.domain.advice

import dev.propor.core.domain.geometry.AspectRatio
import dev.propor.core.domain.geometry.Confidence
import dev.propor.core.domain.geometry.NormPoint
import dev.propor.core.domain.geometry.NormRect
import dev.propor.core.domain.guide.GuideGeometryFactory
import dev.propor.core.domain.guide.GuideKind
import dev.propor.core.domain.scene.Direction
import dev.propor.core.domain.scene.SceneReading
import dev.propor.core.domain.scene.SceneType
import kotlin.math.abs

/**
 * Umbrales del coach. Constantes con nombre y unidad, nunca numeros magicos sueltos.
 *
 * Los valores no son arbitrarios: por debajo de 2 grados casi nadie percibe un horizonte
 * torcido, y avisar de 1 grado seria exactamente el tipo de ruido que hace apagar el coach.
 */
data class AdviceConfig(
    /** Por debajo de esto, silencio. Nadie ve un grado de inclinacion. */
    val maxTiltDeg: Float = 2.0f,
    /** A partir de aqui la magnitud satura en 1. */
    val tiltSaturationDeg: Float = 10.0f,
    /** Radio alrededor del centro dentro del cual se considera que el sujeto esta centrado. */
    val centerToleranceNorm: Float = 0.08f,
    /** Altura ideal de los ojos en retrato. */
    val eyeTargetNorm: Float = 1f / 3f,
    val eyeToleranceNorm: Float = 0.08f,
    /** Aire maximo sobre la cabeza antes de que sobre. */
    val maxHeadroomNorm: Float = 0.22f,
    /** Por debajo de esta fraccion visible, el sujeto esta cortado. */
    val minVisibleFraction: Float = 0.92f,
    val maxVerticalConvergenceDeg: Float = 2.5f,
)

/**
 * El catalogo de reglas del coach: de lo que la vision ve, a lo que merece decirse.
 *
 * Servicio de dominio **puro y determinista**: la misma `SceneReading` con el mismo perfil
 * produce siempre la misma lista. De ahi sale la explicabilidad gratis (se puede reproducir por
 * que la app dijo lo que dijo) y la posibilidad de probar sesiones enteras en CI.
 *
 * Este motor decide QUE esta mal. Cuando —o si— se dice es cosa del [AdviceThrottler], y ahi
 * es donde esta la diferencia real con la competencia.
 */
class AdviceEngine(private val config: AdviceConfig = AdviceConfig()) {

    fun advise(
        reading: SceneReading,
        activeGuide: GuideKind,
        aspect: AspectRatio = AspectRatio.R4_3,
        profile: CoachProfile = CoachProfile.NEUTRAL,
    ): List<Advice> {
        val found = buildList {
            tiltedHorizon(reading)?.let(::add)
            subjectCentered(reading, activeGuide, aspect)?.let(::add)
            eyesOffUpperThird(reading)?.let(::add)
            tooMuchHeadroom(reading)?.let(::add)
            lookingRoom(reading)?.let(::add)
            subjectClipped(reading)?.let(::add)
            convergingVerticals(reading)?.let(::add)
        }

        return found
            .filterNot { profile.isMuted(it.key) }
            .filter { profile.weightFor(it.key) > 0f }
            .sortedWith(weighted(profile))
    }

    /** El peso del perfil solo altera el desempate, nunca la gravedad declarada de la regla. */
    private fun weighted(profile: CoachProfile): Comparator<Advice> =
        compareByDescending<Advice> { it.severity.ordinal }
            .thenBy { it.cost.ordinal }
            .thenByDescending { it.magnitude * profile.weightFor(it.key) }

    // ------------------------------------------------------------------ 1. horizonte

    private fun tiltedHorizon(reading: SceneReading): Advice? {
        val horizon = reading.horizon ?: return null
        // Cada regla respeta la fiabilidad de SU senal. La confianza global de la escena dice
        // si la lectura sirve en conjunto; esta dice si el horizonte concreto es de fiar, y
        // con el telefono en movimiento no lo es.
        if (horizon.confidence < Confidence.COACH_THRESHOLD) return null
        val deg = abs(horizon.angle.value)
        if (deg <= config.maxTiltDeg) return null
        val span = (config.tiltSaturationDeg - config.maxTiltDeg).coerceAtLeast(0.001f)
        val magnitude = ((deg - config.maxTiltDeg) / span).coerceIn(0f, 1f)
        return Advice.TiltHorizon(horizon.angle, magnitude)
    }

    // ------------------------------------------------------------------ 2. sujeto centrado

    /**
     * Solo aplica cuando la guia activa propone puntos fuertes FUERA del centro. Con la
     * reticula central o la simetria, centrar es justo lo que se pide: avisar ahi seria
     * contradecir al usuario.
     */
    private fun subjectCentered(
        reading: SceneReading,
        activeGuide: GuideKind,
        aspect: AspectRatio,
    ): Advice? {
        if (activeGuide.encouragesCentering) return null
        val subject = reading.mainSubject ?: return null
        val anchors = GuideGeometryFactory.geometryFor(activeGuide, aspect).anchors
        if (anchors.isEmpty()) return null

        val center = subject.center
        val distanceToCenter = center.distanceTo(NormPoint.CENTER)
        if (distanceToCenter > config.centerToleranceNorm) return null

        val suggested = anchors.minByOrNull { it.distanceTo(center) } ?: return null
        val magnitude =
            ((config.centerToleranceNorm - distanceToCenter) / config.centerToleranceNorm)
                .coerceIn(0f, 1f)
        return Advice.SubjectCentered(current = center, suggested = suggested, magnitude = magnitude)
    }

    // ------------------------------------------------------------------ 3. linea de ojos

    private fun eyesOffUpperThird(reading: SceneReading): Advice? {
        if (reading.sceneType !in setOf(SceneType.PORTRAIT, SceneType.UNKNOWN)) return null
        val face = reading.mainFace ?: return null
        // Solo tiene sentido cuando el rostro manda en el encuadre.
        if (face.bounds.size.area < 0.03f) return null
        val eyeLine = face.eyeLine?.value ?: return null

        val delta = abs(eyeLine - config.eyeTargetNorm)
        if (delta <= config.eyeToleranceNorm) return null
        val magnitude = ((delta - config.eyeToleranceNorm) / 0.3f).coerceIn(0f, 1f)
        return Advice.EyesOffUpperThird(eyeLine, config.eyeTargetNorm, magnitude)
    }

    // ------------------------------------------------------------------ 4. headroom

    private fun tooMuchHeadroom(reading: SceneReading): Advice? {
        val subject: NormRect = reading.mainFace?.bounds
            ?: reading.bodies.maxByOrNull { it.bounds.size.area }?.bounds
            ?: return null
        val headroom = subject.top
        if (headroom <= config.maxHeadroomNorm) return null
        val excess = headroom - config.maxHeadroomNorm
        val magnitude = (excess / 0.35f).coerceIn(0f, 1f)
        return Advice.TooMuchHeadroom(excess = excess, magnitude = magnitude)
    }

    // ------------------------------------------------------------------ 5. espacio de mirada

    /**
     * Quien mira hacia la izquierda necesita espacio a su izquierda. Si el aire esta detras,
     * la mirada choca contra el borde y la foto se siente apretada.
     */
    private fun lookingRoom(reading: SceneReading): Advice? {
        val face = reading.mainFace ?: return null
        val gaze = face.gaze ?: return null
        if (gaze == Direction.UP || gaze == Direction.DOWN) return null

        val roomTowardsGaze = if (gaze == Direction.LEFT) face.bounds.left else 1f - face.bounds.right
        val roomBehind = if (gaze == Direction.LEFT) 1f - face.bounds.right else face.bounds.left

        if (roomTowardsGaze >= roomBehind) return null
        val deficit = roomBehind - roomTowardsGaze
        val magnitude = (deficit / 0.5f).coerceIn(0f, 1f)
        return Advice.LookingRoomOnWrongSide(gaze, magnitude)
    }

    // ------------------------------------------------------------------ 6. sujeto cortado

    private fun subjectClipped(reading: SceneReading): Advice? {
        val subject = reading.mainSubject ?: return null
        if (!subject.isClipped) return null
        val visible = subject.visibleFraction()
        if (visible >= config.minVisibleFraction) return null

        val overflows = mapOf(
            Direction.LEFT to -subject.left,
            Direction.RIGHT to subject.right - 1f,
            Direction.UP to -subject.top,
            Direction.DOWN to subject.bottom - 1f,
        )
        val edge = overflows.maxByOrNull { it.value }?.key ?: Direction.RIGHT
        val magnitude = (1f - visible).coerceIn(0f, 1f)
        return Advice.SubjectClipped(edge = edge, visibleFraction = visible, magnitude = magnitude)
    }

    // ------------------------------------------------------------------ 7. verticales

    private fun convergingVerticals(reading: SceneReading): Advice? {
        val convergence = reading.verticalConvergence ?: return null
        if (reading.sceneType != SceneType.ARCHITECTURE) return null
        val deg = abs(convergence.value)
        if (deg <= config.maxVerticalConvergenceDeg) return null
        val magnitude = ((deg - config.maxVerticalConvergenceDeg) / 8f).coerceIn(0f, 1f)
        return Advice.ConvergingVerticals(convergence, magnitude)
    }
}
