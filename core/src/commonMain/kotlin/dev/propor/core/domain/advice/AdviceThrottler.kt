package dev.propor.core.domain.advice

import dev.propor.core.domain.port.ClockPort
import dev.propor.core.domain.geometry.Confidence

/** Lo que el coach hace en un frame concreto. */
sealed interface CoachOutput {
    /** Hay algo que senalar. La UI decide si lo dice, lo dibuja o solo lo vibra. */
    data class Speak(val advice: Advice, val signal: HapticSignal) : CoachOutput

    /** No se dice nada, y consta por que. El motivo alimenta la metrica de silencio. */
    data class Silent(val reason: SilenceReason) : CoachOutput
}

/**
 * Por que se callo el coach.
 *
 * Existe para poder medir: la metrica de gobierno del producto es el "silencio saludable"
 * (entre el 60 % y el 80 % del tiempo de visor), y sin saber por que se calla no se puede
 * distinguir un coach prudente de uno roto.
 */
enum class SilenceReason {
    /** No hay nada que decir. Es el silencio bueno. */
    NOTHING_TO_SAY,

    /** La vision no esta suficientemente segura. Mejor callar que equivocarse. */
    LOW_CONFIDENCE,

    /** La condicion aun no se ha sostenido lo bastante. No se reacciona a un frame suelto. */
    NOT_STABLE_YET,

    /** Acaba de decirse otra cosa. Un consejo cada vez. */
    COOLDOWN,

    /** El usuario ya se esta corrigiendo. Insistir aqui es el error que mas molesta. */
    USER_IS_CORRECTING,

    /** Tres segundos de DRIFT son suficientes. El telefono no es una alarma. */
    DRIFT_TIMEOUT,

    /** Esta persona descarto esta regla tres veces: es su estilo, no un error. */
    MUTED,
}

/** Los umbrales del silencio. Cambiarlos cambia el caracter del producto. */
data class ThrottleConfig(
    val minConfidence: Confidence = Confidence.COACH_THRESHOLD,
    /** Cuanto debe sostenerse una condicion antes de senalarla. */
    val stabilityMs: Long = 400,
    /** Separacion minima entre dos consejos distintos. */
    val cooldownMs: Long = 2_000,
    /** Duracion maxima de una senal continua, aunque el error siga. */
    val driftMaxMs: Long = 3_000,
    /** Caida de magnitud a partir de la cual se considera que el usuario esta corrigiendo. */
    val improvementEpsilon: Float = 0.02f,
    /** Descartes del mismo consejo tras los cuales esa regla se apaga para esta persona. */
    val dismissalsToMute: Int = 3,
)

/** Contadores para la metrica de silencio saludable. */
data class ThrottleStats(
    val evaluations: Int = 0,
    val emissions: Int = 0,
    val silenceByReason: Map<SilenceReason, Int> = emptyMap(),
) {
    /** Fraccion del tiempo de visor en la que el coach no dijo nada. Objetivo: 0,60 a 0,80. */
    val silenceRatio: Float
        get() = if (evaluations == 0) 1f else (evaluations - emissions).toFloat() / evaluations
}

/**
 * Decide CUANDO callarse. Es la clase mas importante del repositorio.
 *
 * Detectar que el horizonte esta torcido es facil: lo hace cualquiera con el framework de
 * vision del sistema en veinte lineas. Decidir si merece la pena decirlo, y callarse el resto
 * del tiempo, es el producto. La competencia (Cue, AureaCam, LensMate, WayShot) habla casi
 * siempre; ahi es donde se pierde al usuario en la segunda semana.
 *
 * Reglas del silencio:
 * - confianza por encima del umbral **y** condicion estable durante [ThrottleConfig.stabilityMs]
 * - un solo consejo a la vez, nunca dos senales simultaneas
 * - separacion minima entre consejos distintos
 * - silencio total mientras el usuario ya esta corrigiendo en la direccion correcta
 * - la senal continua se corta a los tres segundos aunque el error persista
 * - tres descartes del mismo consejo lo apagan para esa persona
 *
 * No es thread-safe: vive en el hilo de vision, que es de donde llegan los frames.
 */
