package dev.propor.core.domain.port

import dev.propor.core.domain.capture.Capture
import dev.propor.core.domain.capture.CaptureError
import dev.propor.core.domain.capture.CaptureIntent
import dev.propor.core.domain.geometry.AspectRatio
import dev.propor.core.domain.geometry.NormPoint
import kotlinx.coroutines.flow.StateFlow

/** Camara frontal o trasera. */
enum class LensFacing { BACK, FRONT }

/**
 * Una lente concreta del dispositivo.
 *
 * `id` es opaco y lo pone el adaptador: en Android es el id de camara del sistema, en iOS el
 * tipo de dispositivo. El dominio no lo interpreta, solo lo transporta.
 */
data class LensInfo(
    val id: String,
    val facing: LensFacing,
    /** Zoom equivalente frente a la lente principal: 0.5x, 1x, 3x... */
    val zoomFactor: Float,
    val focalLengthMm: Float? = null,
)

/** Estado observable de la camara. La UI se dibuja desde aqui, nunca desde el adaptador. */
data class CameraState(
    val isOpen: Boolean = false,
    val activeLens: LensInfo? = null,
    val aspect: AspectRatio = AspectRatio.R4_3,
    val zoomRatio: Float = 1f,
    val exposureCompensation: Float = 0f,
    val isCapturing: Boolean = false,
    val error: CaptureError? = null,
)

/**
 * Control de la camara del dispositivo.
 *
 * **Este puerto NO expone los frames al dominio, y es deliberado.** Un frame es un buffer de
 * plataforma (`ImageProxy` en Android, `CMSampleBuffer` en iOS) con un ciclo de vida que hay
 * que respetar; dejarlo cruzar la frontera metería gestion de memoria de plataforma dentro del
 * nucleo y lo ataria a los dos SDK a la vez.
 *
 * La conexion camara -> vision ocurre entera en la capa de adaptadores. Lo que sube al dominio
 * es [SceneVisionPort], que ya viene interpretado: `SceneReading`, sin buffers.
 */
interface CameraPort {

    val state: StateFlow<CameraState>

    /** Abre la sesion. Idempotente: llamarlo dos veces no debe reabrir nada. */
    suspend fun open(facing: LensFacing = LensFacing.BACK): Result<Unit>

    /** Cierra y libera. Debe soportar que la app pase a segundo plano y vuelva. */
    suspend fun close()

    suspend fun capture(intent: CaptureIntent): Result<Capture>

    /** Enfoque por toque. El punto llega normalizado; el adaptador lo convierte a su sistema. */
    suspend fun focusAt(point: NormPoint): Result<Unit>

    suspend fun setExposureCompensation(ev: Float): Result<Unit>

    suspend fun selectLens(lensId: String): Result<Unit>

    suspend fun setZoom(ratio: Float): Result<Unit>

    suspend fun setAspect(aspect: AspectRatio): Result<Unit>
}
