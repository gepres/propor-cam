package dev.propor.core.domain.vision

import dev.propor.core.domain.geometry.Confidence
import dev.propor.core.domain.geometry.Degrees
import dev.propor.core.domain.geometry.NormPoint
import dev.propor.core.domain.geometry.NormRect
import dev.propor.core.domain.geometry.NormSize
import dev.propor.core.domain.geometry.Segment
import dev.propor.core.domain.scene.HorizonReading
import dev.propor.core.domain.scene.HorizonSource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Umbrales del detector. Todos con nombre: son la personalidad del algoritmo. */
data class LineDetectorConfig(
    /** Ancho al que se reduce el frame antes de analizarlo. */
    val targetWidth: Int = 160,
    /** Magnitud de gradiente por debajo de la cual un pixel no es borde. */
    val gradientThreshold: Int = 70,
    /** Resolucion angular del acumulador: 180 bins es un grado por bin. */
    val thetaBins: Int = 180,
    val rhoBins: Int = 128,
    /**
     * Cuantos bins a cada lado de la perpendicular al gradiente reciben voto.
     *
     * Es la clave del rendimiento: la transformada clasica vota en los 180 angulos por cada
     * pixel de borde y no cabe en el presupuesto de 33 ms. Votar solo alrededor de la normal
     * que ya conocemos reduce el trabajo mas de veinte veces sin perder lineas reales.
     */
    val thetaSpreadBins: Int = 4,
    val maxLines: Int = 8,
    /** Fraccion del pico maximo por debajo de la cual ya no se considera una linea. */
    val peakRatio: Float = 0.45f,
    /** Cuanto puede desviarse una linea de la horizontal para valer como horizonte. */
    val horizonToleranceDeg: Float = 25f,
    /** Y de la vertical, para la regla de arquitectura. */
    val verticalToleranceDeg: Float = 25f,
)

/** Lo que el detector encuentra en un frame. Todo en coordenadas normalizadas. */
data class LineDetection(
    val lines: List<Segment> = emptyList(),
    val horizon: HorizonReading? = null,
    /** Divergencia entre las verticales de la escena. Alimenta la regla de arquitectura. */
    val verticalConvergence: Degrees? = null,
    /**
     * Donde se concentra el detalle de la escena, cuando se concentra en algún sitio.
     *
     * No es saliencia de verdad —eso necesita un modelo entrenado, y Android no trae ninguno—
     * pero responde a la misma pregunta con lo que ya estamos calculando: donde hay bordes hay
     * informacion, y donde se agrupan los bordes suele estar el asunto de la foto.
     *
     * Es null cuando el detalle esta repartido por todo el encuadre. Eso es deliberado: en una
     * pared lisa o en una textura uniforme **no hay sujeto**, y decir que el sujeto esta en el
     * centro geometrico seria inventarselo.
     */
    val salientRegion: NormRect? = null,
)

/**
 * Encuentra las lineas rectas dominantes de la escena.
 *
 * Existe porque **Android no tiene equivalente a `VNDetectHorizonRequest`**, que en iOS venia
 * gratis. Y al tener que construirlo, sale ganando el producto: una sola implementacion, en el
 * nucleo, que las dos plataformas comparten. Sin esto, iOS y Android verian lineas distintas en
 * la misma escena y el coach diria cosas distintas segun el telefono.
 *
 * Resuelve de una vez **dos de las siete reglas** del coach —horizonte y lineas principales— y
 * desbloquea la tercera, la de verticales convergentes en arquitectura.
 *
 * ### Como funciona
 *
 * 1. Submuestreo a unos 160 px de ancho. Las lineas dominantes de una escena sobreviven de sobra
 *    a esa reduccion, y el trabajo baja dos ordenes de magnitud.
 * 2. Gradiente Sobel. Donde no hay gradiente no hay borde, y donde no hay borde no hay linea.
 * 3. Transformada de Hough **guiada por la direccion del gradiente**: cada pixel de borde vota
 *    solo en el entorno de su propia normal, no en los 180 angulos posibles.
 * 4. Picos del acumulador con supresion de no-maximos, convertidos a segmentos normalizados.
 *
 * Reutiliza sus buffers entre llamadas: se ejecuta muchas veces por segundo y no puede reservar
 * memoria en cada frame.
 *
 * No es thread-safe. Vive en el hilo de vision.
 */
