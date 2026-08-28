package dev.propor.core.domain.port

import dev.propor.core.domain.advice.HapticSignal
import dev.propor.core.domain.capture.CaptureId
import dev.propor.core.domain.capture.CaptureSidecar
import dev.propor.core.domain.geometry.Degrees
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------- capacidades

/** Lo que ESTE dispositivo concreto puede hacer. Nada se da por hecho. */
data class CameraCapabilities(
    val lenses: List<LensInfo> = emptyList(),
    val supportsRaw: Boolean = false,
    val supportsManualIso: Boolean = false,
    val isoRange: IntRange? = null,
    val supportsManualExposureTime: Boolean = false,
    val exposureTimeRangeNanos: LongRange? = null,
    val supportsManualFocus: Boolean = false,
    val supportsManualWhiteBalance: Boolean = false,
    val whiteBalanceRangeKelvin: IntRange? = null,
    val supportsFlash: Boolean = false,
    val maxZoomRatio: Float = 1f,
) {
    /** Si esto es false, el modo PRO no debe ni aparecer en la interfaz. */
    val supportsProMode: Boolean
        get() = supportsManualIso && supportsManualExposureTime && supportsManualFocus
}

/**
 * Que permite el dispositivo.
 *
 * Existe desde el primer dia por una razon concreta: RAW y los controles manuales **no son
 * universales**. En Android hay que consultar las caracteristicas de cada camara, y en iOS
 * ProRAW exige iPhone 12 Pro o superior.
 *
 * La interfaz se construye desde lo que devuelve este puerto, **nunca desde una lista fija con
 * controles en gris**. Si el telefono no da foco manual, ese control no existe. Prometer en la
 * tienda lo que un dispositivo concreto no puede dar es la via rapida a las resenas de una
 * estrella (riesgo R-03).
 */
interface CameraCapabilitiesPort {
    suspend fun capabilities(facing: LensFacing = LensFacing.BACK): CameraCapabilities
}

// ---------------------------------------------------------------- sensores

/** Inclinacion del dispositivo segun los sensores inerciales. */
data class DeviceTilt(
    /** Giro alrededor del eje de la vista: es el que tuerce el horizonte. */
    val roll: Degrees,
    /** Cabeceo: alimenta la deteccion de verticales convergentes en arquitectura. */
    val pitch: Degrees,
    /** False mientras el usuario esta moviendo el telefono. Evita avisos sobre datos inestables. */
    val isStable: Boolean = true,
)

/**
 * Inclinacion desde el giroscopio.
 *
 * Mide el telefono, no la escena: es preciso pero puede no ser lo relevante. Una foto de un
 * barco inclinado sobre un mar recto hay que corregirla por el mar, no por el sensor. Por eso
 * el horizonte final se fusiona con la lectura visual, dando prioridad a la vision cuando esta
 * segura (H4.3).
 */
interface MotionSensorPort {
    val tilt: Flow<DeviceTilt>
    suspend fun start()
    suspend fun stop()
}

// ---------------------------------------------------------------- haptica

/**
 * El canal del coach silencioso.
 *
 * Debe funcionar con la pantalla apagada: es lo que permite el encuadre haptico para personas
 * con baja vision (H6.3), y esa misma capacidad es la que hace el coach menos intrusivo para
 * todos los demas.
 */
interface HapticPort {
    /** True si el dispositivo tiene motor haptico util. Si no, la UI cae al canal visual o de voz. */
    val isAvailable: Boolean

    fun play(signal: HapticSignal)

    /** Corta cualquier senal continua en curso. */
    fun stop()
}

// ---------------------------------------------------------------- almacen

/** Una captura guardada, con lo necesario para pintarla en la galeria. */
data class StoredCapture(
    val id: CaptureId,
    val uri: String,
    val sidecar: CaptureSidecar,
    val capturedAtMillis: Long,
)

/**
 * Donde viven las fotos y sus expedientes.
 *
 * **El original jamas se toca.** Todo lo que la app anade es metadato adjunto y reversible: esa
 * es la promesa de reversibilidad total, que es una caracteristica del producto y no una nota
 * legal.
 */
interface PhotoStorePort {
    /**
     * Ata el expediente a una imagen que la camara ya persistio.
     *
     * La imagen NO pasa por este puerto: el adaptador de camara la escribe directamente en el
     * almacen del sistema, que es lo que hacen CameraX y AVFoundation de forma nativa. Hacerla
     * viajar como `ByteArray` por el dominio significaria tener una foto RAW de 25 MB en memoria
     * sin ninguna razon.
     */
    suspend fun attachSidecar(id: CaptureId, sidecar: CaptureSidecar): Result<Unit>

    suspend fun recent(limit: Int = 50): List<StoredCapture>
    suspend fun sidecarOf(id: CaptureId): CaptureSidecar?

    /** Historial para el `ProfileLearner`. Solo expedientes: nunca imagenes. */
    suspend fun sidecarsSince(millis: Long): List<CaptureSidecar>

    /** Borrado real, local y remoto. Es un compromiso del producto, no una opcion. */
    suspend fun deleteAll(): Result<Unit>
}
