package dev.propor.core.domain.advice

/**
 * La gramatica haptica: el vocabulario completo del coach silencioso.
 *
 * Es un conjunto CERRADO a proposito. Seis senales, aprendibles en un dia y consistentes para
 * siempre. Anadir una setima exige una buena razon: un vocabulario que crece deja de ser un
 * lenguaje y pasa a ser ruido.
 *
 * Vive en el dominio y no en el adaptador porque es una decision de producto, no de plataforma:
 * iOS y Android deben vibrar lo mismo.
 */
sealed interface HapticSignal {

    /** Un golpe seco y ligero. Cruzaste una linea de la guia. */
    data object Tick : HapticSignal

    /** Dos golpes rapidos. Sujeto en la interseccion, u horizonte a nivel. */
    data object Lock : HapticSignal

    /**
     * Vibracion continua cuya intensidad crece con el error. Te estas desviando.
     *
     * Es la unica senal continua, y el throttler la corta a los tres segundos aunque el error
     * siga. Nunca convertir el telefono en una alarma.
     */
    data class Drift(val intensity: Float) : HapticSignal {
        init {
            require(intensity in 0f..1f) { "intensidad fuera de rango: $intensity" }
        }
    }

    /** Un golpe fuerte. Algo importante se sale del encuadre. */
    data object Edge : HapticSignal

    /** Subida suave. Todo alineado: dispara. */
    data object Ready : HapticSignal

    /** Golpe seco doble. Captura confirmada. */
    data object Shutter : HapticSignal
}
