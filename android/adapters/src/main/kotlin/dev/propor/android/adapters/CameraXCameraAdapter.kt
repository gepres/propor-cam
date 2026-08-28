package dev.propor.android.adapters

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.camera.core.AspectRatio as CameraXAspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import dev.propor.core.domain.capture.Capture
import dev.propor.core.domain.capture.CaptureError
import dev.propor.core.domain.capture.CaptureFormat
import dev.propor.core.domain.capture.CaptureId
import dev.propor.core.domain.capture.CaptureIntent
import dev.propor.core.domain.capture.CaptureSidecar
import dev.propor.core.domain.geometry.AspectRatio
import dev.propor.core.domain.geometry.NormPoint
import dev.propor.core.domain.port.CameraPort
import dev.propor.core.domain.port.CameraState
import dev.propor.core.domain.port.LensFacing
import dev.propor.core.domain.port.LensInfo
import dev.propor.core.domain.scene.SceneType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * La camara del dispositivo sobre CameraX.
 *
 * **Este adaptador no deja que ningun frame llegue al dominio.** El `ImageProxy` es un buffer
 * con ciclo de vida propio que hay que cerrar; si cruzara la frontera, el nucleo tendria que
 * saber de gestion de memoria de Android. En su lugar, el analizador se **inyecta desde fuera**
 * y es el adaptador de vision quien lo implementa. Asi ninguno de los dos adaptadores importa
 * al otro: los conecta la composicion raiz.
 *
 * El analisis usa `STRATEGY_KEEP_ONLY_LATEST`: si la vision se retrasa, se **descartan** frames.
 * Encolarlos produciria latencia acumulada, calentamiento y un visor con tirones. La foto manda;
 * el consejo es opcional.
 */