class LineDetector(private val config: LineDetectorConfig = LineDetectorConfig()) {

    private var accumulator = IntArray(config.thetaBins * config.rhoBins)
    private val cosTable = FloatArray(config.thetaBins) {
        cos(it * PI / config.thetaBins).toFloat()
    }
    private val sinTable = FloatArray(config.thetaBins) {
        sin(it * PI / config.thetaBins).toFloat()
    }

    fun detect(frame: LumaFrame): LineDetection {
        val step = max(1, frame.width / config.targetWidth)
        val gridWidth = frame.width / step
        val gridHeight = frame.height / step
        if (gridWidth < 8 || gridHeight < 8) return LineDetection()

        accumulator.fill(0)

        val centerX = gridWidth / 2f
        val centerY = gridHeight / 2f
        val maxRho = kotlin.math.sqrt((centerX * centerX + centerY * centerY).toDouble()).toFloat()
        if (maxRho <= 0f) return LineDetection()

        var edgePixels = 0

        // Centroide de bordes y su dispersion, acumulados en el MISMO recorrido que ya se hace
        // para la Hough. Sale gratis: no cuesta ni un pixel de lectura extra.
        var weight = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0

        for (gy in 1 until gridHeight - 1) {
            for (gx in 1 until gridWidth - 1) {
                val x = gx * step
                val y = gy * step

                // Sobel. La suma de valores absolutos aproxima el modulo lo bastante bien para
                // decidir si hay borde, y evita una raiz cuadrada por pixel.
                val sx = sobelX(frame, x, y, step)
                val sy = sobelY(frame, x, y, step)
                val magnitude = abs(sx) + abs(sy)
                if (magnitude < config.gradientThreshold) continue

                edgePixels++

                val w = magnitude.toDouble()
                weight += w
                sumX += gx * w
                sumY += gy * w
                sumX2 += gx.toDouble() * gx * w
                sumY2 += gy.toDouble() * gy * w

                // El gradiente apunta perpendicular al borde, que es exactamente el angulo de
                // la normal en la parametrizacion (rho, theta). Por eso se puede votar solo
                // alrededor de este valor en vez de en todos.
                var theta = atan2(sy.toDouble(), sx.toDouble())
                if (theta < 0) theta += PI
                val centralBin = ((theta / PI) * config.thetaBins).toInt()
                    .coerceIn(0, config.thetaBins - 1)

                val dx = gx - centerX
                val dy = gy - centerY

                for (offset in -config.thetaSpreadBins..config.thetaSpreadBins) {
                    val bin = ((centralBin + offset) % config.thetaBins + config.thetaBins) %
                        config.thetaBins
                    val rho = dx * cosTable[bin] + dy * sinTable[bin]
                    val rhoBin = (((rho / maxRho) * 0.5f + 0.5f) * (config.rhoBins - 1))
                        .toInt()
                        .coerceIn(0, config.rhoBins - 1)
                    accumulator[bin * config.rhoBins + rhoBin]++
                }
            }
        }

        // Una escena lisa no tiene lineas. Inventarselas seria peor que no detectar nada.
        if (edgePixels < MIN_EDGE_PIXELS) return LineDetection()

        val peaks = findPeaks()
        if (peaks.isEmpty()) return LineDetection()

        val lines = peaks.mapNotNull { peak ->
            segmentFor(peak, gridWidth, gridHeight, maxRho)
        }

        return LineDetection(
            lines = lines,
            horizon = horizonFrom(peaks, edgePixels),
            verticalConvergence = convergenceFrom(peaks),
            salientRegion = salientRegion(
                weight, sumX, sumY, sumX2, sumY2, gridWidth, gridHeight,
            ),
        )
    }

