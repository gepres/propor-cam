package dev.propor.android.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * Version en Canvas. El paso a shader (AGSL) llega con H3.2 y hara falta cuando se anada el
 * contraste adaptativo, que necesita muestrear la luminancia del frame por debajo de cada tramo.
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
                start = segment.from.toOffset(),
                end = segment.to.toOffset(),
                strokeWidth = strokeWidth,
            )
        }

        geometry.curves.forEach { points ->
            if (points.size < 2) return@forEach
            val path = Path().apply {
                moveTo(points.first().x.value * size.width, points.first().y.value * size.height)
                points.drop(1).forEach { p ->
                    lineTo(p.x.value * size.width, p.y.value * size.height)
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
                center = anchor.toOffset(),
                alpha = if (isHighlighted) 1f else 0.7f,
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
        val height = size.height * 0.4f
        val top = (size.height - height) / 2f

        drawLine(
            color = color.copy(alpha = 0.15f),
            start = Offset(x, top),
            end = Offset(x, top + height),
            strokeWidth = trackWidth,
        )
        drawLine(
            color = color,
            start = Offset(x, top + height),
            end = Offset(x, top + height * (1f - progress.coerceIn(0f, 1f))),
            strokeWidth = trackWidth,
        )
    }
}

private fun NormPoint.toOffset(): Offset = Offset(x.value, y.value)

private fun DrawScope.drawLine(
    color: Color,
    start: Offset,
    end: Offset,
    strokeWidth: Float,
) = drawLine(
    color = color,
    start = Offset(start.x * size.width, start.y * size.height),
    end = Offset(end.x * size.width, end.y * size.height),
    strokeWidth = strokeWidth,
)

private fun DrawScope.drawCircle(
    color: Color,
    radius: Float,
    center: Offset,
    alpha: Float,
) = drawCircle(
    color = color,
    radius = radius,
    center = Offset(center.x * size.width, center.y * size.height),
    alpha = alpha,
)