class CameraXCameraAdapter(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : CameraPort {

    private val _state = MutableStateFlow(CameraState())
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var analyzer: ImageAnalysis.Analyzer? = null
    private var facing: LensFacing = LensFacing.BACK

    /**
     * Hilo propio para el analisis. Nunca el principal: un detector que tarde 20 ms en el hilo
     * de UI se lleva por delante seis frames de visor.
     */
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** La superficie de previsualizacion la aporta la UI. Se puede llamar antes o despues de open. */
    fun attachPreview(provider: Preview.SurfaceProvider) {
        surfaceProvider = provider
        preview?.surfaceProvider = provider
    }

    /**
     * Conecta el analizador de vision. Se inyecta desde la composicion raiz para que este
     * adaptador no tenga que conocer al de vision ni al reves.
     */
    fun attachAnalyzer(analyzer: ImageAnalysis.Analyzer) {
        this.analyzer = analyzer
        imageAnalysis?.setAnalyzer(analysisExecutor, analyzer)
    }

    override suspend fun open(facing: LensFacing): Result<Unit> = withContext(mainDispatcher) {
        runCatching {
            this@CameraXCameraAdapter.facing = facing
            val cameraProvider = provider ?: awaitProvider().also { provider = it }

            // Rebind completo: es la forma soportada de cambiar de lente o de formato sin
            // recrear la sesion entera ni perder el estado de la UI.
            cameraProvider.unbindAll()

            val resolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspectStrategy(_state.value.aspect))
                .build()

            preview = Preview.Builder()
                .setResolutionSelector(resolution)
                .build()
                .also { p -> surfaceProvider?.let { p.surfaceProvider = it } }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(resolution)
                .build()

            imageAnalysis = ImageAnalysis.Builder()
                // Descarte, nunca cola.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolution)
                .build()
                .also { analysis -> analyzer?.let { analysis.setAnalyzer(analysisExecutor, it) } }

            val selector = CameraSelector.Builder()
                .requireLensFacing(
                    if (facing == LensFacing.FRONT) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    },
                )
                .build()

            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageCapture,
                imageAnalysis,
            )

            _state.value = _state.value.copy(
                isOpen = true,
                activeLens = currentLensInfo(),
                zoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f,
                error = null,
            )
        }.recoverCatching { throwable ->
            _state.value = _state.value.copy(
                isOpen = false,
                error = CaptureError.Platform(throwable.message ?: "no se pudo abrir la camara"),
            )
            throw throwable
        }
    }

    override suspend fun close() {
        withContext(mainDispatcher) {
            provider?.unbindAll()
            camera = null
            _state.value = _state.value.copy(isOpen = false, isCapturing = false)
        }
    }

    override suspend fun capture(intent: CaptureIntent): Result<Capture> {
        val capture = imageCapture
            ?: return Result.failure(IllegalStateException("la camara no esta abierta"))

        if (intent.format == CaptureFormat.RAW_DNG || intent.format == CaptureFormat.RAW_PLUS_JPEG) {
            // Se falla explicito en vez de guardar en otro formato a espaldas del usuario.
            // RAW llega en R3 y solo donde el dispositivo lo soporte de verdad.
            return Result.failure(
                UnsupportedOperationException("RAW no disponible todavia (PCA-55)"),
            )
        }

        _state.value = _state.value.copy(isCapturing = true)

        return runCatching {
            val name = "PROPOR_${System.currentTimeMillis()}"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PROPOR")
            }
            val options = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ).build()

            val uri = suspendCancellableCoroutine { continuation ->
                capture.takePicture(
                    options,
                    analysisExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            continuation.resume(output.savedUri?.toString() ?: name)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (continuation.isActive) continuation.resume("")
                        }
                    },
                )
            }

            if (uri.isEmpty()) error("la camara no pudo guardar la foto")

            Capture(
                id = CaptureId(uri),
                format = CaptureFormat.JPEG,
                // El expediente completo lo rellena el caso de uso, que es quien sabe que
                // consejos se emitieron y cuales atendio el usuario.
                sidecar = CaptureSidecar(
                    activeGuide = intent.activeGuide,
                    aspect = intent.aspect,
                    sceneType = SceneType.UNKNOWN,
                    capturedAtMillis = System.currentTimeMillis(),
                ),
            )
        }.also {
            _state.value = _state.value.copy(isCapturing = false)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    override suspend fun focusAt(point: NormPoint): Result<Unit> = runCatching {
        val control = camera?.cameraControl ?: error("la camara no esta abierta")
        // El dominio habla en coordenadas normalizadas; aqui es donde se traducen.
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val meteringPoint = factory.createPoint(point.x.value, point.y.value)
        control.startFocusAndMetering(
            FocusMeteringAction.Builder(meteringPoint).build(),
        )
        Unit
    }

    override suspend fun setExposureCompensation(ev: Float): Result<Unit> = runCatching {
        val cam = camera ?: error("la camara no esta abierta")
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        val step = cam.cameraInfo.exposureState.exposureCompensationStep.toFloat()
        val index = if (step == 0f) 0 else (ev / step).toInt()
        cam.cameraControl.setExposureCompensationIndex(
            index.coerceIn(range.lower, range.upper),
        )
        _state.value = _state.value.copy(exposureCompensation = ev)
    }

    override suspend fun selectLens(lensId: String): Result<Unit> = runCatching {
        // CameraX selecciona por facing y por zoom, no por id de camara del sistema. Cambiar de
        // lente fisica se expresa como cambio de zoom equivalente.
        val target = lensId.toFloatOrNull() ?: error("id de lente no reconocido: $lensId")
        setZoom(target).getOrThrow()
    }

    override suspend fun setZoom(ratio: Float): Result<Unit> = runCatching {
        val cam = camera ?: error("la camara no esta abierta")
        val zoomState = cam.cameraInfo.zoomState.value ?: error("sin estado de zoom")
        val clamped = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        cam.cameraControl.setZoomRatio(clamped)
        _state.value = _state.value.copy(zoomRatio = clamped)
    }

    override suspend fun setAspect(aspect: AspectRatio): Result<Unit> {
        _state.value = _state.value.copy(aspect = aspect)
        // Cambiar de formato exige rebind: CameraX fija la resolucion al vincular los casos de uso.
        return open(facing)
    }

    /** Libera el hilo de analisis. Se llama al destruir el propietario del ciclo de vida. */
    fun release() {
        analysisExecutor.shutdown()
    }

    // ------------------------------------------------------------------ interno

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { continuation.resume(future.get()) },
                androidx.core.content.ContextCompat.getMainExecutor(context),
            )
        }

    private fun currentLensInfo(): LensInfo? {
        val info = camera?.cameraInfo ?: return null
        return LensInfo(
            id = (info.zoomState.value?.zoomRatio ?: 1f).toString(),
            facing = facing,
            zoomFactor = info.zoomState.value?.zoomRatio ?: 1f,
        )
    }

    private fun aspectStrategy(aspect: AspectRatio): AspectRatioStrategy = when {
        aspect.ratio > 1.6f -> AspectRatioStrategy(
            CameraXAspectRatio.RATIO_16_9,
            AspectRatioStrategy.FALLBACK_RULE_AUTO,
        )
        else -> AspectRatioStrategy(
            CameraXAspectRatio.RATIO_4_3,
            AspectRatioStrategy.FALLBACK_RULE_AUTO,
        )
    }
}