    // ------------------------------------------------------------------ picos

    private data class Peak(val thetaBin: Int, val rhoBin: Int, val votes: Int) {
        /**
         * Inclinacion de la LINEA respecto a la horizontal, en grados.
         *
         * `thetaBin` es el angulo de la NORMAL. Una linea horizontal tiene normal vertical, o
         * sea 90 grados; de ahi la resta. El resultado se lleva a [-90, 90], que es como piensa
         * un fotografo: "esta torcida tantos grados", sin importar hacia donde.
         */
        fun inclinationDeg(thetaBins: Int): Float {
            val normalDeg = thetaBin * 180f / thetaBins
            var inclination = normalDeg - 90f
            if (inclination > 90f) inclination -= 180f
            if (inclination < -90f) inclination += 180f
            return inclination
        }
    }

    private fun findPeaks(): List<Peak> {
        var maxVotes = 0
        for (value in accumulator) if (value > maxVotes) maxVotes = value
        if (maxVotes < MIN_PEAK_VOTES) return emptyList()

        val threshold = (maxVotes * config.peakRatio).toInt().coerceAtLeast(MIN_PEAK_VOTES)
        val peaks = mutableListOf<Peak>()

        for (t in 0 until config.thetaBins) {
            for (r in 0 until config.rhoBins) {
                val votes = accumulator[t * config.rhoBins + r]
                if (votes < threshold) continue
                if (!isLocalMaximum(t, r, votes)) continue
                peaks += Peak(t, r, votes)
            }
        }

        return peaks.sortedByDescending { it.votes }.take(config.maxLines)
    }

    /**
     * Supresion de no-maximos. Sin esto, una sola linea de la escena produce una constelacion de
     * picos vecinos y el detector reportaria diez lineas donde hay una.
     */
    private fun isLocalMaximum(thetaBin: Int, rhoBin: Int, votes: Int): Boolean {
        for (dt in -NMS_RADIUS..NMS_RADIUS) {
            for (dr in -NMS_RADIUS..NMS_RADIUS) {
                if (dt == 0 && dr == 0) continue
                val t = ((thetaBin + dt) % config.thetaBins + config.thetaBins) % config.thetaBins
                val r = rhoBin + dr
                if (r !in 0 until config.rhoBins) continue
                if (accumulator[t * config.rhoBins + r] > votes) return false
            }
        }
        return true
    }

    // ------------------------------------------------------------------ interpretacion

    private fun horizonFrom(peaks: List<Peak>, edgePixels: Int): HorizonReading? {
        val candidate = peaks.firstOrNull {
            abs(it.inclinationDeg(config.thetaBins)) <= config.horizonToleranceDeg
        } ?: return null

        val strongest = peaks.first().votes.toFloat().coerceAtLeast(1f)
        // La confianza sale de lo dominante que sea la linea, no de una constante inventada.
        val dominance = (candidate.votes / strongest).coerceIn(0f, 1f)
        val support = (edgePixels.toFloat() / CONFIDENT_EDGE_PIXELS).coerceIn(0f, 1f)

        return HorizonReading(
            angle = Degrees(candidate.inclinationDeg(config.thetaBins)),
            source = HorizonSource.VISION,
            confidence = Confidence((dominance * support).coerceIn(0f, 1f)),
        )
    }

