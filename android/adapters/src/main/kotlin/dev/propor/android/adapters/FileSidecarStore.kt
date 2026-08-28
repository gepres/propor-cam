package dev.propor.android.adapters

import android.content.Context
import dev.propor.core.domain.advice.AdviceKey
import dev.propor.core.domain.capture.CaptureId
import dev.propor.core.domain.capture.CaptureSettings
import dev.propor.core.domain.capture.CaptureSidecar
import dev.propor.core.domain.geometry.AspectRatio
import dev.propor.core.domain.geometry.Degrees
import dev.propor.core.domain.guide.GuideKind
import dev.propor.core.domain.port.PhotoStorePort
import dev.propor.core.domain.port.StoredCapture
import dev.propor.core.domain.scene.SceneType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Guarda el expediente de cada captura en el almacenamiento privado de la app.
 *
 * **Sin esto, cada foto olvida lo que la app supo mientras se encuadraba**, y el perfil del
 * fotografo —el unico foso defendible del producto— no puede acumular nada. El perfil no se
 * reconstruye hacia atras: o los datos se guardan desde el primer disparo, o se pierden.
 *
 * ### Por que archivos y no una base de datos, de momento
 *
 * El plan pide SQLDelight, y llegara: hara falta en cuanto el `ProfileLearner` consulte por
 * rangos de fechas y por tipo de escena. Pero para R1 lo unico que se hace es escribir un
 * expediente por foto y leerlos todos, y para eso una base de datos es infraestructura sin
 * contrapartida.
 *
 * Cambiar de opinion cuesta una clase: nadie fuera de este archivo sabe como se persiste. Es
 * exactamente lo que el puerto existe para permitir.
 *
 * **La imagen no pasa por aqui.** El adaptador de camara la escribe en el almacen del sistema;
 * esto solo guarda hechos sobre la composicion, nunca pixeles.
 */
class FileSidecarStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PhotoStorePort {

    private val directory = File(context.filesDir, "sidecars").apply { mkdirs() }

    override suspend fun attachSidecar(
        id: CaptureId,
        sidecar: CaptureSidecar,
    ): Result<Unit> = withContext(io) {
        runCatching {
            fileFor(id).writeText(sidecar.toJson(id).toString())
        }
    }

    override suspend fun recent(limit: Int): List<StoredCapture> = withContext(io) {
        readAll()
            .sortedByDescending { it.capturedAtMillis }
            .take(limit)
    }

    override suspend fun sidecarOf(id: CaptureId): CaptureSidecar? = withContext(io) {
        runCatching { fileFor(id).takeIf { it.exists() }?.readText() }
            .getOrNull()
            ?.let { runCatching { JSONObject(it).toSidecar() }.getOrNull() }
    }

    override suspend fun sidecarsSince(millis: Long): List<CaptureSidecar> = withContext(io) {
        readAll()
            .filter { it.capturedAtMillis >= millis }
            .map { it.sidecar }
    }

    /**
     * Borrado real. Es un compromiso del producto, no una opcion enterrada en los ajustes.
     *
     * Solo borra los expedientes: las fotos son del usuario y viven en su carrete, asi que
     * eliminarlas desde aqui seria decidir por el.
     */
    override suspend fun deleteAll(): Result<Unit> = withContext(io) {
        runCatching {
            directory.listFiles()?.forEach { it.delete() }
            Unit
        }
    }

    // ------------------------------------------------------------------ interno

    /**
     * El id de captura es una URI del sistema y trae caracteres que no valen en un nombre de
     * archivo. Se usa su hash, que ademas evita nombres absurdamente largos.
     */
    private fun fileFor(id: CaptureId): File =
        File(directory, "${id.value.hashCode().toUInt().toString(16)}.json")

    private fun readAll(): List<StoredCapture> =
        directory.listFiles().orEmpty().mapNotNull { file ->
            runCatching {
                val json = JSONObject(file.readText())
                StoredCapture(
                    id = CaptureId(json.getString(KEY_ID)),
                    uri = json.getString(KEY_ID),
                    sidecar = json.toSidecar(),
                    capturedAtMillis = json.optLong(KEY_CAPTURED_AT),
                )
            }.getOrNull()
        }

