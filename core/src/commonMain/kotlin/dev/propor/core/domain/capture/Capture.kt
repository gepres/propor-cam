package dev.propor.core.domain.capture

import dev.propor.core.domain.advice.Advice
import dev.propor.core.domain.advice.AdviceKey
import dev.propor.core.domain.geometry.AspectRatio
import dev.propor.core.domain.geometry.Degrees
import dev.propor.core.domain.guide.GuideKind
import dev.propor.core.domain.scene.SceneType
import kotlin.jvm.JvmInline

/** Identificador de una captura. Opaco: lo genera el almacen, el dominio solo lo transporta. */
@JvmInline
value class CaptureId(val value: String)

/** En que formato se guarda. RAW no se da por hecho: se consulta a las capacidades. */
enum class CaptureFormat { JPEG, HEIF, RAW_DNG, RAW_PLUS_JPEG }

/** Que quiere el usuario al pulsar el disparador. */
data class CaptureIntent(
    val format: CaptureFormat = CaptureFormat.JPEG,
    val aspect: AspectRatio = AspectRatio.R4_3,
    /** Guia activa en el momento del disparo. Va al sidecar. */
    val activeGuide: GuideKind = GuideKind.THIRDS,
    val flashEnabled: Boolean = false,
    val timerSeconds: Int = 0,
)

/** Lo que puede salir mal al capturar. Errores tipados: nada de excepciones para flujo normal. */
sealed interface CaptureError {
    data object NoPermission : CaptureError
    data object CameraUnavailable : CaptureError
    data object DeviceBusy : CaptureError
    data object StorageFull : CaptureError

    /** El dispositivo no puede hacer lo que se le pide. Nombra la capacidad concreta. */
    data class Unsupported(val feature: String) : CaptureError

    /** Fallo del adaptador que no encaja en los anteriores. El detalle es para la traza. */
    data class Platform(val detail: String) : CaptureError
}

/** Ajustes con los que se tomo la foto. Se guardan tal cual para poder aprender de ellos. */
data class CaptureSettings(
    val isoValue: Int? = null,
    val exposureTimeNanos: Long? = null,
    val exposureCompensation: Float = 0f,
    val whiteBalanceKelvin: Int? = null,
    val zoomRatio: Float = 1f,
    val lensId: String? = null,
)

/**
 * El expediente de una captura: todo lo que la app supo de ella en el momento de dispararla.
 *
 * **Es la materia prima del perfil del fotografo (E9), y por eso se guarda desde R1** aunque el
 * perfil llegue en R2: sin este historial el perfil no se puede reconstruir hacia atras, y el
 * unico foso defendible del producto se retrasaria noventa dias.
 *
 * No contiene la imagen ni nada derivado de ella. Solo hechos sobre la composicion.
 */
data class CaptureSidecar(
    val activeGuide: GuideKind,
    val aspect: AspectRatio,
    val sceneType: SceneType,
    /** Consejos que el coach llego a emitir mientras se encuadraba esta foto. */
    val adviceShown: List<AdviceKey> = emptyList(),
    /** Cuales de ellos el usuario atendio antes de disparar. Es la senal de aprendizaje. */
    val adviceAccepted: List<AdviceKey> = emptyList(),
    /** Inclinacion en el instante del disparo. Alimenta el sesgo de inclinacion del perfil. */
    val tiltAtCapture: Degrees? = null,
    /** Donde quedo el sujeto principal, normalizado. Alimenta la tasa de centrado. */
    val subjectCenterX: Float? = null,
    val subjectCenterY: Float? = null,
    val settings: CaptureSettings = CaptureSettings(),
    /** Version del modelo de vision que produjo la lectura. Sin esto no hay trazabilidad. */
    val visionModelVersion: String = "unversioned",
    val capturedAtMillis: Long = 0L,
) {
    /**
     * Fraccion de consejos atendidos. Es el numerador de la metrica North Star y la senal con
     * la que el perfil aprende que reglas le sirven a esta persona.
     */
    val adviceAcceptanceRate: Float
        get() = if (adviceShown.isEmpty()) 0f else adviceAccepted.size.toFloat() / adviceShown.size

    companion object {
        /** Construye el sidecar a partir de los consejos realmente emitidos en la sesion. */
        fun from(
            activeGuide: GuideKind,
            aspect: AspectRatio,
            sceneType: SceneType,
            shown: List<Advice>,
            accepted: List<Advice>,
        ): CaptureSidecar = CaptureSidecar(
            activeGuide = activeGuide,
            aspect = aspect,
            sceneType = sceneType,
            adviceShown = shown.map { it.key }.distinct(),
            adviceAccepted = accepted.map { it.key }.distinct(),
        )
    }
}

/** Una foto ya guardada, con su expediente. La imagen vive en el almacen, no aqui. */
data class Capture(
    val id: CaptureId,
    val format: CaptureFormat,
    val sidecar: CaptureSidecar,
)
