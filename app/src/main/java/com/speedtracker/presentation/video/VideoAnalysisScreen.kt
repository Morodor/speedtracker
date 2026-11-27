package com.speedtracker.presentation.video

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.core.content.FileProvider
import com.speedtracker.core.ml.OlympicPlateAutoDetector
import com.speedtracker.core.ml.KinoveaBarbellTracker
import java.io.File
import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.unit.IntOffset
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.speedtracker.core.export.VideoFrameTrackingPoint
import com.speedtracker.core.export.VideoOverlayExporter
import com.speedtracker.core.math.JumpMetrics
import com.speedtracker.core.math.JumpType
import com.speedtracker.core.math.VBTAnalyzer
import com.speedtracker.core.math.VBTExerciseFamily
import com.speedtracker.core.math.VBTExerciseModel
import com.speedtracker.core.math.VerticalJumpAnalyzer
import com.speedtracker.core.report.BiomechanicalPdfReportGenerator
import com.speedtracker.presentation.components.ExportReportDataDialog
import com.speedtracker.presentation.history.SavedPdfReportDialog
import com.speedtracker.data.UserRepository
import com.speedtracker.presentation.camera.FramingGuideOverlay
import com.speedtracker.presentation.camera.FramingGuideType
import com.speedtracker.domain.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalDensity
import java.util.UUID
import kotlin.math.hypot

enum class AnalysisSetupStep {
    TRIM_VIDEO,        // Paso 1: Recorte simple de video (Imagen 1)
    SELECT_EXERCISE,   // Paso 2: Selección de ejercicio pantalla completa (Imagen 2)
    CALIBRATE          // Paso 3: Calibración y carga con auto-detección y slider (Imagen 3)
}

data class VideoRenderBounds(
    val scale: Float,
    val left: Float,
    val top: Float,
    val renderWidth: Float,
    val renderHeight: Float
)

fun calculateVideoRenderBounds(
    boxWidth: Float,
    boxHeight: Float,
    videoWidth: Int,
    videoHeight: Int
): VideoRenderBounds {
    if (boxWidth <= 0f || boxHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) {
        return VideoRenderBounds(1f, 0f, 0f, boxWidth, boxHeight)
    }
    val scale = minOf(boxWidth / videoWidth.toFloat(), boxHeight / videoHeight.toFloat())
    val renderW = videoWidth * scale
    val renderH = videoHeight * scale
    val left = (boxWidth - renderW) / 2f
    val top = (boxHeight - renderH) / 2f
    return VideoRenderBounds(scale, left, top, renderW, renderH)
}

fun screenToVideoCoords(
    screenPos: Offset,
    screenRadius: Float,
    bounds: VideoRenderBounds
): Pair<Offset, Float> {
    val vx = (screenPos.x - bounds.left) / bounds.scale
    val vy = (screenPos.y - bounds.top) / bounds.scale
    val vr = screenRadius / bounds.scale
    return Pair(Offset(vx, vy), vr)
}

fun videoToScreenCoords(
    videoPos: Offset,
    videoRadius: Float,
    bounds: VideoRenderBounds
): Pair<Offset, Float> {
    val sx = (videoPos.x * bounds.scale) + bounds.left
    val sy = (videoPos.y * bounds.scale) + bounds.top
    val sr = videoRadius * bounds.scale
    return Pair(Offset(sx, sy), sr)
}

fun getVideoNativeDimensions(context: Context, uri: Uri): Pair<Int, Int> {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
        val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
        if (rotation == 90 || rotation == 270) {
            Pair(rawHeight, rawWidth)
        } else {
            Pair(rawWidth, rawHeight)
        }
    } catch (e: Exception) {
        Pair(1080, 1920)
    } finally {
        try { retriever.release() } catch (e: Exception) {}
    }
}

data class VideoFpsMetadata(
    val playbackFps: Double = 30.0,
    val captureFps: Double = 30.0,
    val frameStepMs: Long = 33L,
    val autoSlowMoFactor: Double = 1.0
)

data class JumpFootSample(
    val timestampMs: Long,
    val footX: Float,
    val footY: Float,
    val leftFootY: Float = footY,
    val rightFootY: Float = footY
)

/**
 * Detecta los FPS reales del video analizando tanto los metadatos del contenedor (CAPTURE_FRAMERATE, VIDEO_FRAME_COUNT)
 * como el intervalo real en microsegundos (sampleTime) entre fotogramas consecutivos mediante MediaExtractor.
 */
fun extractVideoFpsMetadata(context: Context, uri: Uri): VideoFpsMetadata {
    var playbackFps = 30.0
    var captureFps = 0.0
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull() ?: 0.0
        }
        val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && durMs > 100L) {
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
            if (frameCount > 5L) {
                playbackFps = (frameCount * 1000.0 / durMs.toDouble()).coerceIn(15.0, 240.0)
            }
        }
    } catch (e: Exception) {
    } finally {
        try { retriever.release() } catch (e: Exception) {}
    }

    val extractor = android.media.MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                if (format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                    val fmtFps = try {
                        format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE).toDouble()
                    } catch (e: Exception) {
                        try { format.getFloat(android.media.MediaFormat.KEY_FRAME_RATE).toDouble() } catch (ex: Exception) { 0.0 }
                    }
                    if (fmtFps in 15.0..240.0) playbackFps = fmtFps
                }
                extractor.selectTrack(i)
                val timestampsUs = mutableListOf<Long>()
                while (timestampsUs.size < 35) {
                    val t = extractor.sampleTime
                    if (t < 0L) break
                    timestampsUs.add(t)
                    if (!extractor.advance()) break
                }
                if (timestampsUs.size >= 6) {
                    val sorted = timestampsUs.sorted()
                    val deltas = mutableListOf<Long>()
                    for (k in 1 until sorted.size) {
                        val d = sorted[k] - sorted[k - 1]
                        if (d in 2000L..66000L) deltas.add(d)
                    }
                    if (deltas.isNotEmpty()) {
                        deltas.sort()
                        val medianUs = deltas[deltas.size / 2]
                        val measuredFps = (1_000_000.0 / medianUs.toDouble()).coerceIn(15.0, 240.0)
                        playbackFps = measuredFps
                    }
                }
                break
            }
        }
    } catch (e: Exception) {
    } finally {
        try { extractor.release() } catch (e: Exception) {}
    }

    val effectiveCapture = if (captureFps >= 24.0) captureFps else playbackFps
    val stepMs = kotlin.math.round(1000.0 / playbackFps).toLong().coerceIn(4L, 40L)
    val ratio = if (playbackFps > 10.0) effectiveCapture / playbackFps else 1.0
    val autoSlowMo = when {
        ratio >= 6.0 -> 8.0
        ratio >= 3.0 -> 4.0
        ratio >= 1.7 -> 2.0
        else -> 1.0
    }
    return VideoFpsMetadata(
        playbackFps = playbackFps,
        captureFps = maxOf(playbackFps, effectiveCapture),
        frameStepMs = stepMs,
        autoSlowMoFactor = autoSlowMo
    )
}

/**
 * Recalcula con interpolación lineal sub-frame los instantes de Contacto, Despegue y Aterrizaje
 * cuando la trayectoria vertical del pie (samples) cruza la línea de suelo (groundNativeY).
 */
fun computeSubFrameJumpEvents(
    samples: List<JumpFootSample>,
    groundNativeY: Float,
    videoNativeHeight: Int,
    jumpType: JumpType
): Triple<Double?, Double?, Double?> {
    if (samples.size < 4) return Triple(null, null, null)
    val smoothedY = zeroPhaseSmooth(samples.map { it.footY.toDouble() }, passes = 1).map { it.toFloat() }
    val minY = smoothedY.minOrNull() ?: return Triple(null, null, null)
    val elevationPx = groundNativeY - minY
    val minReqElevation = (videoNativeHeight * 0.018f).coerceAtLeast(10f)
    if (elevationPx < minReqElevation) return Triple(null, null, null)

    val apexIdx = smoothedY.indices.minByOrNull { smoothedY[it] } ?: return Triple(null, null, null)
    // Línea efectiva de cruce de despegue/aterrizaje (ligeramente por encima de la línea base de reposo para compensar extensión plantar)
    val crossingY = groundNativeY - (elevationPx * 0.18f).coerceIn(6f, 28f)

    // 1. Buscar Despegue hacia atrás desde el vértice (apexIdx)
    var takeoffIdx = apexIdx
    while (takeoffIdx > 0 && smoothedY[takeoffIdx] < crossingY) {
        takeoffIdx--
    }
    val takeoffMs: Double = if (takeoffIdx < apexIdx) {
        val y0 = smoothedY[takeoffIdx]      // >= crossingY (en el suelo)
        val y1 = smoothedY[takeoffIdx + 1]  // < crossingY (en el aire)
        val t0 = samples[takeoffIdx].timestampMs.toDouble()
        val t1 = samples[takeoffIdx + 1].timestampMs.toDouble()
        val denom = (y0 - y1)
        val frac = if (kotlin.math.abs(denom) > 0.001f) ((y0 - crossingY) / denom).coerceIn(0f, 1f) else 0.5f
        t0 + frac * (t1 - t0)
    } else {
        samples[takeoffIdx].timestampMs.toDouble()
    }

    // 2. Buscar Aterrizaje hacia adelante desde el vértice (apexIdx)
    var landingIdx = apexIdx
    while (landingIdx < samples.size - 1 && smoothedY[landingIdx] < crossingY) {
        landingIdx++
    }
    val landingMs: Double = if (landingIdx > apexIdx) {
        val yPrev = smoothedY[landingIdx - 1] // < crossingY (en el aire)
        val yCurr = smoothedY[landingIdx]     // >= crossingY (en el suelo)
        val tPrev = samples[landingIdx - 1].timestampMs.toDouble()
        val tCurr = samples[landingIdx].timestampMs.toDouble()
        val denom = (yCurr - yPrev)
        val frac = if (kotlin.math.abs(denom) > 0.001f) ((crossingY - yPrev) / denom).coerceIn(0f, 1f) else 0.5f
        tPrev + frac * (tCurr - tPrev)
    } else {
        samples[landingIdx].timestampMs.toDouble()
    }

    // 3. Si es Drop Jump, buscar Contacto Inicial previo al despegue (caída desde el cajón)
    var contactMs: Double? = null
    if (jumpType == JumpType.DROP_JUMP && takeoffIdx >= 2) {
        var cIdx = takeoffIdx
        while (cIdx > 0 && smoothedY[cIdx] >= crossingY) {
            cIdx--
        }
        if (cIdx < takeoffIdx && smoothedY[cIdx] < crossingY) {
            val yAir = smoothedY[cIdx]
            val yFloor = smoothedY[cIdx + 1]
            val tAir = samples[cIdx].timestampMs.toDouble()
            val tFloor = samples[cIdx + 1].timestampMs.toDouble()
            val denom = (yFloor - yAir)
            val frac = if (kotlin.math.abs(denom) > 0.001f) ((crossingY - yAir) / denom).coerceIn(0f, 1f) else 0.5f
            contactMs = tAir + frac * (tFloor - tAir)
        } else {
            contactMs = (takeoffMs - 190.0).coerceAtLeast(samples.first().timestampMs.toDouble())
        }
    }

    return Triple(contactMs, takeoffMs, landingMs)
}

@OptIn(ExperimentalMaterial3Api::class)

data class MetricInfo(
    val title: String,
    val shortName: String,
    val definition: String,
    val practicalValue: String,
    val referenceRange: String
)

object MetricInfoCatalog {
    val PV = MetricInfo(
        title = "Velocidad Pico (PV)",
        shortName = "PV",
        definition = "Velocidad instantánea máxima alcanzada por la barra durante la fase de aceleración concéntrica.",
        practicalValue = "Es la métrica de referencia en levantamientos olímpicos (Snatch, Clean) y movimientos balísticos. Refleja la capacidad del sistema neuromuscular para generar aceleración máxima en el segundo tirón.",
        referenceRange = "> 2.00 m/s: Velocidad óptima en Snatch/Clean con cargas de potencia. 1.60 - 2.00 m/s: Cargas pesadas de entrenamiento. < 1.40 m/s: Límite de fallo técnico."
    )

    val MV = MetricInfo(
        title = "Velocidad Media (MV)",
        shortName = "MV",
        definition = "Promedio de la velocidad de la barra a lo largo de toda la fase concéntrica (desde el punto de inversión inferior hasta el bloqueo final).",
        practicalValue = "Métrica estándar para ejercicios de fuerza tradicional (Sentadilla, Press Banca, Peso Muerto). Se utiliza para dosificar la intensidad y medir la fatiga entre series por pérdida de velocidad (% Velocity Loss).",
        referenceRange = "Sentadilla 1RM ≈ 0.30 m/s | Press Banca 1RM ≈ 0.17 m/s | Pérdida recomendada por serie: 10% a 20% para fuerza e hipertrofia sin fatiga residual excesiva."
    )

    val VMP = MetricInfo(
        title = "Velocidad Propulsiva (VMP)",
        shortName = "VMP",
        definition = "Velocidad media calculada únicamente durante la fase donde la aceleración de la barra es mayor que la gravedad (a ≥ -9.81 m/s²), descartando la fase de frenado involuntario.",
        practicalValue = "Validada por Sánchez-Medina et al. Elimina la subestimación de fuerza con cargas ligeras y medianas (<80% 1RM), donde el atleta frena voluntariamente para no soltar la barra.",
        referenceRange = "Prácticamente igual a la velocidad media en cargas pesadas (>80% 1RM), pero considerablemente más alta y precisa en cargas submáximas y de potencia."
    )

    val CONCENTRIC_TIME = MetricInfo(
        title = "Duración Fase Concéntrica",
        shortName = "Tiempo Concéntrico",
        definition = "Tiempo total (en milisegundos) que dura el ascenso de la barra, desde el despegue o punto más bajo hasta la extensión final.",
        practicalValue = "Permite evaluar la fluidez y economía técnica. Un aumento de la duración con la misma carga es un síntoma directo de fatiga neuromuscular central.",
        referenceRange = "Tirones y Snatch: 600 - 1800 ms según la variante desde el suelo o en suspensión."
    )

    val TIME_TO_PEAK = MetricInfo(
        title = "Tiempo a Velocidad Pico",
        shortName = "T. Vel. Pico",
        definition = "Milisegundos transcurridos desde que inicia la fase concéntrica hasta que se alcanza la velocidad máxima instantánea.",
        practicalValue = "Mide la rapidez de la transición motora (del primer al segundo tirón en halterofilia). Una transición más compacta optimiza la inercia sin desaceleraciones intermedias.",
        referenceRange = "700 - 1100 ms en levantamientos olímpicos completos desde el suelo."
    )

    val RFD = MetricInfo(
        title = "RFD (Tasa de Desarrollo de Fuerza)",
        shortName = "RFD",
        definition = "Rapidez de incremento de fuerza por unidad de tiempo (ΔFuerza / ΔTiempo), medida en Newtons por segundo (N/s).",
        practicalValue = "Indica la capacidad de reclutamiento de unidades motoras en los primeros milisegundos. Factor clave en despegues explosivos, saltos y deportes de combate.",
        referenceRange = "> 150 N/s: Excelente aceleración inicial en levantamientos olímpicos y saltos."
    )

    val POWER = MetricInfo(
        title = "Potencia Mecánica (Watts & W/kg)",
        shortName = "Potencia",
        definition = "Tasa de trabajo mecánico producido (Fuerza × Velocidad). Se presenta en Watts absolutos y en Watts por kg de peso corporal del atleta.",
        practicalValue = "Normalizar los Watts por kilogramo de peso corporal permite comparar objetivamente el rendimiento entre atletas de diferentes pesos y categorías.",
        referenceRange = "Atletas recreacionales: 12 - 18 W/kg | Atletas avanzados/competitivos: 20 - 35+ W/kg en tirones y saltos."
    )

    val FORCE = MetricInfo(
        title = "Fuerza Pico (N) & Trabajo Mecánico (J)",
        shortName = "Fuerza & Trabajo",
        definition = "Fuerza dinámica máxima ejercida contra la barra en Newtons (m·(a+g)) y energía mecánica total invertida en el ascenso expresada en Joules.",
        practicalValue = "Muestra la demanda mecánica total impuesta al sistema musculoesquelético durante el levantamiento.",
        referenceRange = "Directamente proporcional a la carga levantada y a la aceleración generada."
    )

    val ONE_RM = MetricInfo(
        title = "1RM Estimado (%1RM)",
        shortName = "1RM Estimado",
        definition = "Proyección del peso máximo para una repetición basado en la velocidad de ejecución y las ecuaciones de perfil VBT (González-Badillo et al.).",
        practicalValue = "Permite regular el entrenamiento en base al estado de fuerza diario real del atleta (autorregulación), evitando el riesgo lesivo de testear 1RM máximos reales.",
        referenceRange = "El %1RM indica el porcentaje relativo de intensidad que representó la serie realizada."
    )

    val BAR_DRIFT = MetricInfo(
        title = "Desvío Horizontal (Bar Drift)",
        shortName = "Desvío de Barra",
        definition = "Desplazamiento horizontal máximo de la barra durante el tirón vertical, calibrado según los 45 cm de referencia del disco olímpico.",
        practicalValue = "Mide la eficiencia técnica de la trayectoria. El levantamiento óptimo minimiza el balanceo hacia adelante o atrás para aprovechar la aceleración vertical.",
        referenceRange = "< 10 cm: Trayectoria vertical óptima / limpia. 10 - 18 cm: Desvío aceptable con leve balanceo. > 18 cm: Pérdida técnica de palanca (la barra se separa del cuerpo)."
    )

    val JUMP_HEIGHT = MetricInfo(
        title = "Altura de Salto Vertical",
        shortName = "Altura Salto",
        definition = "Altura alcanzada por el centro de gravedad, calculada a partir del tiempo de vuelo con cámara de alta velocidad (h = 1/8 · g · t²).",
        practicalValue = "Métrica fundamental para valorar la potencia neuromuscular y la fatiga neuromuscular del tren inferior (CMJ / Squat Jump).",
        referenceRange = "> 35 cm: Nivel intermedio. > 45 cm: Nivel avanzado / atletas competitivos."
    )

    val FLIGHT_TIME = MetricInfo(
        title = "Tiempo de Vuelo",
        shortName = "Tiempo Vuelo",
        definition = "Milisegundos transcurridos desde que la punta de los pies deja el suelo hasta el primer contacto en el aterrizaje.",
        practicalValue = "Valida la consistencia cinemática del salto y la precisión de la captura del video.",
        referenceRange = "500 - 750 ms según la altura del salto."
    )
}