    private fun CaptureSidecar.toJson(id: CaptureId): JSONObject = JSONObject().apply {
        put(KEY_ID, id.value)
        put(KEY_GUIDE, activeGuide.name)
        put(KEY_ASPECT_W, aspect.width)
        put(KEY_ASPECT_H, aspect.height)
        put(KEY_SCENE, sceneType.name)
        put(KEY_SHOWN, JSONArray(adviceShown.map { it.name }))
        put(KEY_ACCEPTED, JSONArray(adviceAccepted.map { it.name }))
        put(KEY_TILT, tiltAtCapture?.value ?: JSONObject.NULL)
        put(KEY_SUBJECT_X, subjectCenterX ?: JSONObject.NULL)
        put(KEY_SUBJECT_Y, subjectCenterY ?: JSONObject.NULL)
        put(KEY_MODEL, visionModelVersion)
        put(KEY_CAPTURED_AT, capturedAtMillis)
        put(KEY_ISO, settings.isoValue ?: JSONObject.NULL)
        put(KEY_ZOOM, settings.zoomRatio)
        put(KEY_COACH_ON, coachEnabled)
        put(KEY_SILENCE, silenceRatio ?: JSONObject.NULL)
        put(KEY_DISMISSED, JSONArray(adviceDismissed.map { it.name }))
    }

    private fun JSONObject.toSidecar(): CaptureSidecar = CaptureSidecar(
        activeGuide = runCatching { GuideKind.valueOf(getString(KEY_GUIDE)) }
            .getOrDefault(GuideKind.THIRDS),
        aspect = AspectRatio(optInt(KEY_ASPECT_W, 3), optInt(KEY_ASPECT_H, 4)),
        sceneType = runCatching { SceneType.valueOf(getString(KEY_SCENE)) }
            .getOrDefault(SceneType.UNKNOWN),
        adviceShown = optJSONArray(KEY_SHOWN).toAdviceKeys(),
        adviceAccepted = optJSONArray(KEY_ACCEPTED).toAdviceKeys(),
        tiltAtCapture = if (isNull(KEY_TILT)) null else Degrees(getDouble(KEY_TILT).toFloat()),
        subjectCenterX = if (isNull(KEY_SUBJECT_X)) null else getDouble(KEY_SUBJECT_X).toFloat(),
        subjectCenterY = if (isNull(KEY_SUBJECT_Y)) null else getDouble(KEY_SUBJECT_Y).toFloat(),
        settings = CaptureSettings(
            isoValue = if (isNull(KEY_ISO)) null else optInt(KEY_ISO),
            zoomRatio = optDouble(KEY_ZOOM, 1.0).toFloat(),
        ),
        visionModelVersion = optString(KEY_MODEL, "unversioned"),
        capturedAtMillis = optLong(KEY_CAPTURED_AT),
        coachEnabled = optBoolean(KEY_COACH_ON, true),
        silenceRatio = if (isNull(KEY_SILENCE)) null else optDouble(KEY_SILENCE).toFloat(),
        adviceDismissed = optJSONArray(KEY_DISMISSED).toAdviceKeys(),
    )

    /** Una regla que ya no exista en el codigo se ignora, en vez de tumbar todo el historial. */
    private fun JSONArray?.toAdviceKeys(): List<AdviceKey> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            runCatching { AdviceKey.valueOf(getString(index)) }.getOrNull()
        }
    }

    private companion object {
        const val KEY_ID = "id"
        const val KEY_GUIDE = "guide"
        const val KEY_ASPECT_W = "aspectW"
        const val KEY_ASPECT_H = "aspectH"
        const val KEY_SCENE = "scene"
        const val KEY_SHOWN = "adviceShown"
        const val KEY_ACCEPTED = "adviceAccepted"
        const val KEY_TILT = "tilt"
        const val KEY_SUBJECT_X = "subjectX"
        const val KEY_SUBJECT_Y = "subjectY"
        const val KEY_MODEL = "visionModel"
        const val KEY_CAPTURED_AT = "capturedAt"
        const val KEY_ISO = "iso"
        const val KEY_ZOOM = "zoom"
        const val KEY_COACH_ON = "coachEnabled"
        const val KEY_SILENCE = "silenceRatio"
        const val KEY_DISMISSED = "adviceDismissed"
    }
}