    /**
     * Divergencia entre las verticales de la escena.
     *
     * Cuando un edificio se fotografia mirando hacia arriba, sus verticales dejan de ser
     * paralelas. La diferencia entre las dos mas votadas es una medida directa de ese efecto.
     */
    private fun convergenceFrom(peaks: List<Peak>): Degrees? {
        val verticals = peaks.filter {
            abs(abs(it.inclinationDeg(config.thetaBins)) - 90f) <= config.verticalToleranceDeg
        }
        if (verticals.size < 2) return null

        val a = verticals[0].inclinationDeg(config.thetaBins)
        val b = verticals[1].inclinationDeg(config.thetaBins)
        val divergence = abs(abs(a) - abs(b))
        return if (divergence < MIN_CONVERGENCE_DEG) null else Degrees(divergence)
    }

    /**
     * Donde se concentra el detalle, si es que se concentra.
     *
     * Centroide de los bordes ponderado por su fuerza, y una caja de una desviacion tipica
     * alrededor. La dispersion es lo que decide si hay algo: con los bordes repartidos por todo
     * el encuadre no hay sujeto, y devolver el centro geometrico seria fabricar una respuesta.
     */
    private fun salientRegion(
        weight: Double,
        sumX: Double,
        sumY: Double,
        sumX2: Double,
        sumY2: Double,
        gridWidth: Int,
        gridHeight: Int,
    ): NormRect? {
        if (weight <= 0.0) return null

        val meanX = sumX / weight
        val meanY = sumY / weight
        val varianceX = (sumX2 / weight - meanX * meanX).coerceAtLeast(0.0)
        val varianceY = (sumY2 / weight - meanY * meanY).coerceAtLeast(0.0)

        val spreadX = kotlin.math.sqrt(varianceX) / gridWidth
        val spreadY = kotlin.math.sqrt(varianceY) / gridHeight

        // Bordes repartidos por todo el encuadre: textura, no sujeto.
        if (spreadX > MAX_SALIENT_SPREAD && spreadY > MAX_SALIENT_SPREAD) return null

        val halfWidth = (spreadX.toFloat()).coerceIn(MIN_SALIENT_HALF, 0.5f)
        val halfHeight = (spreadY.toFloat()).coerceIn(MIN_SALIENT_HALF, 0.5f)
        val centerX = (meanX / gridWidth).toFloat()
        val centerY = (meanY / gridHeight).toFloat()

        return NormRect(
            origin = NormPoint.clamped(centerX - halfWidth, centerY - halfHeight),
            size = NormSize(halfWidth * 2f, halfHeight * 2f),
        )
    }

