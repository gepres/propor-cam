package dev.propor.android.app

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import dev.propor.android.adapters.AndroidClockAdapter
import dev.propor.android.adapters.CameraXCameraAdapter
import dev.propor.android.adapters.FileSidecarStore
import dev.propor.android.adapters.MlKitSceneVisionAdapter
import dev.propor.android.adapters.SensorMotionAdapter
import dev.propor.android.adapters.VibratorHapticAdapter
import dev.propor.core.application.usecase.EvaluateLiveFrame
import dev.propor.core.application.usecase.LiveFeedback
import dev.propor.core.domain.advice.AdviceEngine
import dev.propor.core.domain.advice.AdviceThrottler
import dev.propor.core.domain.advice.CoachOutput
import dev.propor.core.domain.advice.CoachProfile
import dev.propor.core.domain.advice.HapticSignal
import dev.propor.core.domain.capture.Capture
import dev.propor.core.domain.capture.CaptureIntent
import dev.propor.core.domain.capture.CaptureSidecar
import dev.propor.core.domain.geometry.AspectRatio
import dev.propor.core.domain.geometry.Degrees
import dev.propor.core.domain.guide.GuideKind
import dev.propor.core.domain.port.DeviceTilt
import dev.propor.core.domain.scene.HorizonFusion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * La composicion raiz de la sesion de visor.
 *
 * **Es el unico sitio del proyecto donde se conocen todas las piezas.** Aqui se enchufan los
 * adaptadores concretos a los puertos del dominio; en cualquier otro lugar solo se ven
 * interfaces. Por eso el adaptador de camara y el de vision no se importan entre si: se
 * encuentran exclusivamente en esta clase.
 *
 * Cambiar ML Kit por otro detector, o CameraX por Camera2 puro, se hace modificando estas
 * lineas y nada mas.
 */
class ProporSession(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    private val scope: CoroutineScope,
) {
    val camera = CameraXCameraAdapter(context = context, lifecycleOwner = lifecycleOwner)

    private val vision = MlKitSceneVisionAdapter()
    private val motion = SensorMotionAdapter(context)
    private val haptics = VibratorHapticAdapter(context)
    private val store = FileSidecarStore(context)

    private val fusion = HorizonFusion()

    private val evaluate = EvaluateLiveFrame(
        engine = AdviceEngine(),
        throttler = AdviceThrottler(AndroidClockAdapter),
        haptics = haptics,
    )

    private val _feedback = MutableStateFlow(LiveFeedback(CoachOutput.Silent(NOTHING)))
    val feedback: StateFlow<LiveFeedback> = _feedback.asStateFlow()

    /**
     * Inclinacion del ultimo frame procesado.
     *
     * Se guarda para poder anotarla en el expediente de la captura: el sesgo de inclinacion
     * —"tuerces 1,2 grados a la derecha"— es la primera metrica del perfil del fotografo, y no
     * se puede calcular a posteriori si no consta en cada foto.
     */
    @Volatile
    private var lastTilt: Degrees? = null

    private val _guide = MutableStateFlow(GuideKind.THIRDS)
    val guide: StateFlow<GuideKind> = _guide.asStateFlow()

    var hapticsEnabled: Boolean = true
    var aspect: AspectRatio = AspectRatio.R4_3.rotated()
    var profile: CoachProfile = CoachProfile.NEUTRAL

    init {
        // El analizador se inyecta desde fuera: es lo que mantiene desacoplados camara y vision.
        camera.attachAnalyzer(vision)
    }

    fun selectGuide(kind: GuideKind) {
        _guide.value = kind
    }

    /**
     * Dispara.
     *
     * El expediente de la foto se construye **con lo que de verdad paso durante el encuadre**:
     * que consejos llego a ver el usuario y cuales atendio. Esa es la materia prima del perfil
     * del fotografo (E9), y por eso se recoge desde R1 aunque el perfil llegue despues: sin
     * historial no se puede reconstruir hacia atras.
     */
    suspend fun capture(): Result<Capture> {
        val (shown, accepted) = evaluate.sessionAdvice()
        val reading = _feedback.value

        val result = camera.capture(
            CaptureIntent(activeGuide = _guide.value, aspect = aspect),
        )

        result.onSuccess {
            if (hapticsEnabled) haptics.play(HapticSignal.Shutter)
        }

        // Cada captura cierra un ciclo de encuadre: los consejos de la siguiente foto son otros.
        evaluate.reset()

        return result.map { capture ->
            val enriched = capture.copy(
                sidecar = capture.sidecar.copy(
                    adviceShown = shown,
                    adviceAccepted = accepted,
                    tiltAtCapture = lastTilt,
                    subjectCenterX = reading.suggestedAnchor?.x?.value,
                    subjectCenterY = reading.suggestedAnchor?.y?.value,
                    visionModelVersion = vision.modelVersion,
                ),
            )

            // Persistir el expediente es lo que convierte esto en una app con memoria. Si
            // falla, la foto ya esta guardada y el usuario no pierde nada: solo se pierde un
            // dato de aprendizaje, y eso no justifica hacer fallar una captura.
            store.attachSidecar(enriched.id, enriched.sidecar)

            enriched
        }
    }

    /** Expedientes recientes. Los usara la galeria y, mas adelante, el `ProfileLearner`. */
    suspend fun recentCaptures() = store.recent()

    /**
     * Arranca el bucle del coach.
     *
     * El sensor arranca con un valor por defecto para que la combinacion emita desde el primer
     * frame: si se esperase a la primera lectura del giroscopio, el visor se quedaria sin coach
     * durante los primeros milisegundos y el usuario lo notaria como un arranque en falso.
     */
    fun start() {
        scope.launch { motion.start() }

        combine(
            vision.readings,
            motion.tilt.onStart { emit(DeviceTilt(Degrees.ZERO, Degrees.ZERO)) },
            _guide,
        ) { reading, tilt, guide ->
            val horizon = fusion.fuse(sensor = tilt, visual = reading.horizon)
            lastTilt = horizon?.angle
            evaluate(
                reading = reading.copy(horizon = horizon),
                activeGuide = guide,
                aspect = aspect,
                profile = profile,
                hapticsEnabled = hapticsEnabled,
            )
        }
            .onEach { _feedback.value = it }
            .launchIn(scope)
    }

    suspend fun stop() {
        motion.stop()
        camera.close()
        evaluate.reset()
        fusion.reset()
    }

    fun release() {
        vision.release()
        camera.release()
    }

    private companion object {
        val NOTHING = dev.propor.core.domain.advice.SilenceReason.NOTHING_TO_SAY
    }
}
