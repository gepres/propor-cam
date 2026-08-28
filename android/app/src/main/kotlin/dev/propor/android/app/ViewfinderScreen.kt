package dev.propor.android.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.propor.android.adapters.CameraXCameraAdapter
import dev.propor.core.domain.geometry.AspectRatio
import dev.propor.core.domain.guide.GuideGeometryFactory
import dev.propor.core.domain.guide.GuideKind
import kotlinx.coroutines.launch

/**
 * El visor.
 *
 * Principio rector: **el visor es sagrado**. La escena ocupa toda la pantalla y los controles
 * viven en los bordes, en los dos tercios inferiores, dentro del arco del pulgar. Se fotografia
 * a una mano y muchas veces en equilibrio precario.
 */
@Composable
fun ViewfinderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // La regla de tercios ya viene puesta: se entra al visor y se dispara, sin tour ni ajustes.
    var activeGuide by remember { mutableStateOf(GuideKind.THIRDS) }

    // El visor es vertical, asi que el aspecto que se pasa tambien: 3:4 y no 4:3. Las guias que
    // dependen de la forma del rectangulo —triangulos y espiral— dan geometrias distintas segun
    // la orientacion, y pasar la equivocada dibuja una figura que no corresponde a la escena.
    val viewfinderAspect = remember { AspectRatio.R4_3.rotated() }

    val geometry = remember(activeGuide, viewfinderAspect) {
        GuideGeometryFactory.geometryFor(activeGuide, viewfinderAspect)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ProporColors.Background),
    ) {
        if (hasPermission) {
            CameraSurface(
                lifecycleOwner = lifecycleOwner,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PermissionNotice(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // La guia tiene que caer EXACTAMENTE sobre la imagen, no sobre la pantalla. Como la
        // previsualizacion usa FIT_CENTER, queda centrada verticalmente con bandas negras
        // arriba y abajo; alinear el overlay arriba lo desplazaria respecto a la escena y las
        // lineas mentirian sobre donde cae cada tercio.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        ) {
            GuideOverlay(geometry = geometry, modifier = Modifier.fillMaxSize())
        }

        TechnicalBar(modifier = Modifier.align(Alignment.TopStart))

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GuidePicker(
                active = activeGuide,
                onSelect = { activeGuide = it },
                modifier = Modifier.fillMaxWidth(),
            )
            ShutterButton(modifier = Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
private fun CameraSurface(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val adapter = remember(lifecycleOwner) {
        CameraXCameraAdapter(context = context, lifecycleOwner = lifecycleOwner)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                // FIT_CENTER y no FILL: recortar la previsualizacion mentiria sobre el encuadre,
                // y el encuadre es justamente lo que esta app ensena a decidir.
                scaleType = PreviewView.ScaleType.FIT_CENTER
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            }
        },
        update = { view ->
            adapter.attachPreview(view.surfaceProvider)
            scope.launch { adapter.open() }
        },
    )
}

/** Barra tecnica. Mono tabular: los numeros no deben bailar al cambiar. */
@Composable
private fun TechnicalBar(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        listOf("AUTO", "ISO —", "1/—", "EV 0.0").forEach { value ->
            Text(
                text = value,
                color = ProporColors.TextSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Selector de composicion.
 *
 * Version provisional con nombres. El selector definitivo (H3.4) muestra cada guia **dibujada
 * sobre la escena congelada**: se elige viendo el resultado, no leyendo una lista.
 */
@Composable
private fun GuidePicker(
    active: GuideKind,
    onSelect: (GuideKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(GuideKind.R1) { kind ->
            val selected = kind == active
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) ProporColors.Accent else ProporColors.SurfaceRaised,
                    )
                    .clickable { onSelect(kind) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = kind.shortLabel(),
                    color = if (selected) Color.White else ProporColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.92f)),
    )
}

@Composable
private fun PermissionNotice(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "PROPOR necesita la cámara para mostrarte el visor.\n" +
                "Tus fotos no salen de tu teléfono.",
            color = ProporColors.TextPrimary,
            fontSize = 15.sp,
        )
        Box(
            modifier = Modifier
                .padding(top = 20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ProporColors.Accent)
                .clickable(onClick = onRequest)
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Text(text = "Permitir", color = Color.White, fontSize = 14.sp)
        }
    }
}

/**
 * Etiqueta corta de cada guia.
 *
 * Provisional: el texto definitivo va en recursos, en es, en y pt (ADR-004 obliga a que el
 * dominio no lleve cadenas, y esta capa es donde se resuelven).
 */
private fun GuideKind.shortLabel(): String = when (this) {
    GuideKind.THIRDS -> "Tercios"
    GuideKind.GRID_2X2 -> "2×2"
    GuideKind.GRID_3X3 -> "3×3"
    GuideKind.GRID_4X4 -> "4×4"
    GuideKind.DIAGONALS -> "Diagonales"
    GuideKind.TRIANGLES -> "Triángulos"
    GuideKind.GOLDEN_RATIO -> "Áurea"
    GuideKind.GOLDEN_SPIRAL -> "Espiral"
    GuideKind.SYMMETRY -> "Simetría"
    GuideKind.CENTER -> "Centro"
    else -> name
}