    /** De (theta, rho) a un segmento normalizado, recortado contra los bordes del frame. */
    private fun segmentFor(
        peak: Peak,
        gridWidth: Int,
        gridHeight: Int,
        maxRho: Float,
    ): Segment? {
        val cosT = cosTable[peak.thetaBin]
        val sinT = sinTable[peak.thetaBin]
        val rho = ((peak.rhoBin.toFloat() / (config.rhoBins - 1)) - 0.5f) * 2f * maxRho

        val halfWidth = gridWidth / 2f
        val halfHeight = gridHeight / 2f
        val points = mutableListOf<Pair<Float, Float>>()

        // Interseccion con los bordes verticales y horizontales del frame.
        if (abs(sinT) > 1e-4f) {
            listOf(-halfWidth, halfWidth).forEach { x ->
                val y = (rho - x * cosT) / sinT
                if (y >= -halfHeight - 1f && y <= halfHeight + 1f) points += x to y
            }
        }
        if (abs(cosT) > 1e-4f) {
            listOf(-halfHeight, halfHeight).forEach { y ->
                val x = (rho - y * sinT) / cosT
                if (x >= -halfWidth - 1f && x <= halfWidth + 1f) points += x to y
            }
        }
        if (points.size < 2) return null

        fun toNorm(point: Pair<Float, Float>): NormPoint = NormPoint.clamped(
            ((point.first + halfWidth) / gridWidth),
            ((point.second + halfHeight) / gridHeight),
        )

        // Los dos extremos mas separados: con esquinas puede haber mas de dos intersecciones.
        var best = points[0] to points[1]
        var bestDistance = -1f
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val dx = points[i].first - points[j].first
                val dy = points[i].second - points[j].second
                val distance = dx * dx + dy * dy
                if (distance > bestDistance) {
                    bestDistance = distance
                    best = points[i] to points[j]
                }
            }
        }
        return Segment(toNorm(best.first), toNorm(best.second))
    }

    // ------------------------------------------------------------------ sobel

    /**
     * Luminancia promediada sobre el bloque que representa este punto del grid.
     *
     * Cualquier detector de bordes serio suaviza antes de derivar —Canny empieza con un
     * gaussiano— y por la misma razon: la derivada amplifica el ruido y el aliasing. Aqui el
     * suavizado sale gratis del propio submuestreo, promediando el bloque en vez de quedarse
     * con un pixel suelto.
     *
     * Sin esto, una linea con escalones de un pixel produce bordes horizontales falsos y el
     * detector se equivoca justo en los angulos suaves, que son los que mas importan: nadie
     * necesita que le avisen de un horizonte a 40 grados.
     */
    private fun sample(frame: LumaFrame, x: Int, y: Int, step: Int): Int {
        val radius = min(step, MAX_SAMPLE_RADIUS)
        if (radius <= 1) return frame.luma(x.coerceIn(0, frame.width - 1), y.coerceIn(0, frame.height - 1))

        var sum = 0
        var count = 0
        for (dy in 0 until radius) {
            val py = (y + dy).coerceIn(0, frame.height - 1)
            for (dx in 0 until radius) {
                val px = (x + dx).coerceIn(0, frame.width - 1)
                sum += frame.luma(px, py)
                count++
            }
        }
        return sum / count
    }

    private fun sobelX(frame: LumaFrame, x: Int, y: Int, step: Int): Int {
        val xm = max(0, x - step)
        val xp = min(frame.width - 1, x + step)
        val ym = max(0, y - step)
        val yp = min(frame.height - 1, y + step)
        return (sample(frame, xp, ym, step) + 2 * sample(frame, xp, y, step) + sample(frame, xp, yp, step)) -
            (sample(frame, xm, ym, step) + 2 * sample(frame, xm, y, step) + sample(frame, xm, yp, step))
    }

    private fun sobelY(frame: LumaFrame, x: Int, y: Int, step: Int): Int {
        val xm = max(0, x - step)
        val xp = min(frame.width - 1, x + step)
        val ym = max(0, y - step)
        val yp = min(frame.height - 1, y + step)
        return (sample(frame, xm, yp, step) + 2 * sample(frame, x, yp, step) + sample(frame, xp, yp, step)) -
            (sample(frame, xm, ym, step) + 2 * sample(frame, x, ym, step) + sample(frame, xp, ym, step))
    }

    private companion object {
        /** Por debajo de esto la escena no tiene estructura suficiente para hablar de lineas. */
        const val MIN_EDGE_PIXELS = 40

        /** Votos minimos para que un pico sea una linea y no ruido. */
        const val MIN_PEAK_VOTES = 12

        /** Radio de la supresion de no-maximos, en bins. */
        const val NMS_RADIUS = 3

        /** Bordes a partir de los cuales la deteccion se considera plenamente respaldada. */
        const val CONFIDENT_EDGE_PIXELS = 300f

        /** Tope del bloque de promediado: mas alla no mejora y cuesta caro. */
        const val MAX_SAMPLE_RADIUS = 3

        /** Dispersion por encima de la cual el detalle esta repartido y no hay sujeto. */
        const val MAX_SALIENT_SPREAD = 0.26

        /** Semilado minimo de la region: un punto no es una region. */
        const val MIN_SALIENT_HALF = 0.06f

        /** Divergencia por debajo de la cual las verticales se consideran paralelas. */
        const val MIN_CONVERGENCE_DEG = 1.5f
    }
}
