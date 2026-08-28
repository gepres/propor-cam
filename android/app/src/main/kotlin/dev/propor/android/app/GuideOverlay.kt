package dev.propor.android.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.propor.core.domain.geometry.NormPoint
import dev.propor.core.domain.guide.GuideGeometry

/**
 * Dibuja la guia activa sobre el visor.
 *
 * Recibe geometria ya calculada por el dominio en coordenadas normalizadas y solo la escala al
 * tamano real. **No calcula nada**: si esta capa tuviera que saber que es la proporcion aurea,
 * la regla acabaria escrita dos veces y divergiendo entre plataformas.
 *
 * La conversion de normalizado a pixeles se hace **explicitamente** con [scaledTo] y nunca
 * envolviendo `drawLine` en una extension del mismo nombre: los metodos miembro de `DrawScope`
 * ganan a las extensiones, asi que una extension homonima jamas se llegaria a llamar y las
 * lineas se dibujarian dentro de un cuadrado de un pixel en la esquina, sin error ninguno.
 *
 * Version en Canvas. El paso a shader (AGSL) llega con H3.2 y hara falta para el contraste
 * adaptativo, que necesita muestrear la luminancia del frame bajo cada tramo.
 */
@Composable
fun GuideOverlay(
    geometry: GuideGeometry,
    modifier: Modifier = Modifier,
    lineColor: Color = ProporColors.Guide,
    anchorColor: Color = ProporColors.Anchor,
    /** Ancla que el coach sugiere ahora mismo. Se pinta destacada sobre las demas. */
    highlightedAnchor: NormPoint? = null,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.dp.toPx()

        geometry.segments.forEach { segment ->
            drawLine(
                color = lineColor,
                start = segment.from.scaledTo(size),
                end = segment.to.scaledTo(size),
                strokeWidth = strokeWidth,
            )
        }

        geometry.curves.forEach { points ->
            if (points.size < 2) return@forEach
            val path = Path().apply {
                val first = points.first().scaledTo(size)
                moveTo(first.x, first.y)
                points.drop(1).forEach { point ->
                    val offset = point.scaledTo(size)
                    lineTo(offset.x, offset.y)
                }
            }
            drawPath(path, color = lineColor, style = Stroke(width = strokeWidth * 1.4f))
        }

        geometry.anchors.forEach { anchor ->
            val isHighlighted = highlightedAnchor != null &&
                anchor.distanceTo(highlightedAnchor) < 0.01f
            drawCircle(
                color = if (isHighlighted) anchorColor else lineColor,
                radius = if (isHighlighted) 7.dp.toPx() else 3.dp.toPx(),
                center = anchor.scaledTo(size),
                alpha = if (isHighlighted) 1f else 0.75f,
            )
        }
    }
}

/**
 * Arco periferico del coach: se llena conforme el encuadre se acerca al sugerido.
 *
 * Vive en el borde derecho a proposito. Tiene que ser perceptible sin robar la mirada, porque
 * el principio del producto es que **nunca se lee mientras se compone**. Sustituye al texto que
 * usa la competencia.
 */
@Composable
fun CoachIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = ProporColors.Adjust,
) {
    Canvas(modifier = modifier) {
        val trackWidth = 3.dp.toPx()
        val x = size.width - trackWidth
        val trackHeight = size.height * 0.4f
        val top = (size.height - trackHeight) / 2f

        drawLine(
            color = color.copy(alpha = 0.15f),
            start = Offset(x, top),
            end = Offset(x, top + trackHeight),
            strokeWidth = trackWidth,
        )
        drawLine(
            color = color,
            start = Offset(x, top + trackHeight),
            end = Offset(x, top + trackHeight * (1f - progress.coerceIn(0f, 1f))),
            strokeWidth = trackWidth,
        )
    }
}

/** De coordenada normalizada del dominio a pixeles de pantalla. La unica conversion que hay. */
private fun NormPoint.scaledTo(size: Size): Offset =
    Offset(x.value * size.width, y.value * size.height)
