package dev.propor.core.domain.port

import dev.propor.core.domain.scene.SceneReading
import kotlinx.coroutines.flow.Flow

/**
 * Las senales que la vision puede producir.
 *
 * No caben todas en cada frame: el presupuesto es de 33 ms para el conjunto. El adaptador las
 * reparte con un planificador por rotacion —inclinacion y horizonte cada frame, rostro cada 2,
 * saliencia cada 5, tipo de escena cada 15— con seguimiento entre ejecuciones para que las
 * lentas no salten en pantalla.
 *
 * El orden de esta enumeracion es el orden de valor: cuando el dispositivo no da para todo, se
 * apagan **por el final**. El horizonte es lo ultimo que se sacrifica.
 */
enum class VisionSignal {
    HORIZON,
    FACES,
    BODIES,
    SALIENCY,
    LINES,
    SCENE_TYPE,
}

/**
 * Lo que la app entiende de la escena mientras se encuadra.
 *
 * El adaptador se suscribe al stream de frames de la camara, ejecuta los detectores y publica
 * `SceneReading` ya interpretado. Ningun buffer de plataforma cruza esta frontera.
 *
 * **Descarte, nunca cola.** Si la vision se retrasa, se saltan frames. Encolar trabajo en una
 * app de camara es el camino directo a la latencia acumulada, al calentamiento y a un visor con
 * tirones. La foto manda; el consejo es opcional.
 */
interface SceneVisionPort {

    /** Lecturas de escena. Conflated: al consumidor solo le interesa la ultima. */
    val readings: Flow<SceneReading>

    /** Enciende o apaga senales. La UI lo usa segun la guia activa y el modo. */
    fun enableSignals(signals: Set<VisionSignal>)

    /**
     * Senales que el dispositivo puede sostener dentro del presupuesto de tiempo.
     *
     * El adaptador la calcula midiendo, no adivinando por modelo de telefono. En gama media el
     * usuario vera un coach algo menos listo, nunca un visor lento.
     */
    suspend fun affordableSignals(): Set<VisionSignal>

    /** Version del modelo activo. Va al sidecar: sin esto no hay trazabilidad de regresiones. */
    val modelVersion: String
}