class AdviceThrottler(
    private val clock: ClockPort,
    private val config: ThrottleConfig = ThrottleConfig(),
) {

    private var pendingKey: AdviceKey? = null
    private var pendingSince: Long = 0

    private var activeKey: AdviceKey? = null
    private var activeAdvice: Advice? = null
    private var activeSince: Long = 0
    private var activeMagnitude: Float = 0f

    /** Clave cuya senal continua ya se agoto; no se reanuda hasta que cambie la situacion. */
    private var exhaustedKey: AdviceKey? = null

    private var lastEmitAt: Long = Long.MIN_VALUE

    private val dismissals = mutableMapOf<AdviceKey, Int>()
    private val muted = mutableSetOf<AdviceKey>()

    private var evaluations = 0
    private var emissions = 0
    private val silences = mutableMapOf<SilenceReason, Int>()

    val stats: ThrottleStats
        get() = ThrottleStats(evaluations, emissions, silences.toMap())

    /** Reglas apagadas por descarte repetido. Alimenta el `CoachProfile` que se persiste. */
    val mutedKeys: Set<AdviceKey> get() = muted.toSet()

    /**
     * @param candidates ya priorizados por el [AdviceEngine]. Solo se mira el primero: un
     *   consejo cada vez.
     * @param confidence confianza de la lectura de escena que los produjo.
     */
    fun evaluate(candidates: List<Advice>, confidence: Confidence): CoachOutput {
        evaluations++
        val now = clock.nowMillis()

        val top = candidates.firstOrNull { it.key !in muted }

        // Nada que senalar. Si veniamos avisando de algo, es que se resolvio: eso si merece
        // un LOCK, que es la unica confirmacion positiva del vocabulario.
        if (top == null) {
            val resolved = activeAdvice
            val hadSomethingActive = activeKey != null
            clearActive()
            pendingKey = null
            return if (hadSomethingActive && resolved != null) {
                emit(now, resolved, HapticSignal.Lock, updateActive = false)
            } else {
                val muteHit = candidates.isNotEmpty() // habia consejos, pero todos silenciados
                silent(if (muteHit) SilenceReason.MUTED else SilenceReason.NOTHING_TO_SAY)
            }
        }

        if (confidence < config.minConfidence) return silent(SilenceReason.LOW_CONFIDENCE)

        // El usuario ya se esta corrigiendo: callarse es lo mas util que puede hacer la app.
        if (top.key == activeKey && top.magnitude < activeMagnitude - config.improvementEpsilon) {
            activeMagnitude = top.magnitude
            activeAdvice = top
            exhaustedKey = null // la situacion cambio: la senal puede volver si empeora
            return silent(SilenceReason.USER_IS_CORRECTING)
        }

        // Mismo consejo que ya se esta senalando y sin mejora: se sostiene, con limite.
        if (top.key == activeKey) {
            if (exhaustedKey == top.key) return silent(SilenceReason.DRIFT_TIMEOUT)
            if (now - activeSince >= config.driftMaxMs) {
                exhaustedKey = top.key
                return silent(SilenceReason.DRIFT_TIMEOUT)
            }
            activeAdvice = top
            if (top.magnitude > activeMagnitude) activeMagnitude = top.magnitude
            return emit(now, top, signalFor(top), updateActive = false)
        }

        // Consejo nuevo: primero tiene que sostenerse.
        if (top.key != pendingKey) {
            pendingKey = top.key
            pendingSince = now
            return silent(SilenceReason.NOT_STABLE_YET)
        }
        if (now - pendingSince < config.stabilityMs) return silent(SilenceReason.NOT_STABLE_YET)

        // Y respetar la separacion con el consejo anterior.
        if (lastEmitAt != Long.MIN_VALUE && now - lastEmitAt < config.cooldownMs) {
            return silent(SilenceReason.COOLDOWN)
        }

        activeKey = top.key
        activeSince = now
        activeMagnitude = top.magnitude
        exhaustedKey = null
        return emit(now, top, signalFor(top), updateActive = true)
    }

    /**
     * El usuario descarto un consejo. Al tercero, esa regla se apaga para siempre para el.
     *
     * No es una concesion: si alguien rompe la misma convencion tres veces a proposito, es su
     * estilo. Una app que insiste despues de eso deja de ser un asistente.
     */
    fun onDismissed(key: AdviceKey) {
        val count = (dismissals[key] ?: 0) + 1
        dismissals[key] = count
        if (count >= config.dismissalsToMute) {
            muted += key
            if (activeKey == key) clearActive()
        }
    }

    /** Al cambiar de escena o reabrir el visor. No borra los descartes: esos son del usuario. */
    fun reset() {
        clearActive()
        pendingKey = null
        lastEmitAt = Long.MIN_VALUE
        exhaustedKey = null
    }

    // ------------------------------------------------------------------ interno

    private fun signalFor(advice: Advice): HapticSignal = when (advice) {
        is Advice.SubjectClipped -> HapticSignal.Edge
        else -> HapticSignal.Drift(advice.magnitude.coerceIn(0f, 1f))
    }

    private fun emit(
        now: Long,
        advice: Advice,
        signal: HapticSignal,
        updateActive: Boolean,
    ): CoachOutput {
        emissions++
        if (updateActive) {
            activeAdvice = advice
            lastEmitAt = now
        }
        return CoachOutput.Speak(advice, signal)
    }

    private fun silent(reason: SilenceReason): CoachOutput {
        silences[reason] = (silences[reason] ?: 0) + 1
        return CoachOutput.Silent(reason)
    }

    private fun clearActive() {
        activeKey = null
        activeAdvice = null
        activeSince = 0
        activeMagnitude = 0f
    }
}