@Composable
fun MetricLabelWithInfo(
    label: String,
    info: MetricInfo,
    onInfoClick: (MetricInfo) -> Unit,
    modifier: Modifier = Modifier,
    isStarred: Boolean = false,
    starColor: Color = Color(0xFF00FFCC)
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onInfoClick(info) }
    ) {
        Text(label, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        if (isStarred) {
            Spacer(modifier = Modifier.width(3.dp))
            Text("★", color = starColor, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = "Información sobre $label",
            modifier = Modifier.size(13.dp),
            tint = Color(0xFF00FFCC).copy(alpha = 0.85f)
        )
    }
}

@Composable
fun BiomechanicalInterpretationCard(
    evaluation: BiomechanicalPdfReportGenerator.BiomechanicalEvaluation,
    peakVelocity: Double,
    loadKg: Double,
    relativeWattsPerKg: Double,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131B24)),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FFCC).copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color(0xFF00FFCC),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Interpretación Biomecánica & Diagnóstico",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = Color(0xFF1C2734),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "⚡ ${evaluation.velocityZoneTag}",
                        color = Color(0xFF00FFCC),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        evaluation.velocityZoneDetail,
                        color = Color(0xFFE2E8F0),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = Color(0xFF1C2734),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "📐 ${evaluation.driftTag}",
                        color = if ("ÓPTIMA" in evaluation.driftTag) Color(0xFF00FFCC) else Color(0xFFFFB300),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        evaluation.driftDetail,
                        color = Color(0xFFE2E8F0),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = Color(0xFF16232E),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        evaluation.loadRecommendation,
                        color = Color(0xFF00E5FF),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        evaluation.rfdDetail,
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                "✓ Validación VBT: González-Badillo & Sánchez-Medina (2010, 2017)",
                color = Color(0xFF64748B),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoAnalysisScreen(
    videoUriString: String,
    userRepository: UserRepository,
    onCloseClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedUserState by remember { mutableStateOf(userRepository.getActiveUser()) }
    val activeUser = selectedUserState
    var allUsers by remember { mutableStateOf(userRepository.getUsers()) }
    var showPreCalibrationSetupDialog by remember { mutableStateOf(false) }
    var showExportDataDialog by remember { mutableStateOf(false) }

    var showTutorialDialog by remember { mutableStateOf(false) }
    var tutorialStep by remember { mutableIntStateOf(0) }
    var currentMode by remember { mutableStateOf("VBT") }
    var setupFamily by remember { mutableStateOf(VBTExerciseFamily.DLP_OLYMPIC) }
    var selectedVBTExercise by remember { mutableStateOf(VBTExerciseModel.SNATCH_FLOOR) }
    var selectedExerciseSequence by remember { mutableStateOf<List<VBTExerciseModel>>(emptyList()) }
    var selectedJumpType by remember { mutableStateOf(JumpType.CMJ) }
    var isReadyToAnalyze by remember { mutableStateOf(false) }
    var analysisSetupStep by remember { mutableStateOf(AnalysisSetupStep.TRIM_VIDEO) }
    var isLoopingTrimmedRange by remember { mutableStateOf(false) }
    var resultSeekPosMs by remember { mutableStateOf<Long?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    // Mantener la pantalla encendida activamente mientras se procesa el video para evitar suspensión
    val activity = context as? android.app.Activity
    DisposableEffect(isProcessing) {
        if (isProcessing) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    var finalResult by remember { mutableStateOf<Double?>(null) }
    var jumpMetricsResult by remember { mutableStateOf<JumpMetrics?>(null) }
    var vbtDeviationResultCm by remember { mutableStateOf<Double?>(null) }
    var barbellLoadKg by remember { mutableStateOf("70") }
    var trackingPointsResult by remember { mutableStateOf<List<VideoFrameTrackingPoint>>(emptyList()) }
    var isGeneratingPdf by remember { mutableStateOf(false) }
    var generatedPdfPath by remember { mutableStateOf<String?>(null) }
    var showPdfPreviewDialog by remember { mutableStateOf(false) }
    var generatedVideoPath by remember { mutableStateOf<String?>(null) }
    var isSavingToProfile by remember { mutableStateOf(false) }
    var selectedMetricInfo by remember { mutableStateOf<MetricInfo?>(null) }
    var isExportingVideo by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    // Dimensiones nativas del video y coordenadas calibradas en píxeles del video
    var videoNativeWidth by remember { mutableIntStateOf(1080) }
    var videoNativeHeight by remember { mutableIntStateOf(1920) }
    var calibratedPlateCenterVideo by remember { mutableStateOf<Offset?>(null) }
    var calibratedPlateRadiusVideo by remember { mutableFloatStateOf(0f) }

    // Metadatos de FPS reales, Cámara Lenta y Calibración de Salto (My Jump + IA Sub-Frame)
    var detectedVideoFps by remember { mutableStateOf(30.0) }
    var detectedCaptureFps by remember { mutableStateOf(30.0) }
    var nativeFrameStepMs by remember { mutableLongStateOf(33L) }
    var jumpSlowMoFactor by remember { mutableStateOf(1.0) }
    var jumpGroundLineScreenY by remember { mutableStateOf<Float?>(null) }
    var jumpGroundLineNativeY by remember { mutableStateOf<Float?>(null) }
    var jumpContactMs by remember { mutableStateOf<Double?>(null) }
    var jumpTakeoffMs by remember { mutableStateOf<Double?>(null) }
    var jumpLandingMs by remember { mutableStateOf<Double?>(null) }
    var activeAsymmetryLeg by remember { mutableStateOf("LEFT") } // "LEFT" (Pierna Izq) o "RIGHT" (Pierna Der)
    var leftJumpTakeoffMs by remember { mutableStateOf<Double?>(null) }
    var leftJumpLandingMs by remember { mutableStateOf<Double?>(null) }
    var rightJumpTakeoffMs by remember { mutableStateOf<Double?>(null) }
    var rightJumpLandingMs by remember { mutableStateOf<Double?>(null) }
    var isSubFrameInterpolated by remember { mutableStateOf(false) }
    var isDetectingJumpEvents by remember { mutableStateOf(false) }
    var jumpFootSamples by remember { mutableStateOf<List<JumpFootSample>>(emptyList()) }

    LaunchedEffect(videoUriString) {
        currentMode = "VBT"
        if (videoUriString.contains("sample_snatch")) {
            setupFamily = VBTExerciseFamily.DLP_OLYMPIC
            selectedVBTExercise = VBTExerciseModel.SNATCH_FLOOR
            showTutorialDialog = true
            tutorialStep = 0
        }
        withContext(Dispatchers.IO) {
            val parsedUri = Uri.parse(videoUriString)
            val dims = getVideoNativeDimensions(context, parsedUri)
            if (dims.first > 0 && dims.second > 0) {
                videoNativeWidth = dims.first
                videoNativeHeight = dims.second
            }
            val fpsMeta = extractVideoFpsMetadata(context, parsedUri)
            detectedVideoFps = fpsMeta.playbackFps
            detectedCaptureFps = fpsMeta.captureFps
            nativeFrameStepMs = fpsMeta.frameStepMs
            jumpSlowMoFactor = fpsMeta.autoSlowMoFactor
        }
    }

    // Gestures State (Pan / Zoom)
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Video playback control & scrubber (Módulo 4: Frame a Frame)
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isVideoMuted by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var videoDurationMs by remember { mutableLongStateOf(0L) }
    var currentVideoPosMs by remember { mutableLongStateOf(0L) }

        // Rango de Recorte (Inicio y Término de medición)
    var trimStartMs by remember { mutableLongStateOf(0L) }
    var trimEndMs by remember { mutableLongStateOf(0L) }

    // Bitmap del fotograma exacto pausado para navegación milimétrica y calibración sin desfase de I-Frames
    var stillFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var stillFrameJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Retriever compartido en memoria para respuesta instantánea (< 10 ms) sin reabrir el archivo en cada frame
    val sharedFrameRetriever = remember(videoUriString) {
        MediaMetadataRetriever().apply {
            try {
                setDataSource(context, Uri.parse(videoUriString))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    DisposableEffect(sharedFrameRetriever) {
        onDispose {
            try { sharedFrameRetriever.release() } catch (e: Exception) {}
        }
    }

    fun updateStillFrame(targetMs: Long) {
        stillFrameJob?.cancel()
        stillFrameJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val bm = sharedFrameRetriever.getFrameAtTime(targetMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: sharedFrameRetriever.getFrameAtTime(targetMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bm != null) {
                    withContext(Dispatchers.Main) {
                        stillFrameBitmap = bm
                    }
                }
            } catch (e: Exception) {
            }
        }
    }


    fun changePlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                mediaPlayerRef?.let { mp ->
                    val params = mp.playbackParams
                    params.speed = speed
                    mp.playbackParams = params
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Calibración Circular Kinovea con Disco Olímpico (45 cm)
    var isCalibrationMode by remember { mutableStateOf(false) }
    var circleCenter by remember { mutableStateOf<Offset?>(null) }
    var circleRadiusPx by remember { mutableFloatStateOf(85f) }
    var isCalibrated by remember { mutableStateOf(false) }
    var customRatioMmPerPx by remember { mutableStateOf<Double?>(null) }
    var isDetectingPlate by remember { mutableStateOf(false) }
    var screenBoxWidth by remember { mutableFloatStateOf(0f) }
    var screenBoxHeight by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isCalibrationMode) {
        if (isCalibrationMode) {
            videoViewRef?.pause()
            isPlaying = false
        }
    }

    fun runAutoDetectPlate(boxW: Float, boxH: Float) {
        val effectiveW = if (boxW > 0f) boxW else screenBoxWidth
        val effectiveH = if (boxH > 0f) boxH else screenBoxHeight
        if (effectiveW <= 0f || effectiveH <= 0f) return
        coroutineScope.launch {
            try {
                isDetectingPlate = true
                val result = withContext(Dispatchers.IO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, Uri.parse(videoUriString))
                        val timeUs = trimStartMs * 1000L
                        val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                            ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (bitmap != null) {
                            if (bitmap.width > 0 && bitmap.height > 0) {
                                videoNativeWidth = bitmap.width
                                videoNativeHeight = bitmap.height
                            }
                            val options = AccuratePoseDetectorOptions.Builder()
                                .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
                                .build()
                            val detector = PoseDetection.getClient(options)
                            val image = InputImage.fromBitmap(bitmap, 0)
                            val pose = try {
                                detector.process(image).await()
                            } catch (e: Exception) {
                                null
                            }
                            OlympicPlateAutoDetector.detect(bitmap, pose)
                        } else {
                            null
                        }
                    } finally {
                        try { retriever.release() } catch (e: Exception) {}
                    }
                }

                if (result != null) {
                    val bounds = calculateVideoRenderBounds(effectiveW, effectiveH, videoNativeWidth, videoNativeHeight)
                    val padPx = 20f
                    val maxR = (minOf(effectiveW, effectiveH) / 2f - padPx).coerceAtLeast(22f)
                    val screenRadius = (result.radiusPx * bounds.scale).coerceIn(22f, maxR)
                    val minX = (screenRadius + padPx).coerceAtMost(effectiveW / 2f)
                    val maxX = (effectiveW - screenRadius - padPx).coerceAtLeast(effectiveW / 2f)
                    val minY = (screenRadius + padPx).coerceAtMost(effectiveH / 2f)
                    val maxY = (effectiveH - screenRadius - padPx).coerceAtLeast(effectiveH / 2f)
                    val rawScreenX = bounds.left + result.center.x * bounds.scale
                    val rawScreenY = bounds.top + result.center.y * bounds.scale
                    val screenCenter = Offset(
                        rawScreenX.coerceIn(minX, maxX),
                        rawScreenY.coerceIn(minY, maxY)
                    )

                    circleCenter = screenCenter
                    circleRadiusPx = screenRadius
                    calibratedPlateCenterVideo = result.center
                    calibratedPlateRadiusVideo = result.radiusPx
                    isCalibrated = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isDetectingPlate = false
            }
        }
    }

    fun runAutoDetectJumpFlight(boxW: Float, boxH: Float) {
        val effectiveW = if (boxW > 0f) boxW else screenBoxWidth
        val effectiveH = if (boxH > 0f) boxH else screenBoxHeight
        if (effectiveW <= 0f || effectiveH <= 0f || isDetectingJumpEvents) return
        coroutineScope.launch {
            try {
                isDetectingJumpEvents = true
                val scanResult = withContext(Dispatchers.IO) {
                    val retriever = MediaMetadataRetriever()
                    val samples = mutableListOf<JumpFootSample>()
                    try {
                        retriever.setDataSource(context, Uri.parse(videoUriString))
                        val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val totalDur = durStr?.toLongOrNull() ?: videoDurationMs.coerceAtLeast(1000L)
                        val start = trimStartMs.coerceIn(0L, totalDur)
                        val end = if (trimEndMs in (start + 150L)..totalDur) trimEndMs else totalDur
                        val rangeMs = (end - start).coerceAtLeast(150L)
                        // Muestrear hasta ~65 fotogramas a lo largo del recorte para respuesta rápida y alta precisión
                        val stepMs = maxOf(nativeFrameStepMs, (rangeMs / 65L).coerceAtLeast(12L)).coerceAtMost(33L)

                        val options = AccuratePoseDetectorOptions.Builder()
                            .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
                            .build()
                        val detector = PoseDetection.getClient(options)

                        var t = start
                        while (t <= end) {
                            val bmp = retriever.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                            if (bmp != null) {
                                if (bmp.width > 0 && bmp.height > 0) {
                                    videoNativeWidth = bmp.width
                                    videoNativeHeight = bmp.height
                                }
                                val pose = try {
                                    detector.process(InputImage.fromBitmap(bmp, 0)).await()
                                } catch (e: Exception) { null }
                                if (pose != null) {
                                    val lf = pose.getPoseLandmark(PoseLandmark.LEFT_FOOT_INDEX)
                                        ?: pose.getPoseLandmark(PoseLandmark.LEFT_HEEL)
                                        ?: pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
                                    val rf = pose.getPoseLandmark(PoseLandmark.RIGHT_FOOT_INDEX)
                                        ?: pose.getPoseLandmark(PoseLandmark.RIGHT_HEEL)
                                        ?: pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
                                    if (lf != null && rf != null && (lf.inFrameLikelihood > 0.25f || rf.inFrameLikelihood > 0.25f)) {
                                        val fx = (lf.position.x + rf.position.x) / 2f
                                        val fy = maxOf(lf.position.y, rf.position.y)
                                        samples.add(JumpFootSample(t, fx, fy, lf.position.y, rf.position.y))
                                    }
                                }
                            }
                            t += stepMs
                        }
                    } finally {
                        try { retriever.release() } catch (e: Exception) {}
                    }
                    samples
                }

                if (scanResult.size >= 4) {
                    jumpFootSamples = scanResult
                    val sortedY = scanResult.map { it.footY }.sorted()
                    val autoGroundNativeY = sortedY[(sortedY.size * 0.85).toInt().coerceIn(0, sortedY.size - 1)]
                    jumpGroundLineNativeY = autoGroundNativeY

                    val (contact, takeoff, landing) = computeSubFrameJumpEvents(
                        samples = scanResult,
                        groundNativeY = autoGroundNativeY,
                        videoNativeHeight = videoNativeHeight,
                        jumpType = selectedJumpType
                    )
                    if (takeoff != null && landing != null && landing > takeoff + 80.0) {
                        jumpContactMs = contact
                        jumpTakeoffMs = takeoff
                        jumpLandingMs = landing
                        if (selectedJumpType == JumpType.ASYMMETRY_TEST) {
                            val leftSamples = scanResult.map { it.copy(footY = it.leftFootY) }
                            val rightSamples = scanResult.map { it.copy(footY = it.rightFootY) }
                            val leftGround = leftSamples.map { it.footY }.sorted()[(leftSamples.size * 0.85).toInt().coerceIn(0, leftSamples.size - 1)]
                            val rightGround = rightSamples.map { it.footY }.sorted()[(rightSamples.size * 0.85).toInt().coerceIn(0, rightSamples.size - 1)]
                            val (_, lTakeoff, lLanding) = computeSubFrameJumpEvents(leftSamples, leftGround, videoNativeHeight, JumpType.CMJ)
                            val (_, rTakeoff, rLanding) = computeSubFrameJumpEvents(rightSamples, rightGround, videoNativeHeight, JumpType.CMJ)
                            if (lTakeoff != null && lLanding != null && lLanding > lTakeoff + 80.0) {
                                leftJumpTakeoffMs = lTakeoff
                                leftJumpLandingMs = lLanding
                            } else {
                                leftJumpTakeoffMs = takeoff
                                leftJumpLandingMs = landing
                            }
                            if (rTakeoff != null && rLanding != null && rLanding > rTakeoff + 80.0) {
                                rightJumpTakeoffMs = rTakeoff
                                rightJumpLandingMs = rLanding
                            } else {
                                rightJumpTakeoffMs = takeoff
                                rightJumpLandingMs = landing
                            }
                        }
                        isSubFrameInterpolated = true
                        val seekTarget = takeoff.toLong()
                        currentVideoPosMs = seekTarget
                        videoViewRef?.let { seekVideoAccurate(mediaPlayerRef, it, seekTarget) }
                        updateStillFrame(seekTarget)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isDetectingJumpEvents = false
            }
        }
    }

    // Polling del tiempo de video y bucle dentro del rango recortado [trimStartMs, trimEndMs]
    LaunchedEffect(videoViewRef, isPlaying, trimStartMs, trimEndMs, isLoopingTrimmedRange, analysisSetupStep) {
        if (!isPlaying) return@LaunchedEffect
        while (isPlaying) {
            videoViewRef?.let { vv ->
                if (vv.isPlaying) {
                    val cur = vv.currentPosition.toLong()
                    currentVideoPosMs = cur
                    val shouldLoop = (isLoopingTrimmedRange || analysisSetupStep == AnalysisSetupStep.CALIBRATE)
                    if (shouldLoop && trimEndMs > trimStartMs + 200L && cur >= trimEndMs) {
                        vv.seekTo(trimStartMs.toInt())
                        currentVideoPosMs = trimStartMs
                    }
                }
            }
            delay(50)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when {
                            !isReadyToAnalyze -> {
                                when (analysisSetupStep) {
                                    AnalysisSetupStep.TRIM_VIDEO -> "Trim Video"
                                    AnalysisSetupStep.SELECT_EXERCISE -> if (currentMode == "VBT") "Halterofilia / VBT" else "Salto Vertical"
                                    AnalysisSetupStep.CALIBRATE -> if (currentMode == "JUMP") "Calibración de Salto (${selectedJumpType.name})" else "Calibración y Carga"
                                }
                            }
                            isProcessing -> "Analizando..."
                            else -> if (currentMode == "JUMP") "Resultados Salto (${selectedJumpType.name}) Biomecánicos" else "Resultados ${selectedVBTExercise.displayName} Biomecánicos"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (!isReadyToAnalyze) {
                        when (analysisSetupStep) {
                            AnalysisSetupStep.TRIM_VIDEO -> {
                                IconButton(onClick = onCloseClick) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Atrás", tint = Color.White)
                                }
                            }
                            AnalysisSetupStep.SELECT_EXERCISE -> {
                                IconButton(onClick = { analysisSetupStep = AnalysisSetupStep.TRIM_VIDEO }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Volver a Recorte", tint = Color.White)
                                }
                            }
                            AnalysisSetupStep.CALIBRATE -> {
                                IconButton(onClick = {
                                    analysisSetupStep = AnalysisSetupStep.TRIM_VIDEO
                                    isCalibrationMode = false
                                }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Volver a Ejercicio", tint = Color.White)
                                }
                            }
                        }
                    } else {
                        IconButton(onClick = onCloseClick) {
                            Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { showTutorialDialog = true }) {
                        Icon(
                            Icons.Default.HelpOutline,
                            contentDescription = "Tutorial",
                            tint = Color(0xFF00FFCC),
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Tutorial",
                            color = Color(0xFF00FFCC),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121212),
                    titleContentColor = Color(0xFF00FFCC)
                )
            )
        },
        containerColor = Color(0xFF121212)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            if (!isReadyToAnalyze) {
                when (analysisSetupStep) {
                    AnalysisSetupStep.TRIM_VIDEO -> {
                        // ── PASO 1: RECORTE SIMPLE DE VIDEO (IMAGEN 1) ──
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Contenedor de Video Centrado con Gestos
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                val boxW = constraints.maxWidth.toFloat()
                                val boxH = constraints.maxHeight.toFloat()

                                LaunchedEffect(boxW, boxH) {
                                    screenBoxWidth = boxW
                                    screenBoxHeight = boxH
                                    if (boxW > 0f && (circleCenter == null || !isCalibrated) && !isDetectingPlate) {
                                        runAutoDetectPlate(boxW, boxH)
                                    }
                                }

                                val bounds = calculateVideoRenderBounds(boxW, boxH, videoNativeWidth, videoNativeHeight)
                                val rWDp = with(LocalDensity.current) { bounds.renderWidth.toDp() }
                                val rHDp = with(LocalDensity.current) { bounds.renderHeight.toDp() }

                                Box(
                                    modifier = Modifier
                                        .size(rWDp, rHDp)
                                        .align(Alignment.Center)
                                        .pointerInput(Unit) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                scale = (scale * zoom).coerceIn(1f, 5f)
                                                offsetX += pan.x
                                                offsetY += pan.y
                                            }
                                        }
                                ) {
                                    AndroidView(
                                        factory = { ctx ->
                                            VideoView(ctx).apply {
                                                setVideoURI(Uri.parse(videoUriString))
                                                setOnPreparedListener { mp ->
                                                    mediaPlayerRef = mp
                                                    videoDurationMs = duration.toLong()
                                                    if (trimEndMs == 0L || trimEndMs > videoDurationMs) {
                                                        trimEndMs = videoDurationMs
                                                    }
                                                    if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                                        val isPortrait = videoNativeHeight >= videoNativeWidth
                                                        val rawW = mp.videoWidth
                                                        val rawH = mp.videoHeight
                                                        if (isPortrait) {
                                                            videoNativeWidth = minOf(rawW, rawH)
                                                            videoNativeHeight = maxOf(rawW, rawH)
                                                        } else {
                                                            videoNativeWidth = maxOf(rawW, rawH)
                                                            videoNativeHeight = minOf(rawW, rawH)
                                                        }
                                                    }
                                                    if (isVideoMuted) mp.setVolume(0f, 0f) else mp.setVolume(1f, 1f)
                                                    mp.isLooping = true
                                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                                        try {
                                                            val params = mp.playbackParams
                                                            params.speed = playbackSpeed
                                                            mp.playbackParams = params
                                                        } catch (e: Exception) { }
                                                    }
                                                    pause()
                                                    isPlaying = false
                                                    seekVideoAccurate(mp, this, trimStartMs)
                                                    updateStillFrame(trimStartMs)
                                                }
                                                videoViewRef = this
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer(
                                                scaleX = scale,
                                                scaleY = scale,
                                                translationX = offsetX,
                                                translationY = offsetY
                                            )
                                    )

                                    // Fotograma fijo en pausa: elimina 100% de parpadeo a frame 0 al navegar cuadro a cuadro
                                    if (!isPlaying && stillFrameBitmap != null) {
                                        Image(
                                            bitmap = stillFrameBitmap!!.asImageBitmap(),
                                            contentDescription = "Fotograma pausado",
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .graphicsLayer(
                                                    scaleX = scale,
                                                    scaleY = scale,
                                                    translationX = offsetX,
                                                    translationY = offsetY
                                                ),
                                            contentScale = ContentScale.Fit
                                        )
                                    }

                                    // Controles superiores en video (Audio y Reset Zoom)
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        FilledTonalIconButton(
                                            onClick = {
                                                isVideoMuted = !isVideoMuted
                                                mediaPlayerRef?.let { mp ->
                                                    val vol = if (isVideoMuted) 0f else 1f
                                                    mp.setVolume(vol, vol)
                                                }
                                            },
                                            modifier = Modifier.size(34.dp),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = if (isVideoMuted) Color(0xCCFF5252) else Color(0x99000000),
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Icon(
                                                if (isVideoMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                                contentDescription = if (isVideoMuted) "Activar Sonido" else "Silenciar Video",
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }

                                        if (currentMode == "JUMP") {
                                            Surface(
                                                onClick = {
                                                    if (scale > 1.3f) {
                                                        scale = 1f
                                                        offsetX = 0f
                                                        offsetY = 0f
                                                    } else {
                                                        scale = 2.2f
                                                        offsetX = 0f
                                                        offsetY = -boxH * 0.28f
                                                    }
                                                },
                                                shape = RoundedCornerShape(16.dp),
                                                color = if (scale > 1.3f) Color(0xFF00FFCC) else Color(0xB3000000),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FFCC)),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.ZoomIn,
                                                        contentDescription = "Lupa a los pies",
                                                        tint = if (scale > 1.3f) Color.Black else Color(0xFF00FFCC),
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Text(
                                                        if (scale > 1.3f) "1x" else "Pies 2x",
                                                        color = if (scale > 1.3f) Color.Black else Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }

                                        IconButton(
                                            onClick = {
                                                scale = 1f
                                                offsetX = 0f
                                                offsetY = 0f
                                            },
                                            modifier = Modifier
                                                .size(34.dp)
                                                .background(Color(0x99000000), shape = CircleShape)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Centrar", tint = Color.White, modifier = Modifier.size(17.dp))
                                        }
                                    }
                                }
                            }

                            // Banner Guiado de Recorte
                            Surface(
                                color = Color(0x1F00FFCC),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x4400FFCC)),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF00FFCC), modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Paso 1: Fija ▶| Inicio al despegar del suelo y |◀ Fin al bloquear overhead.",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                            }

                            // Fila de Tiempos: Tiempo Actual, Duración del Recorte y Duración Total (Altura fija para evitar jitter)
                            val trimDurationSec = ((trimEndMs - trimStartMs).coerceAtLeast(0L) / 1000.0)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⏱ ${formatTimeMs(currentVideoPosMs)}",
                                    color = Color(0xFF00FFCC),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "Recorte: ${String.format(Locale.US, "%.2fs", trimDurationSec)}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = formatTimeMs(videoDurationMs),
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            // Dual RangeSlider para inicio y fin
                            val maxDur = if (videoDurationMs > 0) videoDurationMs.toFloat() else 10000f
                            val safeStart = trimStartMs.toFloat().coerceIn(0f, maxDur)
                            val safeEnd = trimEndMs.toFloat().coerceIn(safeStart, maxDur)
                            RangeSlider(
                                value = safeStart..safeEnd,
                                onValueChange = { range ->
                                    val dStart = kotlin.math.abs(range.start.toLong() - trimStartMs)
                                    val dEnd = kotlin.math.abs(range.endInclusive.toLong() - trimEndMs)
                                    val changedStart = dStart >= dEnd
                                    trimStartMs = range.start.toLong()
                                    trimEndMs = range.endInclusive.toLong()
                                    val targetMs = if (changedStart) trimStartMs else trimEndMs
                                    currentVideoPosMs = targetMs
                                    if (changedStart && dStart > 30L) {
                                        isCalibrated = false
                                        circleCenter = null
                                        calibratedPlateCenterVideo = null
                                        calibratedPlateRadiusVideo = 0f
                                    }
                                    videoViewRef?.let { vv ->
                                        vv.pause()
                                        isPlaying = false
                                        seekVideoAccurate(mediaPlayerRef, vv, targetMs)
                                    }
                                    updateStillFrame(targetMs)
                                },
                                onValueChangeFinished = {
                                    videoViewRef?.let { vv ->
                                        seekVideoAccurate(mediaPlayerRef, vv, trimStartMs)
                                        currentVideoPosMs = trimStartMs
                                    }
                                    updateStillFrame(trimStartMs)
                                    if (!isCalibrated && (screenBoxWidth > 0f || screenBoxHeight > 0f)) {
                                        runAutoDetectPlate(screenBoxWidth, screenBoxHeight)
                                    }
                                },
                                valueRange = 0f..maxDur,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.LightGray,
                                    inactiveTrackColor = Color(0xFF333333)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                            )

                            // Indicadores de tiempo de inicio y fin debajo del slider
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp)
                                    .padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Inicio: ${formatTimeMs(trimStartMs)}",
                                    color = Color(0xFF00FFCC),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "Fin: ${formatTimeMs(trimEndMs)}",
                                    color = Color(0xFF00FFCC),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            // Fila de precisión: Botones frame a frame + Fijar marcas + FAB checkmark circular (Imagen 1)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    // -1 Frame
                                    FilledTonalIconButton(
                                        onClick = {
                                            videoViewRef?.let { vv ->
                                                vv.pause()
                                                isPlaying = false
                                                val newPos = (currentVideoPosMs - 33L).coerceAtLeast(0L)
                                                currentVideoPosMs = newPos
                                                seekVideoAccurate(mediaPlayerRef, vv, newPos)
                                                updateStillFrame(newPos)
                                            }
                                        },
                                        modifier = Modifier.size(34.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = Color(0xFF262626),
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(Icons.Default.FastRewind, contentDescription = "-1 Frame", modifier = Modifier.size(16.dp))
                                    }

                                    // Play / Pause
                                    FilledTonalIconButton(
                                        onClick = {
                                            videoViewRef?.let { vv ->
                                                if (isPlaying) {
                                                    vv.pause()
                                                    isPlaying = false
                                                    val cur = vv.currentPosition.toLong()
                                                    currentVideoPosMs = cur
                                                    updateStillFrame(cur)
                                                } else {
                                                    stillFrameBitmap = null
                                                    vv.start()
                                                    isPlaying = true
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(34.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = Color(0xFF00FFCC),
                                            contentColor = Color.Black
                                        )
                                    ) {
                                        Icon(
                                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play/Pausa",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // +1 Frame
                                    FilledTonalIconButton(
                                        onClick = {
                                            videoViewRef?.let { vv ->
                                                vv.pause()
                                                isPlaying = false
                                                val maxDur = if (videoDurationMs > 0) videoDurationMs else vv.duration.toLong()
                                                val newPos = (currentVideoPosMs + 33L).coerceAtMost(maxDur)
                                                currentVideoPosMs = newPos
                                                seekVideoAccurate(mediaPlayerRef, vv, newPos)
                                                updateStillFrame(newPos)
                                            }
                                        },
                                        modifier = Modifier.size(34.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = Color(0xFF262626),
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(Icons.Default.FastForward, contentDescription = "+1 Frame", modifier = Modifier.size(16.dp))
                                    }

                                    // Fijar Inicio
                                    OutlinedButton(
                                        onClick = {
                                            trimStartMs = currentVideoPosMs.coerceAtMost(trimEndMs - 100L)
                                            isCalibrated = false
                                            circleCenter = null
                                            calibratedPlateCenterVideo = null
                                            calibratedPlateRadiusVideo = 0f
                                            updateStillFrame(trimStartMs)
                                            if (screenBoxWidth > 0f || screenBoxHeight > 0f) {
                                                runAutoDetectPlate(screenBoxWidth, screenBoxHeight)
                                            }
                                        },
                                        modifier = Modifier.height(34.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00FFCC))
                                    ) {
                                        Text("▶| Inicio", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // Fijar Fin
                                    OutlinedButton(
                                        onClick = {
                                            trimEndMs = currentVideoPosMs.coerceAtLeast(trimStartMs + 100L)
                                            updateStillFrame(trimEndMs)
                                        },
                                        modifier = Modifier.height(34.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00FFCC))
                                    ) {
                                        Text("|◀ Fin", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // Probar Bucle
                                    FilledTonalIconButton(
                                        onClick = {
                                            isLoopingTrimmedRange = !isLoopingTrimmedRange
                                            if (isLoopingTrimmedRange) {
                                                stillFrameBitmap = null
                                                videoViewRef?.let { seekVideoAccurate(mediaPlayerRef, it, trimStartMs) }
                                                currentVideoPosMs = trimStartMs
                                                videoViewRef?.start()
                                                isPlaying = true
                                            } else {
                                                videoViewRef?.pause()
                                                isPlaying = false
                                                updateStillFrame(currentVideoPosMs)
                                            }
                                        },
                                        modifier = Modifier.size(34.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = if (isLoopingTrimmedRange) Color(0xFF00FFCC) else Color(0xFF262626),
                                            contentColor = if (isLoopingTrimmedRange) Color.Black else Color.White
                                        )
                                    ) {
                                        Icon(Icons.Default.Repeat, contentDescription = "Probar", modifier = Modifier.size(16.dp))
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Botón FAB Circular Checkmark (Confirmar y avanzar a Selección de Ejercicio)
                                FloatingActionButton(
                                    onClick = {
                                        isLoopingTrimmedRange = false
                                        videoViewRef?.pause()
                                        isPlaying = false
                                        currentVideoPosMs = trimStartMs
                                        videoViewRef?.let { seekVideoAccurate(mediaPlayerRef, it, trimStartMs) }
                                        updateStillFrame(trimStartMs)
                                        allUsers = userRepository.getUsers()
                                        selectedUserState = userRepository.getActiveUser()
                                        showPreCalibrationSetupDialog = true
                                    },
                                    containerColor = Color(0xFFE0E0E0),
                                    contentColor = Color.Black,
                                    shape = CircleShape,
                                    modifier = Modifier.size(50.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "Confirmar Recorte", modifier = Modifier.size(26.dp))
                                }
                            }
                        }
                    }

                    AnalysisSetupStep.SELECT_EXERCISE -> {
                        // ── PASO 2: SELECCIÓN DE EJERCICIO PANTALLA COMPLETA (IMAGEN 2) ──
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                if (currentMode == "VBT") "¿Qué ejercicio específico vas a realizar?" else "¿Qué tipo de Salto Vertical?",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (currentMode == "VBT")
                                    "Selecciona el ejercicio para calibrar las fórmulas biomecánicas y el 1RM"
                                else
                                    "Configura las fórmulas de potencia Sayers, tiempo de vuelo y reactividad",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            if (currentMode == "VBT") {
                                // Pestañas: DLP (Olímpicos) vs Ley de Hill (Fuerza)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    VBTExerciseFamily.values().forEach { family ->
                                        val isSelected = setupFamily == family
                                        val familyColor = Color(0xFFFFB300) // Oro ámbar vibrante para diferenciar categoría de los ejercicios
                                        Surface(
                                            onClick = { setupFamily = family },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) familyColor.copy(alpha = 0.22f) else Color(0xFF1E1E1E),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.5.dp,
                                                if (isSelected) familyColor else Color(0xFF333333)
                                            ),
                                            modifier = Modifier.weight(1f).height(40.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center,
                                                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)
                                            ) {
                                                Text(family.icon, fontSize = 16.sp)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    family.label,
                                                    color = if (isSelected) Color(0xFFFFD54F) else Color.LightGray,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Cuadrícula de Tarjetas ocupando todo el alto disponible (weight(1f)) sin scroll vertical
                                val exercises = VBTExerciseModel.values().filter { it.family == setupFamily }
                                val rows = exercises.chunked(2)

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rows.forEach { rowPair ->
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            rowPair.forEach { model ->
                                                val isSelected = selectedVBTExercise == model
                                                Surface(
                                                    onClick = {
                                                        selectedVBTExercise = model
                                                        currentMode = "VBT"
                                                        analysisSetupStep = AnalysisSetupStep.CALIBRATE
                                                    },
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (isSelected) Color(0xFF162520) else Color(0xFF1E1E1E),
                                                    border = androidx.compose.foundation.BorderStroke(
                                                        1.5.dp,
                                                        if (isSelected) Color(0xFF00FFCC) else Color(0xFF2C2C2C)
                                                    ),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .fillMaxHeight()
                                                ) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                                        verticalArrangement = Arrangement.Center
                                                    ) {
                                                        Text(
                                                            model.displayName,
                                                            color = if (isSelected) Color(0xFF00FFCC) else Color.White,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Spacer(modifier = Modifier.height(3.dp))
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                if (model.usesPeakVelocity) "⚡ PV • ${model.mvt} m/s" else "🏋️ VM • ${model.mvt} m/s",
                                                                color = if (isSelected) Color(0xFF00FFCC) else Color(0xFF00E676),
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                            Text(
                                                                if (model.displayName.contains("Hang", ignoreCase = true)) "Colgante" else if (model.family == VBTExerciseFamily.DLP_OLYMPIC) "Suelo" else "Bilateral",
                                                                color = Color.Gray,
                                                                fontSize = 10.sp
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            if (rowPair.size == 1) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Salto Vertical (CMJ, SJ, Drop Jump) ocupando el espacio vertical
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    listOf(
                                        Triple(JumpType.CMJ, "Salto con Contramovimiento (CMJ)", "Inicio erguido, flexión y despegue explosivo • Altura Sayers y Watts pico"),
                                        Triple(JumpType.SJ, "Squat Jump (SJ)", "Salto concéntrico puro a 90° sin contramovimiento • Fuerza concéntrica y EUR"),
                                        Triple(JumpType.DROP_JUMP, "Drop Jump (Caída desde Cajón)", "Contacto reactivo mínimo (<250 ms) • Reactividad, tiempo contacto y RSI"),
                                        Triple(JumpType.ASYMMETRY_TEST, "Test de Asimetría (Izq vs Der)", "Comparación unipodal/bilateral • % Asimetría Inter-Limb y riesgo lesivo")
                                    ).forEach { (jType, title, desc) ->
                                        val isSelected = selectedJumpType == jType
                                        Surface(
                                            onClick = {
                                                selectedJumpType = jType
                                                VerticalJumpAnalyzer.currentJumpType = jType
                                                currentMode = "JUMP"
                                                analysisSetupStep = AnalysisSetupStep.CALIBRATE
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) Color(0xFF162520) else Color(0xFF1E1E1E),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.5.dp,
                                                if (isSelected) Color(0xFF00FFCC) else Color(0xFF2C2C2C)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        title,
                                                        color = if (isSelected) Color(0xFF00FFCC) else Color.White,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(desc, color = Color.Gray, fontSize = 11.sp)
                                                }
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = null,
                                                    tint = if (isSelected) Color(0xFF00FFCC) else Color.Gray
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    currentMode = if (currentMode == "VBT") "JUMP" else "VBT"
                                },
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (currentMode == "VBT") "Cambiar a Salto Vertical" else "Cambiar a Halterofilia / VBT",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    AnalysisSetupStep.CALIBRATE -> {
                        // ── PASO 3: CALIBRACIÓN MÉTRICA Y CARGA (IMAGEN 3) ──
                        // Al ingresar a calibración, asegurar que el video inicie detenido en el primer frame y auto-detectar
                        LaunchedEffect(analysisSetupStep, trimStartMs, currentMode) {
                            if (analysisSetupStep == AnalysisSetupStep.CALIBRATE) {
                                videoViewRef?.pause()
                                isPlaying = false
                                currentVideoPosMs = trimStartMs
                                videoViewRef?.let { seekVideoAccurate(mediaPlayerRef, it, trimStartMs) }
                                updateStillFrame(trimStartMs)
                                if (currentMode == "JUMP") {
                                    if (jumpGroundLineScreenY == null && screenBoxHeight > 0f) {
                                        jumpGroundLineScreenY = screenBoxHeight * 0.78f
                                    }
                                    if (jumpTakeoffMs == null && !isDetectingJumpEvents) {
                                        runAutoDetectJumpFlight(screenBoxWidth, screenBoxHeight)
                                    }
                                } else {
                                    if ((circleCenter == null || !isCalibrated) && !isDetectingPlate) {
                                        runAutoDetectPlate(screenBoxWidth, screenBoxHeight)
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Contenedor de Video Centrado con Render Bounds Exactos (Sin márgenes exteriores que desplacen las flechas)
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                val boxW = constraints.maxWidth.toFloat()
                                val boxH = constraints.maxHeight.toFloat()

                                LaunchedEffect(boxW, boxH) {
                                    screenBoxWidth = boxW
                                    screenBoxHeight = boxH
                                }

                                val bounds = calculateVideoRenderBounds(boxW, boxH, videoNativeWidth, videoNativeHeight)
                                val rWDp = with(LocalDensity.current) { bounds.renderWidth.toDp() }
                                val rHDp = with(LocalDensity.current) { bounds.renderHeight.toDp() }
                                val edgePadPx = with(LocalDensity.current) { 14.dp.toPx() }

                                // Reajustar centro y radio dentro de todo el ancho de la caja del video (incluyendo franjas negras laterales)
                                LaunchedEffect(boxW, boxH, bounds.renderWidth, bounds.renderHeight, calibratedPlateCenterVideo, calibratedPlateRadiusVideo) {
                                    if (boxW > 0f && boxH > 0f && bounds.renderWidth > 0f && bounds.renderHeight > 0f) {
                                        val maxR = (minOf(boxW, boxH) / 2f - edgePadPx).coerceAtLeast(22f)
                                        val vidCenter = calibratedPlateCenterVideo
                                        val vidRad = calibratedPlateRadiusVideo
                                        if (vidCenter != null && vidRad > 5f && videoNativeWidth > 0 && videoNativeHeight > 0) {
                                            val sr = (vidRad * bounds.scale).coerceIn(22f, maxR)
                                            val minX = (sr + edgePadPx).coerceAtMost(boxW / 2f)
                                            val maxX = (boxW - sr - edgePadPx).coerceAtLeast(boxW / 2f)
                                            val minY = (sr + edgePadPx).coerceAtMost(boxH / 2f)
                                            val maxY = (boxH - sr - edgePadPx).coerceAtLeast(boxH / 2f)
                                            val rawScreenX = bounds.left + vidCenter.x * bounds.scale
                                            val rawScreenY = bounds.top + vidCenter.y * bounds.scale
                                            circleRadiusPx = sr
                                            circleCenter = Offset(
                                                rawScreenX.coerceIn(minX, maxX),
                                                rawScreenY.coerceIn(minY, maxY)
                                            )
                                        } else {
                                            val sr = circleRadiusPx.coerceIn(22f, maxR)
                                            val minX = (sr + edgePadPx).coerceAtMost(boxW / 2f)
                                            val maxX = (boxW - sr - edgePadPx).coerceAtLeast(boxW / 2f)
                                            val minY = (sr + edgePadPx).coerceAtMost(boxH / 2f)
                                            val maxY = (boxH - sr - edgePadPx).coerceAtLeast(boxH / 2f)
                                            val c = circleCenter ?: Offset(boxW / 2f, boxH * 0.65f)
                                            circleRadiusPx = sr
                                            circleCenter = Offset(
                                                c.x.coerceIn(minX, maxX),
                                                c.y.coerceIn(minY, maxY)
                                            )
                                        }
                                    }
                                }

                                // Contenedor del video renderizado centrado dentro de la caja completa
                                Box(
                                    modifier = Modifier
                                        .size(rWDp, rHDp)
                                        .clip(RoundedCornerShape(0.dp))
                                        .align(Alignment.Center)
                                ) {
                                    AndroidView(
                                        factory = { ctx ->
                                            VideoView(ctx).apply {
                                                setVideoURI(Uri.parse(videoUriString))
                                                setOnPreparedListener { mp ->
                                                    mediaPlayerRef = mp
                                                    videoDurationMs = duration.toLong()
                                                    if (trimEndMs == 0L || trimEndMs > videoDurationMs) {
                                                        trimEndMs = videoDurationMs
                                                    }
                                                    if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                                        val isPortrait = videoNativeHeight >= videoNativeWidth
                                                        val rawW = mp.videoWidth
                                                        val rawH = mp.videoHeight
                                                        if (isPortrait) {
                                                            videoNativeWidth = minOf(rawW, rawH)
                                                            videoNativeHeight = maxOf(rawW, rawH)
                                                        } else {
                                                            videoNativeWidth = maxOf(rawW, rawH)
                                                            videoNativeHeight = minOf(rawW, rawH)
                                                        }
                                                    }
                                                    if (isVideoMuted) mp.setVolume(0f, 0f) else mp.setVolume(1f, 1f)
                                                    mp.isLooping = true
                                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                                        try {
                                                            val params = mp.playbackParams
                                                            params.speed = playbackSpeed
                                                            mp.playbackParams = params
                                                        } catch (e: Exception) { }
                                                    }
                                                    // Iniciar con el video detenido en el primer frame del recorte
                                                    seekVideoAccurate(mp, this, trimStartMs)
                                                    currentVideoPosMs = trimStartMs
                                                    pause()
                                                    isPlaying = false
                                                    updateStillFrame(trimStartMs)
                                                }
                                                videoViewRef = this
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer(
                                                scaleX = scale,
                                                scaleY = scale,
                                                translationX = offsetX,
                                                translationY = offsetY
                                            )
                                    )

                                    if (!isPlaying && stillFrameBitmap != null) {
                                        Image(
                                            bitmap = stillFrameBitmap!!.asImageBitmap(),
                                            contentDescription = "Frame de calibración",
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .graphicsLayer(
                                                    scaleX = scale,
                                                    scaleY = scale,
                                                    translationX = offsetX,
                                                    translationY = offsetY
                                                ),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                }

                                // Capa de ancho completo (boxW x boxH):
                                // - En modo VBT: Círculo Tracker de 45 cm (intacto).
                                // - En modo JUMP: Línea de Nivel de Suelo (Zapatillas) + Badge de FPS e Interpolación Sub-Frame.
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    var hasUserInteractedWithTracker by remember(analysisSetupStep, currentMode) { mutableStateOf(currentMode == "JUMP") }
                                    if (currentMode == "JUMP") {
                                        // Sin línea de piso (el tiempo de vuelo depende exclusivamente de los frames de Despegue y Aterrizaje).
                                        // Badge Superior Izquierdo en Salto: FPS reales detectados + Interpolación Sub-Frame + Lupa Pies 2x
                                        Surface(
                                            color = Color(0xD9101620),
                                            shape = RoundedCornerShape(8.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x8800FFCC)),
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    "🎥 ${kotlin.math.round(detectedVideoFps).toInt()} FPS (${nativeFrameStepMs} ms/f)",
                                                    color = Color(0xFF00FFCC),
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                if (jumpSlowMoFactor > 1.1) {
                                                    Text(
                                                        "• Slow-Mo 1/${jumpSlowMoFactor.toInt()}x",
                                                        color = Color(0xFFFFD600),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                } else if (isSubFrameInterpolated) {
                                                    Text(
                                                        "• ✨ Sub-Frame IA",
                                                        color = Color(0xFF00E676),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        val maxAllowedRadius = (minOf(boxW, boxH) / 2f - edgePadPx).coerceAtLeast(20f)
                                        val clampedRadius = circleRadiusPx.coerceIn(20f, maxAllowedRadius)
                                        val minCX = (clampedRadius + edgePadPx).coerceAtMost(boxW / 2f)
                                        val maxCX = (boxW - clampedRadius - edgePadPx).coerceAtLeast(boxW / 2f)
                                        val minCY = (clampedRadius + edgePadPx).coerceAtMost(boxH / 2f)
                                        val maxCY = (boxH - clampedRadius - edgePadPx).coerceAtLeast(boxH / 2f)
                                        val rawCenter = circleCenter ?: Offset(boxW / 2f, boxH * 0.65f)
                                        val activeCenter = Offset(
                                            rawCenter.x.coerceIn(minCX, maxCX),
                                            rawCenter.y.coerceIn(minCY, maxCY)
                                        )
                                        KinoveaCircleReticleCanvas(
                                            center = activeCenter,
                                            radius = clampedRadius,
                                            onCenterChange = {
                                                circleCenter = it
                                                hasUserInteractedWithTracker = true
                                            },
                                            onRadiusChange = {
                                                circleRadiusPx = it
                                                hasUserInteractedWithTracker = true
                                            },
                                            onInteraction = {
                                                hasUserInteractedWithTracker = true
                                            }
                                        )
                                    }

                                    // Shader negro translúcido + Símbolo táctil intermitente para enseñar a mover y cambiar tamaño del tracker.
                                    // Al pinchar en cualquier parte se oculta de inmediato y se ve el video normal.
                                    if (!hasUserInteractedWithTracker) {
                                        val infiniteTransition = rememberInfiniteTransition(label = "pulseTouchShader")
                                        val blinkAlpha by infiniteTransition.animateFloat(
                                            initialValue = 0.25f,
                                            targetValue = 1.0f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(650, easing = FastOutSlowInEasing),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "blinkAlpha"
                                        )
                                        val handOffsetX by infiniteTransition.animateFloat(
                                            initialValue = -14f,
                                            targetValue = 14f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(1100, easing = FastOutSlowInEasing),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "handOffsetX"
                                        )
                                        val handScale by infiniteTransition.animateFloat(
                                            initialValue = 0.92f,
                                            targetValue = 1.08f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(650, easing = FastOutSlowInEasing),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "handScale"
                                        )

                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color(0xA6000000))
                                                .pointerInput(Unit) {
                                                    detectTapGestures(
                                                        onPress = {
                                                            hasUserInteractedWithTracker = true
                                                        }
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                                modifier = Modifier
                                                    .padding(20.dp)
                                                    .graphicsLayer {
                                                        alpha = blinkAlpha
                                                        scaleX = handScale
                                                        scaleY = handScale
                                                    }
                                            ) {
                                                // Dibujo vectorial de la mano táctil con ondas concéntricas (igual a la referencia) y flechas de desplazamiento
                                                Canvas(
                                                    modifier = Modifier
                                                        .size(110.dp)
                                                        .graphicsLayer { translationX = handOffsetX }
                                                ) {
                                                    val w = size.width
                                                    val h = size.height
                                                    val strokeWhite = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                                    val strokeCyan = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

                                                    // Ondas táctiles concéntricas superiores (arcos alrededor de la punta del dedo índice)
                                                    val tipX = w * 0.44f
                                                    val tipY = h * 0.30f
                                                    drawArc(
                                                        color = Color(0xFF00FFCC),
                                                        startAngle = 155f,
                                                        sweepAngle = 230f,
                                                        useCenter = false,
                                                        topLeft = Offset(tipX - 16.dp.toPx(), tipY - 16.dp.toPx()),
                                                        size = Size(32.dp.toPx(), 32.dp.toPx()),
                                                        style = strokeCyan
                                                    )
                                                    drawArc(
                                                        color = Color(0xFF00FFCC).copy(alpha = 0.75f),
                                                        startAngle = 160f,
                                                        sweepAngle = 220f,
                                                        useCenter = false,
                                                        topLeft = Offset(tipX - 25.dp.toPx(), tipY - 25.dp.toPx()),
                                                        size = Size(50.dp.toPx(), 50.dp.toPx()),
                                                        style = strokeCyan
                                                    )

                                                    // Silueta de la mano táctil (índice extendido, nudillos y palma redondeada)
                                                    val handPath = Path().apply {
                                                        // Dedo índice subiendo y redondeando en la punta
                                                        moveTo(w * 0.38f, h * 0.56f)
                                                        lineTo(w * 0.38f, h * 0.30f)
                                                        cubicTo(w * 0.38f, h * 0.22f, w * 0.50f, h * 0.22f, w * 0.50f, h * 0.30f)
                                                        lineTo(w * 0.50f, h * 0.50f)

                                                        // Dedo medio
                                                        cubicTo(w * 0.50f, h * 0.43f, w * 0.61f, h * 0.43f, w * 0.61f, h * 0.50f)
                                                        lineTo(w * 0.61f, h * 0.55f)

                                                        // Dedo anular
                                                        cubicTo(w * 0.61f, h * 0.47f, w * 0.72f, h * 0.47f, w * 0.72f, h * 0.54f)
                                                        lineTo(w * 0.72f, h * 0.59f)

                                                        // Dedo meñique y borde derecho de la palma
                                                        cubicTo(w * 0.72f, h * 0.52f, w * 0.82f, h * 0.53f, w * 0.82f, h * 0.61f)
                                                        lineTo(w * 0.82f, h * 0.74f)

                                                        // Base redondeada de la palma
                                                        cubicTo(w * 0.82f, h * 0.92f, w * 0.32f, h * 0.94f, w * 0.27f, h * 0.76f)

                                                        // Pulgar izquierdo
                                                        lineTo(w * 0.23f, h * 0.58f)
                                                        cubicTo(w * 0.22f, h * 0.48f, w * 0.33f, h * 0.47f, w * 0.35f, h * 0.56f)
                                                        lineTo(w * 0.38f, h * 0.62f)
                                                    }
                                                    drawPath(
                                                        path = handPath,
                                                        color = Color(0x33000000)
                                                    )
                                                    drawPath(
                                                        path = handPath,
                                                        color = Color.White,
                                                        style = strokeWhite
                                                    )
                                                }

                                                Surface(
                                                    color = Color(0xE610151C),
                                                    shape = RoundedCornerShape(14.dp),
                                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00FFCC))
                                                ) {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                                    ) {
                                                        Text(
                                                            if (currentMode == "JUMP") "Desliza la línea al nivel de las zapatillas"
                                                            else "Toca y desliza para mover el Tracker",
                                                            color = Color.White,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                            textAlign = TextAlign.Center
                                                        )
                                                        Spacer(modifier = Modifier.height(3.dp))
                                                        Text(
                                                            if (currentMode == "JUMP") "Marca 🛫 Despegue y 🛬 Aterrizaje o usa ⚡ Auto IA Sub-Frame"
                                                            else "Arrastra el punto amarillo para ajustar al tamaño del disco",
                                                            color = Color(0xFF00FFCC),
                                                            fontSize = 11.sp,
                                                            textAlign = TextAlign.Center
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Botones Superiores Derechos ÚNICOS: Lupa Pies 2x (en Salto), Audio y Reset Zoom
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (currentMode == "JUMP") {
                                            Surface(
                                                onClick = {
                                                    if (scale > 1.3f) {
                                                        scale = 1f
                                                        offsetX = 0f
                                                        offsetY = 0f
                                                    } else {
                                                        scale = 2.2f
                                                        offsetX = 0f
                                                        offsetY = -boxH * 0.28f
                                                    }
                                                },
                                                shape = RoundedCornerShape(16.dp),
                                                color = if (scale > 1.3f) Color(0xFF00FFCC) else Color(0xB3000000),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FFCC)),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.ZoomIn,
                                                        contentDescription = "Lupa a los pies",
                                                        tint = if (scale > 1.3f) Color.Black else Color(0xFF00FFCC),
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Text(
                                                        if (scale > 1.3f) "1x" else "Pies 2x",
                                                        color = if (scale > 1.3f) Color.Black else Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                        FilledTonalIconButton(
                                            onClick = {
                                                isVideoMuted = !isVideoMuted
                                                mediaPlayerRef?.let { mp ->
                                                    val vol = if (isVideoMuted) 0f else 1f
                                                    mp.setVolume(vol, vol)
                                                }
                                            },
                                            modifier = Modifier.size(34.dp),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = if (isVideoMuted) Color(0xCCFF5252) else Color(0x99000000),
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Icon(
                                                if (isVideoMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                                contentDescription = if (isVideoMuted) "Activar Sonido" else "Silenciar Video",
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                scale = 1f
                                                offsetX = 0f
                                                offsetY = 0f
                                            },
                                            modifier = Modifier
                                                .size(34.dp)
                                                .background(Color(0x99000000), shape = CircleShape)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Centrar", tint = Color.White, modifier = Modifier.size(17.dp))
                                        }
                                    }
                                }

                                // Botones de dirección retirados: el tracker se mueve fluidamente deslizando el dedo en la pantalla
                            }

                            // Panel Inferior de Controles (con padding horizontal para los controles)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Banner Guiado de Calibración (Adaptado a VBT o Salto)
                                Surface(
                                    color = Color(0x1F00FFCC),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x4400FFCC)),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF00FFCC), modifier = Modifier.size(15.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                if (currentMode == "JUMP")
                                                    "Paso 2: Marca 🛫 Despegue y 🛬 Aterrizaje cuadro a cuadro o usa ⚡ Auto IA."
                                                else
                                                    "Paso 2: Desliza el dedo sobre la pantalla para centrar la cruceta en el buje y ajusta al disco (45 cm).",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                maxLines = 1
                                            )
                                        }

                                        // En modo JUMP: Selector rápido de factor Cámara Lenta (Slow-Mo 1x, 1/2x, 1/4x, 1/8x)
                                        if (currentMode == "JUMP") {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                listOf(1.0 to "1x", 2.0 to "1/2x", 4.0 to "1/4x", 8.0 to "1/8x").forEach { (factor, label) ->
                                                    val isSel = kotlin.math.abs(jumpSlowMoFactor - factor) < 0.1
                                                    Surface(
                                                        onClick = { jumpSlowMoFactor = factor },
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = if (isSel) Color(0xFF00FFCC) else Color(0xFF222831),
                                                        modifier = Modifier.height(20.dp)
                                                    ) {
                                                        Box(
                                                            contentAlignment = Alignment.Center,
                                                            modifier = Modifier.padding(horizontal = 5.dp)
                                                        ) {
                                                            Text(
                                                                label,
                                                                color = if (isSel) Color.Black else Color.LightGray,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Fila de Controles de Reproducción y Avance de 1 Frame Nativo Exacto (1000 / FPS ms)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // -1 Frame Nativo Exacto
                                        FilledTonalIconButton(
                                            onClick = {
                                                videoViewRef?.let { vv ->
                                                    vv.pause()
                                                    isPlaying = false
                                                    val step = nativeFrameStepMs.coerceIn(4L, 40L)
                                                    val newPos = (currentVideoPosMs - step).coerceAtLeast(trimStartMs)
                                                    currentVideoPosMs = newPos
                                                    seekVideoAccurate(mediaPlayerRef, vv, newPos)
                                                    updateStillFrame(newPos)
                                                }
                                            },
                                            modifier = Modifier.size(34.dp),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = Color(0xFF262626),
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Icon(Icons.Default.FastRewind, contentDescription = "-1 Frame", modifier = Modifier.size(16.dp))
                                        }

                                        // Play / Pause
                                        FilledTonalIconButton(
                                            onClick = {
                                                videoViewRef?.let { vv ->
                                                    if (isPlaying) {
                                                        vv.pause()
                                                        isPlaying = false
                                                        val cur = vv.currentPosition.toLong()
                                                        currentVideoPosMs = cur
                                                        updateStillFrame(cur)
                                                    } else {
                                                        stillFrameBitmap = null
                                                        vv.start()
                                                        isPlaying = true
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(34.dp),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = Color(0xFF00FFCC),
                                                contentColor = Color.Black
                                            )
                                        ) {
                                            Icon(
                                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = "Play/Pausa",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        // +1 Frame Nativo Exacto
                                        FilledTonalIconButton(
                                            onClick = {
                                                videoViewRef?.let { vv ->
                                                    vv.pause()
                                                    isPlaying = false
                                                    val step = nativeFrameStepMs.coerceIn(4L, 40L)
                                                    val maxLimit = if (trimEndMs > 0) trimEndMs else (if (videoDurationMs > 0) videoDurationMs else vv.duration.toLong())
                                                    val newPos = (currentVideoPosMs + step).coerceAtMost(maxLimit)
                                                    currentVideoPosMs = newPos
                                                    seekVideoAccurate(mediaPlayerRef, vv, newPos)
                                                    updateStillFrame(newPos)
                                                }
                                            },
                                            modifier = Modifier.size(34.dp),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = Color(0xFF262626),
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Icon(Icons.Default.FastForward, contentDescription = "+1 Frame", modifier = Modifier.size(16.dp))
                                        }

                                        // Volver al Primer Frame del recorte
                                        OutlinedButton(
                                            onClick = {
                                                videoViewRef?.let { vv ->
                                                    vv.pause()
                                                    isPlaying = false
                                                    currentVideoPosMs = trimStartMs
                                                    seekVideoAccurate(mediaPlayerRef, vv, trimStartMs)
                                                    updateStillFrame(trimStartMs)
                                                }
                                            },
                                            modifier = Modifier.height(34.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00FFCC))
                                        ) {
                                            Text("▶| Primer Frame", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    val frameNumber = ((currentVideoPosMs - trimStartMs).coerceAtLeast(0L) / nativeFrameStepMs.coerceAtLeast(1L)) + 1L
                                    Text(
                                        text = if (currentMode == "JUMP") "⏱ ${formatTimeMs(currentVideoPosMs)} (F#$frameNumber)" else "⏱ ${formatTimeMs(currentVideoPosMs)}",
                                        color = Color(0xFF00FFCC),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (currentMode == "JUMP") {
                                    // ── PANEL EXCLUSIVO DE CALIBRACIÓN DE SALTO (ESTILO MY JUMP LAB + IA SUB-FRAME) ──
                                    val maxTrimLimit = if (trimEndMs > trimStartMs + 100L) trimEndMs else videoDurationMs.coerceAtLeast(trimStartMs + 1000L)
                                    Slider(
                                        value = currentVideoPosMs.toFloat().coerceIn(trimStartMs.toFloat(), maxTrimLimit.toFloat()),
                                        onValueChange = { newVal ->
                                            val target = newVal.toLong()
                                            videoViewRef?.pause()
                                            isPlaying = false
                                            currentVideoPosMs = target
                                            videoViewRef?.let { seekVideoAccurate(mediaPlayerRef, it, target) }
                                            updateStillFrame(target)
                                        },
                                        valueRange = trimStartMs.toFloat()..maxTrimLimit.toFloat(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(26.dp)
                                            .padding(horizontal = 4.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFF00FFCC),
                                            activeTrackColor = Color(0xFF00FFCC),
                                            inactiveTrackColor = Color(0xFF333333)
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Si es Test de Asimetría: Selector rápido de Pierna Izquierda vs Pierna Derecha
                                    if (selectedJumpType == JumpType.ASYMMETRY_TEST) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 2.dp, vertical = 2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val isLeftActive = activeAsymmetryLeg == "LEFT"
                                            val leftDone = leftJumpTakeoffMs != null && leftJumpLandingMs != null && leftJumpLandingMs!! > leftJumpTakeoffMs!!
                                            val rightDone = rightJumpTakeoffMs != null && rightJumpLandingMs != null && rightJumpLandingMs!! > rightJumpTakeoffMs!!

                                            Surface(
                                                onClick = { activeAsymmetryLeg = "LEFT" },
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isLeftActive) Color(0x2600FFCC) else Color(0xFF1A1E24),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.2.dp,
                                                    if (isLeftActive) Color(0xFF00FFCC) else Color(0xFF3A424E)
                                                ),
                                                modifier = Modifier.weight(1f).height(30.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        if (leftDone) "🦵 Pierna Izquierda ✓" else "🦵 Pierna Izquierda (Izq)",
                                                        color = if (isLeftActive) Color(0xFF00FFCC) else Color.LightGray,
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }

                                            Surface(
                                                onClick = { activeAsymmetryLeg = "RIGHT" },
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (!isLeftActive) Color(0x2600E5FF) else Color(0xFF1A1E24),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.2.dp,
                                                    if (!isLeftActive) Color(0xFF00E5FF) else Color(0xFF3A424E)
                                                ),
                                                modifier = Modifier.weight(1f).height(30.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        if (rightDone) "🦵 Pierna Derecha ✓" else "🦵 Pierna Derecha (Der)",
                                                        color = if (!isLeftActive) Color(0xFF00E5FF) else Color.LightGray,
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                    }

                                    // Botones de Marcado de Eventos (Contacto, Despegue, Aterrizaje) + Auto IA Sub-Frame
                                    val displayedTakeoffMs = if (selectedJumpType == JumpType.ASYMMETRY_TEST) {
                                        if (activeAsymmetryLeg == "LEFT") leftJumpTakeoffMs else rightJumpTakeoffMs
                                    } else jumpTakeoffMs
                                    val displayedLandingMs = if (selectedJumpType == JumpType.ASYMMETRY_TEST) {
                                        if (activeAsymmetryLeg == "LEFT") leftJumpLandingMs else rightJumpLandingMs
                                    } else jumpLandingMs

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (selectedJumpType == JumpType.DROP_JUMP) {
                                            val hasContact = jumpContactMs != null
                                            OutlinedButton(
                                                onClick = {
                                                    jumpContactMs = currentVideoPosMs.toDouble()
                                                    isSubFrameInterpolated = false
                                                },
                                                modifier = Modifier.weight(1f).height(38.dp),
                                                contentPadding = PaddingValues(horizontal = 4.dp),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.dp,
                                                    if (hasContact) Color(0xFFFFB300) else Color(0xFF444444)
                                                ),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    containerColor = if (hasContact) Color(0x22FFB300) else Color(0xFF1A1E24),
                                                    contentColor = if (hasContact) Color(0xFFFFB300) else Color.LightGray
                                                ),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("🦶 Contacto", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                                    Text(
                                                        if (hasContact) String.format(Locale.US, "%.3fs", (jumpContactMs!! - trimStartMs).coerceAtLeast(0.0) / 1000.0)
                                                        else "Fijar",
                                                        fontSize = 8.5.sp,
                                                        color = if (hasContact) Color.White else Color.Gray
                                                    )
                                                }
                                            }
                                        }

                                        // Botón 🛫 Despegue (Take-off)
                                        val hasTakeoff = displayedTakeoffMs != null
                                        OutlinedButton(
                                            onClick = {
                                                val tNow = currentVideoPosMs.toDouble()
                                                if (selectedJumpType == JumpType.ASYMMETRY_TEST) {
                                                    if (activeAsymmetryLeg == "LEFT") leftJumpTakeoffMs = tNow else rightJumpTakeoffMs = tNow
                                                }
                                                jumpTakeoffMs = tNow
                                                isSubFrameInterpolated = false
                                            },
                                            modifier = Modifier.weight(1.1f).height(38.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.5.dp,
                                                if (hasTakeoff) Color(0xFF00FFCC) else Color(0xFF444444)
                                            ),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (hasTakeoff) Color(0x2600FFCC) else Color(0xFF1A1E24),
                                                contentColor = if (hasTakeoff) Color(0xFF00FFCC) else Color.White
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("🛫 Despegue", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                                    Text(
                                                        if (hasTakeoff) String.format(Locale.US, "%.3fs", (displayedTakeoffMs!! - trimStartMs).coerceAtLeast(0.0) / 1000.0)
                                                        else "Toca en frame",
                                                        fontSize = 9.sp,
                                                        color = if (hasTakeoff) Color.White else Color.Gray,
                                                        maxLines = 1
                                                    )
                                                }
                                                if (hasTakeoff) {
                                                    Icon(
                                                        Icons.Default.Visibility,
                                                        contentDescription = "Ir a Despegue",
                                                        tint = Color(0xFF00FFCC),
                                                        modifier = Modifier
                                                            .size(15.dp)
                                                            .clickable {
                                                                val t = displayedTakeoffMs!!.toLong()
                                                                videoViewRef?.pause()
                                                                isPlaying = false
                                                                currentVideoPosMs = t
                                                                videoViewRef?.let { seekVideoAccurate(mediaPlayerRef, it, t) }
                                                                updateStillFrame(t)
                                                            }
                                                    )
                                                }
                                            }
                                        }

                                        // Botón 🛬 Aterrizaje (Landing)
                                        val hasLanding = displayedLandingMs != null
                                        OutlinedButton(
                                            onClick = {
                                                val tNow = currentVideoPosMs.toDouble()
                                                if (selectedJumpType == JumpType.ASYMMETRY_TEST) {
                                                    if (activeAsymmetryLeg == "LEFT") leftJumpLandingMs = tNow else rightJumpLandingMs = tNow
                                                }
                                                jumpLandingMs = tNow
                                                isSubFrameInterpolated = false
                                            },
                                            modifier = Modifier.weight(1.1f).height(38.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.5.dp,
                                                if (hasLanding) Color(0xFF00E676) else Color(0xFF444444)
                                            ),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (hasLanding) Color(0x2600E676) else Color(0xFF1A1E24),
                                                contentColor = if (hasLanding) Color(0xFF00E676) else Color.White
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("🛬 Aterrizaje", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                                    Text(
                                                        if (hasLanding) String.format(Locale.US, "%.3fs", (displayedLandingMs!! - trimStartMs).coerceAtLeast(0.0) / 1000.0)
                                                        else "Toca en frame",
                                                        fontSize = 9.sp,
                                                        color = if (hasLanding) Color.White else Color.Gray,
                                                        maxLines = 1
                                                    )
                                                }
                                                if (hasLanding) {
                                                    Icon(
                                                        Icons.Default.Visibility,
                                                        contentDescription = "Ir a Aterrizaje",
                                                        tint = Color(0xFF00E676),
                                                        modifier = Modifier
                                                            .size(15.dp)
                                                            .clickable {
                                                                val t = displayedLandingMs!!.toLong()
                                                                videoViewRef?.pause()
                                                                isPlaying = false
                                                                currentVideoPosMs = t
                                                                videoViewRef?.let { seekVideoAccurate(mediaPlayerRef, it, t) }
                                                                updateStillFrame(t)
                                                            }
                                                    )
                                                }
                                            }
                                        }

                                        // Botón ⚡ Auto IA Sub-Frame
                                        Button(
                                            onClick = { runAutoDetectJumpFlight(screenBoxWidth, screenBoxHeight) },
                                            modifier = Modifier.height(38.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF00FFCC),
                                                contentColor = Color.Black
                                            )
                                        ) {
                                            Text(
                                                if (isDetectingJumpEvents) "IA..." else "⚡ Auto IA",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(5.dp))

                                    // Tarjeta de Vista Previa en Vivo (Tiempo de Vuelo, Altura Sayers, Vel. Despegue / RSI / Asimetría)
                                    val safeSlow = jumpSlowMoFactor.coerceAtLeast(0.25)
                                    val liveFlightSec = if (displayedTakeoffMs != null && displayedLandingMs != null && displayedLandingMs > displayedTakeoffMs) {
                                        ((displayedLandingMs - displayedTakeoffMs) / 1000.0) / safeSlow
                                    } else null
                                    val liveHeightCm = if (liveFlightSec != null && liveFlightSec in 0.08..1.35) {
                                        0.125 * 9.81 * liveFlightSec * liveFlightSec * 100.0
                                    } else null
                                    val liveTakeoffVel = if (liveFlightSec != null && liveFlightSec in 0.08..1.35) {
                                        0.5 * 9.81 * liveFlightSec
                                    } else null
                                    val liveContactMs = if (selectedJumpType == JumpType.DROP_JUMP && jumpContactMs != null && jumpTakeoffMs != null && jumpTakeoffMs!! > jumpContactMs!!) {
                                        ((jumpTakeoffMs!! - jumpContactMs!!) / safeSlow).toLong()
                                    } else null
                                    val liveRsi = if (liveHeightCm != null && liveContactMs != null && liveContactMs > 50L) {
                                        (liveHeightCm / 100.0) / (liveContactMs / 1000.0)
                                    } else null

                                    val leftLiveCm = if (leftJumpTakeoffMs != null && leftJumpLandingMs != null && leftJumpLandingMs!! > leftJumpTakeoffMs!!) {
                                        val lSec = ((leftJumpLandingMs!! - leftJumpTakeoffMs!!) / 1000.0) / safeSlow
                                        if (lSec in 0.08..1.35) 0.125 * 9.81 * lSec * lSec * 100.0 else null
                                    } else null
                                    val rightLiveCm = if (rightJumpTakeoffMs != null && rightJumpLandingMs != null && rightJumpLandingMs!! > rightJumpTakeoffMs!!) {
                                        val rSec = ((rightJumpLandingMs!! - rightJumpTakeoffMs!!) / 1000.0) / safeSlow
                                        if (rSec in 0.08..1.35) 0.125 * 9.81 * rSec * rSec * 100.0 else null
                                    } else null
                                    val liveAsymPct = if (leftLiveCm != null && rightLiveCm != null && maxOf(leftLiveCm, rightLiveCm) > 1.0) {
                                        (kotlin.math.abs(rightLiveCm - leftLiveCm) / maxOf(leftLiveCm, rightLiveCm)) * 100.0
                                    } else null

                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFF14181F),
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x4400FFCC))
                                    ) {
                                        if (selectedJumpType == JumpType.ASYMMETRY_TEST) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text("PIERNA IZQUIERDA", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                    Text(
                                                        if (leftLiveCm != null) "${String.format(Locale.US, "%.1f", leftLiveCm)} cm" else "-- cm",
                                                        color = Color(0xFF00FFCC),
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Black
                                                    )
                                                }
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("PIERNA DERECHA", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                    Text(
                                                        if (rightLiveCm != null) "${String.format(Locale.US, "%.1f", rightLiveCm)} cm" else "-- cm",
                                                        color = Color(0xFF00E5FF),
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Black
                                                    )
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text("% ASIMETRÍA", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                    val asymColor = when {
                                                        liveAsymPct == null -> Color.Gray
                                                        liveAsymPct <= 10.0 -> Color(0xFF00E676)
                                                        liveAsymPct <= 15.0 -> Color(0xFFFFB300)
                                                        else -> Color(0xFFFF5252)
                                                    }
                                                    Text(
                                                        if (liveAsymPct != null) "${String.format(Locale.US, "%.1f", liveAsymPct)}%" else "-- %",
                                                        color = asymColor,
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Black
                                                    )
                                                }
                                            }
                                        } else {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        if (isSubFrameInterpolated) "TIEMPO DE VUELO (✨ IA SUB-FRAME)" else "TIEMPO DE VUELO (MY JUMP)",
                                                        color = Color.Gray,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        if (liveFlightSec != null) "${String.format(Locale.US, "%.1f", liveFlightSec * 1000.0)} ms" else "-- ms",
                                                        color = Color.White,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Black
                                                    )
                                                }

                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("ALTURA ESTIMADA", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                    Text(
                                                        if (liveHeightCm != null) "${String.format(Locale.US, "%.1f", liveHeightCm)} cm" else "-- cm",
                                                        color = Color(0xFF00FFCC),
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Black
                                                    )
                                                }

                                                Column(horizontalAlignment = Alignment.End) {
                                                    if (selectedJumpType == JumpType.DROP_JUMP && liveRsi != null) {
                                                        Text("RSI (Tc: ${liveContactMs}ms)", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                        Text(
                                                            String.format(Locale.US, "%.2f", liveRsi),
                                                            color = Color(0xFF00E676),
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Black
                                                        )
                                                    } else {
                                                        Text("VEL. DESPEGUE", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                        Text(
                                                            if (liveTakeoffVel != null) "${String.format(Locale.US, "%.2f", liveTakeoffVel)} m/s" else "-- m/s",
                                                            color = Color(0xFF00E676),
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Black
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        "Ajusta el círculo sobre el borde del disco olímpico (45 cm). Arrastra el centro o usa las flechas HUD.",
                                        color = Color(0xFF00FFCC),
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Fila de Controles de Tamaño de Círculo [-] [Slider] [+] y Botón Auto Detectar
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        FilledTonalIconButton(
                                            onClick = { circleRadiusPx = (circleRadiusPx - 4f).coerceAtLeast(20f) },
                                            modifier = Modifier.size(32.dp),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = Color(0xFF262626),
                                                contentColor = Color.White
                                            )
                                        ) {
                                            Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Slider(
                                            value = circleRadiusPx.coerceIn(20f, 220f),
                                            onValueChange = { circleRadiusPx = it },
                                            valueRange = 20f..220f,
                                            modifier = Modifier.weight(1f),
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color(0xFF00FFCC),
                                                activeTrackColor = Color(0xFF00FFCC)
                                            )
                                        )

                                        FilledTonalIconButton(
                                            onClick = { circleRadiusPx = (circleRadiusPx + 4f).coerceAtMost(220f) },
                                            modifier = Modifier.size(32.dp),
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = Color(0xFF262626),
                                                contentColor = Color(0xFF00FFCC)
                                            )
                                        ) {
                                            Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = { runAutoDetectPlate(screenBoxWidth, screenBoxHeight) },
                                            modifier = Modifier.height(34.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF00FFCC),
                                                contentColor = Color.Black
                                            )
                                        ) {
                                            Text(
                                                if (isDetectingPlate) "..." else "⚡ Auto Detectar",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Módulo de Carga de la Barra para VBT
                                if (currentMode == "VBT") {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFF141414),
                                        shape = RoundedCornerShape(10.dp),
                                        border = CardDefaults.outlinedCardBorder().copy(
                                            brush = androidx.compose.ui.graphics.SolidColor(Color(0x6600FFCC))
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(6.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        "🏋️ Carga de la Barra:",
                                                        color = Color.White,
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        "${barbellLoadKg} kg",
                                                        color = Color(0xFF00FFCC),
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                }
                                                Text("Calcula Watts, N y 1RM", color = Color.Gray, fontSize = 10.sp)
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))

                                            // Fila de Ajuste Stepper con cuadro de texto y fuente ajustada
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val currentLoad = barbellLoadKg.toDoubleOrNull() ?: 70.0
                                                FilledTonalButton(
                                                    onClick = {
                                                        val next = (currentLoad - 5.0).coerceAtLeast(10.0)
                                                        barbellLoadKg = if (next % 1.0 == 0.0) next.toInt().toString() else String.format(Locale.US, "%.1f", next)
                                                    },
                                                    contentPadding = PaddingValues(0.dp),
                                                    modifier = Modifier.weight(1f).height(34.dp),
                                                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF262626))
                                                ) { Text("-5", fontSize = 11.sp, color = Color.LightGray) }

                                                FilledTonalButton(
                                                    onClick = {
                                                        val next = (currentLoad - 2.5).coerceAtLeast(10.0)
                                                        barbellLoadKg = if (next % 1.0 == 0.0) next.toInt().toString() else String.format(Locale.US, "%.1f", next)
                                                    },
                                                    contentPadding = PaddingValues(0.dp),
                                                    modifier = Modifier.weight(1f).height(34.dp),
                                                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF262626))
                                                ) { Text("-2.5", fontSize = 10.sp, color = Color.LightGray) }

                                                Box(
                                                    modifier = Modifier
                                                        .weight(1.8f)
                                                        .height(36.dp)
                                                        .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                                        .border(1.5.dp, Color(0xFF00FFCC), RoundedCornerShape(8.dp)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.Center,
                                                        modifier = Modifier.padding(horizontal = 4.dp)
                                                    ) {
                                                        BasicTextField(
                                                            value = barbellLoadKg,
                                                            onValueChange = { newVal ->
                                                                if (newVal.isEmpty() || newVal.all { it.isDigit() || it == '.' }) {
                                                                    barbellLoadKg = newVal
                                                                }
                                                            },
                                                            singleLine = true,
                                                            textStyle = androidx.compose.ui.text.TextStyle(
                                                                color = Color.White,
                                                                fontWeight = FontWeight.Bold,
                                                                textAlign = TextAlign.Center,
                                                                fontSize = 15.sp
                                                            ),
                                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                            cursorBrush = SolidColor(Color(0xFF00FFCC)),
                                                            modifier = Modifier.wrapContentWidth()
                                                        )
                                                        Text(" kg", color = Color(0xFF00FFCC), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }

                                                FilledTonalButton(
                                                    onClick = {
                                                        val next = (currentLoad + 2.5)
                                                        barbellLoadKg = if (next % 1.0 == 0.0) next.toInt().toString() else String.format(Locale.US, "%.1f", next)
                                                    },
                                                    contentPadding = PaddingValues(0.dp),
                                                    modifier = Modifier.weight(1f).height(34.dp),
                                                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF262626))
                                                ) { Text("+2.5", fontSize = 10.sp, color = Color(0xFF00FFCC)) }

                                                FilledTonalButton(
                                                    onClick = {
                                                        val next = (currentLoad + 5.0)
                                                        barbellLoadKg = if (next % 1.0 == 0.0) next.toInt().toString() else String.format(Locale.US, "%.1f", next)
                                                    },
                                                    contentPadding = PaddingValues(0.dp),
                                                    modifier = Modifier.weight(1f).height(34.dp),
                                                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF262626))
                                                ) { Text("+5", fontSize = 11.sp, color = Color(0xFF00FFCC)) }
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))

                                            // Chips de Cargas Rápidas
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                listOf("20", "40", "60", "70", "80", "100").forEach { preset ->
                                                    val isSelected = barbellLoadKg == preset
                                                    Surface(
                                                        onClick = { barbellLoadKg = preset },
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = if (isSelected) Color(0x3300FFCC) else Color(0xFF222222),
                                                        border = if (isSelected) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00FFCC))) else null,
                                                        modifier = Modifier.weight(1f).height(24.dp)
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Text(
                                                                if (preset == "20") "Barra" else "${preset}k",
                                                                fontSize = 9.sp,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                color = if (isSelected) Color(0xFF00FFCC) else Color.Gray
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Botón Iniciar Análisis a Pantalla Completa (Sin botón Volver abajo, ya que está la flecha arriba)
                                Button(
                                    onClick = {
                                        val bounds = calculateVideoRenderBounds(screenBoxWidth, screenBoxHeight, videoNativeWidth, videoNativeHeight)
                                        val invScale = if (bounds.scale > 0f) 1f / bounds.scale else 1f
                                        if (currentMode == "JUMP") {
                                            val scrGroundY = jumpGroundLineScreenY ?: (screenBoxHeight * 0.78f)
                                            jumpGroundLineNativeY = ((scrGroundY - bounds.top) * invScale).coerceIn(0f, videoNativeHeight.toFloat())
                                            VerticalJumpAnalyzer.currentJumpType = selectedJumpType
                                        } else {
                                            val centerToUse = circleCenter ?: Offset(screenBoxWidth / 2f, screenBoxHeight * 0.65f)
                                            val nativeCenter = Offset(
                                                ((centerToUse.x - bounds.left) * invScale).coerceIn(0f, videoNativeWidth.toFloat()),
                                                ((centerToUse.y - bounds.top) * invScale).coerceIn(0f, videoNativeHeight.toFloat())
                                            )
                                            val nativeRadius = (circleRadiusPx * invScale).coerceAtLeast(12f)

                                            calibratedPlateCenterVideo = nativeCenter
                                            calibratedPlateRadiusVideo = nativeRadius

                                            val nativeDiameterPx = nativeRadius * 2f
                                            val ratio = if (nativeDiameterPx > 5f) 0.45 / nativeDiameterPx.toDouble() else 0.0024
                                            VBTAnalyzer.setCalibratedPlate(nativeCenter, nativeRadius)
                                            VBTAnalyzer.pxToMetersRatio = ratio
                                            customRatioMmPerPx = ratio * 1000.0
                                        }
                                        isCalibrated = true

                                        videoViewRef?.pause()
                                        isReadyToAnalyze = true
                                        isProcessing = true
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF00FFCC),
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Text("Iniciar Análisis 🚀", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                }
            } else if (isProcessing) {
                // Pantalla de procesamiento
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(progress = progress, color = Color(0xFF00FFCC))
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        "Calculando Biomecánica... ${(progress * 100).toInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (currentMode == "JUMP") "Extrayendo Tiempo de Vuelo y Potencia Sayers" else "Extrayendo Trayectoria de Barra y Velocidad VBT",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                LaunchedEffect(Unit) {
                    val weight = activeUser?.bodyWeightKg ?: 70.0
                    val videoOutput = processVideo(
                        context = context,
                        uri = Uri.parse(videoUriString),
                        mode = currentMode!!,
                        bodyWeightKg = weight,
                        startMs = trimStartMs,
                        endMs = trimEndMs,
                        calibratedPlateCenter = calibratedPlateCenterVideo,
                        calibratedPlateRadius = calibratedPlateRadiusVideo,
                        calibratedJumpTakeoffMs = jumpTakeoffMs,
                        calibratedJumpLandingMs = jumpLandingMs,
                        calibratedJumpContactMs = jumpContactMs,
                        calibratedJumpGroundY = jumpGroundLineNativeY,
                        jumpSlowMoFactor = jumpSlowMoFactor,
                        nativeFrameStepMs = nativeFrameStepMs,
                        selectedJumpType = selectedJumpType,
                        leftJumpTakeoffMs = leftJumpTakeoffMs,
                        leftJumpLandingMs = leftJumpLandingMs,
                        rightJumpTakeoffMs = rightJumpTakeoffMs,
                        rightJumpLandingMs = rightJumpLandingMs
                    ) { p ->
                        progress = p
                    }
                    finalResult = videoOutput.maxResult
                    trackingPointsResult = videoOutput.trackingPoints
                    if (calibratedPlateRadiusVideo <= 5f && videoOutput.detectedPlateRadius != null && videoOutput.detectedPlateRadius > 5f) {
                        calibratedPlateRadiusVideo = videoOutput.detectedPlateRadius
                    }
                    if (calibratedPlateCenterVideo == null && videoOutput.detectedPlateCenter != null) {
                        calibratedPlateCenterVideo = videoOutput.detectedPlateCenter
                    }
                    if (currentMode == "JUMP") {
                        jumpMetricsResult = VerticalJumpAnalyzer.lastJumpMetrics
                    } else if (currentMode == "VBT") {
                        vbtDeviationResultCm = VBTAnalyzer.getHorizontalDeviationMeters() * 100.0
                    }
                    isProcessing = false
                }
            } else if (finalResult != null) {
                // Resultados Completos (Módulos 2 & 3)
                var selectedExerciseModel by remember(selectedVBTExercise) { mutableStateOf(selectedVBTExercise) }
                val combinedExerciseDisplayName = remember(selectedExerciseSequence, selectedExerciseModel) {
                    if (selectedExerciseSequence.size > 1) {
                        selectedExerciseSequence.joinToString(" + ") { it.shortName }
                    } else {
                        selectedExerciseModel.displayName
                    }
                }

                val currentLoadKg = barbellLoadKg.toDoubleOrNull() ?: 70.0
                val peakVelocity = finalResult ?: 0.0
                val meanVelocity = VBTAnalyzer.currentMeanVelocity.takeIf { it > 0.0 } ?: (peakVelocity * 0.72)

                // Aceleración media concéntrica estimada (m/s²)
                val concentricPoints = trackingPointsResult.filter { it.isConcentric || it.velocityMps > 0.15 }
                val concDurSec = if (concentricPoints.size > 2) {
                    (concentricPoints.last().timestampMs - concentricPoints.first().timestampMs).coerceAtLeast(100L) / 1000.0
                } else {
                    0.60
                }
                val meanAccel = (peakVelocity / concDurSec).coerceIn(0.5, 6.0)
                val peakAccel = meanAccel * 1.5

                // Fuerzas Dinámicas (Newtons) F = m * (g + a)
                val meanForceN = currentLoadKg * (9.81 + meanAccel)
                val peakForceN = currentLoadKg * (9.81 + peakAccel)

                // Potencias Mecánicas (Watts) P = F * v
                val peakPowerWatts = peakForceN * peakVelocity
                val meanPowerWatts = meanForceN * meanVelocity
                val athleteWeight = activeUser?.bodyWeightKg ?: 70.0
                val relativeWattsPerKg = peakPowerWatts / athleteWeight

                // Trabajo Mecánico (Joules) W = F * d
                val romMeters = VBTAnalyzer.currentSetReps.lastOrNull()?.romMeters ?: 0.60
                val workJoules = meanForceN * romMeters

                // Estimación del 1RM con modelo biomecánico específico
                val pctOneRM = selectedExerciseModel.calculatePercentOneRM(peakVelocity, meanVelocity)
                val estimatedOneRM = selectedExerciseModel.estimateOneRM(currentLoadKg, peakVelocity, meanVelocity)

                // 1. Velocidad Media Propulsiva (VMP - m/s): fase concéntrica con aceleración >= -9.81 m/s²
                val vmpVelocity = remember(trackingPointsResult, peakVelocity, meanVelocity) {
                    if (trackingPointsResult.size >= 2) {
                        val rawV = trackingPointsResult.map { it.velocityMps }
                        val smoothedV = zeroPhaseSmooth(rawV)
                        val propulsiveV = mutableListOf<Double>()
                        for (idx in trackingPointsResult.indices) {
                            val a = if (idx > 0) {
                                val dt = (trackingPointsResult[idx].timestampMs - trackingPointsResult[idx - 1].timestampMs) / 1000.0
                                if (dt > 0.008) (smoothedV[idx] - smoothedV[idx - 1]) / dt else 0.0
                            } else 0.0
                            val isConc = trackingPointsResult[idx].isConcentric || smoothedV[idx] > 0.15
                            if (isConc && a >= -9.81) {
                                propulsiveV.add(smoothedV[idx])
                            }
                        }
                        if (propulsiveV.isNotEmpty()) {
                            propulsiveV.average().coerceAtLeast(meanVelocity)
                        } else {
                            (meanVelocity * 1.05).coerceAtMost(peakVelocity)
                        }
                    } else {
                        (meanVelocity * 1.05).coerceAtMost(peakVelocity)
                    }
                }

                // 3. Tiempo de la Fase Concéntrica (ms): duración total del impulso concéntrico
                val concStartMs = concentricPoints.firstOrNull()?.timestampMs ?: 0L
                val concEndMs = concentricPoints.lastOrNull()?.timestampMs ?: (concStartMs + 600L)
                val concentricDurationMs = if (concentricPoints.size > 2) {
                    (concEndMs - concStartMs).coerceIn(120L, 4000L)
                } else {
                    600L
                }

                // 5. Tiempo hasta la Velocidad Pico (ms): instante relativo al inicio del tirón
                val peakPoint = trackingPointsResult.maxByOrNull { it.velocityMps }
                val timeToPeakMs = if (peakPoint != null && peakPoint.timestampMs >= concStartMs) {
                    (peakPoint.timestampMs - concStartMs).coerceIn(40L, concentricDurationMs)
                } else {
                    (concentricDurationMs * 0.65).toLong().coerceAtLeast(50L)
                }

                // 4. Tasa de Desarrollo de Fuerza (RFD - N/s): explosividad neuromuscular ΔF / Δt
                val deltaF = (peakForceN - (currentLoadKg * 9.81)).coerceAtLeast(currentLoadKg * 1.5)
                val timeToPeakSec = (timeToPeakMs / 1000.0).coerceAtLeast(0.08)
                val rfdNPerSec = (deltaF / timeToPeakSec).toInt()

                val scrollState = rememberScrollState()
                var analysisDataYOffset by remember { mutableStateOf(0) }
                var actionsYOffset by remember { mutableStateOf(0) }
                var previewPlaybackPosMs by remember { mutableLongStateOf(trimStartMs) }

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 8.dp)
                    ) {


                        // 1. Vista Previa Interactiva del Video Analizado (Trayectoria Bar Path + Retícula en tiempo real)
                        AnalysisResultVideoPreview(
                            videoUriString = videoUriString,
                            videoNativeWidth = videoNativeWidth,
                            videoNativeHeight = videoNativeHeight,
                            trimStartMs = trimStartMs,
                            trimEndMs = trimEndMs,
                            trackingPoints = trackingPointsResult,
                            originBaselineY = VBTAnalyzer.originBaselineY,
                            lowestInflectionY = VBTAnalyzer.lowestInflectionY,
                            mode = currentMode ?: "VBT",
                            barbellLoadKg = currentLoadKg,
                            calibratedPlateRadius = calibratedPlateRadiusVideo,
                            externalSeekPosMs = resultSeekPosMs,
                            onCurrentPositionChange = { previewPlaybackPosMs = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )

                        // 2. Telemetría Cinemática JUSTO DEBAJO DEL VIDEO (Visible al instante al presionar Play y con Scrubbing táctil)
                        if (trackingPointsResult.size >= 2) {
                            val chartPeakV = if (currentMode == "JUMP") {
                                val takeoffV = (jumpMetricsResult?.flightTimeSeconds ?: 0.5) * (9.81 / 2.0)
                                trackingPointsResult.maxOfOrNull { it.velocityMps }?.takeIf { it > 0.1 } ?: takeoffV
                            } else {
                                peakVelocity
                            }
                            val chartMeanV = if (currentMode == "JUMP") {
                                val takeoffV = (jumpMetricsResult?.flightTimeSeconds ?: 0.5) * (9.81 / 2.0)
                                takeoffV * 0.72
                            } else {
                                meanVelocity
                            }
                            KinematicTelemetryChart(
                                trackingPoints = trackingPointsResult,
                                peakVelocity = chartPeakV,
                                meanVelocity = chartMeanV,
                                vmpVelocity = if (currentMode == "VBT") vmpVelocity else null,
                                currentVideoPosMs = (previewPlaybackPosMs - trimStartMs).coerceAtLeast(0L),
                                onSeekToTimestamp = { relativeMs ->
                                    resultSeekPosMs = trimStartMs + relativeMs.coerceAtLeast(0L)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp)
                            )
                        }

                        // 3. ANCHOR: DATOS ANÁLISIS (Cuadro de Ejercicio debajo del gráfico de telemetría + Métricas)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coords ->
                                    analysisDataYOffset = coords.positionInParent().y.toInt()
                                }
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (currentMode == "JUMP") {
                                    // Ficha de Datos de Sesión (Atleta, Tipo de Ejercicio y Salto)
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0xFF1E1E1E),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    when (selectedJumpType) {
                                                        JumpType.CMJ -> "Salto Contramovimiento (CMJ)"
                                                        JumpType.SJ -> "Squat Jump (SJ)"
                                                        JumpType.DROP_JUMP -> "Drop Jump (Reactivo)"
                                                        JumpType.ASYMMETRY_TEST -> "Test de Asimetría (Izq vs Der)"
                                                    },
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    "Balísticos-Explosivos (Saltos)",
                                                    color = Color(0xFF00FFCC),
                                                    fontSize = 11.sp
                                                )
                                            }
                                            Text(
                                                "👤 ${activeUser?.name ?: "Invitado"} (${String.format(Locale.US, "%.1f", activeUser?.bodyWeightKg ?: 70.0)} kg)",
                                                color = Color.LightGray,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    // Módulo 3: Salto + Potencia Watts (Sayers)
                                    val metrics = jumpMetricsResult
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            MetricLabelWithInfo("SALTO VERTICAL", MetricInfoCatalog.JUMP_HEIGHT, { selectedMetricInfo = it })
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                "${String.format("%.1f", finalResult!! * 100)} cm",
                                                color = Color(0xFF00FFCC),
                                                style = MaterialTheme.typography.displayMedium,
                                                fontWeight = FontWeight.Black
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Divider(color = Color(0xFF333333))
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    MetricLabelWithInfo("Potencia Pico (Sayers)", MetricInfoCatalog.POWER, { selectedMetricInfo = it })
                                                    Text(
                                                        "${metrics?.peakPowerWatts?.toInt() ?: "--"} W",
                                                        color = Color.White,
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    MetricLabelWithInfo("Potencia Relativa", MetricInfoCatalog.POWER, { selectedMetricInfo = it })
                                                    Text(
                                                        "${String.format("%.1f", metrics?.relativePowerWattsPerKg ?: 0.0)} W/kg",
                                                        color = Color(0xFF00FFCC),
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.clickable { selectedMetricInfo = MetricInfoCatalog.FLIGHT_TIME }
                                            ) {
                                                Text(
                                                    "Tiempo de vuelo: ${(metrics?.flightTimeSeconds?.times(1000))?.toInt() ?: "--"} ms",
                                                    color = Color.Gray,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Info,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(11.dp),
                                                    tint = Color(0xFF00FFCC).copy(alpha = 0.7f)
                                                )
                                            }

                                            if (metrics?.leftLegHeightMeters != null && metrics.rightLegHeightMeters != null) {
                                                Spacer(modifier = Modifier.height(12.dp))
                                                Divider(color = Color(0xFF333333))
                                                Spacer(modifier = Modifier.height(12.dp))
                                                val lCm = metrics.leftLegHeightMeters * 100.0
                                                val rCm = metrics.rightLegHeightMeters * 100.0
                                                val asym = metrics.asymmetryPercent ?: 0.0
                                                val asymColor = when {
                                                    asym <= 10.0 -> Color(0xFF00E676)
                                                    asym <= 15.0 -> Color(0xFFFFB300)
                                                    else -> Color(0xFFFF5252)
                                                }
                                                val asymStatus = when {
                                                    asym <= 10.0 -> "✓ Simetría Funcional Óptima (≤ 10%)"
                                                    asym <= 15.0 -> "⚠ Asimetría Moderada (10% - 15%)"
                                                    else -> "🚨 Alerta de Asimetría Inter-Limb (> 15%)"
                                                }
                                                Text(
                                                    "⚖️ TEST DE ASIMETRÍA INTER-LIMB (IZQ vs DER)",
                                                    color = Color(0xFF00FFCC),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Column {
                                                        Text("Pierna Izquierda", color = Color.LightGray, fontSize = 11.sp)
                                                        Text(
                                                            "${String.format(Locale.US, "%.1f", lCm)} cm",
                                                            color = Color(0xFF00FFCC),
                                                            fontWeight = FontWeight.Black,
                                                            style = MaterialTheme.typography.titleMedium
                                                        )
                                                    }
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text("Pierna Derecha", color = Color.LightGray, fontSize = 11.sp)
                                                        Text(
                                                            "${String.format(Locale.US, "%.1f", rCm)} cm",
                                                            color = Color(0xFF00E5FF),
                                                            fontWeight = FontWeight.Black,
                                                            style = MaterialTheme.typography.titleMedium
                                                        )
                                                    }
                                                    Column(horizontalAlignment = Alignment.End) {
                                                        Text("Asimetría", color = Color.LightGray, fontSize = 11.sp)
                                                        Text(
                                                            "${String.format(Locale.US, "%.1f", asym)}%",
                                                            color = asymColor,
                                                            fontWeight = FontWeight.Black,
                                                            style = MaterialTheme.typography.titleMedium
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Surface(
                                                    color = asymColor.copy(alpha = 0.14f),
                                                    shape = RoundedCornerShape(8.dp),
                                                    border = androidx.compose.foundation.BorderStroke(1.dp, asymColor.copy(alpha = 0.5f)),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            asymStatus,
                                                            color = asymColor,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            "Dom: ${metrics.dominantLeg ?: "--"}",
                                                            color = Color.White,
                                                            fontSize = 10.5.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else if (currentMode == "VBT") {
                                    // Ficha de Datos de Sesión (Debajo de Telemetría Cinemática, perfectamente alineada sin desencuadrar el badge derecho)
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0xFF1E1E1E),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 10.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .padding(end = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(selectedExerciseModel.family.icon, fontSize = 19.sp)
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = combinedExerciseDisplayName,
                                                            color = Color.White,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.5.sp,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = when {
                                                                selectedExerciseSequence.size > 1 -> "Complex (${selectedExerciseSequence.size} ejercicios) • Olímpicos (DLP)"
                                                                selectedExerciseModel.family == VBTExerciseFamily.HILL_TRADITIONAL -> "Tradicionales (Ley de Hill)"
                                                                else -> "Olímpicos (DLP) • MVT: ${selectedExerciseModel.mvt} m/s"
                                                            },
                                                            color = Color(0xFF00FFCC),
                                                            fontSize = 11.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color(0xFF121212),
                                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C2C2C))
                                                ) {
                                                    Text(
                                                        text = if (selectedExerciseModel.usesPeakVelocity) "⚡ Vel. Pico (PV)" else "🏋️ Vel. Media (VM)",
                                                        color = Color.LightGray,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }
                                            Divider(color = Color(0xFF2C2C2C), thickness = 1.dp)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "👤 Atleta: ${activeUser?.name ?: "Invitado"} (${String.format(Locale.US, "%.1f", activeUser?.bodyWeightKg ?: 70.0)} kg)",
                                                    color = Color.LightGray,
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                                )
                                                Text(
                                                    "⚖️ Peso: ${currentLoadKg.toInt()} kg",
                                                    color = Color(0xFF00FFCC),
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }

                                    // Módulo 2: VBT Avanzado con Carga, Potencia, Fuerza, Trabajo y 1RM
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {

                                            // Fila superior: Carga desplazada con ajuste rápido
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text("CARGA DESPLAZADA", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        "${currentLoadKg.toInt()} kg",
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.titleLarge,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    FilledTonalIconButton(
                                                        onClick = {
                                                            val next = (currentLoadKg - 5.0).coerceAtLeast(10.0)
                                                            barbellLoadKg = next.toInt().toString()
                                                        },
                                                        modifier = Modifier.size(32.dp),
                                                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF2A2A2A))
                                                    ) { Text("-5", fontSize = 11.sp, color = Color.LightGray) }

                                                    FilledTonalIconButton(
                                                        onClick = {
                                                            val next = (currentLoadKg + 5.0)
                                                            barbellLoadKg = next.toInt().toString()
                                                        },
                                                        modifier = Modifier.size(32.dp),
                                                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF2A2A2A))
                                                    ) { Text("+5", fontSize = 11.sp, color = Color(0xFF00FFCC)) }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))
                                            Divider(color = Color(0xFF333333))
                                            Spacer(modifier = Modifier.height(10.dp))

                                            // Velocidades (Pico, Media y Propulsiva VMP - Sugerencia 1)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    MetricLabelWithInfo(
                                                        label = "Vel. Pico (PV)",
                                                        info = MetricInfoCatalog.PV,
                                                        onInfoClick = { selectedMetricInfo = it },
                                                        isStarred = selectedExerciseModel.usesPeakVelocity,
                                                        starColor = Color(0xFF00FFCC)
                                                    )
                                                    Text(
                                                        "${String.format(Locale.US, "%.2f", peakVelocity)}",
                                                        color = Color(0xFF00FFCC),
                                                        style = MaterialTheme.typography.titleLarge,
                                                        fontWeight = FontWeight.Black
                                                    )
                                                    Text("m/s", color = Color.Gray, fontSize = 10.sp)
                                                }

                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    MetricLabelWithInfo(
                                                        label = "Vel. Media (MV)",
                                                        info = MetricInfoCatalog.MV,
                                                        onInfoClick = { selectedMetricInfo = it },
                                                        isStarred = !selectedExerciseModel.usesPeakVelocity,
                                                        starColor = Color(0xFF00E676)
                                                    )
                                                    Text(
                                                        "${String.format(Locale.US, "%.2f", meanVelocity)}",
                                                        color = Color(0xFF00E676),
                                                        style = MaterialTheme.typography.titleLarge,
                                                        fontWeight = FontWeight.Black
                                                    )
                                                    Text("m/s", color = Color.Gray, fontSize = 10.sp)
                                                }

                                                Column(horizontalAlignment = Alignment.End) {
                                                    MetricLabelWithInfo(
                                                        label = "Propulsiva (VMP)",
                                                        info = MetricInfoCatalog.VMP,
                                                        onInfoClick = { selectedMetricInfo = it }
                                                    )
                                                    Text(
                                                        "${String.format(Locale.US, "%.2f", vmpVelocity)}",
                                                        color = Color(0xFF00E5FF),
                                                        style = MaterialTheme.typography.titleLarge,
                                                        fontWeight = FontWeight.Black
                                                    )
                                                    Text("m/s (a ≥ -g)", color = Color(0xFF00E5FF).copy(alpha = 0.7f), fontSize = 10.sp)
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))
                                            Divider(color = Color(0xFF333333))
                                            Spacer(modifier = Modifier.height(10.dp))

                                            // Dinámica Temporal y Explosividad (Sugerencias 3, 4 y 5)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    MetricLabelWithInfo(
                                                        label = "Fase Concéntrica",
                                                        info = MetricInfoCatalog.CONCENTRIC_TIME,
                                                        onInfoClick = { selectedMetricInfo = it }
                                                    )
                                                    Text(
                                                        "${concentricDurationMs} ms",
                                                        color = Color.White,
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                    Text(
                                                        "Duración total",
                                                        color = Color.Gray,
                                                        fontSize = 10.sp
                                                    )
                                                }

                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    MetricLabelWithInfo(
                                                        label = "T. a Vel. Pico",
                                                        info = MetricInfoCatalog.TIME_TO_PEAK,
                                                        onInfoClick = { selectedMetricInfo = it }
                                                    )
                                                    Text(
                                                        "${timeToPeakMs} ms",
                                                        color = Color(0xFF00FFCC),
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                    Text(
                                                        "Pico concéntrico",
                                                        color = Color.Gray,
                                                        fontSize = 10.sp
                                                    )
                                                }

                                                Column(horizontalAlignment = Alignment.End) {
                                                    MetricLabelWithInfo(
                                                        label = "RFD (Explosividad)",
                                                        info = MetricInfoCatalog.RFD,
                                                        onInfoClick = { selectedMetricInfo = it }
                                                    )
                                                    Text(
                                                        "${rfdNPerSec} N/s",
                                                        color = Color(0xFFFF9100),
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                    Text(
                                                        "Tasa de fuerza",
                                                        color = Color.Gray,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(10.dp))
                                            Divider(color = Color(0xFF333333))
                                            Spacer(modifier = Modifier.height(10.dp))

                                            // Métricas de Potencia (Watts), Fuerza (Newtons) y 1RM Estimado
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    MetricLabelWithInfo(
                                                        label = "Potencia Pico",
                                                        info = MetricInfoCatalog.POWER,
                                                        onInfoClick = { selectedMetricInfo = it }
                                                    )
                                                    Text(
                                                        "${peakPowerWatts.toInt()} W",
                                                        color = Color.White,
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                    Text(
                                                        "${String.format("%.1f", relativeWattsPerKg)} W/kg",
                                                        color = Color(0xFF00FFCC),
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    MetricLabelWithInfo(
                                                        label = "Fuerza Pico",
                                                        info = MetricInfoCatalog.FORCE,
                                                        onInfoClick = { selectedMetricInfo = it }
                                                    )
                                                    Text(
                                                        "${peakForceN.toInt()} N",
                                                        color = Color.White,
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                    Text(
                                                        "Trabajo: ${workJoules.toInt()} J",
                                                        color = Color.Gray,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                                Column(horizontalAlignment = Alignment.End) {
                                                    MetricLabelWithInfo(
                                                        label = "1RM Estimado",
                                                        info = MetricInfoCatalog.ONE_RM,
                                                        onInfoClick = { selectedMetricInfo = it }
                                                    )
                                                    Text(
                                                        "${estimatedOneRM.toInt()} kg",
                                                        color = Color(0xFFFFD600),
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                    Text(
                                                        "~${pctOneRM.toInt()}% 1RM",
                                                        color = Color(0xFFFFD600),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                "${selectedExerciseModel.formulaDescription} (MVT: ${selectedExerciseModel.mvt} m/s)",
                                                color = Color(0xFF888888),
                                                fontSize = 10.sp,
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.End
                                            )

                                            Spacer(modifier = Modifier.height(10.dp))
                                            Divider(color = Color(0xFF333333))
                                            Spacer(modifier = Modifier.height(10.dp))

                                            // Desvío Horizontal (Bar Drift)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    MetricLabelWithInfo(
                                                        label = "Desvío Horizontal (Bar Drift)",
                                                        info = MetricInfoCatalog.BAR_DRIFT,
                                                        onInfoClick = { selectedMetricInfo = it }
                                                    )
                                                    Text(
                                                        "${String.format("%.1f", vbtDeviationResultCm ?: 0.0)} cm",
                                                        color = if ((vbtDeviationResultCm ?: 0.0) < 8.0) Color(0xFF00FFCC) else Color(0xFFFFB300),
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                }
                                                Text(
                                                    if ((vbtDeviationResultCm ?: 0.0) < 8.0) "✓ Trayectoria vertical limpia" else "⚠ Desvío hacia adelante",
                                                    color = if ((vbtDeviationResultCm ?: 0.0) < 8.0) Color(0xFF00FFCC) else Color(0xFFFFB300),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Tarjeta de Interpretación Biomecánica & Diagnóstico
                                    val evaluation = BiomechanicalPdfReportGenerator.evaluateLift(
                                        peakVelocity = peakVelocity,
                                        loadKg = currentLoadKg,
                                        pctOneRM = pctOneRM,
                                        horizontalDeviationCm = vbtDeviationResultCm,
                                        relativeWattsPerKg = relativeWattsPerKg,
                                        peakForceN = peakForceN,
                                        workJoules = workJoules,
                                        timeToPeakMs = timeToPeakMs,
                                        rfdNPerSec = rfdNPerSec,
                                        exerciseName = combinedExerciseDisplayName
                                    )
                                    BiomechanicalInterpretationCard(
                                        evaluation = evaluation,
                                        peakVelocity = peakVelocity,
                                        loadKg = currentLoadKg,
                                        relativeWattsPerKg = relativeWattsPerKg
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // ANCHOR: ACCIONES
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { coords ->
                                    actionsYOffset = coords.positionInParent().y.toInt()
                                }
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Button(
                                    onClick = {
                                        if (!isSavingToProfile) {
                                            coroutineScope.launch {
                                                isSavingToProfile = true
                                                isExportingVideo = true
                                                exportProgress = 0.05f
                                                try {
                                                    val targetUser = activeUser
                                                        ?: userRepository.getUsers().firstOrNull()
                                                        ?: userRepository.saveUser("Atleta", 75.0)

                                                    // 1. Generar / asegurar Informe PDF en almacenamiento persistente (filesDir/reports)
                                                    val reportPath = generatedPdfPath?.takeIf { File(it).exists() } ?: try {
                                                        val repFile = BiomechanicalPdfReportGenerator.generateReport(
                                                            context = context,
                                                            videoUri = Uri.parse(videoUriString),
                                                            athleteName = targetUser.name,
                                                            athleteWeightKg = targetUser.bodyWeightKg,
                                                            exerciseName = if (currentMode == "VBT") combinedExerciseDisplayName else "Salto Vertical (${selectedJumpType.name})",
                                                            exerciseFamily = if (currentMode == "VBT") selectedExerciseModel.family.label else "Salto",
                                                            loadKg = currentLoadKg,
                                                            peakVelocity = peakVelocity,
                                                            meanVelocity = meanVelocity,
                                                            vmpVelocity = vmpVelocity,
                                                            concentricDurationMs = concentricDurationMs,
                                                            timeToPeakMs = timeToPeakMs,
                                                            rfdNPerSec = rfdNPerSec,
                                                            peakPowerWatts = peakPowerWatts,
                                                            relativeWattsPerKg = relativeWattsPerKg,
                                                            peakForceN = peakForceN,
                                                            workJoules = workJoules,
                                                            estimatedOneRM = estimatedOneRM,
                                                            pctOneRM = pctOneRM,
                                                            horizontalDeviationCm = vbtDeviationResultCm,
                                                            trackingPoints = trackingPointsResult,
                                                            trimStartMs = trimStartMs,
                                                            trimEndMs = trimEndMs,
                                                            repsList = com.speedtracker.core.math.VBTAnalyzer.currentSetReps.toList(),
                                                            exerciseSequenceNames = if (currentMode == "VBT") selectedExerciseSequence.map { it.shortName } else emptyList(),
                                                            exerciseMvt = if (currentMode == "VBT") selectedExerciseModel.mvt else null
                                                        )
                                                        generatedPdfPath = repFile.absolutePath
                                                        repFile.absolutePath
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                        null
                                                    }

                                                    // 2. Generar / asegurar Video Analizado con HUD y trayectoria en almacenamiento persistente (filesDir/saved_videos)
                                                    val savedVideosDir = File(context.filesDir, "saved_videos").apply { mkdirs() }
                                                    val sanitizedAthlete = targetUser.name.replace(" ", "_").replace(Regex("[^a-zA-Z0-9_]"), "")
                                                    val persistentVideoFile = File(savedVideosDir, "Video_${sanitizedAthlete}_${System.currentTimeMillis()}.mp4")

                                                    val savedVideoPath = generatedVideoPath?.takeIf { File(it).exists() } ?: try {
                                                        val metricSummary = if (currentMode == "VBT") {
                                                            "$combinedExerciseDisplayName • ${currentLoadKg.toInt()} kg • 1RM: ${estimatedOneRM.toInt()}kg • VP: ${String.format(Locale.US, "%.2f", peakVelocity)} m/s"
                                                        } else {
                                                            "Altura: ${String.format(Locale.US, "%.1f", (finalResult ?: 0.0) * 100)} cm"
                                                        }
                                                        val exported = VideoOverlayExporter.exportVideoWithOverlay(
                                                            context = context,
                                                            inputUri = Uri.parse(videoUriString),
                                                            outputFile = persistentVideoFile,
                                                            mode = currentMode ?: "VBT",
                                                            trackingPoints = trackingPointsResult,
                                                            exerciseName = if (currentMode == "VBT") combinedExerciseDisplayName else "Salto Vertical",
                                                            athleteName = targetUser.name,
                                                            bestMetricSummary = metricSummary,
                                                            originBaselineY = VBTAnalyzer.originBaselineY,
                                                            lowestInflectionY = VBTAnalyzer.lowestInflectionY,
                                                            calibratedPlateRadius = calibratedPlateRadiusVideo,
                                                            startMs = trimStartMs,
                                                            endMs = trimEndMs,
                                                            onProgress = { p -> exportProgress = p }
                                                        )
                                                        generatedVideoPath = exported.absolutePath
                                                        exported.absolutePath
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                        // Respaldo: copiar el archivo de video original si el codificador falla
                                                        try {
                                                            context.contentResolver.openInputStream(Uri.parse(videoUriString))?.use { input ->
                                                                persistentVideoFile.outputStream().use { output ->
                                                                    input.copyTo(output)
                                                                }
                                                            }
                                                            if (persistentVideoFile.exists() && persistentVideoFile.length() > 0L) {
                                                                persistentVideoFile.absolutePath
                                                            } else null
                                                        } catch (ex: Exception) {
                                                            null
                                                        }
                                                    }

                                                    // 3. Guardar Sesión completa con Informe PDF y Video en el perfil del atleta
                                                    userRepository.saveSession(
                                                        Session(
                                                            id = UUID.randomUUID().toString(),
                                                            userId = targetUser.id,
                                                            activityType = if (currentMode == "VBT") "${currentMode} ($combinedExerciseDisplayName)" else currentMode!!,
                                                            result = finalResult!!,
                                                            timestamp = System.currentTimeMillis(),
                                                            powerWatts = if (currentMode == "VBT") peakPowerWatts else jumpMetricsResult?.peakPowerWatts,
                                                            powerWattsPerKg = if (currentMode == "VBT") relativeWattsPerKg else jumpMetricsResult?.relativePowerWattsPerKg,
                                                            horizontalDeviationCm = vbtDeviationResultCm,
                                                            meanVelocity = if (currentMode == "VBT") meanVelocity else null,
                                                            loadKg = if (currentMode == "VBT") currentLoadKg else null,
                                                            vmpVelocity = if (currentMode == "VBT") vmpVelocity else null,
                                                            concentricDurationMs = if (currentMode == "VBT") concentricDurationMs else null,
                                                            timeToPeakMs = if (currentMode == "VBT") timeToPeakMs else null,
                                                            rfdNPerSec = if (currentMode == "VBT") rfdNPerSec else null,
                                                            pdfReportPath = reportPath,
                                                            videoExportPath = savedVideoPath
                                                        )
                                                    )
                                                    Toast.makeText(context, "✓ Informe PDF y Video guardados en el perfil de ${targetUser.name}", Toast.LENGTH_LONG).show()
                                                    onCloseClick()
                                                } finally {
                                                    isExportingVideo = false
                                                    isSavingToProfile = false
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF00FFCC),
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Text(
                                        if (isSavingToProfile) "Guardando Informe PDF y Video..." else "💾 Guardar en Perfil del Atleta (PDF + Video)",
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Botón: Generar Informe PDF con Keyframes y Diagnóstico
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            isGeneratingPdf = true
                                            try {
                                                val reportFile = BiomechanicalPdfReportGenerator.generateReport(
                                                    context = context,
                                                    videoUri = Uri.parse(videoUriString),
                                                    athleteName = activeUser?.name ?: "Atleta Invitado",
                                                    athleteWeightKg = activeUser?.bodyWeightKg ?: 75.0,
                                                    exerciseName = if (currentMode == "VBT") combinedExerciseDisplayName else "Salto Vertical (${selectedJumpType.name})",
                                                    exerciseFamily = if (currentMode == "VBT") selectedExerciseModel.family.label else "Salto",
                                                    loadKg = currentLoadKg,
                                                    peakVelocity = peakVelocity,
                                                    meanVelocity = meanVelocity,
                                                    vmpVelocity = vmpVelocity,
                                                    concentricDurationMs = concentricDurationMs,
                                                    timeToPeakMs = timeToPeakMs,
                                                    rfdNPerSec = rfdNPerSec,
                                                    peakPowerWatts = peakPowerWatts,
                                                    relativeWattsPerKg = relativeWattsPerKg,
                                                    peakForceN = peakForceN,
                                                    workJoules = workJoules,
                                                    estimatedOneRM = estimatedOneRM,
                                                    pctOneRM = pctOneRM,
                                                    horizontalDeviationCm = vbtDeviationResultCm,
                                                    trackingPoints = trackingPointsResult,
                                                    trimStartMs = trimStartMs,
                                                    trimEndMs = trimEndMs,
                                                    repsList = com.speedtracker.core.math.VBTAnalyzer.currentSetReps.toList(),
                                                    exerciseSequenceNames = if (currentMode == "VBT") selectedExerciseSequence.map { it.shortName } else emptyList(),
                                                    exerciseMvt = if (currentMode == "VBT") selectedExerciseModel.mvt else null
                                                )
                                                generatedPdfPath = reportFile.absolutePath
                                                showPdfPreviewDialog = true
                                                Toast.makeText(context, "Informe PDF generado con éxito", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                Toast.makeText(context, "Error al generar informe PDF: ${e.message}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                isGeneratingPdf = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (generatedPdfPath != null) Color(0xFF00E676) else Color(0xFF1E293B),
                                        contentColor = if (generatedPdfPath != null) Color.Black else Color(0xFF00FFCC)
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FFCC))
                                ) {
                                    if (isGeneratingPdf) {
                                        CircularProgressIndicator(
                                            color = Color(0xFF00FFCC),
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Generando Informe PDF...", fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.PictureAsPdf,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (generatedPdfPath != null) Color.Black else Color(0xFF00FFCC)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            if (generatedPdfPath != null) "Ver / Compartir Informe PDF ✓" else "📄 Generar Informe PDF",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedButton(
                                    onClick = {
                                        showExportDataDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF00FFCC))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Compartir Video + Informe", color = Color.White, fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedButton(
                                    onClick = {
                                        isReadyToAnalyze = false
                                        isCalibrated = false
                                        circleCenter = null
                                        calibratedPlateCenterVideo = null
                                        calibratedPlateRadiusVideo = 0f
                                        finalResult = null
                                        progress = 0f
                                        jumpMetricsResult = null
                                        vbtDeviationResultCm = null
                                        trackingPointsResult = emptyList()
                                        analysisSetupStep = AnalysisSetupStep.CALIBRATE
                                        runAutoDetectPlate(screenBoxWidth, screenBoxHeight)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(46.dp)
                                ) {
                                    Text("Reintentar Ajuste", color = Color.White)
                                }

                                TextButton(onClick = onCloseClick) {
                                    Text("Descartar y Salir", color = Color.Gray)
                                }
                            }

                            if (showExportDataDialog) {
                                val initialExName = if (currentMode == "VBT") combinedExerciseDisplayName else "Salto Vertical"
                                ExportReportDataDialog(
                                    initialAthleteName = activeUser?.name ?: "",
                                    initialLoadKg = currentLoadKg,
                                    initialExerciseName = initialExName,
                                    availableAthletes = allUsers,
                                    onDismiss = { showExportDataDialog = false },
                                    onConfirmExport = { athleteName, loadKg, exerciseName ->
                                        showExportDataDialog = false
                                        barbellLoadKg = loadKg.toInt().toString()

                                        // Buscar o registrar nuevo atleta
                                        val targetUser = allUsers.find { it.name.equals(athleteName, ignoreCase = true) }
                                            ?: userRepository.saveUser(athleteName, 75.0)
                                        selectedUserState = targetUser
                                        userRepository.setActiveUserId(targetUser.id)
                                        allUsers = userRepository.getUsers()

                                        // Sincronizar modelo de ejercicio si coincide
                                        VBTExerciseModel.values().find { it.displayName.equals(exerciseName, ignoreCase = true) }?.let {
                                            selectedExerciseModel = it
                                            selectedExerciseSequence = listOf(it)
                                        }

                                        coroutineScope.launch {
                                            isExportingVideo = true
                                            exportProgress = 0f
                                            try {
                                                val exportDir = File(context.filesDir, "saved_videos").apply { mkdirs() }
                                                val sanitizedAthlete = targetUser.name.replace(" ", "_").replace(Regex("[^a-zA-Z0-9_]"), "")
                                                val exportedFile = File(exportDir, "SpeedTracker_Export_${sanitizedAthlete}_${System.currentTimeMillis()}.mp4")

                                                val metricSummary = if (currentMode == "VBT") {
                                                    val est1RM = selectedExerciseModel.estimateOneRM(loadKg, peakVelocity, meanVelocity)
                                                    "$exerciseName • ${loadKg.toInt()} kg • 1RM: ${est1RM.toInt()}kg • VP: ${String.format(Locale.US, "%.2f", peakVelocity)} m/s"
                                                } else {
                                                    "Altura: ${String.format(Locale.US, "%.1f", (finalResult ?: 0.0) * 100)} cm"
                                                }

                                                // 1. Generar Informe PDF con el atleta y carga indicados
                                                val reportPath = try {
                                                    val repFile = BiomechanicalPdfReportGenerator.generateReport(
                                                        context = context,
                                                        videoUri = Uri.parse(videoUriString),
                                                        athleteName = targetUser.name,
                                                        athleteWeightKg = targetUser.bodyWeightKg,
                                                        exerciseName = exerciseName,
                                                        exerciseFamily = if (currentMode == "VBT") selectedExerciseModel.family.label else "Salto",
                                                        loadKg = loadKg,
                                                        peakVelocity = peakVelocity,
                                                        meanVelocity = meanVelocity,
                                                        vmpVelocity = vmpVelocity,
                                                        concentricDurationMs = concentricDurationMs,
                                                        timeToPeakMs = timeToPeakMs,
                                                        rfdNPerSec = rfdNPerSec,
                                                        peakPowerWatts = peakPowerWatts,
                                                        relativeWattsPerKg = relativeWattsPerKg,
                                                        peakForceN = peakForceN,
                                                        workJoules = workJoules,
                                                        estimatedOneRM = selectedExerciseModel.estimateOneRM(loadKg, peakVelocity, meanVelocity),
                                                        pctOneRM = selectedExerciseModel.calculatePercentOneRM(peakVelocity, meanVelocity),
                                                        horizontalDeviationCm = vbtDeviationResultCm,
                                                        trackingPoints = trackingPointsResult,
                                                        trimStartMs = trimStartMs,
                                                        trimEndMs = trimEndMs,
                                                        repsList = com.speedtracker.core.math.VBTAnalyzer.currentSetReps.toList(),
                                                        exerciseSequenceNames = if (currentMode == "VBT") selectedExerciseSequence.map { it.shortName } else emptyList(),
                                                        exerciseMvt = if (currentMode == "VBT") selectedExerciseModel.mvt else null
                                                    )
                                                    generatedPdfPath = repFile.absolutePath
                                                    repFile.absolutePath
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                    null
                                                }

                                                // 2. Exportar Video MP4 con Telemetría e incrustación de Atleta y Carga
                                                val finalVideoFile = VideoOverlayExporter.exportVideoWithOverlay(
                                                    context = context,
                                                    inputUri = Uri.parse(videoUriString),
                                                    outputFile = exportedFile,
                                                    mode = currentMode ?: "VBT",
                                                    trackingPoints = trackingPointsResult,
                                                    exerciseName = exerciseName,
                                                    athleteName = targetUser.name,
                                                    bestMetricSummary = metricSummary,
                                                    originBaselineY = VBTAnalyzer.originBaselineY,
                                                    lowestInflectionY = VBTAnalyzer.lowestInflectionY,
                                                    calibratedPlateRadius = calibratedPlateRadiusVideo,
                                                    startMs = trimStartMs,
                                                    endMs = trimEndMs,
                                                    onProgress = { p -> exportProgress = p }
                                                )
                                                generatedVideoPath = finalVideoFile.absolutePath

                                                // 3. Guardar Sesión persistente en el perfil del atleta
                                                userRepository.saveSession(
                                                    Session(
                                                        id = UUID.randomUUID().toString(),
                                                        userId = targetUser.id,
                                                        activityType = if (currentMode == "VBT") "VBT ($exerciseName)" else currentMode!!,
                                                        result = finalResult ?: peakVelocity,
                                                        timestamp = System.currentTimeMillis(),
                                                        powerWatts = if (currentMode == "VBT") peakPowerWatts else jumpMetricsResult?.peakPowerWatts,
                                                        powerWattsPerKg = if (currentMode == "VBT") relativeWattsPerKg else jumpMetricsResult?.relativePowerWattsPerKg,
                                                        horizontalDeviationCm = vbtDeviationResultCm,
                                                        meanVelocity = if (currentMode == "VBT") meanVelocity else null,
                                                        loadKg = if (currentMode == "VBT") loadKg else null,
                                                        vmpVelocity = if (currentMode == "VBT") vmpVelocity else null,
                                                        concentricDurationMs = if (currentMode == "VBT") concentricDurationMs else null,
                                                        timeToPeakMs = if (currentMode == "VBT") timeToPeakMs else null,
                                                        rfdNPerSec = if (currentMode == "VBT") rfdNPerSec else null,
                                                        pdfReportPath = reportPath,
                                                        videoExportPath = finalVideoFile.absolutePath
                                                    )
                                                )

                                                val summary = buildString {
                                                    append("⚡ Speed Tracker - Informe Biomecánico de Video\n")
                                                    append("Atleta: ${targetUser.name}\n")
                                                    append("Ejercicio: $exerciseName\n")
                                                    append("Carga en Barra: ${loadKg.toInt()} kg\n")
                                                    append("Velocidad Pico: ${String.format(Locale.US, "%.2f", peakVelocity)} m/s\n")
                                                    append("Velocidad Media: ${String.format(Locale.US, "%.2f", meanVelocity)} m/s\n")
                                                    val vmpStr = String.format(Locale.US, "%.2f", vmpVelocity)
                                                    append("Velocidad Propulsiva (VMP): $vmpStr m/s\n")
                                                    append("1RM Estimado: ${selectedExerciseModel.estimateOneRM(loadKg, peakVelocity, meanVelocity).toInt()} kg\n")
                                                }

                                                shareVideoWithReport(context, Uri.fromFile(finalVideoFile), summary)
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                shareVideoWithReport(context, Uri.parse(videoUriString), "Speed Tracker VBT")
                                            } finally {
                                                isExportingVideo = false
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        // Espacio al final para que la barra inferior flotante no tape los botones
                        Spacer(modifier = Modifier.height(84.dp))
                    }

                    // Barra persistente de navegación: [Video] [Datos Analizados] [Acciones]
                    // Solo el botón de la sección actual tiene color (#00FFCC) y cambia dinámicamente al hacer scroll o tocar.
                    var manualTabOverride by remember { mutableStateOf<Int?>(null) }
                    val activeBottomTab = manualTabOverride ?: when {
                        scrollState.maxValue > 0 && (
                            scrollState.value >= (scrollState.maxValue - 90).coerceAtLeast(100) ||
                            (actionsYOffset > 0 && scrollState.value >= (actionsYOffset - 320).coerceAtLeast(140))
                        ) -> 2
                        analysisDataYOffset > 0 && scrollState.value >= (analysisDataYOffset - 180).coerceAtLeast(80) -> 1
                        else -> 0
                    }

                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        color = Color(0xF5141414),
                        tonalElevation = 8.dp,
                        shadowElevation = 14.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A2A)),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Botón VIDEO
                            val isVideoTab = activeBottomTab == 0
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        manualTabOverride = 0
                                        scrollState.animateScrollTo(0)
                                        manualTabOverride = null
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isVideoTab) Color(0xFF162B24) else Color(0xFF1E1E1E),
                                    contentColor = if (isVideoTab) Color(0xFF00FFCC) else Color(0xFF8A8A8A)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isVideoTab) 1.5.dp else 1.dp,
                                    if (isVideoTab) Color(0xFF00FFCC) else Color(0xFF333333)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Videocam,
                                    contentDescription = "Video",
                                    modifier = Modifier.size(17.dp),
                                    tint = if (isVideoTab) Color(0xFF00FFCC) else Color(0xFF8A8A8A)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Video",
                                    fontWeight = if (isVideoTab) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.5.sp,
                                    maxLines = 1
                                )
                            }

                            // 2. Botón DATOS ANALIZADOS
                            val isDataTab = activeBottomTab == 1
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        manualTabOverride = 1
                                        scrollState.animateScrollTo((analysisDataYOffset - 4).coerceAtLeast(0))
                                        manualTabOverride = null
                                    }
                                },
                                modifier = Modifier
                                    .weight(1.3f)
                                    .height(44.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDataTab) Color(0xFF162B24) else Color(0xFF1E1E1E),
                                    contentColor = if (isDataTab) Color(0xFF00FFCC) else Color(0xFF8A8A8A)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isDataTab) 1.5.dp else 1.dp,
                                    if (isDataTab) Color(0xFF00FFCC) else Color(0xFF333333)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.QueryStats,
                                    contentDescription = "Datos",
                                    modifier = Modifier.size(17.dp),
                                    tint = if (isDataTab) Color(0xFF00FFCC) else Color(0xFF8A8A8A)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Datos Analizados",
                                    fontWeight = if (isDataTab) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            // 3. Botón ACCIONES
                            val isActionsTab = activeBottomTab == 2
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        manualTabOverride = 2
                                        scrollState.animateScrollTo(scrollState.maxValue)
                                        manualTabOverride = null
                                    }
                                },
                                modifier = Modifier
                                    .weight(1.1f)
                                    .height(44.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isActionsTab) Color(0xFF162B24) else Color(0xFF1E1E1E),
                                    contentColor = if (isActionsTab) Color(0xFF00FFCC) else Color(0xFF8A8A8A)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isActionsTab) 1.5.dp else 1.dp,
                                    if (isActionsTab) Color(0xFF00FFCC) else Color(0xFF333333)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.TouchApp,
                                    contentDescription = "Acciones",
                                    modifier = Modifier.size(17.dp),
                                    tint = if (isActionsTab) Color(0xFF00FFCC) else Color(0xFF8A8A8A)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Acciones",
                                    fontWeight = if (isActionsTab) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.5.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // Diálogo de exportación y quemado de video con trayectoria
        
    // Visor Instantáneo del Informe PDF dentro de la App (Cero pantalla en blanco + Compartir)
    if (showPdfPreviewDialog && generatedPdfPath != null && File(generatedPdfPath!!).exists()) {
        SavedPdfReportDialog(
            pdfFile = File(generatedPdfPath!!),
            title = "Informe Biomecánico • ${if (currentMode == "VBT") selectedVBTExercise.displayName else "Salto Vertical"}",
            onDismiss = { showPdfPreviewDialog = false }
        )
    }

    // Dialog explicativo de métricas biomecánicas
    if (selectedMetricInfo != null) {
        val info = selectedMetricInfo!!
        AlertDialog(
            onDismissRequest = { selectedMetricInfo = null },
            containerColor = Color(0xFF161F28),
            icon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFF00FFCC),
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    info.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column {
                        Text(
                            "¿QUÉ MIDE?",
                            color = Color(0xFF00FFCC),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            info.definition,
                            color = Color(0xFFE0E0E0),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }

                    Column {
                        Text(
                            "¿CÓMO INTERPRETARLO EN EL ENTRENAMIENTO?",
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            info.practicalValue,
                            color = Color(0xFFE0E0E0),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }

                    Column {
                        Text(
                            "VALORES DE REFERENCIA / CONSEJO TÉCNICO",
                            color = Color(0xFFFFD600),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            info.referenceRange,
                            color = Color(0xFFE0E0E0),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { selectedMetricInfo = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00FFCC),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Entendido", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (isExportingVideo) {
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = Color(0xFF00FFCC),
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            if (isSavingToProfile) "Guardando en Perfil del Atleta" else "Exportando Video",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                },
                text = {
                    Column {
                        Text(
                            "Incrustando trayectoria de la barra y telemetría HUD directamente en el video...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = exportProgress,
                            color = Color(0xFF00FFCC),
                            trackColor = Color(0xFF333333),
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "${(exportProgress * 100).toInt()}% procesado",
                            color = Color(0xFF00FFCC),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                },
                confirmButton = {},
                containerColor = Color(0xFF1E1E1E)
            )
        }

        if (showPreCalibrationSetupDialog) {
            PreCalibrationSetupDialog(
                currentMode = currentMode,
                onModeChange = { currentMode = it },
                setupFamily = setupFamily,
                onFamilyChange = { setupFamily = it },
                selectedVBTExercise = selectedVBTExercise,
                onVBTExerciseChange = {
                    selectedVBTExercise = it
                },
                selectedExerciseSequence = selectedExerciseSequence,
                onExerciseSequenceChange = { seq ->
                    selectedExerciseSequence = seq
                    if (seq.isNotEmpty()) {
                        selectedVBTExercise = seq.first()
                    }
                },
                selectedJumpType = selectedJumpType,
                onJumpTypeChange = {
                    selectedJumpType = it
                    VerticalJumpAnalyzer.currentJumpType = it
                },
                allUsers = allUsers,
                activeUser = activeUser,
                onSelectUser = { user ->
                    userRepository.setActiveUserId(user.id)
                    selectedUserState = user
                },
                onCreateUser = { name, weightKg ->
                    val created = userRepository.saveUser(name, weightKg)
                    allUsers = userRepository.getUsers()
                    selectedUserState = created
                },
                barbellLoadKg = barbellLoadKg,
                onLoadChange = { barbellLoadKg = it },
                onDismiss = { showPreCalibrationSetupDialog = false },
                onConfirmAndCalibrate = {
                    showPreCalibrationSetupDialog = false
                    analysisSetupStep = AnalysisSetupStep.CALIBRATE
                }
            )
        }

        if (showTutorialDialog) {
            TutorialGuideDialog(
                step = tutorialStep,
                onStepChange = { tutorialStep = it },
                onDismiss = {
                    showTutorialDialog = false
                    videoViewRef?.pause()
                    isPlaying = false
                },
                initialTopic = if (currentMode == "JUMP") "JUMP" else "VBT"
            )
        }
    }
}

/**
 * Estructura de datos temporal para la telemetría cinemática
 */
data class KinematicDataPoint(
    val timestampMs: Long,
    val velocityMps: Double,
    val accelerationMps2: Double,
    val isConcentric: Boolean
)

/**
 * Filtro digital bidireccional de fase cero (emulación Butterworth de 4to orden).
 * Aplica un pase hacia adelante y otro hacia atrás con ponderaciones gaussianas/binomiales
 * para eliminar el ruido de alta frecuencia del tracking sin introducir desfase temporal (Δφ = 0).
 */
/**
 * Filtro pasabajas gaussiano binomial de 7 coeficientes y fase cero (Forward-Backward, passes = 3).
 * Elimina por completo el temblor o ruido digital de cuantización sin introducir retardo temporal (Δt = 0),
 * preservando fielmente los picos fisiológicos de velocidad del levantamiento.
 */
fun zeroPhaseSmooth7Tap(data: List<Double>, passes: Int = 3): List<Double> {
    if (data.size < 7) return data
    var current = data.toDoubleArray()
    val weights = doubleArrayOf(0.04, 0.12, 0.22, 0.24, 0.22, 0.12, 0.04)
    val n = current.size
    repeat(passes) {
        val fwd = DoubleArray(n)
        for (i in 0 until n) {
            var sum = 0.0
            var wSum = 0.0
            for (k in -3..3) {
                val idx = (i + k).coerceIn(0, n - 1)
                val w = weights[k + 3]
                sum += current[idx] * w
                wSum += w
            }
            fwd[i] = sum / wSum
        }
        val bwd = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = 0.0
            var wSum = 0.0
            for (k in -3..3) {
                val idx = (i + k).coerceIn(0, n - 1)
                val w = weights[k + 3]
                sum += fwd[idx] * w
                wSum += w
            }
            bwd[i] = sum / wSum
        }
        current = bwd
    }
    return current.toList()
}

fun zeroPhaseSmooth(data: List<Double>, passes: Int = 2): List<Double> {
    if (data.size < 5) return data
    var current = data.toDoubleArray()
    val weights = doubleArrayOf(0.0625, 0.25, 0.375, 0.25, 0.0625)
    val n = current.size
    repeat(passes) {
        val fwd = DoubleArray(n)
        for (i in 0 until n) {
            var sum = 0.0
            var wSum = 0.0
            for (k in -2..2) {
                val idx = (i + k).coerceIn(0, n - 1)
                val w = weights[k + 2]
                sum += current[idx] * w
                wSum += w
            }
            fwd[i] = sum / wSum
        }
        val bwd = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = 0.0
            var wSum = 0.0
            for (k in -2..2) {
                val idx = (i + k).coerceIn(0, n - 1)
                val w = weights[k + 2]
                sum += fwd[idx] * w
                wSum += w
            }
            bwd[i] = sum / wSum
        }
        current = bwd
    }
    return current.toList()
}

/**
 * Gráfico dinámico de telemetría cinemática con cuadrícula oscura de alta precisión (estilo osciloscopio).
 * Traza las ondas dinámicas de Velocidad (m/s) y Aceleración (m/s²) a lo largo de la repetición,
 * con marcador de velocidad pico, línea de velocidad media (VM), línea cero de aceleración,
 * y cursor de reproducción dinámico sincronizado en tiempo real con el video en loop superior.
 */
@Composable
fun KinematicTelemetryChart(
    trackingPoints: List<VideoFrameTrackingPoint>,
    peakVelocity: Double,
    meanVelocity: Double,
    vmpVelocity: Double? = null,
    currentVideoPosMs: Long = 0L,
    onSeekToTimestamp: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (trackingPoints.size < 2) return

    val minTimeMs = trackingPoints.first().timestampMs
    val maxTimeMs = trackingPoints.last().timestampMs
    val totalDurationMs = (maxTimeMs - minTimeMs).coerceAtLeast(100L)

    // Modo de telemetría: 0: Velocidad (m/s), 1: Aceleración (m/s²), 2: Ambas superpuestas (activas por defecto)
    var telemetryMode by remember { mutableIntStateOf(2) }

    // Procesar datos y preservar exactamente el pico real (peakVelocity) para 100% de consistencia
    // entre la tarjeta principal, el marcador VP sobre la curva y la leyenda inferior
    val telemetryPoints = remember(trackingPoints, peakVelocity) {
        val rawV = trackingPoints.map { it.velocityMps }
        val baseSmoothedV = zeroPhaseSmooth(rawV, passes = 1)
        val smoothMax = baseSmoothedV.maxOrNull() ?: 0.0
        val targetPeak = if (peakVelocity > 0.05) peakVelocity else (rawV.maxOrNull() ?: 0.0)
        val peakScale = if (smoothMax > 0.05 && targetPeak > 0.05) (targetPeak / smoothMax) else 1.0
        val smoothedV = baseSmoothedV.map { it * peakScale }

        val rawA = mutableListOf<Double>()
        for (i in trackingPoints.indices) {
            val a = if (i > 0) {
                val dt = (trackingPoints[i].timestampMs - trackingPoints[i - 1].timestampMs) / 1000.0
                if (dt > 0.008) (smoothedV[i] - smoothedV[i - 1]) / dt else 0.0
            } else 0.0
            rawA.add(a.coerceIn(-25.0, 35.0))
        }
        val smoothedA = zeroPhaseSmooth(rawA)

        trackingPoints.indices.map { i ->
            KinematicDataPoint(
                timestampMs = trackingPoints[i].timestampMs,
                velocityMps = smoothedV[i],
                accelerationMps2 = smoothedA[i],
                isConcentric = trackingPoints[i].isConcentric
            )
        }
    }

    // Velocidad Media Propulsiva (VMP): unificada al 100% con el cálculo principal de la tarjeta de resultados
    val vmp = remember(telemetryPoints, vmpVelocity, meanVelocity) {
        vmpVelocity ?: run {
            val propulsivePoints = telemetryPoints.filter { (it.isConcentric || it.velocityMps > 0.15) && it.accelerationMps2 >= -9.81 }
            if (propulsivePoints.isNotEmpty()) propulsivePoints.map { it.velocityMps }.average().coerceAtLeast(meanVelocity) else null
        }
    }

    val maxV = remember(telemetryPoints, peakVelocity) {
        val top = maxOf(telemetryPoints.maxOfOrNull { it.velocityMps } ?: 0.0, peakVelocity)
        (top * 1.15).coerceAtLeast(0.8)
    }
    val minV = 0.0

    val maxA = remember(telemetryPoints) {
        val top = telemetryPoints.maxOfOrNull { it.accelerationMps2 } ?: 5.0
        (top * 1.15).coerceAtLeast(5.0)
    }
    val minA = remember(telemetryPoints) {
        val btm = telemetryPoints.minOfOrNull { it.accelerationMps2 } ?: -4.0
        (btm * 1.15).coerceAtMost(-3.0)
    }

    val peakAccel = remember(telemetryPoints) {
        telemetryPoints.maxOfOrNull { it.accelerationMps2 } ?: 0.0
    }

    // Punto activo sincronizado con el cursor dinámico
    val activePoint = remember(telemetryPoints, currentVideoPosMs) {
        if (telemetryPoints.isEmpty()) null
        else telemetryPoints.minByOrNull { kotlin.math.abs(it.timestampMs - currentVideoPosMs) }
    }

    val curTimeSec = ((activePoint?.timestampMs ?: currentVideoPosMs) - minTimeMs).coerceAtLeast(0L) / 1000.0
    val curV = activePoint?.velocityMps ?: 0.0
    val curA = activePoint?.accelerationMps2 ?: 0.0

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14181F)),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262C36))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Cabecera: Título y Selector de Métrica
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ShowChart,
                        contentDescription = null,
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Telemetría Cinemática",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                // Chips para alternar métricas
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Velocidad", "Aceleración", "Ambas").forEachIndexed { idx, label ->
                        val isSelected = telemetryMode == idx
                        val activeColor = when (idx) {
                            0 -> Color(0xFF00FFCC)
                            1 -> Color(0xFFFF9100)
                            else -> Color(0xFFE0E0E0)
                        }
                        Surface(
                            onClick = { telemetryMode = idx },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) activeColor.copy(alpha = 0.22f) else Color(0xFF1C222C),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) activeColor else Color(0xFF2D3542)
                            ),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 7.dp)
                            ) {
                                Text(
                                    label,
                                    color = if (isSelected) activeColor else Color.Gray,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Barra HUD con valores instantáneos dinámicos sincronizados
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0D1015),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F242C)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "⏱ t: ${String.format(Locale.US, "%.2f", curTimeSec)} s",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (telemetryMode == 0 || telemetryMode == 2) {
                        Text(
                            "⚡ V: ${String.format(Locale.US, "%.2f", curV)} m/s",
                            color = Color(0xFF00FFCC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (telemetryMode == 1 || telemetryMode == 2) {
                        Text(
                            "📈 A: ${String.format(Locale.US, "%+.1f", curA)} m/s²",
                            color = Color(0xFFFF9100),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Cuadrícula y Ondas (Canvas de Telemetría Dinámica con Touch & Drag Scrubbing)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(155.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF090C10))
                    .border(1.dp, Color(0xFF1E2530), RoundedCornerShape(8.dp))
                    .pointerInput(minTimeMs, totalDurationMs) {
                        val leftPad = 34.dp.toPx()
                        val rightPad = if (telemetryMode == 2) 34.dp.toPx() else 12.dp.toPx()
                        fun updateSeek(x: Float) {
                            val plotW = (size.width - leftPad - rightPad).coerceAtLeast(10f)
                            val frac = ((x - leftPad) / plotW).coerceIn(0f, 1f)
                            val targetMs = (minTimeMs + frac * totalDurationMs).toLong()
                            onSeekToTimestamp?.invoke(targetMs)
                        }
                        detectTapGestures { offset ->
                            updateSeek(offset.x)
                        }
                    }
                    .pointerInput(minTimeMs, totalDurationMs) {
                        val leftPad = 34.dp.toPx()
                        val rightPad = if (telemetryMode == 2) 34.dp.toPx() else 12.dp.toPx()
                        fun updateSeek(x: Float) {
                            val plotW = (size.width - leftPad - rightPad).coerceAtLeast(10f)
                            val frac = ((x - leftPad) / plotW).coerceIn(0f, 1f)
                            val targetMs = (minTimeMs + frac * totalDurationMs).toLong()
                            onSeekToTimestamp?.invoke(targetMs)
                        }
                        detectDragGestures(
                            onDragStart = { offset -> updateSeek(offset.x) },
                            onDrag = { change, _ ->
                                change.consume()
                                updateSeek(change.position.x)
                            }
                        )
                    }
            ) {
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val w = size.width
                    val h = size.height

                    val leftPad = 34.dp.toPx()
                    val rightPad = if (telemetryMode == 2) 34.dp.toPx() else 12.dp.toPx()
                    val topPad = 14.dp.toPx()
                    val bottomPad = 20.dp.toPx()

                    val plotW = (w - leftPad - rightPad).coerceAtLeast(10f)
                    val plotH = (h - topPad - bottomPad).coerceAtLeast(10f)

                    val textPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 9.sp.toPx()
                        typeface = android.graphics.Typeface.MONOSPACE
                    }

                    // 1. Líneas de Cuadrícula Horizontal (4 subdivisiones)
                    val gridDashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    for (step in 0..4) {
                        val y = topPad + (step.toFloat() / 4f) * plotH
                        drawLine(
                            color = Color(0xFF1B222C),
                            start = Offset(leftPad, y),
                            end = Offset(leftPad + plotW, y),
                            strokeWidth = 1f,
                            pathEffect = gridDashEffect
                        )

                        // Escala Y izquierda (Velocidad en modo 0 o 2)
                        if (telemetryMode == 0 || telemetryMode == 2) {
                            val vVal = maxV - (step.toFloat() / 4f) * (maxV - minV)
                            textPaint.color = android.graphics.Color.parseColor("#00FFCC")
                            textPaint.textAlign = android.graphics.Paint.Align.RIGHT
                            drawContext.canvas.nativeCanvas.drawText(
                                String.format(Locale.US, "%.1f", vVal),
                                leftPad - 4.dp.toPx(),
                                y + 3.dp.toPx(),
                                textPaint
                            )
                        } else if (telemetryMode == 1) {
                            val aVal = maxA - (step.toFloat() / 4f) * (maxA - minA)
                            textPaint.color = android.graphics.Color.parseColor("#FF9100")
                            textPaint.textAlign = android.graphics.Paint.Align.RIGHT
                            drawContext.canvas.nativeCanvas.drawText(
                                String.format(Locale.US, "%+.0f", aVal),
                                leftPad - 4.dp.toPx(),
                                y + 3.dp.toPx(),
                                textPaint
                            )
                        }

                        // Escala Y derecha (Aceleración en modo 2 Ambas)
                        if (telemetryMode == 2) {
                            val aVal = maxA - (step.toFloat() / 4f) * (maxA - minA)
                            textPaint.color = android.graphics.Color.parseColor("#FF9100")
                            textPaint.textAlign = android.graphics.Paint.Align.LEFT
                            drawContext.canvas.nativeCanvas.drawText(
                                String.format(Locale.US, "%+.0f", aVal),
                                leftPad + plotW + 4.dp.toPx(),
                                y + 3.dp.toPx(),
                                textPaint
                            )
                        }
                    }

                    // 2. Líneas de Cuadrícula Vertical (Tiempo)
                    for (step in 0..4) {
                        val x = leftPad + (step.toFloat() / 4f) * plotW
                        drawLine(
                            color = Color(0xFF1B222C),
                            start = Offset(x, topPad),
                            end = Offset(x, topPad + plotH),
                            strokeWidth = 1f,
                            pathEffect = gridDashEffect
                        )

                        // Etiquetas de tiempo en eje X inferior
                        val tSec = (step.toFloat() / 4f) * (totalDurationMs / 1000f)
                        textPaint.color = android.graphics.Color.parseColor("#78909C")
                        textPaint.textAlign = android.graphics.Paint.Align.CENTER
                        drawContext.canvas.nativeCanvas.drawText(
                            String.format(Locale.US, "%.1fs", tSec),
                            x,
                            h - 4.dp.toPx(),
                            textPaint
                        )
                    }

                    // 3. Línea Base Cero de Aceleración (si aplica)
                    if ((telemetryMode == 1 || telemetryMode == 2) && minA < 0.0 && maxA > 0.0) {
                        val zeroFrac = ((0.0 - minA) / (maxA - minA)).toFloat()
                        val zeroY = topPad + plotH - (zeroFrac * plotH)
                        drawLine(
                            color = Color(0xFF555555),
                            start = Offset(leftPad, zeroY),
                            end = Offset(leftPad + plotW, zeroY),
                            strokeWidth = 1.2f
                        )
                    }

                    // 4. Línea de Referencia Velocidad Media (VM) si modo 0 o 2
                    if (telemetryMode == 0 || telemetryMode == 2) {
                        val vmFrac = ((meanVelocity - minV) / (maxV - minV)).coerceIn(0.0, 1.0).toFloat()
                        val vmY = topPad + plotH - (vmFrac * plotH)
                        drawLine(
                            color = Color(0xFF00E676).copy(alpha = 0.7f),
                            start = Offset(leftPad, vmY),
                            end = Offset(leftPad + plotW, vmY),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                        )
                        textPaint.color = android.graphics.Color.parseColor("#00E676")
                        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
                        drawContext.canvas.nativeCanvas.drawText(
                            "VM ${String.format(Locale.US, "%.2f", meanVelocity)}",
                            leftPad + plotW - 4.dp.toPx(),
                            vmY - 3.dp.toPx(),
                            textPaint
                        )

                        // 4.1 Línea de Referencia Velocidad Media Propulsiva (VMP)
                        if (vmp != null) {
                            val vmpFrac = ((vmp - minV) / (maxV - minV)).coerceIn(0.0, 1.0).toFloat()
                            val vmpY = topPad + plotH - (vmpFrac * plotH)
                            drawLine(
                                color = Color(0xFF00FFCC).copy(alpha = 0.6f),
                                start = Offset(leftPad, vmpY),
                                end = Offset(leftPad + plotW, vmpY),
                                strokeWidth = 1.2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                            )
                            textPaint.color = android.graphics.Color.parseColor("#00FFCC")
                            textPaint.textAlign = android.graphics.Paint.Align.RIGHT
                            drawContext.canvas.nativeCanvas.drawText(
                                "VMP ${String.format(Locale.US, "%.2f", vmp)}",
                                leftPad + plotW - 4.dp.toPx(),
                                vmpY + 11.dp.toPx(),
                                textPaint
                            )
                        }
                    }

                    // 5. Onda de Aceleración (ámbar/naranja)
                    if (telemetryMode == 1 || telemetryMode == 2) {
                        val pathA = Path()
                        val fillPathA = Path()

                        telemetryPoints.forEachIndexed { i, dp ->
                            val tFrac = ((dp.timestampMs - minTimeMs).toFloat() / totalDurationMs).coerceIn(0f, 1f)
                            val x = leftPad + tFrac * plotW
                            val aFrac = ((dp.accelerationMps2 - minA) / (maxA - minA)).coerceIn(0.0, 1.0).toFloat()
                            val y = topPad + plotH - (aFrac * plotH)

                            if (i == 0) {
                                pathA.moveTo(x, y)
                                fillPathA.moveTo(x, topPad + plotH)
                                fillPathA.lineTo(x, y)
                            } else {
                                pathA.lineTo(x, y)
                                fillPathA.lineTo(x, y)
                            }

                            if (i == telemetryPoints.size - 1) {
                                fillPathA.lineTo(x, topPad + plotH)
                                fillPathA.close()
                            }
                        }

                        // Relleno degradado
                        drawPath(
                            fillPathA,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0x35FF9100), Color(0x02FF9100)),
                                startY = topPad,
                                endY = topPad + plotH
                            )
                        )

                        // Trazo de la curva de aceleración
                        drawPath(
                            pathA,
                            color = Color(0xFFFF9100),
                            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // 6. Onda de Velocidad (cyan/neón)
                    if (telemetryMode == 0 || telemetryMode == 2) {
                        val pathV = Path()
                        val fillPathV = Path()

                        telemetryPoints.forEachIndexed { i, dp ->
                            val tFrac = ((dp.timestampMs - minTimeMs).toFloat() / totalDurationMs).coerceIn(0f, 1f)
                            val x = leftPad + tFrac * plotW
                            val vFrac = ((dp.velocityMps - minV) / (maxV - minV)).coerceIn(0.0, 1.0).toFloat()
                            val y = topPad + plotH - (vFrac * plotH)

                            if (i == 0) {
                                pathV.moveTo(x, y)
                                fillPathV.moveTo(x, topPad + plotH)
                                fillPathV.lineTo(x, y)
                            } else {
                                pathV.lineTo(x, y)
                                fillPathV.lineTo(x, y)
                            }

                            if (i == telemetryPoints.size - 1) {
                                fillPathV.lineTo(x, topPad + plotH)
                                fillPathV.close()
                            }
                        }

                        // Relleno degradado
                        drawPath(
                            fillPathV,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0x4000FFCC), Color(0x0200FFCC)),
                                startY = topPad,
                                endY = topPad + plotH
                            )
                        )

                        // Trazo de la curva de velocidad
                        drawPath(
                            pathV,
                            color = Color(0xFF00FFCC),
                            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // 7. Marcador de Velocidad Pico
                    if (telemetryMode == 0 || telemetryMode == 2) {
                        val peakPt = telemetryPoints.maxByOrNull { it.velocityMps }
                        if (peakPt != null && peakPt.velocityMps > 0.3) {
                            val tFrac = ((peakPt.timestampMs - minTimeMs).toFloat() / totalDurationMs).coerceIn(0f, 1f)
                            val px = leftPad + tFrac * plotW
                            val vFrac = ((peakPt.velocityMps - minV) / (maxV - minV)).coerceIn(0.0, 1.0).toFloat()
                            val py = topPad + plotH - (vFrac * plotH)

                            drawCircle(Color(0xFF00FFCC).copy(alpha = 0.35f), radius = 7.dp.toPx(), center = Offset(px, py))
                            drawCircle(Color(0xFF00FFCC), radius = 3.5.dp.toPx(), center = Offset(px, py))
                            drawCircle(Color.White, radius = 1.5.dp.toPx(), center = Offset(px, py))

                            textPaint.color = android.graphics.Color.WHITE
                            textPaint.textAlign = android.graphics.Paint.Align.CENTER
                            drawContext.canvas.nativeCanvas.drawText(
                                "VP ${String.format(Locale.US, "%.2f", peakPt.velocityMps)}",
                                px,
                                py - 8.dp.toPx(),
                                textPaint
                            )
                        }
                    }

                    // 8. Cursor Dinámico Sincronizado en Tiempo Real con la Reproducción
                    val cursorFrac = ((currentVideoPosMs - minTimeMs).toFloat() / totalDurationMs)
                    if (cursorFrac in 0f..1f) {
                        val cursorX = leftPad + cursorFrac * plotW

                        // Línea vertical del cabezal
                        drawLine(
                            color = Color.White.copy(alpha = 0.85f),
                            start = Offset(cursorX, topPad),
                            end = Offset(cursorX, topPad + plotH),
                            strokeWidth = 1.8.dp.toPx()
                        )

                        // Puntos activos sobre las ondas en la posición del cursor
                        if (telemetryMode == 0 || telemetryMode == 2) {
                            val vFrac = ((curV - minV) / (maxV - minV)).coerceIn(0.0, 1.0).toFloat()
                            val curY_V = topPad + plotH - (vFrac * plotH)
                            drawCircle(Color(0xFF00FFCC), radius = 4.5.dp.toPx(), center = Offset(cursorX, curY_V))
                            drawCircle(Color.White, radius = 2.dp.toPx(), center = Offset(cursorX, curY_V))
                        }

                        if (telemetryMode == 1 || telemetryMode == 2) {
                            val aFrac = ((curA - minA) / (maxA - minA)).coerceIn(0.0, 1.0).toFloat()
                            val curY_A = topPad + plotH - (aFrac * plotH)
                            drawCircle(Color(0xFFFF9100), radius = 4.5.dp.toPx(), center = Offset(cursorX, curY_A))
                            drawCircle(Color.White, radius = 2.dp.toPx(), center = Offset(cursorX, curY_A))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Pie: Leyenda y Valores Pico (Ranuras compactas sin saltos de línea para evitar temblores)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(7.dp).background(Color(0xFF00FFCC), CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (vmp != null)
                            "Vel: VP ${String.format(Locale.US, "%.2f", peakVelocity)} | VMP ${String.format(Locale.US, "%.2f", vmp)} m/s"
                        else
                            "Velocidad: ${String.format(Locale.US, "%.2f", peakVelocity)} m/s",
                        color = Color.LightGray,
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(7.dp).background(Color(0xFFFF9100), CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Acel: ${String.format(Locale.US, "%+.1f", peakAccel)} m/s²",
                        color = Color.LightGray,
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * Overlay de telemetría de gráficos en tiempo real sincronizado con el video.
 * Ocupa exactamente 1/5 del espacio inferior del video.
 * Traza las curvas de Velocidad (m/s) en cian y Aceleración (m/s²) en naranja,
 * con un cursor blanco vertical que avanza frame a frame según la reproducción.
 */
/**
 * Overlay de Telemetría Dinámica en Tiempo Real (1/5 del alto del video).
 * Muestra la curva de Velocidad Biomecánica limpia, con degradado suave,
 * hito de Velocidad Pico (VP), línea de Velocidad Media (VM), sombreado
 * de Fase Concéntrica y cursor vertical sincronizado fotograma a fotograma.
 * Incluye un filtro pasabajas de fase cero de 7 coeficientes y toggle para
 * superponer la aceleración sin saturar la vista.
 */
@Composable
fun RealtimeTelemetryOverlayChart(
    trackingPoints: List<VideoFrameTrackingPoint>,
    currentVideoPosMs: Long,
    trimStartMs: Long,
    effectiveEndMs: Long,
    onSeekPositionMs: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (trackingPoints.size < 2) return

    val minTimeMs = trackingPoints.first().timestampMs
    val maxTimeMs = trackingPoints.last().timestampMs
    val totalDurationMs = (maxTimeMs - minTimeMs).coerceAtLeast(100L)

    var showAcceleration by remember { mutableStateOf(true) }

    // Suavizado avanzado de fase cero con preservación exacta del pico real (VP)
    // para que ★ Pico en el gráfico del video sea idéntico a la tarjeta principal y a Telemetría Cinemática
    val telemetryPoints = remember(trackingPoints) {
        val rawV = trackingPoints.map { it.velocityMps }
        val rawPeak = trackingPoints.maxOfOrNull { maxOf(it.velocityMps, it.currentPeakVelocity) } ?: 0.0
        val baseSmoothedV = zeroPhaseSmooth7Tap(rawV, passes = 2)
        val smoothMax = baseSmoothedV.maxOrNull() ?: 0.0
        val peakScale = if (smoothMax > 0.05 && rawPeak > 0.05) (rawPeak / smoothMax) else 1.0
        val smoothedV = baseSmoothedV.map { it * peakScale }

        val rawA = mutableListOf<Double>()
        for (i in trackingPoints.indices) {
            val a = if (i > 0) {
                val dt = (trackingPoints[i].timestampMs - trackingPoints[i - 1].timestampMs) / 1000.0
                if (dt > 0.008) (smoothedV[i] - smoothedV[i - 1]) / dt else 0.0
            } else 0.0
            rawA.add(a.coerceIn(-25.0, 35.0))
        }
        val smoothedA = zeroPhaseSmooth7Tap(rawA, passes = 2)

        trackingPoints.indices.map { i ->
            KinematicDataPoint(
                timestampMs = trackingPoints[i].timestampMs,
                velocityMps = smoothedV[i],
                accelerationMps2 = smoothedA[i],
                isConcentric = trackingPoints[i].isConcentric
            )
        }
    }

    // Métricas clave para destacar
    val peakPoint = remember(telemetryPoints) {
        telemetryPoints.maxByOrNull { it.velocityMps }
    }
    val peakV = peakPoint?.velocityMps ?: 1.0

    val meanV = remember(telemetryPoints) {
        val conc = telemetryPoints.filter { it.isConcentric }
        if (conc.isNotEmpty()) conc.map { it.velocityMps }.average() else (peakV * 0.55)
    }

    val maxV = remember(telemetryPoints) {
        (peakV * 1.18).coerceAtLeast(0.6)
    }

    val maxA = remember(telemetryPoints) {
        (telemetryPoints.maxOfOrNull { it.accelerationMps2 } ?: 5.0).coerceAtLeast(4.0) * 1.15
    }
    val minA = remember(telemetryPoints) {
        (telemetryPoints.minOfOrNull { it.accelerationMps2 } ?: -4.0).coerceAtMost(-3.0) * 1.15
    }

    // Rango concéntrico para sombreado de fondo
    val concentricRange = remember(telemetryPoints) {
        val concPoints = telemetryPoints.filter { it.isConcentric }
        if (concPoints.size >= 2) {
            Pair(concPoints.first().timestampMs, concPoints.last().timestampMs)
        } else null
    }

    val elapsedInTrim = (currentVideoPosMs - trimStartMs).coerceAtLeast(0L)
    val activePoint = remember(telemetryPoints, elapsedInTrim) {
        telemetryPoints.minByOrNull { kotlin.math.abs(it.timestampMs - elapsedInTrim) }
    }

    Column(modifier = modifier) {
        // 1. Barra HUD Superior Compacta con Ranuras Fijas (Inmune a variación de caracteres numéricos)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Ranura fija Velocidad instantánea en el cursor (86dp)
                Box(modifier = Modifier.width(86.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        "⚡ V: ${String.format(Locale.US, "%.2f", activePoint?.velocityMps ?: 0.0)} m/s",
                        color = Color(0xFF00FFCC),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // Ranura fija Velocidad Pico (74dp)
                Box(modifier = Modifier.width(74.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        "★ Pico: ${String.format(Locale.US, "%.2f", peakV)}",
                        color = Color(0xFFFFD600),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // Ranura fija Badge de Fase (76dp con ancho fijo para evitar empujar otros elementos)
                val isConc = activePoint?.isConcentric == true
                Surface(
                    color = if (isConc) Color(0x3300FFCC) else Color(0x22FFFFFF),
                    shape = RoundedCornerShape(3.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, if (isConc) Color(0xFF00FFCC) else Color(0x44FFFFFF)),
                    modifier = Modifier.width(76.dp)
                ) {
                    Text(
                        if (isConc) "CONCÉNTRICA" else "INERCIAL",
                        color = if (isConc) Color(0xFF00FFCC) else Color.LightGray,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 1.dp),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                // Ranura fija Aceleración (84dp)
                if (showAcceleration) {
                    Box(modifier = Modifier.width(84.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            "📈 A: ${String.format(Locale.US, "%+4.1f", activePoint?.accelerationMps2 ?: 0.0)} m/s²",
                            color = Color(0xFFFF9100),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Chip fijo para encender/apagar aceleración sin saturar la vista (48dp)
            Surface(
                onClick = { showAcceleration = !showAcceleration },
                shape = RoundedCornerShape(4.dp),
                color = if (showAcceleration) Color(0xFF332000) else Color(0x22FFFFFF),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (showAcceleration) Color(0xFFFF9100) else Color(0x44FFFFFF)),
                modifier = Modifier.width(48.dp)
            ) {
                Text(
                    if (showAcceleration) "Acel ✓" else "+ Acel",
                    color = if (showAcceleration) Color(0xFFFF9100) else Color.LightGray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 2.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // 2. Gráfico Limpio con Curva de Velocidad, Gradiente, VM, VP y Cursor (Interactuable con Touch & Drag para Scrubbing Cuadro a Cuadro)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(totalDurationMs, minTimeMs, trimStartMs, effectiveEndMs) {
                    val pad = 6.dp.toPx()
                    fun updateSeek(x: Float) {
                        val plotW = (size.width - pad * 2f).coerceAtLeast(10f)
                        val frac = ((x - pad) / plotW).coerceIn(0f, 1f)
                        val targetMs = trimStartMs + (frac * totalDurationMs).toLong()
                        onSeekPositionMs?.invoke(targetMs.coerceIn(trimStartMs, effectiveEndMs))
                    }
                    detectTapGestures { offset ->
                        updateSeek(offset.x)
                    }
                }
                .pointerInput(totalDurationMs, minTimeMs, trimStartMs, effectiveEndMs) {
                    val pad = 6.dp.toPx()
                    fun updateSeek(x: Float) {
                        val plotW = (size.width - pad * 2f).coerceAtLeast(10f)
                        val frac = ((x - pad) / plotW).coerceIn(0f, 1f)
                        val targetMs = trimStartMs + (frac * totalDurationMs).toLong()
                        onSeekPositionMs?.invoke(targetMs.coerceIn(trimStartMs, effectiveEndMs))
                    }
                    detectDragGestures(
                        onDragStart = { offset -> updateSeek(offset.x) },
                        onDrag = { change, _ ->
                            change.consume()
                            updateSeek(change.position.x)
                        }
                    )
                }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                // Sombreado de fondo en Fase Concéntrica (delimita la fase de tirón propulsivo)
                if (concentricRange != null) {
                    val startX = ((concentricRange.first - minTimeMs).toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) * w
                    val endX = ((concentricRange.second - minTimeMs).toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) * w
                    val rangeW = (endX - startX).coerceAtLeast(2f)
                    drawRect(
                        color = Color(0x1800FFCC),
                        topLeft = Offset(startX, 0f),
                        size = Size(rangeW, h)
                    )
                }

                // Línea discontinua de Velocidad Media (VM)
                val vmY = (h - ((meanV / maxV).toFloat().coerceIn(0f, 1f) * h))
                drawLine(
                    color = Color(0x4400FFCC),
                    start = Offset(0f, vmY),
                    end = Offset(w, vmY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )

                // Si está activada la aceleración, trazar su línea cero y curva filtrada
                if (showAcceleration) {
                    val zeroAY = (h - ((0.0 - minA) / (maxA - minA) * h).toFloat()).coerceIn(0f, h)
                    drawLine(
                        color = Color(0x33FFFFFF),
                        start = Offset(0f, zeroAY),
                        end = Offset(w, zeroAY),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    )

                    val aPath = Path()
                    telemetryPoints.forEachIndexed { idx, pt ->
                        val x = ((pt.timestampMs - minTimeMs).toFloat() / totalDurationMs.toFloat()) * w
                        val normA = ((pt.accelerationMps2 - minA) / (maxA - minA)).toFloat().coerceIn(0f, 1f)
                        val y = h - (normA * h)
                        if (idx == 0) aPath.moveTo(x, y) else aPath.lineTo(x, y)
                    }
                    drawPath(
                        path = aPath,
                        color = Color(0xFFFF9100).copy(alpha = 0.85f),
                        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                // Curva de Velocidad (Protagonista: Trazado en cian neón con degradado)
                val vPath = Path()
                val vFillPath = Path()
                telemetryPoints.forEachIndexed { idx, pt ->
                    val x = ((pt.timestampMs - minTimeMs).toFloat() / totalDurationMs.toFloat()) * w
                    val normV = (pt.velocityMps / maxV).toFloat().coerceIn(0f, 1f)
                    val y = h - (normV * h)
                    if (idx == 0) {
                        vPath.moveTo(x, y)
                        vFillPath.moveTo(x, h)
                        vFillPath.lineTo(x, y)
                    } else {
                        vPath.lineTo(x, y)
                        vFillPath.lineTo(x, y)
                    }
                    if (idx == telemetryPoints.size - 1) {
                        vFillPath.lineTo(x, h)
                        vFillPath.close()
                    }
                }

                // Relleno degradado sutil y moderno
                drawPath(
                    path = vFillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0x4000FFCC), Color(0x0200FFCC)),
                        startY = 0f,
                        endY = h
                    )
                )

                // Trazo nítido de la velocidad
                drawPath(
                    path = vPath,
                    color = Color(0xFF00FFCC),
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Marcador visual de Velocidad Pico (VP)
                if (peakPoint != null && peakV > 0.2) {
                    val px = ((peakPoint.timestampMs - minTimeMs).toFloat() / totalDurationMs.toFloat()) * w
                    val py = h - ((peakV / maxV).toFloat().coerceIn(0f, 1f) * h)
                    // Guía vertical discontinua
                    drawLine(
                        color = Color(0x5500FFCC),
                        start = Offset(px, 0f),
                        end = Offset(px, h),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    )
                    // Halo exterior cian
                    drawCircle(Color(0x6600FFCC), radius = 6.dp.toPx(), center = Offset(px, py))
                    // Anillo cian brillante
                    drawCircle(Color(0xFF00FFCC), radius = 3.5.dp.toPx(), center = Offset(px, py))
                    // Núcleo blanco nítido
                    drawCircle(Color.White, radius = 1.5.dp.toPx(), center = Offset(px, py))
                }

                // Cursor vertical dinámico sincronizado en tiempo real con el video
                val cursorFrac = (elapsedInTrim.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                val cursorX = cursorFrac * w
                drawLine(
                    color = Color.White,
                    start = Offset(cursorX, 0f),
                    end = Offset(cursorX, h),
                    strokeWidth = 2.dp.toPx()
                )
                val dotY = (h - (((activePoint?.velocityMps ?: 0.0) / maxV).toFloat().coerceIn(0f, 1f) * h))
                drawCircle(
                    color = Color.White,
                    radius = 3.5.dp.toPx(),
                    center = Offset(cursorX, dotY)
                )
                drawCircle(
                    color = Color(0xFF00FFCC),
                    radius = 2.dp.toPx(),
                    center = Offset(cursorX, dotY)
                )
            }
        }
    }
}

/**
 * Reproductor interactivo de Vista Previa para la pantalla de Resultados.
 * Permite visualizar el video analizado con la trayectoria (Bar Path) trazada
 * en tiempo real con colores cinemáticos de velocidad, mira telescópica sobre el disco,
 * scrub cuadro a cuadro y selector de cámara lenta (1.0x, 0.5x, 0.25x).
 */
@Composable
fun AnalysisResultVideoPreview(
    videoUriString: String,
    videoNativeWidth: Int,
    videoNativeHeight: Int,
    trimStartMs: Long,
    trimEndMs: Long,
    trackingPoints: List<VideoFrameTrackingPoint>,
    originBaselineY: Float?,
    lowestInflectionY: Float?,
    mode: String,
    barbellLoadKg: Double? = null,
    calibratedPlateRadius: Float? = null,
    externalSeekPosMs: Long? = null,
    onCurrentPositionChange: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val previewContext = androidx.compose.ui.platform.LocalContext.current
    var previewPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewVideoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isPreviewMuted by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var currentPosMs by remember { mutableLongStateOf(trimStartMs) }
    var previewStillBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val previewRetriever = remember(videoUriString) {
        MediaMetadataRetriever().apply {
            try {
                setDataSource(previewContext, Uri.parse(videoUriString))
            } catch (e: Exception) {}
        }
    }
    DisposableEffect(previewRetriever) {
        onDispose {
            try { previewRetriever.release() } catch (e: Exception) {}
        }
    }
    val effectiveEndMs = if (trimEndMs > trimStartMs + 100L) trimEndMs else (trimStartMs + 3000L)

    LaunchedEffect(currentPosMs, isPlaying) {
        if (isPlaying) {
            previewStillBitmap = null
        } else {
            // Sincronización exacta 1:1 con el fotograma analizado del tracker:
            // Si existen trackingPoints, decodificamos exactamente el mismo timestamp (trimStartMs + pt.timestampMs)
            // que se usó para rastrear el disco, evitando cualquier desfase entre imagen pausada y retícula.
            val elapsedRelMs = (currentPosMs - trimStartMs).coerceAtLeast(0L)
            val matchedPt = if (trackingPoints.isNotEmpty()) {
                trackingPoints.lastOrNull { it.timestampMs <= elapsedRelMs } ?: trackingPoints.first()
            } else null
            val exactDecodeMs = if (matchedPt != null) {
                (trimStartMs + matchedPt.timestampMs).coerceIn(trimStartMs, effectiveEndMs)
            } else {
                currentPosMs
            }

            withContext(Dispatchers.IO) {
                try {
                    val targetUs = exactDecodeMs * 1000L
                    val bm = previewRetriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: previewRetriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: previewRetriever.getFrameAtTime((exactDecodeMs - 50L).coerceAtLeast(0L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bm != null) {
                        withContext(Dispatchers.Main) {
                            if (!isPlaying) {
                                previewStillBitmap = bm
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }
    var isUserSeeking by remember { mutableStateOf(false) }
    var showTelemetryOverlay by remember { mutableStateOf(false) }
    var lastSyncWallClockMs by remember { mutableLongStateOf(0L) }
    var lastSyncPositionMs by remember { mutableLongStateOf(trimStartMs) }

    LaunchedEffect(currentPosMs) {
        onCurrentPositionChange(currentPosMs)
    }

    LaunchedEffect(externalSeekPosMs) {
        externalSeekPosMs?.let { targetMs ->
            val clamped = targetMs.coerceIn(trimStartMs, effectiveEndMs)
            currentPosMs = clamped
            lastSyncPositionMs = clamped
            lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
            seekVideoAccurate(previewPlayerRef, previewVideoViewRef, clamped)
        }
    }

    val isVertical = if (videoNativeWidth > 0 && videoNativeHeight > 0) videoNativeHeight > videoNativeWidth else true
    val previewHeight = if (isVertical) 330.dp else 240.dp

    fun applySpeed(speed: Float) {
        playbackSpeed = speed
        lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
        lastSyncPositionMs = currentPosMs
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                previewPlayerRef?.let { mp ->
                    val params = mp.playbackParams
                    params.speed = speed
                    mp.playbackParams = params
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (isPlaying) {
            previewVideoViewRef?.let { vv ->
                if (!vv.isPlaying) {
                    vv.start()
                }
            }
        }
    }

    LaunchedEffect(isPreviewMuted, previewPlayerRef) {
        previewPlayerRef?.let { mp ->
            try {
                if (isPreviewMuted) mp.setVolume(0f, 0f) else mp.setVolume(1f, 1f)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Bucle continuo en el rango recortado del levantamiento con interpolación suave a 60 fps (solo activo cuando isPlaying == true)
    LaunchedEffect(previewVideoViewRef, isPlaying, trimStartMs, effectiveEndMs, isUserSeeking, playbackSpeed) {
        if (!isPlaying) return@LaunchedEffect
        lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
        lastSyncPositionMs = currentPosMs

        while (isPlaying) {
            if (!isUserSeeking) {
                previewVideoViewRef?.let { vv ->
                    if (vv.isPlaying) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val reportedPos = vv.currentPosition.toLong()

                        // Si el MediaPlayer reportó un nuevo fotograma, resincronizar el ancla temporal
                        if (reportedPos != lastSyncPositionMs) {
                            lastSyncPositionMs = reportedPos
                            lastSyncWallClockMs = now
                        }

                        // Interpolar avance de tiempo entre ticks nativos del MediaPlayer para evitar retardo
                        val elapsedSinceSync = ((now - lastSyncWallClockMs) * playbackSpeed).toLong()
                        val interpolated = (lastSyncPositionMs + elapsedSinceSync).coerceIn(trimStartMs, effectiveEndMs)
                        currentPosMs = interpolated

                        if (interpolated >= effectiveEndMs) {
                            vv.seekTo(trimStartMs.toInt())
                            currentPosMs = trimStartMs
                            lastSyncPositionMs = trimStartMs
                            lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                }
            }
            delay(16) // ~60 fps fluido sin jitter
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                previewVideoViewRef?.stopPlayback()
            } catch (e: Exception) {}
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0x5500FFCC))
        )
    ) {
        Column {
            // Contenedor del video con Canvas de trayectoria superpuesto centrado
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(previewHeight)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val bounds = calculateVideoRenderBounds(boxW, boxH, videoNativeWidth, videoNativeHeight)
                val rWDp = with(LocalDensity.current) { bounds.renderWidth.toDp() }
                val rHDp = with(LocalDensity.current) { bounds.renderHeight.toDp() }

                Box(
                    modifier = Modifier
                        .size(rWDp, rHDp)
                        .align(Alignment.Center)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(Uri.parse(videoUriString))
                                setOnPreparedListener { mp ->
                                    previewPlayerRef = mp
                                    mp.isLooping = false
                                    if (isPreviewMuted) mp.setVolume(0f, 0f) else mp.setVolume(1f, 1f)
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                        try {
                                            val params = mp.playbackParams
                                            params.speed = playbackSpeed
                                            mp.playbackParams = params
                                        } catch (e: Exception) {}
                                    }
                                    // Iniciar siempre en pausa en el primer frame; solo reproduce al presionar el botón Play
                                    seekVideoAccurate(mp, this, trimStartMs)
                                    pause()
                                    isPlaying = false
                                    currentPosMs = trimStartMs
                                    lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
                                    lastSyncPositionMs = trimStartMs
                                }
                                setOnCompletionListener {
                                    if (isPlaying) {
                                        seekTo(trimStartMs.toInt())
                                        currentPosMs = trimStartMs
                                        lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
                                        lastSyncPositionMs = trimStartMs
                                        start()
                                    }
                                }
                                previewVideoViewRef = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { vv ->
                            try { vv.stopPlayback() } catch (e: Exception) {}
                        }
                    )

                    if (!isPlaying && previewStillBitmap != null) {
                        Image(
                            bitmap = previewStillBitmap!!.asImageBitmap(),
                            contentDescription = "Fotograma en pausa",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }

                    // Capa Canvas: Bar Path dinámico suavizado con curvas Catmull-Rom Cubic Bezier y mira telescópica 1:1
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val rW = size.width
                        val rH = size.height
                        if (rW <= 0f || rH <= 0f) return@Canvas

                        val scaleX = rW / videoNativeWidth.toFloat()
                        val scaleY = rH / videoNativeHeight.toFloat()

                        // 1. Líneas de referencia (Base y Punto de inflexión)
                        originBaselineY?.let { origY ->
                            val sBaseY = origY * scaleY
                            drawLine(
                                color = Color(0x6600FFCC),
                                start = Offset(0f, sBaseY),
                                end = Offset(rW, sBaseY),
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 16f))
                            )
                        }
                        lowestInflectionY?.let { inflectY ->
                            val sInfY = inflectY * scaleY
                            drawLine(
                                color = Color(0x66FFD600),
                                start = Offset(0f, sInfY),
                                end = Offset(rW, sInfY),
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f))
                            )
                        }

                        // 2. Trayectoria estática completa suavizada (Fondo translúcido de referencia)
                        if (trackingPoints.size > 1) {
                            val bgPath = Path()
                            val firstS = Offset(trackingPoints[0].x * scaleX, trackingPoints[0].y * scaleY)
                            bgPath.moveTo(firstS.x, firstS.y)
                            for (i in 0 until trackingPoints.size - 1) {
                                val p0 = trackingPoints[(i - 1).coerceAtLeast(0)]
                                val p1 = trackingPoints[i]
                                val p2 = trackingPoints[i + 1]
                                val p3 = trackingPoints[(i + 2).coerceAtMost(trackingPoints.lastIndex)]
                                val s0 = Offset(p0.x * scaleX, p0.y * scaleY)
                                val s1 = Offset(p1.x * scaleX, p1.y * scaleY)
                                val s2 = Offset(p2.x * scaleX, p2.y * scaleY)
                                val s3 = Offset(p3.x * scaleX, p3.y * scaleY)
                                val cp1X = s1.x + (s2.x - s0.x) * 0.20f
                                val cp1Y = s1.y + (s2.y - s0.y) * 0.20f
                                val cp2X = s2.x - (s3.x - s1.x) * 0.20f
                                val cp2Y = s2.y - (s3.y - s1.y) * 0.20f
                                bgPath.cubicTo(cp1X, cp1Y, cp2X, cp2Y, s2.x, s2.y)
                            }
                            drawPath(
                                path = bgPath,
                                color = Color(0x2BFFFFFF),
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }

                        // 3. Trayectoria activa dinámica sincronizada con el fotograma (suavizada con splines cúbicos)
                        val elapsedInTrimMs = (currentPosMs - trimStartMs).coerceAtLeast(0L)
                        val activePoints = if (currentPosMs >= effectiveEndMs - 25L) {
                            trackingPoints
                        } else {
                            trackingPoints.filter { it.timestampMs <= elapsedInTrimMs }
                        }
                        val currentPoint = activePoints.lastOrNull() ?: trackingPoints.firstOrNull()

                        if (activePoints.size > 1) {
                            val glowStroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            val coreStroke = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            for (i in 0 until activePoints.size - 1) {
                                val p0 = activePoints[(i - 1).coerceAtLeast(0)]
                                val p1 = activePoints[i]
                                val p2 = activePoints[i + 1]
                                val p3 = activePoints[(i + 2).coerceAtMost(activePoints.lastIndex)]

                                val isMoving = p2.isConcentric || p2.isEccentric || p2.velocityMps > 0.15 || p1.isConcentric
                                if (!isMoving && mode == "VBT") continue

                                val s0 = Offset(p0.x * scaleX, p0.y * scaleY)
                                val s1 = Offset(p1.x * scaleX, p1.y * scaleY)
                                val s2 = Offset(p2.x * scaleX, p2.y * scaleY)
                                val s3 = Offset(p3.x * scaleX, p3.y * scaleY)

                                val cp1X = s1.x + (s2.x - s0.x) * 0.20f
                                val cp1Y = s1.y + (s2.y - s0.y) * 0.20f
                                val cp2X = s2.x - (s3.x - s1.x) * 0.20f
                                val cp2Y = s2.y - (s3.y - s1.y) * 0.20f

                                val segPath = Path().apply {
                                    moveTo(s1.x, s1.y)
                                    cubicTo(cp1X, cp1Y, cp2X, cp2Y, s2.x, s2.y)
                                }

                                val segColor = when {
                                    p2.velocityMps >= 1.10 -> Color(0xFFFF3D00)
                                    p2.velocityMps >= 0.60 -> Color(0xFF00E676)
                                    p2.isEccentric -> Color(0xFFFFD600)
                                    else -> Color(0xFF00E5FF)
                                }

                                drawPath(
                                    path = segPath,
                                    color = segColor.copy(alpha = 0.35f),
                                    style = glowStroke
                                )
                                drawPath(
                                    path = segPath,
                                    color = segColor,
                                    style = coreStroke
                                )
                            }
                        }

                        // 4. Retícula / Mira óptica sobre el disco o centro de masa
                        if (currentPoint != null) {
                            val sc = Offset(currentPoint.x * scaleX, currentPoint.y * scaleY)
                            val trackerColor = when {
                                currentPoint.velocityMps >= 1.10 -> Color(0xFFFF3D00)
                                currentPoint.velocityMps >= 0.60 -> Color(0xFF00E676)
                                currentPoint.isEccentric -> Color(0xFFFFD600)
                                else -> Color(0xFF00FFCC)
                            }

                            val effectivePlateRadius = calibratedPlateRadius?.takeIf { it > 10f }
                                ?: VBTAnalyzer.calibratedPlateRadius?.takeIf { it > 10f }
                                ?: (if (VBTAnalyzer.pxToMetersRatio > 0.0001) (0.225 / VBTAnalyzer.pxToMetersRatio).toFloat() else null)

                            val screenPlateRadius = if (effectivePlateRadius != null && effectivePlateRadius > 10f) {
                                effectivePlateRadius * scaleX
                            } else {
                                (rH * 0.075f).coerceAtLeast(36.dp.toPx())
                            }

                            // 1. Círculo exterior con el tamaño real del disco (45 cm)
                            drawCircle(
                                color = trackerColor.copy(alpha = 0.16f),
                                radius = screenPlateRadius,
                                center = sc
                            )
                            drawCircle(
                                color = trackerColor,
                                radius = screenPlateRadius,
                                center = sc,
                                style = Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                                )
                            )

                            // 2. Collarín / Buje central óptico (28% del disco)
                            val collarRadius = (screenPlateRadius * 0.28f).coerceAtLeast(10.dp.toPx())
                            drawCircle(
                                color = trackerColor.copy(alpha = 0.35f),
                                radius = collarRadius,
                                center = sc
                            )
                            drawCircle(
                                color = trackerColor,
                                radius = collarRadius,
                                center = sc,
                                style = Stroke(width = 2.dp.toPx())
                            )

                            // 3. Punto central bullseye
                            drawCircle(
                                color = trackerColor,
                                radius = 3.5.dp.toPx(),
                                center = sc
                            )

                            // 4. Cruz (+)
                            val crossArm = collarRadius + 6.dp.toPx()
                            drawLine(
                                color = trackerColor,
                                start = Offset(sc.x - crossArm, sc.y),
                                end = Offset(sc.x + crossArm, sc.y),
                                strokeWidth = 1.5.dp.toPx()
                            )
                            drawLine(
                                color = trackerColor,
                                start = Offset(sc.x, sc.y - crossArm),
                                end = Offset(sc.x, sc.y + crossArm),
                                strokeWidth = 1.5.dp.toPx()
                            )
                        }
                    }

                    // Icono central sutil cuando está en pausa
                    if (!isPlaying) {
                        Surface(
                            color = Color(0x88000000),
                            shape = CircleShape,
                            modifier = Modifier
                                .size(54.dp)
                                .align(Alignment.Center)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Pausado",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }

                // HUD Superior Izquierdo: Telemetría instantánea alineada al costado izquierdo con ranuras fijas (Cero temblor o vibración)
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .width(250.dp),
                    color = Color(0xDD111111),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x6600FFCC))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val elapsedInTrimMs = (currentPosMs - trimStartMs).coerceAtLeast(0L)
                        val activePt = trackingPoints.filter { it.timestampMs <= elapsedInTrimMs }.lastOrNull()
                        if (mode == "VBT") {
                            val vel = activePt?.velocityMps ?: 0.0
                            val load = barbellLoadKg ?: 70.0
                            val instPower = (vel * load * 9.81).toInt()

                            // Ranura fija para Carga (56dp)
                            Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) {
                                Text(
                                    "⚡ ${load.toInt()} kg",
                                    color = Color(0xFF00FFCC),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            Text("•", color = Color.Gray, fontSize = 9.sp)

                            // Ranura fija para Velocidad (56dp)
                            Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "${String.format(Locale.US, "%.2f", vel)} m/s",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            Text("•", color = Color.Gray, fontSize = 9.sp)

                            // Ranura fija para Watts (48dp)
                            Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.CenterEnd) {
                                Text(
                                    "${instPower} W",
                                    color = Color(0xFF00FFCC),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            // Ranura fija para Badge de Fase (32dp siempre presente para evitar saltos horizontales)
                            val isConc = activePt?.isConcentric == true
                            val isEcc = activePt?.isEccentric == true
                            Surface(
                                color = when {
                                    isConc -> Color(0x3300E676)
                                    isEcc -> Color(0x33FFD600)
                                    else -> Color(0x22333333)
                                },
                                shape = RoundedCornerShape(4.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    0.5.dp,
                                    when {
                                        isConc -> Color(0xFF00E676)
                                        isEcc -> Color(0xFFFFD600)
                                        else -> Color(0x33666666)
                                    }
                                ),
                                modifier = Modifier.width(32.dp)
                            ) {
                                Text(
                                    when {
                                        isConc -> "CON"
                                        isEcc -> "EXC"
                                        else -> "---"
                                    },
                                    color = when {
                                        isConc -> Color(0xFF00E676)
                                        isEcc -> Color(0xFFFFD600)
                                        else -> Color.DarkGray
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        } else {
                            Text(
                                "🎯 VISTA PREVIA SALTO",
                                color = Color(0xFF00FFCC),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // Badge Superior Derecho: Estado alineado al costado derecho
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    color = Color(0xDD00221A),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x9900FFCC))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF00FFCC), CircleShape)
                        )
                        Text(
                            "VISTA PREVIA",
                            color = Color(0xFF00FFCC),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // Overlay de Gráficos de Velocidad y Aceleración en tiempo real (1/5 del alto del video en la parte inferior)
                if (showTelemetryOverlay && trackingPoints.size > 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(previewHeight * 0.20f)
                            .align(Alignment.BottomCenter)
                            .background(Color(0xD90A0D14))
                            .border(
                                width = 1.dp,
                                color = Color(0x5500FFCC),
                                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                            )
                    ) {
                        RealtimeTelemetryOverlayChart(
                            trackingPoints = trackingPoints,
                            currentVideoPosMs = currentPosMs,
                            trimStartMs = trimStartMs,
                            effectiveEndMs = effectiveEndMs,
                            onSeekPositionMs = { targetMs ->
                                val clamped = targetMs.coerceIn(trimStartMs, effectiveEndMs)
                                currentPosMs = clamped
                                lastSyncPositionMs = clamped
                                lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
                                seekVideoAccurate(previewPlayerRef, previewVideoViewRef, clamped)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            // Panel de Control de Reproducción (Scrubber, Play/Pause, Cuadro a Cuadro, Velocidad)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF181818)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Fila con Controles, Cuadro a Cuadro y Scrubber
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                previewVideoViewRef?.let { vv ->
                                    if (isPlaying) {
                                        val actualPos = vv.currentPosition.toLong().coerceIn(trimStartMs, effectiveEndMs)
                                        vv.pause()
                                        isPlaying = false
                                        currentPosMs = actualPos
                                        lastSyncPositionMs = actualPos
                                        lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
                                        seekVideoAccurate(previewPlayerRef, vv, actualPos)
                                    } else {
                                        val startFrom = if (currentPosMs >= effectiveEndMs - 50L) trimStartMs else currentPosMs
                                        currentPosMs = startFrom
                                        lastSyncPositionMs = startFrom
                                        lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
                                        previewStillBitmap = null
                                        seekVideoAccurate(previewPlayerRef, vv, startFrom)
                                        vv.start()
                                        isPlaying = true
                                    }
                                }
                            },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                tint = Color(0xFF00FFCC),
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                previewVideoViewRef?.let { vv ->
                                    vv.seekTo(trimStartMs.toInt())
                                    vv.start()
                                    currentPosMs = trimStartMs
                                    lastSyncPositionMs = trimStartMs
                                    lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
                                    isPlaying = true
                                }
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.Default.Replay,
                                contentDescription = "Reiniciar",
                                tint = Color.LightGray,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Botón -1 Frame: retrocede al fotograma analizado exacto anterior
                        FilledTonalIconButton(
                            onClick = {
                                previewVideoViewRef?.let { vv ->
                                    if (vv.isPlaying) vv.pause()
                                }
                                isPlaying = false
                                val elapsedRelMs = (currentPosMs - trimStartMs).coerceAtLeast(0L)
                                val prevPt = trackingPoints.lastOrNull { it.timestampMs < elapsedRelMs - 5L }
                                val stepPos = if (prevPt != null) {
                                    (trimStartMs + prevPt.timestampMs).coerceIn(trimStartMs, effectiveEndMs)
                                } else {
                                    val rawPrev = currentPosMs - 33L
                                    if (rawPrev <= trimStartMs + 15L) trimStartMs else rawPrev.coerceIn(trimStartMs, effectiveEndMs)
                                }
                                currentPosMs = stepPos
                                lastSyncPositionMs = stepPos
                                lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
                                seekVideoAccurate(previewPlayerRef, previewVideoViewRef, stepPos)
                            },
                            modifier = Modifier.size(36.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xFF262626),
                                contentColor = Color.LightGray
                            )
                        ) {
                            Text("◀", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Botón +1 Frame: avanza al siguiente fotograma analizado exacto
                        FilledTonalIconButton(
                            onClick = {
                                previewVideoViewRef?.let { vv ->
                                    if (vv.isPlaying) vv.pause()
                                }
                                isPlaying = false
                                val elapsedRelMs = (currentPosMs - trimStartMs).coerceAtLeast(0L)
                                val nextPt = trackingPoints.firstOrNull { it.timestampMs > elapsedRelMs + 5L }
                                val stepPos = if (nextPt != null) {
                                    (trimStartMs + nextPt.timestampMs).coerceIn(trimStartMs, effectiveEndMs)
                                } else {
                                    val rawNext = currentPosMs + 33L
                                    if (rawNext >= effectiveEndMs - 15L) effectiveEndMs else rawNext.coerceIn(trimStartMs, effectiveEndMs)
                                }
                                currentPosMs = stepPos
                                lastSyncPositionMs = stepPos
                                lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
                                seekVideoAccurate(previewPlayerRef, previewVideoViewRef, stepPos)
                            },
                            modifier = Modifier.size(36.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xFF262626),
                                contentColor = Color.LightGray
                            )
                        ) {
                            Text("▶", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        val rangeMin = trimStartMs.toFloat()
                        val rangeMax = if (effectiveEndMs > trimStartMs) effectiveEndMs.toFloat() else (trimStartMs + 1000f)
                        val sliderVal = currentPosMs.toFloat().coerceIn(rangeMin, rangeMax)
                        var lastSeekRequestedMs by remember { mutableLongStateOf(0L) }

                        Slider(
                            value = sliderVal,
                            onValueChange = { newPos ->
                                isUserSeeking = true
                                currentPosMs = newPos.toLong().coerceIn(trimStartMs, effectiveEndMs)
                                lastSyncPositionMs = currentPosMs
                                lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (isPlaying && now - lastSeekRequestedMs >= 35L) {
                                    lastSeekRequestedMs = now
                                    seekVideoAccurate(previewPlayerRef, previewVideoViewRef, currentPosMs)
                                }
                            },
                            onValueChangeFinished = {
                                isUserSeeking = false
                                lastSyncWallClockMs = android.os.SystemClock.elapsedRealtime()
                                lastSyncPositionMs = currentPosMs
                                if (isPlaying) {
                                    seekVideoAccurate(previewPlayerRef, previewVideoViewRef, currentPosMs)
                                    previewVideoViewRef?.start()
                                }
                            },
                            valueRange = rangeMin..rangeMax,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00FFCC),
                                activeTrackColor = Color(0xFF00FFCC),
                                inactiveTrackColor = Color(0xFF333333)
                            )
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        val elapsedSec = ((currentPosMs - trimStartMs).coerceAtLeast(0L) / 1000.0)
                        val totalSec = ((effectiveEndMs - trimStartMs).coerceAtLeast(100L) / 1000.0)
                        Text(
                            "${String.format("%.1f", elapsedSec)}s / ${String.format("%.1f", totalSec)}s",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // Botón de silenciar video de vista previa
                        FilledTonalIconButton(
                            onClick = {
                                isPreviewMuted = !isPreviewMuted
                            },
                            modifier = Modifier.size(36.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (isPreviewMuted) Color(0xCCFF5252) else Color(0xFF262626),
                                contentColor = if (isPreviewMuted) Color.White else Color(0xFF00FFCC)
                            )
                        ) {
                            Icon(
                                if (isPreviewMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = if (isPreviewMuted) "Activar sonido" else "Silenciar video",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Selector de velocidad (cámara lenta) y botón de Gráficos Overlay centrado
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Controles de velocidad a la izquierda
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Velocidad:",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )

                            listOf(0.25f to "0.25x", 0.5f to "0.5x", 1.0f to "1.0x").forEach { (spd, lbl) ->
                                val isSelected = playbackSpeed == spd
                                Surface(
                                    onClick = { applySpeed(spd) },
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSelected) Color(0x3300FFCC) else Color(0xFF262626),
                                    border = if (isSelected) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00FFCC))) else null,
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    ) {
                                        Text(
                                            lbl,
                                            color = if (isSelected) Color(0xFF00FFCC) else Color.LightGray,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


fun seekVideoAccurate(mp: MediaPlayer?, vv: VideoView?, targetMs: Long) {
    if (mp != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        try {
            mp.seekTo(targetMs, MediaPlayer.SEEK_CLOSEST)
            return
        } catch (e: Exception) { }
    }
    vv?.seekTo(targetMs.toInt())
}

fun formatTimeMs(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val ms = (timeMs % 1000) / 10
    return String.format("%02d:%02d.%02d", minutes, seconds, ms)
}

data class ProcessVideoResult(
    val maxResult: Double,
    val trackingPoints: List<VideoFrameTrackingPoint>,
    val detectedPlateCenter: Offset? = null,
    val detectedPlateRadius: Float? = null
)

suspend fun processVideo(
    context: Context,
    uri: Uri,
    mode: String,
    bodyWeightKg: Double,
    startMs: Long = 0L,
    endMs: Long = 0L,
    calibratedPlateCenter: Offset? = null,
    calibratedPlateRadius: Float? = null,
    calibratedJumpTakeoffMs: Double? = null,
    calibratedJumpLandingMs: Double? = null,
    calibratedJumpContactMs: Double? = null,
    calibratedJumpGroundY: Float? = null,
    jumpSlowMoFactor: Double = 1.0,
    nativeFrameStepMs: Long = 33L,
    selectedJumpType: JumpType = JumpType.CMJ,
    leftJumpTakeoffMs: Double? = null,
    leftJumpLandingMs: Double? = null,
    rightJumpTakeoffMs: Double? = null,
    rightJumpLandingMs: Double? = null,
    onProgress: (Float) -> Unit
): ProcessVideoResult = withContext(Dispatchers.IO) {
    var maxResult = 0.0
    val trackingPoints = mutableListOf<VideoFrameTrackingPoint>()
    var activePlateCenter: Offset? = calibratedPlateCenter
    var activePlateRadius: Float? = calibratedPlateRadius
    val retriever = MediaMetadataRetriever()
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    val wakeLock = powerManager?.newWakeLock(
        android.os.PowerManager.PARTIAL_WAKE_LOCK,
        "SpeedTracker:VideoProcessingWakeLock"
    )

    try {
        try { wakeLock?.acquire(10 * 60 * 1000L) } catch (e: Exception) {}
        retriever.setDataSource(context, uri)
        val timeString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = timeString?.toLongOrNull() ?: 0L

        if (durationMs > 0) {
            val effectiveStartMs = startMs.coerceIn(0L, durationMs)
            val effectiveEndMs = if (endMs in (effectiveStartMs + 100)..durationMs) endMs else durationMs
            val trimDurationMs = (effectiveEndMs - effectiveStartMs).coerceAtLeast(100L)

            val options = AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
                .build()
            val detector = PoseDetection.getClient(options)

            if (mode == "VBT") {
                VBTAnalyzer.reset()
                if (calibratedPlateCenter != null && calibratedPlateRadius != null) {
                    VBTAnalyzer.setCalibratedPlate(calibratedPlateCenter, calibratedPlateRadius)
                }
            }
            if (mode == "JUMP") {
                VerticalJumpAnalyzer.reset()
                VerticalJumpAnalyzer.currentJumpType = selectedJumpType
            }

            val frameIntervalMs = if (mode == "JUMP") nativeFrameStepMs.coerceIn(16L, 33L) else 33L
            val totalFrames = (trimDurationMs / frameIntervalMs).coerceAtLeast(1L)

            val barbellTracker = KinoveaBarbellTracker()
            activePlateCenter = calibratedPlateCenter
            activePlateRadius = calibratedPlateRadius
            val rawJumpFootSamples = mutableListOf<JumpFootSample>()
            var detectedVideoH = 1920

            for (i in 0..totalFrames) {
                val timeOffsetMs = i * frameIntervalMs
                val timeUs = (effectiveStartMs + timeOffsetMs) * 1000L
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)

                if (bitmap != null) {
                    try {
                        if (mode == "JUMP") {
                            if (bitmap.height > 0) detectedVideoH = bitmap.height
                            val image = InputImage.fromBitmap(bitmap, 0)
                            val pose = detector.process(image).await()
                            val height = VerticalJumpAnalyzer.processPose(pose, timeOffsetMs, bodyWeightKg)
                            if (height != null && height > maxResult) {
                                maxResult = height
                            }
                            // En plano de salto, priorizar punta/talón/tobillo de ambos pies
                            val lf = pose.getPoseLandmark(PoseLandmark.LEFT_FOOT_INDEX)
                                ?: pose.getPoseLandmark(PoseLandmark.LEFT_HEEL)
                                ?: pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
                            val rf = pose.getPoseLandmark(PoseLandmark.RIGHT_FOOT_INDEX)
                                ?: pose.getPoseLandmark(PoseLandmark.RIGHT_HEEL)
                                ?: pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
                            val ptX: Float?
                            val ptY: Float?
                            val ptLabel: String
                            if (lf != null && rf != null && (lf.inFrameLikelihood > 0.25f || rf.inFrameLikelihood > 0.25f)) {
                                ptX = (lf.position.x + rf.position.x) / 2f
                                ptY = maxOf(lf.position.y, rf.position.y)
                                ptLabel = "Pies (Despegue)"
                                rawJumpFootSamples.add(JumpFootSample(effectiveStartMs + timeOffsetMs, ptX, ptY, lf.position.y, rf.position.y))
                            } else {
                                val lh = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
                                val rh = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
                                if (lh != null && rh != null) {
                                    ptX = (lh.position.x + rh.position.x) / 2f
                                    ptY = (lh.position.y + rh.position.y) / 2f
                                    ptLabel = "Centro de Masa"
                                } else {
                                    ptX = null
                                    ptY = null
                                    ptLabel = ""
                                }
                            }
                            if (ptX != null && ptY != null) {
                                trackingPoints.add(
                                    VideoFrameTrackingPoint(
                                        timestampMs = timeOffsetMs,
                                        x = ptX,
                                        y = ptY,
                                        velocityMps = 0.0,
                                        labelText = ptLabel
                                    )
                                )
                            }
                        } else if (mode == "VBT") {
                            // Inicializar el tracker visual Kinovea en el primer frame disponible
                            if (!barbellTracker.isInitialized) {
                                val currentRadius = activePlateRadius
                                if (activePlateCenter == null || currentRadius == null || currentRadius <= 5f) {
                                    val image = InputImage.fromBitmap(bitmap, 0)
                                    val initialPose = try { detector.process(image).await() } catch (e: Exception) { null }
                                    val detected = OlympicPlateAutoDetector.detect(bitmap, initialPose)
                                    if (detected.radiusPx > 10f) {
                                        activePlateCenter = detected.center
                                        activePlateRadius = detected.radiusPx
                                    }
                                }

                                val plateCenter = activePlateCenter ?: Offset(bitmap.width * 0.5f, bitmap.height * 0.70f)
                                val curPlateRad = activePlateRadius
                                val plateRadius = if (curPlateRad != null && curPlateRad > 10f) curPlateRad else (minOf(bitmap.width, bitmap.height) * 0.12f)

                                VBTAnalyzer.setCalibratedPlate(plateCenter, plateRadius)
                                barbellTracker.initialize(bitmap, plateCenter, plateRadius)
                            }

                            // Rastrear la posición exacta del buje/collarín del disco (Algoritmo Kinovea ZNCC)
                            val barPos = barbellTracker.trackFrame(bitmap)
                            val velocity = VBTAnalyzer.processBarPosition(barPos.x, barPos.y, timeOffsetMs)
                            if (velocity != null && velocity > maxResult) {
                                maxResult = velocity
                            }

                            trackingPoints.add(
                                VideoFrameTrackingPoint(
                                    timestampMs = timeOffsetMs,
                                    x = barPos.x,
                                    y = barPos.y,
                                    velocityMps = velocity ?: 0.0,
                                    isConcentric = VBTAnalyzer.isConcentricActive,
                                    isEccentric = VBTAnalyzer.isEccentricActive,
                                    currentMeanVelocity = VBTAnalyzer.currentMeanVelocity,
                                    currentPeakVelocity = VBTAnalyzer.currentPeakVelocity
                                )
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                withContext(Dispatchers.Main) {
                    if (totalFrames > 0) {
                        onProgress((i.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }

            // En modo JUMP: Aplicar cálculo de alta precisión (My Jump + Interpolación Sub-Frame + Factor Slow-Mo)
            if (mode == "JUMP") {
                var finalTakeoffMs = calibratedJumpTakeoffMs
                var finalLandingMs = calibratedJumpLandingMs
                var finalContactMs = calibratedJumpContactMs

                if ((finalTakeoffMs == null || finalLandingMs == null || finalLandingMs <= finalTakeoffMs) && rawJumpFootSamples.size >= 4) {
                    val sortedY = rawJumpFootSamples.map { it.footY }.sorted()
                    val groundY = calibratedJumpGroundY ?: sortedY[(sortedY.size * 0.85).toInt().coerceIn(0, sortedY.size - 1)]
                    val (cMs, tMs, lMs) = computeSubFrameJumpEvents(
                        samples = rawJumpFootSamples,
                        groundNativeY = groundY,
                        videoNativeHeight = detectedVideoH,
                        jumpType = selectedJumpType
                    )
                    if (tMs != null && lMs != null && lMs > tMs) {
                        finalContactMs = cMs
                        finalTakeoffMs = tMs
                        finalLandingMs = lMs
                    }
                }

                var finalLeftTakeoff = leftJumpTakeoffMs
                var finalLeftLanding = leftJumpLandingMs
                var finalRightTakeoff = rightJumpTakeoffMs
                var finalRightLanding = rightJumpLandingMs

                if (selectedJumpType == JumpType.ASYMMETRY_TEST && rawJumpFootSamples.size >= 4) {
                    if (finalLeftTakeoff == null || finalLeftLanding == null) {
                        val lSamples = rawJumpFootSamples.map { it.copy(footY = it.leftFootY) }
                        val lGround = lSamples.map { it.footY }.sorted()[(lSamples.size * 0.85).toInt().coerceIn(0, lSamples.size - 1)]
                        val (_, lT, lL) = computeSubFrameJumpEvents(lSamples, lGround, detectedVideoH, JumpType.CMJ)
                        finalLeftTakeoff = lT ?: finalTakeoffMs
                        finalLeftLanding = lL ?: finalLandingMs
                    }
                    if (finalRightTakeoff == null || finalRightLanding == null) {
                        val rSamples = rawJumpFootSamples.map { it.copy(footY = it.rightFootY) }
                        val rGround = rSamples.map { it.footY }.sorted()[(rSamples.size * 0.85).toInt().coerceIn(0, rSamples.size - 1)]
                        val (_, rT, rL) = computeSubFrameJumpEvents(rSamples, rGround, detectedVideoH, JumpType.CMJ)
                        finalRightTakeoff = rT ?: finalTakeoffMs
                        finalRightLanding = rL ?: finalLandingMs
                    }
                    if (finalTakeoffMs == null && finalLeftTakeoff != null) finalTakeoffMs = finalLeftTakeoff
                    if (finalLandingMs == null && finalLeftLanding != null) finalLandingMs = finalLeftLanding
                }

                if (finalTakeoffMs != null && finalLandingMs != null && finalLandingMs > finalTakeoffMs) {
                    val preciseMetrics = VerticalJumpAnalyzer.calculateJumpMetricsPrecise(
                        takeoffTimeMs = finalTakeoffMs,
                        landingTimeMs = finalLandingMs,
                        bodyWeightKg = bodyWeightKg,
                        jumpType = selectedJumpType,
                        contactStartTimeMs = finalContactMs,
                        slowMoFactor = jumpSlowMoFactor,
                        leftTakeoffTimeMs = finalLeftTakeoff,
                        leftLandingTimeMs = finalLeftLanding,
                        rightTakeoffTimeMs = finalRightTakeoff,
                        rightLandingTimeMs = finalRightLanding
                    )
                    if (preciseMetrics != null) {
                        maxResult = preciseMetrics.heightMeters
                        val takeoffRelMs = (finalTakeoffMs - effectiveStartMs).coerceAtLeast(0.0)
                        val landingRelMs = (finalLandingMs - effectiveStartMs).coerceAtLeast(takeoffRelMs + 50.0)
                        val apexRelMs = (takeoffRelMs + landingRelMs) / 2.0
                        val pushStartRelMs = (takeoffRelMs - 260.0 * jumpSlowMoFactor).coerceAtLeast(0.0)
                        val vTakeoff = 0.5 * 9.81 * preciseMetrics.flightTimeSeconds

                        for (idx in trackingPoints.indices) {
                            val pt = trackingPoints[idx]
                            val t = pt.timestampMs.toDouble()
                            val vel = when {
                                t in pushStartRelMs..takeoffRelMs -> {
                                    val frac = ((t - pushStartRelMs) / (takeoffRelMs - pushStartRelMs).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                                    vTakeoff * kotlin.math.sin(frac * Math.PI / 2.0)
                                }
                                t in takeoffRelMs..apexRelMs -> {
                                    val frac = ((apexRelMs - t) / (apexRelMs - takeoffRelMs).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                                    vTakeoff * frac
                                }
                                t in apexRelMs..landingRelMs -> {
                                    val frac = ((t - apexRelMs) / (landingRelMs - apexRelMs).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                                    vTakeoff * frac * 0.65
                                }
                                else -> 0.0
                            }
                            trackingPoints[idx] = pt.copy(
                                velocityMps = vel,
                                isConcentric = t in pushStartRelMs..apexRelMs,
                                currentPeakVelocity = vTakeoff
                            )
                        }
                    }
                }
            }

            // Suavizado biomecánico de fase cero (adelante-atrás) sobre las coordenadas (x, y)
            // y sobre la curva de velocidad con preservación exacta del pico real (maxResult),
            // garantizando que todas las pantallas, gráficos, HUD y video exportado muestren la misma velocidad.
            if (trackingPoints.size >= 4) {
                val smoothX = zeroPhaseSmooth(trackingPoints.map { it.x.toDouble() }, passes = 4)
                val smoothY = zeroPhaseSmooth(trackingPoints.map { it.y.toDouble() }, passes = 2)
                val rawV = trackingPoints.map { it.velocityMps }
                val baseSmoothV = zeroPhaseSmooth(rawV, passes = 2)
                val smoothPeak = baseSmoothV.maxOrNull() ?: 0.0
                val truePeak = if (maxResult > 0.05) maxResult else (rawV.maxOrNull() ?: 0.0)
                val vScale = if (smoothPeak > 0.05 && truePeak > 0.05) (truePeak / smoothPeak) else 1.0

                for (idx in trackingPoints.indices) {
                    val canonicalV = if (mode == "VBT") (baseSmoothV[idx] * vScale) else trackingPoints[idx].velocityMps
                    trackingPoints[idx] = trackingPoints[idx].copy(
                        x = smoothX[idx].toFloat(),
                        y = smoothY[idx].toFloat(),
                        velocityMps = canonicalV,
                        currentPeakVelocity = if (mode == "VBT") truePeak else trackingPoints[idx].currentPeakVelocity
                    )
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        if (wakeLock?.isHeld == true) {
            try { wakeLock.release() } catch (e: Exception) {}
        }
        try {
            retriever.release()
        } catch (e: Exception) { }
    }

    return@withContext ProcessVideoResult(
        maxResult = maxResult,
        trackingPoints = trackingPoints,
        detectedPlateCenter = activePlateCenter,
        detectedPlateRadius = activePlateRadius
    )
}

fun shareVideoWithReport(context: Context, videoUri: Uri, summary: String) {
    val contentUri: Uri = try {
        if (videoUri.scheme == "file") {
            val file = File(videoUri.path ?: "")
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            videoUri
        }
    } catch (e: Exception) {
        videoUri
    }

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, contentUri)
        putExtra(Intent.EXTRA_TEXT, summary)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(Intent.createChooser(shareIntent, "Compartir Video & Informe").apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    })
}

@Composable
fun TutorialGuideDialog(
    step: Int,
    onStepChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    initialTopic: String = "JUMP",
    onTryDemoVideo: (() -> Unit)? = null
) {
    var activeTopic by remember(initialTopic) { mutableStateOf("VBT") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Encabezado de Tutorial VBT
                Surface(
                    color = Color(0xFF00FFCC).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FFCC).copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().height(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "🏋️ Guía Biomecánica Barra VBT (45 cm)",
                            color = Color(0xFF00FFCC),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = Color(0xFF00FFCC).copy(alpha = 0.2f),
                        shape = CircleShape
                    ) {
                        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Text(
                                when (step) {
                                    0 -> if (activeTopic == "JUMP") "🎥" else "✂️"
                                    1 -> if (activeTopic == "JUMP") "🛫" else "🎯"
                                    else -> if (activeTopic == "JUMP") "⚖️" else "📊"
                                },
                                fontSize = 18.sp
                            )
                        }
                    }
                    Column {
                        Text(
                            if (activeTopic == "JUMP") {
                                when (step) {
                                    0 -> "Paso 1: Grabar, Recortar y FPS"
                                    1 -> "Paso 2: Despegue y Aterrizaje"
                                    else -> "Paso 3: Asimetría, RSI y Ficha"
                                }
                            } else {
                                when (step) {
                                    0 -> "Paso 1: Recortar la Repetición"
                                    1 -> "Paso 2: Calibrar el Disco (45 cm)"
                                    else -> "Paso 3: Análisis y Telemetría"
                                }
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            if (activeTopic == "JUMP") "Tutorial de Salto Vertical (${step + 1}/3)"
                            else "Tutorial de Halterofilia / VBT (${step + 1}/3)",
                            color = Color(0xFF00FFCC),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (activeTopic == "JUMP") {
                    when (step) {
                        0 -> {
                            Text(
                                "Cómo grabar o importar un salto para lograr precisión científica (método My Jump + IA):",
                                color = Color.LightGray,
                                fontSize = 12.5.sp
                            )
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("1️⃣ Enfoca los pies de frente o perfil (a ~1.5 m de distancia). Puedes usar Grabar desde Cámara o Importar Video.", color = Color.White, fontSize = 11.5.sp)
                                    Text("2️⃣ En Trim Video, acorta el rango desde justo antes de despegar hasta después de aterrizar.", color = Color(0xFF00FFCC), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text("3️⃣ Elige Balísticos-Explosivos (Saltos): CMJ, Squat Jump (SJ), Drop Jump o Test de Asimetría.", color = Color.White, fontSize = 11.5.sp)
                                    Text("4️⃣ La app detecta automáticamente los FPS reales del video (30/60/120/240 FPS) y el factor Slow-Mo.", color = Color(0xFF00FFCC), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        1 -> {
                            Text(
                                "No necesitas línea de piso: la altura depende exclusivamente del Tiempo de Vuelo entre Despegue y Aterrizaje:",
                                color = Color.LightGray,
                                fontSize = 12.5.sp
                            )
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("1️⃣ Usa el botón 🔍 Pies 2x arriba a la derecha para hacer zoom directo a las zapatillas.", color = Color(0xFF00FFCC), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text("2️⃣ Avanza frame por frame (⏪ / ⏩) hasta el 1er fotograma donde ambas puntas dejan el suelo y toca 🛫 Despegue.", color = Color.White, fontSize = 11.5.sp)
                                    Text("3️⃣ Avanza hasta el 1er fotograma en que la punta vuelve a tocar el suelo y presiona 🛬 Aterrizaje.", color = Color(0xFF00FFCC), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text("4️⃣ También puedes tocar ⚡ Auto IA para detección sub-fotograma automática.", color = Color.LightGray, fontSize = 11.5.sp)
                                }
                            }
                        }
                        else -> {
                            Text(
                                "Evalúa el Test de Asimetría (Izq vs Der), reactividad RSI y guarda todo en la ficha del atleta:",
                                color = Color.LightGray,
                                fontSize = 12.5.sp
                            )
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("🦵 Test de Asimetría: Marca 🛫 Despegue y 🛬 Aterrizaje para Pierna Izquierda y para Pierna Derecha.", color = Color(0xFF00FFCC), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text("⚖️ Semáforo Clínico: ≤10% Simetría Óptima (Verde) • 10–15% Moderada (Ámbar) • >15% Alerta de Riesgo (Rojo).", color = Color.White, fontSize = 11.5.sp)
                                    Text("⚡ Potencia Sayers & RSI: En Drop Jump puedes marcar también 🦶 Contacto inicial para obtener el índice reactivo RSI.", color = Color.LightGray, fontSize = 11.5.sp)
                                    Text("💾 Ficha del Atleta: Al guardar, el Informe PDF, el Video y la Mejor Marca (PB) quedan registrados dentro del perfil del atleta.", color = Color(0xFF00FFCC), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                } else {
                    when (step) {
                        0 -> {
                            Text(
                                "Aísla la fase concéntrica del levantamiento para que los cálculos de velocidad y potencia sean exactos:",
                                color = Color.LightGray,
                                fontSize = 12.5.sp
                            )
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("1️⃣ Usa el control deslizante para avanzar al inicio del tirón.", color = Color.White, fontSize = 11.5.sp)
                                    Text("2️⃣ Pulsa ▶| Inicio justo cuando la barra despega del suelo.", color = Color(0xFF00FFCC), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text("3️⃣ Desplaza al bloqueo final y pulsa |◀ Fin.", color = Color(0xFF00FFCC), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text("4️⃣ Pulsa ✔ para elegir ejercicio, atleta y peso.", color = Color.LightGray, fontSize = 11.5.sp)
                                }
                            }
                            if (onTryDemoVideo != null) {
                                OutlinedButton(
                                    onClick = onTryDemoVideo,
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FFCC)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00FFCC))
                                ) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("🎬 Probar con Video Demo (Snatch)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        1 -> {
                            Text(
                                "El disco olímpico mide 45 cm de diámetro estándar. Esto calibra los píxeles a metros reales:",
                                color = Color.LightGray,
                                fontSize = 12.5.sp
                            )
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("1️⃣ Pulsa ⚡ Auto Detectar para detección por IA anatómica.", color = Color.White, fontSize = 11.5.sp)
                                    Text("2️⃣ Desliza tu dedo en la pantalla para mover la cruceta y centrar el círculo en el disco olímpico.", color = Color(0xFF00FFCC), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text("3️⃣ Arrastra el punto amarillo hasta que el círculo exterior calce con el borde del disco.", color = Color.White, fontSize = 11.5.sp)
                                    Text("4️⃣ Confirma la carga (kg) y pulsa Iniciar Análisis 🚀.", color = Color(0xFF00FFCC), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        else -> {
                            Text(
                                "Visualiza la telemetría biomecánica en tiempo real y exporta reportes:",
                                color = Color.LightGray,
                                fontSize = 12.5.sp
                            )
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("📈 Telemetría: Curvas de velocidad (cian) y aceleración (naranja) sincronizadas cuadro a cuadro con el video.", color = Color.White, fontSize = 11.sp)
                                    Text("⚡ Métricas: Velocidad Pico (PV), Media (MV), Propulsiva (VMP), RFD y Potencia en Watts.", color = Color(0xFF00FFCC), fontSize = 11.sp)
                                    Text("📄 Informe PDF y Video: Se guardan automáticamente dentro de la ficha del atleta seleccionado.", color = Color.LightGray, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (step < 2) {
                        onStepChange(step + 1)
                    } else {
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
            ) {
                Text(if (step < 2) "Siguiente ➔" else "¡Entendido! ✓", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            if (step > 0) {
                TextButton(onClick = { onStepChange(step - 1) }) {
                    Text("◀ Anterior", color = Color.LightGray, fontSize = 12.sp)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cerrar", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    )
}


@Composable
fun PreCalibrationSetupDialog(
    currentMode: String,
    onModeChange: (String) -> Unit,
    setupFamily: VBTExerciseFamily,
    onFamilyChange: (VBTExerciseFamily) -> Unit,
    selectedVBTExercise: VBTExerciseModel,
    onVBTExerciseChange: (VBTExerciseModel) -> Unit,
    selectedExerciseSequence: List<VBTExerciseModel> = listOf(selectedVBTExercise),
    onExerciseSequenceChange: (List<VBTExerciseModel>) -> Unit = {},
    selectedJumpType: JumpType,
    onJumpTypeChange: (JumpType) -> Unit,
    allUsers: List<com.speedtracker.domain.model.User>,
    activeUser: com.speedtracker.domain.model.User?,
    onSelectUser: (com.speedtracker.domain.model.User) -> Unit,
    onCreateUser: (String, Double) -> Unit,
    barbellLoadKg: String,
    onLoadChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirmAndCalibrate: () -> Unit
) {
    var showExerciseTypePickerPopup by remember { mutableStateOf(false) }
    var showExercisePickerPopup by remember { mutableStateOf(false) }
    var showAthletePickerPopup by remember { mutableStateOf(false) }

    var notesText by remember { mutableStateOf("") }
    var plateDiameterMm by remember { mutableStateOf("450") }
    var defaultPlateDiameter by remember { mutableStateOf(true) }

    val nowDateStr = remember {
        java.text.SimpleDateFormat("dd-MM-yy", Locale.getDefault()).format(java.util.Date())
    }
    val nowTimeStr = remember {
        java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date())
    }

    // 3 Tipos de Ejercicio definidos:
    // 1. Tradicionales (Ley de Hill)
    // 2. Olímpicos (DLP)
    // 3. Balísticos-Explosivos (Saltos)
    val currentExerciseTypeLabel = when {
        currentMode == "JUMP" -> "Balísticos-Explosivos (Saltos)"
        setupFamily == VBTExerciseFamily.HILL_TRADITIONAL -> "Tradicionales (Ley de Hill)"
        else -> "Olímpicos (DLP)"
    }

    val isExerciseUnselected = currentMode == "VBT" && selectedExerciseSequence.isEmpty()
    val currentExerciseText = if (currentMode == "VBT") {
        when {
            selectedExerciseSequence.isEmpty() -> "Selecciona tu ejercicio o serie"
            selectedExerciseSequence.size > 1 ->
                selectedExerciseSequence.mapIndexed { idx, ex -> "${idx + 1}. ${ex.shortName}" }.joinToString(" + ")
            else -> selectedExerciseSequence.first().displayName
        }
    } else {
        when (selectedJumpType) {
            JumpType.CMJ -> "Salto Contramovimiento (CMJ)"
            JumpType.SJ -> "Squat Jump (SJ)"
            JumpType.DROP_JUMP -> "Drop Jump (Reactivo)"
            JumpType.ASYMMETRY_TEST -> "Test de Asimetría (Izq vs Der)"
        }
    }

    val selectedAthlete = activeUser ?: allUsers.firstOrNull()
    val currentAthleteText = if (selectedAthlete != null) {
        "${selectedAthlete.name} (${selectedAthlete.bodyWeightKg} kg)"
    } else {
        "Seleccionar o crear atleta..."
    }

    val labelColor = Color(0xFF9EABB8)
    val valueColor = Color.White
    val lineColor = Color(0xFF455161)
    val accentColor = Color(0xFF00FFCC)

    // ── POP-UP PRINCIPAL ESTILO "VIDEO INFO" MINIMALISTA ──
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF1C2128),
            tonalElevation = 10.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Título centrado limpio
                Text(
                    text = "Información del Video",
                    color = valueColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                // 1. Campo: Tipo de Ejercicio (Tradicionales Hill | Olímpicos DLP | Balísticos-Explosivos Saltos)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showExerciseTypePickerPopup = true }
                ) {
                    Text(
                        text = "Tipo de Ejercicio",
                        color = labelColor,
                        fontSize = 11.5.sp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentExerciseTypeLabel,
                            color = valueColor,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Seleccionar tipo de ejercicio",
                            tint = labelColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Divider(color = lineColor, thickness = 1.dp)
                }

                // 2. Campo: Ejercicio Específico (filtrado automáticamente según el Tipo de Ejercicio elegido)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showExercisePickerPopup = true }
                ) {
                    Text(
                        text = "Ejercicio",
                        color = labelColor,
                        fontSize = 11.5.sp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentExerciseText,
                            color = if (isExerciseUnselected) Color(0xFFFFB74D) else valueColor,
                            fontSize = 15.sp,
                            fontWeight = if (isExerciseUnselected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Seleccionar ejercicio",
                            tint = labelColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Divider(color = lineColor, thickness = 1.dp)
                    if (currentMode == "VBT") {
                        Spacer(modifier = Modifier.height(5.dp))
                        Surface(
                            onClick = { showExercisePickerPopup = true },
                            color = Color(0x1800FFCC),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, Color(0x5500FFCC)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = when {
                                    selectedExerciseSequence.isEmpty() ->
                                        "💡 Puedes elegir 1 ejercicio o sumar varios (Complex)"
                                    selectedExerciseSequence.size == 1 ->
                                        "➕ ¿Es un Complex? Toca aquí para sumar otro ejercicio"
                                    else ->
                                        "🔗 Complex de ${selectedExerciseSequence.size} ejercicios • Toca para editar"
                                },
                                color = accentColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // 3. Campo: Atleta (abre Pop-up seleccionable al tocar)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAthletePickerPopup = true }
                ) {
                    Text(
                        text = "Atleta",
                        color = labelColor,
                        fontSize = 11.5.sp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentAthleteText,
                            color = valueColor,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Seleccionar atleta",
                            tint = labelColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Divider(color = lineColor, thickness = 1.dp)
                }

                // 4. Fila compacta 2 columnas: Peso (kg) + Diámetro del Disco (mm)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Peso (kg)",
                            color = labelColor,
                            fontSize = 11.5.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        BasicTextField(
                            value = barbellLoadKg,
                            onValueChange = { newVal ->
                                if (newVal.isEmpty() || newVal.all { it.isDigit() || it == '.' }) {
                                    onLoadChange(newVal)
                                }
                            },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = valueColor,
                                fontSize = 15.sp
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            cursorBrush = SolidColor(accentColor),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Divider(color = lineColor, thickness = 1.dp)
                    }

                    if (currentMode != "JUMP") {
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text(
                                text = "Disco (mm, est. 450)",
                                color = labelColor,
                                fontSize = 11.5.sp,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            BasicTextField(
                                value = plateDiameterMm,
                                onValueChange = { newVal ->
                                    if (newVal.isEmpty() || newVal.all { it.isDigit() }) {
                                        plateDiameterMm = newVal
                                    }
                                },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = valueColor,
                                    fontSize = 15.sp
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                cursorBrush = SolidColor(accentColor),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Divider(color = lineColor, thickness = 1.dp)
                        }
                    }
                }

                // 5. Notas (1 línea compacta)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Notas",
                        color = labelColor,
                        fontSize = 11.5.sp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    BasicTextField(
                        value = notesText,
                        onValueChange = { notesText = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = valueColor,
                            fontSize = 14.5.sp
                        ),
                        cursorBrush = SolidColor(accentColor),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Divider(color = lineColor, thickness = 1.dp)
                }

                // 6. Fila compacta: Switch diámetro defecto + Fecha y Hora
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentMode != "JUMP") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Disco defecto",
                                color = labelColor,
                                fontSize = 11.5.sp
                            )
                            Switch(
                                checked = defaultPlateDiameter,
                                onCheckedChange = { defaultPlateDiameter = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = accentColor,
                                    checkedTrackColor = accentColor.copy(alpha = 0.35f)
                                )
                            )
                        }
                    } else {
                        Text(
                            text = "IA Sub-Frame + My Jump",
                            color = accentColor,
                            fontSize = 11.5.sp
                        )
                    }

                    Text(
                        text = "$nowDateStr • $nowTimeStr",
                        color = labelColor,
                        fontSize = 11.5.sp
                    )
                }

                // Botones inferiores a la derecha: CANCELAR / GUARDAR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "CANCELAR",
                            color = accentColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    TextButton(
                        onClick = {
                            if (currentMode == "VBT" && selectedExerciseSequence.isEmpty()) {
                                android.widget.Toast.makeText(
                                    ctx,
                                    "Selecciona tu ejercicio o serie para continuar",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                showExercisePickerPopup = true
                            } else {
                                onConfirmAndCalibrate()
                            }
                        }
                    ) {
                        Text(
                            text = "GUARDAR",
                            color = accentColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }
        }
    }

    // ── POP-UP SECUNDARIO 1: SELECCIONAR TIPO DE EJERCICIO (3 CATEGORÍAS) ──
    if (showExerciseTypePickerPopup) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showExerciseTypePickerPopup = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF21262E),
                tonalElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp)
                ) {
                    Text(
                        text = "Seleccionar Tipo de Ejercicio",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = Color(0xFF333C48), thickness = 1.dp)

                    val typeOptions = listOf(
                        Triple(
                            "HILL",
                            "Tradicionales (Ley de Hill)",
                            "Sentadilla, Press de Banca, Peso Muerto, Press Militar, Remo, Hip Thrust"
                        ),
                        Triple(
                            "DLP",
                            "Olímpicos (DLP)",
                            "Snatch Suelo, Hang Snatch, Power Snatch, Clean, Power Clean, Tirón, Jerk"
                        )
                    )

                    typeOptions.forEach { (key, title, subtitle) ->
                        val isSelected = when (key) {
                            "HILL" -> currentMode == "VBT" && setupFamily == VBTExerciseFamily.HILL_TRADITIONAL
                            "DLP" -> currentMode == "VBT" && setupFamily == VBTExerciseFamily.DLP_OLYMPIC
                            else -> currentMode == "JUMP"
                        }

                        val selectAction = {
                            when (key) {
                                "HILL" -> {
                                    onModeChange("VBT")
                                    onFamilyChange(VBTExerciseFamily.HILL_TRADITIONAL)
                                    if (selectedVBTExercise.family != VBTExerciseFamily.HILL_TRADITIONAL) {
                                        val firstHill = VBTExerciseModel.values().first { it.family == VBTExerciseFamily.HILL_TRADITIONAL }
                                        onVBTExerciseChange(firstHill)
                                    }
                                }
                                "DLP" -> {
                                    onModeChange("VBT")
                                    onFamilyChange(VBTExerciseFamily.DLP_OLYMPIC)
                                    if (selectedVBTExercise.family != VBTExerciseFamily.DLP_OLYMPIC) {
                                        val firstDlp = VBTExerciseModel.values().first { it.family == VBTExerciseFamily.DLP_OLYMPIC }
                                        onVBTExerciseChange(firstDlp)
                                    }
                                }

                            }
                            showExerciseTypePickerPopup = false
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectAction() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectAction() },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = accentColor,
                                    unselectedColor = labelColor
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    color = Color.White,
                                    fontSize = 15.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = subtitle,
                                    color = labelColor,
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                    }

                    Divider(color = Color(0xFF333C48), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showExerciseTypePickerPopup = false }) {
                            Text("CERRAR", color = accentColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ── POP-UP SECUNDARIO 2: VENTANA AMPLIA DE SELECCIÓN DE EJERCICIO Y SECUENCIA COMPLEX ──
    if (showExercisePickerPopup) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showExercisePickerPopup = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.86f),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E232B),
                tonalElevation = 14.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333C48))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                ) {
                    // Cabecera amplia con soporte de secuencia de ejercicios (Complex)
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Seleccionar Ejercicio o Complex",
                                    color = Color.White,
                                    fontSize = 17.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "$currentExerciseTypeLabel • Toca para elegir o [+ Sumar] para armar un Complex en orden",
                                    color = accentColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (currentMode == "VBT" && selectedExerciseSequence.size > 1) {
                                TextButton(
                                    onClick = {
                                        val first = selectedExerciseSequence.first()
                                        onExerciseSequenceChange(listOf(first))
                                        onVBTExerciseChange(first)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Limpiar", color = Color(0xFFFF5252), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Barra visual con el orden de ejercicios seleccionados en el video
                        if (currentMode == "VBT") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = Color(0xFF14181E),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2D3744)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        text = if (selectedExerciseSequence.size > 1)
                                            "Orden del Complex en el video (${selectedExerciseSequence.size} ejercicios):"
                                        else
                                            "Ejercicio seleccionado (puedes sumar otro con [+ Sumar] para un Complex):",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        selectedExerciseSequence.forEachIndexed { idx, exModel ->
                                            Surface(
                                                color = Color(0x2600FFCC),
                                                shape = RoundedCornerShape(6.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, accentColor)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                ) {
                                                    Text(
                                                        text = "${idx + 1}. ${exModel.shortName}",
                                                        color = Color.White,
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1
                                                    )
                                                    if (selectedExerciseSequence.size > 1) {
                                                        Icon(
                                                            imageVector = Icons.Default.Close,
                                                            contentDescription = "Quitar ejercicio",
                                                            tint = accentColor,
                                                            modifier = Modifier
                                                                .size(14.dp)
                                                                .clickable {
                                                                    val updated = selectedExerciseSequence.toMutableList().apply { removeAt(idx) }
                                                                    if (updated.isNotEmpty()) {
                                                                        onExerciseSequenceChange(updated)
                                                                        onVBTExerciseChange(updated.first())
                                                                    }
                                                                }
                                                        )
                                                    }
                                                }
                                            }
                                            if (idx < selectedExerciseSequence.lastIndex) {
                                                Text("➔", color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = Color(0xFF333C48), thickness = 1.dp)

                    val exerciseScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .drawWithContent {
                                drawContent()
                                val maxScroll = exerciseScrollState.maxValue.toFloat()
                                if (maxScroll > 0f) {
                                    val barWidth = 5.dp.toPx()
                                    val barMarginRight = 5.dp.toPx()
                                    val barPaddingVertical = 6.dp.toPx()
                                    val trackHeight = (size.height - barPaddingVertical * 2f).coerceAtLeast(24f)
                                    val totalContentHeight = size.height + maxScroll
                                    val thumbHeight = ((size.height / totalContentHeight) * trackHeight).coerceIn(36.dp.toPx(), trackHeight)
                                    val scrollFraction = (exerciseScrollState.value.toFloat() / maxScroll).coerceIn(0f, 1f)
                                    val thumbOffsetY = barPaddingVertical + scrollFraction * (trackHeight - thumbHeight)
                                    val barX = size.width - barWidth - barMarginRight

                                    drawRoundRect(
                                        color = Color(0xFF333C48),
                                        topLeft = Offset(barX, barPaddingVertical),
                                        size = Size(barWidth, trackHeight),
                                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                                    )
                                    drawRoundRect(
                                        color = accentColor,
                                        topLeft = Offset(barX, thumbOffsetY),
                                        size = Size(barWidth, thumbHeight),
                                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                                    )
                                }
                            }
                            .verticalScroll(exerciseScrollState)
                            .padding(end = 10.dp)
                    ) {
                        if (currentMode == "JUMP") {
                            listOf(
                                JumpType.CMJ to ("Salto Contramovimiento (CMJ)" to "Evaluación elástico-explosiva con fase excéntrica"),
                                JumpType.SJ to ("Squat Jump (SJ)" to "Potencia concéntrica pura desde flexión estática de 90°"),
                                JumpType.DROP_JUMP to ("Drop Jump (Reactivo)" to "Índice de fuerza reactiva (RSI) y ciclo estiramiento-acortamiento"),
                                JumpType.ASYMMETRY_TEST to ("Test de Asimetría (Izq vs Der)" to "Comparación de salto Pierna Izquierda vs Derecha (% Asimetría)")
                            ).forEach { (jType, info) ->
                                val (title, desc) = info
                                val isSelected = selectedJumpType == jType
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onJumpTypeChange(jType)
                                            showExercisePickerPopup = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            onJumpTypeChange(jType)
                                            showExercisePickerPopup = false
                                        },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = accentColor,
                                            unselectedColor = labelColor
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = title,
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = desc,
                                            color = labelColor,
                                            fontSize = 11.5.sp
                                        )
                                    }
                                }
                            }
                        } else {
                            // Catálogo completo filtrado por la familia activa (36 Olímpicos DLP o Ley de Hill)
                            val filteredExercises = VBTExerciseModel.values().filter { it.family == setupFamily }
                            filteredExercises.forEach { model ->
                                val orderIndices = selectedExerciseSequence.mapIndexedNotNull { idx, m -> if (m == model) idx + 1 else null }
                                val isSelected = orderIndices.isNotEmpty()

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // Agrega el ejercicio a la secuencia y mantiene abierta la ventana
                                            // para que el usuario pueda sumar otro si es un Complex, o confirmar.
                                            val nextSeq = selectedExerciseSequence + model
                                            onExerciseSequenceChange(nextSeq)
                                            onVBTExerciseChange(nextSeq.first())
                                        }
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            val nextSeq = selectedExerciseSequence + model
                                            onExerciseSequenceChange(nextSeq)
                                            onVBTExerciseChange(nextSeq.first())
                                        },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = accentColor,
                                            unselectedColor = labelColor
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = model.displayName,
                                            color = if (isSelected) accentColor else Color.White,
                                            fontSize = 14.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (model.usesPeakVelocity)
                                                "PV (Pico) • MVT: ${String.format(Locale.US, "%.2f", model.mvt)} m/s"
                                            else
                                                "VM (Media) • MVT: ${String.format(Locale.US, "%.2f", model.mvt)} m/s",
                                            color = labelColor,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }

                                    // Botón [+ Sumar] para encadenar otro ejercicio en orden (Complex) sin cerrar la ventana
                                    Surface(
                                        onClick = {
                                            val nextSeq = selectedExerciseSequence + model
                                            onExerciseSequenceChange(nextSeq)
                                            onVBTExerciseChange(nextSeq.first())
                                        },
                                        color = if (isSelected) Color(0x3300FFCC) else Color(0xFF2B333E),
                                        shape = RoundedCornerShape(6.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSelected) accentColor else Color(0xFF475569)
                                        )
                                    ) {
                                        Text(
                                            text = if (orderIndices.isNotEmpty()) "+ Sumar (#${orderIndices.joinToString(",")})" else "+ Sumar",
                                            color = if (isSelected) accentColor else Color.White,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                                Divider(color = Color(0xFF28303A), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 14.dp))
                            }
                        }
                    }

                    Divider(color = Color(0xFF333C48), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${VBTExerciseModel.values().count { it.family == setupFamily }} ejercicios disponibles",
                            color = labelColor,
                            fontSize = 11.sp
                        )
                        Button(
                            onClick = { showExercisePickerPopup = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (selectedExerciseSequence.size > 1)
                                    "CONFIRMAR COMPLEX (${selectedExerciseSequence.size})"
                                else
                                    "LISTO",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // ── POP-UP SECUNDARIO 3: LISTA SELECCIONABLE DE ATLETAS ──
    if (showAthletePickerPopup) {
        var isAddingAthlete by remember { mutableStateOf(allUsers.isEmpty()) }
        var newName by remember { mutableStateOf("") }
        var newWeight by remember { mutableStateOf("75") }

        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showAthletePickerPopup = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .heightIn(max = 480.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF21262E),
                tonalElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isAddingAthlete) "Nuevo Atleta" else "Seleccionar Atleta",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { isAddingAthlete = !isAddingAthlete }) {
                            Text(
                                text = if (isAddingAthlete) "Ver Lista" else "+ Nuevo",
                                color = accentColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                    Divider(color = Color(0xFF333C48), thickness = 1.dp)

                    if (!isAddingAthlete && allUsers.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                        ) {
                            allUsers.forEach { user ->
                                val isSel = selectedAthlete?.id == user.id
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSelectUser(user)
                                            showAthletePickerPopup = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSel,
                                        onClick = {
                                            onSelectUser(user)
                                            showAthletePickerPopup = false
                                        },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = accentColor,
                                            unselectedColor = labelColor
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = user.name,
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = "Peso corporal: ${user.bodyWeightKg} kg",
                                            color = labelColor,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 22.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("Nombre del Atleta", color = labelColor, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                BasicTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 17.sp),
                                    cursorBrush = SolidColor(accentColor),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Divider(color = lineColor, thickness = 1.dp)
                            }
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("Peso Corporal (kg)", color = labelColor, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                                BasicTextField(
                                    value = newWeight,
                                    onValueChange = { if (it.isEmpty() || it.all { ch -> ch.isDigit() || ch == '.' }) newWeight = it },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 17.sp),
                                    cursorBrush = SolidColor(accentColor),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Divider(color = lineColor, thickness = 1.dp)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = {
                                        val cleanName = newName.trim().ifBlank { "Atleta" }
                                        val w = newWeight.toDoubleOrNull() ?: 75.0
                                        onCreateUser(cleanName, w)
                                        showAthletePickerPopup = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.Black)
                                ) {
                                    Text("GUARDAR ATLETA", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Divider(color = Color(0xFF333C48), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showAthletePickerPopup = false }) {
                            Text("CERRAR", color = accentColor, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}


/**
 * Lienzo interactivo para la Calibración de Salto Vertical (Estilo My Jump + IA Sub-Frame).
 * Dibuja la Línea de Nivel de Suelo (Zapatillas) deslizable verticalmente y el marcador óptico de los pies.
 */
@Composable
fun JumpGroundLineOverlayCanvas(
    groundY: Float,
    boxWidth: Float,
    boxHeight: Float,
    bounds: VideoRenderBounds,
    currentFootSample: JumpFootSample?,
    onGroundYChange: (Float) -> Unit,
    onInteraction: () -> Unit = {}
) {
    val currentY by rememberUpdatedState(groundY)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(boxHeight) {
                detectTapGestures(
                    onPress = { pos ->
                        onInteraction()
                        if (kotlin.math.abs(pos.y - currentY) < 95f) {
                            onGroundYChange(pos.y)
                        }
                    }
                )
            }
            .pointerInput(boxHeight) {
                detectDragGestures(
                    onDragStart = { onInteraction() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onGroundYChange(currentY + dragAmount.y)
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val y = groundY.coerceIn(24f, (h - 24f).coerceAtLeast(28f))
        val neonCyan = Color(0xFF00FFCC)
        val neonGreen = Color(0xFF00E676)

        // 1. Sombreado sutil debajo de la línea de suelo (zona de piso)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(neonCyan.copy(alpha = 0.22f), Color.Transparent),
                startY = y,
                endY = (y + 38.dp.toPx()).coerceAtMost(h)
            ),
            topLeft = Offset(0f, y),
            size = Size(w, (h - y).coerceAtMost(38.dp.toPx()))
        )

        // 2. Líneas diagonales de achurado técnico de suelo
        val hatchSpacing = 22.dp.toPx()
        val hatchLen = 10.dp.toPx()
        var hx = 12.dp.toPx()
        while (hx < w - 12.dp.toPx()) {
            drawLine(
                color = neonCyan.copy(alpha = 0.45f),
                start = Offset(hx, y),
                end = Offset(hx - hatchLen * 0.7f, y + hatchLen),
                strokeWidth = 1.5.dp.toPx()
            )
            hx += hatchSpacing
        }

        // 3. Línea Horizontal Principal de Nivel de Suelo (Suela de las zapatillas)
        drawLine(
            color = Color.Black.copy(alpha = 0.65f),
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = 5.dp.toPx()
        )
        drawLine(
            color = neonCyan,
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = 2.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f), 0f)
        )

        // 4. Tiradores laterales (Handles izquierdo y derecho para arrastrar la línea)
        val handleR = 9.dp.toPx()
        listOf(22.dp.toPx(), w - 22.dp.toPx()).forEach { cx ->
            drawCircle(color = Color(0xFF0F172A), radius = handleR + 2.dp.toPx(), center = Offset(cx, y))
            drawCircle(color = neonCyan, radius = handleR, center = Offset(cx, y))
            drawCircle(color = Color.Black, radius = 3.dp.toPx(), center = Offset(cx, y))
        }

        // 5. Etiqueta central sobre la línea de suelo
        val labelPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#00FFCC")
            textSize = 10.5.sp.toPx()
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val pillW = 210.dp.toPx()
        val pillH = 20.dp.toPx()
        val pillTop = (y - pillH - 6.dp.toPx()).coerceAtLeast(8.dp.toPx())
        drawRoundRect(
            color = Color(0xD90D131A),
            topLeft = Offset(w / 2f - pillW / 2f, pillTop),
            size = Size(pillW, pillH),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        )
        drawRoundRect(
            color = neonCyan.copy(alpha = 0.8f),
            topLeft = Offset(w / 2f - pillW / 2f, pillTop),
            size = Size(pillW, pillH),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )
        drawContext.canvas.nativeCanvas.drawText(
            "👟 NIVEL DEL SUELO (ARRASTRA A LA SUELA)",
            w / 2f,
            pillTop + pillH * 0.72f,
            labelPaint
        )

        // 6. Si hay muestra de pies detectada por IA en este fotograma, dibujar indicador óptico en los pies
        if (currentFootSample != null) {
            val fx = bounds.left + currentFootSample.footX * bounds.scale
            val fy = bounds.top + currentFootSample.footY * bounds.scale
            val isAir = fy < y - 6.dp.toPx()
            val ptColor = if (isAir) neonGreen else neonCyan

            drawCircle(
                color = ptColor.copy(alpha = 0.28f),
                radius = 14.dp.toPx(),
                center = Offset(fx, fy)
            )
            drawCircle(
                color = ptColor,
                radius = 5.dp.toPx(),
                center = Offset(fx, fy)
            )
            if (isAir) {
                // Flecha vertical de elevación desde el suelo hasta los pies en el aire
                drawLine(
                    color = neonGreen,
                    start = Offset(fx, y),
                    end = Offset(fx, fy),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                )
            }
        }
    }
}
