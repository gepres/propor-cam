package dev.propor.android.adapters

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import dev.propor.core.domain.geometry.Confidence
import dev.propor.core.domain.geometry.NormPoint
import dev.propor.core.domain.geometry.NormRect
import dev.propor.core.domain.port.SceneVisionPort
import dev.propor.core.domain.port.VisionSignal
import dev.propor.core.domain.scene.Direction
import dev.propor.core.domain.scene.FaceReading
import dev.propor.core.domain.scene.SceneReading
import dev.propor.core.domain.scene.SceneType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs

/**
 * Lo que la app entiende de la escena, sobre ML Kit.
 *
 * Implementa a la vez el puerto del dominio y el analizador de CameraX. Esa doble cara es lo que
 * permite que el adaptador de camara y este **no se conozcan**: la camara recibe un
 * `ImageAnalysis.Analyzer` cualquiera y quien los une es la composicion raiz.
 *
 * **Ningun `ImageProxy` sale de esta clase.** Entra un buffer de Android y sale un `SceneReading`
 * del dominio, ya normalizado. Si el buffer cruzara la frontera, el nucleo tendria que saber de
 * ciclos de vida y de cierres.
 *
 * ### Que falta respecto a iOS, y conviene tenerlo presente
 *
 * Android **no trae equivalente a `VNDetectHorizonRequest`**. Aqui el horizonte visual no existe
 * todavia: se emite `null` y la fusion del dominio (`HorizonFusion`) cae al giroscopio, que es
 * exactamente el degradado elegante para el que se diseno. La deteccion de lineas dominantes
 * llega con H4.4 y hara falta implementarla, no solo llamarla.
 */
class MlKitSceneVisionAdapter : SceneVisionPort, ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // FAST y no ACCURATE: en el visor manda la latencia. Un contorno un poco menos
            // preciso no cambia el consejo; ocho milisegundos de mas si.
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .enableTracking()
            .build(),
    )

    private val _readings = MutableSharedFlow<SceneReading>(
        replay = 1,
        extraBufferCapacity = 0,
        // Descarte, nunca cola: al coach solo le sirve lo que ve AHORA.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val readings: Flow<SceneReading> = _readings.asSharedFlow()

    override val modelVersion: String = MODEL_VERSION

    @Volatile
    private var enabled: Set<VisionSignal> = setOf(VisionSignal.FACES)

    private var frameCounter = 0L

    /** Ultima lectura util. Sostiene las senales lentas entre ejecuciones para que no salten. */
    private var lastFaces: List<FaceReading> = emptyList()

    override fun enableSignals(signals: Set<VisionSignal>) {
        enabled = signals
    }

    /**
     * Por ahora se declara lo que ya funciona. Cuando haya mas detectores, esto tiene que
     * MEDIR en el dispositivo y no adivinar por modelo de telefono: en gama media el usuario
     * vera un coach algo menos listo, nunca un visor lento.
     */
    override suspend fun affordableSignals(): Set<VisionSignal> = setOf(VisionSignal.FACES)

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        frameCounter++

        // Planificador por rotacion: los rostros no hacen falta en cada frame. A 30 fps, cada
        // dos frames son 66 ms, muy por debajo de lo que tarda una persona en recomponer.
        if (VisionSignal.FACES !in enabled || frameCounter % FACE_EVERY_N_FRAMES != 0L) {
            emit(lastFaces, imageProxy)
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)

        // Tras rotar, el ancho y el alto se intercambian en 90 y 270 grados. Normalizar con las
        // dimensiones equivocadas descoloca cada rostro y el coach opinaria sobre un encuadre
        // que no existe.
        val width = if (rotation == 90 || rotation == 270) mediaImage.height else mediaImage.width
        val height = if (rotation == 90 || rotation == 270) mediaImage.width else mediaImage.height

        detector.process(input)
            .addOnSuccessListener { faces ->
                lastFaces = faces.map { it.toDomain(width.toFloat(), height.toFloat()) }
                emit(lastFaces, imageProxy)
            }
            .addOnFailureListener {
                // Un fallo del detector no puede parar el visor: se emite lo ultimo conocido,
                // pero marcado como poco fiable para que el coach se calle.
                emit(lastFaces, imageProxy, analysisSucceeded = false)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    fun release() {
        detector.close()
    }

    // ------------------------------------------------------------------ interno

    /**
     * @param analysisSucceeded false solo cuando el detector fallo. **No** cuando no hay caras.
     *
     * Que no haya rostros no hace la lectura menos fiable: hace que no haya rostros. Ligar la
     * confianza a su presencia dejaba al coach mudo en paisaje, que es justo donde el horizonte
     * mas importa. El fallo no daba error de ninguna clase: simplemente el coach no hablaba.
     */
    private fun emit(
        faces: List<FaceReading>,
        imageProxy: ImageProxy,
        analysisSucceeded: Boolean = true,
    ) {
        _readings.tryEmit(
            SceneReading(
                faces = faces,
                // El horizonte visual no existe todavia en Android: lo aporta el sensor a
                // traves de HorizonFusion, en el dominio.
                horizon = null,
                sceneType = if (faces.isNotEmpty()) SceneType.PORTRAIT else SceneType.UNKNOWN,
                confidence = if (analysisSucceeded) Confidence(0.9f) else Confidence(0.4f),
                timestampMs = imageProxy.imageInfo.timestamp,
            ),
        )
    }

    private fun Face.toDomain(imageWidth: Float, imageHeight: Float): FaceReading {
        val box = boundingBox

        val bounds = NormRect.of(
            left = box.left / imageWidth,
            top = box.top / imageHeight,
            width = box.width() / imageWidth,
            height = box.height() / imageHeight,
        )

        val left = getLandmark(FaceLandmark.LEFT_EYE)?.position
        val right = getLandmark(FaceLandmark.RIGHT_EYE)?.position

        return FaceReading(
            bounds = bounds,
            leftEye = left?.let { NormPoint.clamped(it.x / imageWidth, it.y / imageHeight) },
            rightEye = right?.let { NormPoint.clamped(it.x / imageWidth, it.y / imageHeight) },
            gaze = gazeFrom(headEulerAngleY),
            confidence = Confidence.CERTAIN,
        )
    }

    /**
     * Direccion de la mirada a partir del giro de la cabeza.
     *
     * Por debajo del umbral se devuelve null a proposito: una cabeza casi de frente no tiene
     * "lado hacia el que mira", y forzar uno haria que el consejo de espacio de mirada saltara
     * de izquierda a derecha con cualquier micromovimiento.
     *
     * **Pendiente de validar con una persona delante:** el signo del angulo de ML Kit se presta
     * a confusion y aqui esta puesto segun la documentacion, no segun una prueba. Si en el
     * dispositivo sale al reves, se invierte esta unica linea.
     */
    private fun gazeFrom(headEulerAngleY: Float): Direction? = when {
        abs(headEulerAngleY) < GAZE_THRESHOLD_DEG -> null
        headEulerAngleY > 0f -> Direction.RIGHT
        else -> Direction.LEFT
    }

    private companion object {
        const val MODEL_VERSION = "mlkit-face-fast-1"
        const val FACE_EVERY_N_FRAMES = 2L
        const val GAZE_THRESHOLD_DEG = 12f
    }
}
