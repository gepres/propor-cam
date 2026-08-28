package dev.propor.core.application.usecase

import dev.propor.core.domain.advice.Advice
import dev.propor.core.domain.advice.AdviceEngine
import dev.propor.core.domain.advice.AdviceKey
import dev.propor.core.domain.advice.AdviceThrottler
import dev.propor.core.domain.advice.CoachOutput
import dev.propor.core.domain.advice.CoachProfile
import dev.propor.core.domain.geometry.AspectRatio
import dev.propor.core.domain.geometry.NormPoint
import dev.propor.core.domain.guide.GuideGeometryFactory
import dev.propor.core.domain.guide.GuideKind
import dev.propor.core.domain.port.HapticPort
import dev.propor.core.domain.scene.SceneReading

/**
 * Lo que la interfaz necesita saber tras evaluar un frame.
 *
 * Sin una sola cadena de texto: la presentacion decide como se dice cada cosa, y si se dice o
 * solo se vibra.
 */
data class LiveFeedback(
    val output: CoachOutput,
    /** Ancla que el coach sugiere ahora. Null cuando no hay nada que sugerir. */
    val suggestedAnchor: NormPoint? = null,
    /**
     * Cuanto falta para el encuadre sugerido, de 0 a 1. Alimenta el arco periferico.
     * 1 es alineado.
     */
    val alignment: Float = 1f,
) {
    val isSpeaking: Boolean get() = output is CoachOutput.Speak
}

/**
 * El bucle del coach: de lo que la vision ve, a lo que el usuario siente.
 *
 * Se ejecuta a la velocidad del visor, asi que **no puede hacer trabajo pesado ni reservar
 * memoria en estado estable**. Todo lo caro ocurre antes (la vision) o se calcula una sola vez
 * al cambiar de guia.
 *
 * La haptica se dispara aqui y no en la capa de interfaz por una razon concreta: el instante
 * exacto en que vibra forma parte de la regla de producto, y si dependiera de cada plataforma
 * acabaria sintiendose distinto en Android y en iOS.
 */
class EvaluateLiveFrame(
    private val engine: AdviceEngine,
    private val throttler: AdviceThrottler,
    private val haptics: HapticPort,
) {

    /** Consejos que llegaron a mostrarse en esta sesion. Va al sidecar de la captura. */
    private val shown = mutableSetOf<AdviceKey>()

    /** Los que el usuario atendio: la magnitud bajo tras avisarle. Es la senal de aprendizaje. */
    private val accepted = mutableSetOf<AdviceKey>()

    private var lastMagnitudeByKey = mutableMapOf<AdviceKey, Float>()

    operator fun invoke(
        reading: SceneReading,
        activeGuide: GuideKind,
        aspect: AspectRatio = AspectRatio.R4_3,
        profile: CoachProfile = CoachProfile.NEUTRAL,
        hapticsEnabled: Boolean = true,
    ): LiveFeedback {
        val advice = engine.advise(reading, activeGuide, aspect, profile)

        // Un consejo se considera atendido cuando su gravedad baja despues de haberlo mostrado.
        advice.forEach { current ->
            val previous = lastMagnitudeByKey[current.key]
            if (previous != null && current.key in shown && current.magnitude < previous - 0.1f) {
                accepted += current.key
            }
            lastMagnitudeByKey[current.key] = current.magnitude
        }
        // Y tambien cuando desaparece del todo tras haberse mostrado.
        val activeKeys = advice.map { it.key }.toSet()
        shown.filterNot { it in activeKeys }.forEach { accepted += it }

        val output = throttler.evaluate(advice, reading.confidence)

        if (output is CoachOutput.Speak) {
            shown += output.advice.key
            if (hapticsEnabled && haptics.isAvailable) haptics.play(output.signal)
        }

        val subject = reading.mainSubject?.center

        // El ancla se enciende SOLO cuando el consejo activo trata de donde esta el sujeto.
        //
        // Antes se destacaba siempre que se detectaba un sujeto, aunque el coach estuviera
        // callado: aparecia un punto senalando un sitio sin que nada lo hubiera provocado y sin
        // decir por que. Es ruido con forma de consejo, y contradice que el silencio sea el
        // estado normal. Lo detecto un usuario preguntando "ese punto que me quiere decir",
        // que es la mejor prueba de que un elemento de interfaz no se explica solo.
        //
        // Con el horizonte torcido no se destaca nada: el problema no es donde esta el sujeto.
        val suggested = when {
            output !is CoachOutput.Speak -> null
            output.advice is Advice.SubjectCentered -> (output.advice as Advice.SubjectCentered).suggested
            else -> null
        }

        return LiveFeedback(
            output = output,
            suggestedAnchor = suggested,
            alignment = alignmentOf(subject, suggested),
        )
    }

    /** Consejos mostrados y atendidos, para el expediente de la captura. */
    fun sessionAdvice(): Pair<List<AdviceKey>, List<AdviceKey>> =
        shown.toList() to accepted.toList()

    fun reset() {
        shown.clear()
        accepted.clear()
        lastMagnitudeByKey.clear()
        throttler.reset()
        haptics.stop()
    }

    /**
     * Cuanto de cerca esta el sujeto del ancla sugerida.
     *
     * Se satura a media pantalla: mas alla de eso, decir "te falta mucho" y "te falta muchisimo"
     * es la misma informacion para quien esta encuadrando.
     */
    private fun alignmentOf(subject: NormPoint?, anchor: NormPoint?): Float {
        if (subject == null || anchor == null) return 1f
        val distance = subject.distanceTo(anchor)
        return (1f - (distance / MAX_USEFUL_DISTANCE)).coerceIn(0f, 1f)
    }

    private companion object {
        const val MAX_USEFUL_DISTANCE = 0.5f
    }
}
