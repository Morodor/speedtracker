@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.speedtracker.core.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.opengl.GLES20
import android.opengl.GLUtils
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.speedtracker.core.math.BarPathPoint
import com.speedtracker.core.math.VBTAnalyzer
import com.speedtracker.core.math.VBTExerciseType
import com.speedtracker.core.math.VerticalJumpAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Estructura de datos que describe el estado cinemático de cada fotograma
 */
data class VideoFrameTrackingPoint(
    val timestampMs: Long,
    val x: Float,
    val y: Float,
    val velocityMps: Double,
    val isConcentric: Boolean = false,
    val isEccentric: Boolean = false,
    val repNumber: Int = 1,
    val currentMeanVelocity: Double = 0.0,
    val currentPeakVelocity: Double = 0.0,
    val labelText: String = ""
)

/**
 * Muestra cinemática sincronizada de velocidad y aceleración para la gráfica del video exportado
 */
data class KinematicSamplePoint(
    val timestampMs: Long,
    val velocityMps: Double,
    val accelerationMps2: Double
)

/**
 * Exportador de video biomecánico con quemado en hardware (Media3 Transformer)
 * de la trayectoria completa de la barra (Bar Path), tracker activo en el disco y HUD métrico.
 */
object VideoOverlayExporter {

    /**
     * Extrae fotograma a fotograma la trayectoria anclada directamente al disco olímpico.
     */
    suspend fun extractTrackingPoints(
        context: Context,
        videoUri: Uri,
        mode: String, // "VBT" o "JUMP"
        calibratedPlateCenter: Offset? = null,
        calibratedPlateRadius: Float? = null,
        startMs: Long = 0L,
        endMs: Long = 0L
    ): List<VideoFrameTrackingPoint> = withContext(Dispatchers.IO) {
        val points = mutableListOf<VideoFrameTrackingPoint>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs <= 0) return@withContext emptyList()

            val effectiveStartMs = startMs.coerceIn(0L, durationMs)
            val effectiveEndMs = if (endMs in (effectiveStartMs + 100)..durationMs) endMs else durationMs
            val trimDurationMs = (effectiveEndMs - effectiveStartMs).coerceAtLeast(100L)

            val options = AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
                .build()
            val detector = PoseDetection.getClient(options)

            val frameIntervalMs = 33L
            val totalFrames = trimDurationMs / frameIntervalMs
            var prevY: Float? = null
            var prevTimeMs: Long? = null
            var peakVel = 0.0

            var initialHandsPos: Offset? = null
            var plateOffset: Offset? = null
            var lastTrackedDisc: Offset? = null

            for (i in 0..totalFrames) {
                val timeOffsetMs = i * frameIntervalMs
                val timeUs = (effectiveStartMs + timeOffsetMs) * 1000L
                val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue

                try {
                    val img = InputImage.fromBitmap(bmp, 0)
                    val pose = detector.process(img).await()

                    if (mode == "VBT") {
                        val lw = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
                        val rw = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
                        val lConf = lw?.inFrameLikelihood ?: 0f
                        val rConf = rw?.inFrameLikelihood ?: 0f

                        // Detección robusta de agarre (prioriza mano visible en vista sagital / lateral)
                        val handsPos = when {
                            lConf > 0.45f && rConf > 0.45f -> Offset((lw!!.position.x + rw!!.position.x) / 2f, (lw.position.y + rw.position.y) / 2f)
                            lConf > 0.45f -> Offset(lw!!.position.x, lw.position.y)
                            rConf > 0.45f -> Offset(rw!!.position.x, rw.position.y)
                            else -> null
                        }

                        if (handsPos != null && initialHandsPos == null) {
                            initialHandsPos = handsPos
                            if (calibratedPlateCenter != null) {
                                plateOffset = Offset(
                                    calibratedPlateCenter.x - handsPos.x,
                                    calibratedPlateCenter.y - handsPos.y
                                )
                            }
                        }

                        val trackX: Float
                        val trackY: Float

                        // Si hay calibración Kinovea del disco, el tracker queda FIJADO AL DISCO
                        if (calibratedPlateCenter != null && plateOffset != null && handsPos != null) {
                            trackX = handsPos.x + plateOffset.x
                            trackY = handsPos.y + plateOffset.y
                            lastTrackedDisc = Offset(trackX, trackY)
                        } else if (calibratedPlateCenter != null && lastTrackedDisc != null) {
                            trackX = lastTrackedDisc.x
                            trackY = lastTrackedDisc.y
                        } else if (handsPos != null) {
                            trackX = handsPos.x
                            trackY = handsPos.y
                            lastTrackedDisc = handsPos
                        } else {
                            continue
                        }

                        var vel = 0.0
                        var isConcentric = false
                        var isEccentric = false

                        if (prevY != null && prevTimeMs != null) {
                            val dt = (timeOffsetMs - prevTimeMs) / 1000.0
                            if (dt > 0.005) {
                                val pxMoved = prevY - trackY
                                val rawVel = (pxMoved * VBTAnalyzer.pxToMetersRatio / dt).coerceIn(-3.0, 3.0)
                                if (rawVel > 0.18) {
                                    vel = rawVel
                                    isConcentric = true
                                } else if (rawVel < -0.15) {
                                    isEccentric = true
                                }
                            }
                        }
                        prevY = trackY
                        prevTimeMs = timeOffsetMs
                        if (vel > peakVel) peakVel = vel

                        points.add(
                            VideoFrameTrackingPoint(
                                timestampMs = timeOffsetMs,
                                x = trackX,
                                y = trackY,
                                velocityMps = vel,
                                isConcentric = isConcentric,
                                isEccentric = isEccentric,
                                currentMeanVelocity = if (vel > 0) (vel + peakVel) / 2.0 else 0.0,
                                currentPeakVelocity = peakVel
                            )
                        )
                    } else { // JUMP mode: Plano Frontal enfocado en ambos pies
                        val lf = pose.getPoseLandmark(PoseLandmark.LEFT_FOOT_INDEX) ?: pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
                        val rf = pose.getPoseLandmark(PoseLandmark.RIGHT_FOOT_INDEX) ?: pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
                        if (lf != null && rf != null && (lf.inFrameLikelihood > 0.25f || rf.inFrameLikelihood > 0.25f)) {
                            points.add(
                                VideoFrameTrackingPoint(
                                    timestampMs = timeOffsetMs,
                                    x = (lf.position.x + rf.position.x) / 2f,
                                    y = (lf.position.y + rf.position.y) / 2f,
                                    velocityMps = 0.0,
                                    labelText = "Pies (Despegue)"
                                )
                            )
                        } else {
                            val lh = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
                            val rh = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
                            if (lh != null && rh != null) {
                                val hipX = (lh.position.x + rh.position.x) / 2f
                                val hipY = (lh.position.y + rh.position.y) / 2f
                                points.add(
                                    VideoFrameTrackingPoint(
                                        timestampMs = timeOffsetMs,
                                        x = hipX,
                                        y = hipY,
                                        velocityMps = 0.0,
                                        labelText = "Centro de Masa"
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        return@withContext points
    }

    /**
     * Renderiza e incrusta la trayectoria y HUD en un nuevo archivo de video MP4.
     */
    suspend fun exportVideoWithOverlay(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        mode: String, // "VBT" o "JUMP"
        trackingPoints: List<VideoFrameTrackingPoint>,
        exerciseName: String = "Sentadilla",
        athleteName: String = "Atleta",
        bestMetricSummary: String = "",
        originBaselineY: Float? = null,
        lowestInflectionY: Float? = null,
        calibratedPlateRadius: Float? = null,
        sourceFrameWidth: Int? = null,
        sourceFrameHeight: Int? = null,
        startMs: Long? = null,
        endMs: Long? = null,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.Main) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        // Normalizar timestamps si vienen en tiempo absoluto respecto al recorte
        val effectivePoints = if (trackingPoints.isNotEmpty() && startMs != null && startMs > 0L && trackingPoints.first().timestampMs >= startMs) {
            trackingPoints.filter { it.timestampMs in startMs..(endMs ?: Long.MAX_VALUE) }
                .map { it.copy(timestampMs = it.timestampMs - startMs) }
        } else {
            trackingPoints
        }

        // Precalcular aceleración y serie cinemática continua para la gráfica en video
        val rawKinematicSamples = ArrayList<KinematicSamplePoint>(effectivePoints.size)
        for (i in effectivePoints.indices) {
            val pt = effectivePoints[i]
            val a = if (i > 0) {
                val dt = (pt.timestampMs - effectivePoints[i - 1].timestampMs) / 1000.0
                val dv = pt.velocityMps - effectivePoints[i - 1].velocityMps
                if (dt > 0.005) (dv / dt).coerceIn(-15.0, 25.0) else 0.0
            } else 0.0
            rawKinematicSamples.add(KinematicSamplePoint(pt.timestampMs, pt.velocityMps, a))
        }

        // Suavizado leve de 3 puntos en aceleración para una curva limpia
        val smoothedKinematics = ArrayList<KinematicSamplePoint>(rawKinematicSamples.size)
        for (i in rawKinematicSamples.indices) {
            val prevA = rawKinematicSamples[(i - 1).coerceAtLeast(0)].accelerationMps2
            val curA = rawKinematicSamples[i].accelerationMps2
            val nextA = rawKinematicSamples[(i + 1).coerceAtMost(rawKinematicSamples.lastIndex)].accelerationMps2
            val smoothA = (prevA + curA * 2.0 + nextA) / 4.0
            smoothedKinematics.add(rawKinematicSamples[i].copy(accelerationMps2 = smoothA))
        }

        // Localizar el Punto de Máxima Velocidad (Pico / VP)
        val peakKinematicSample = smoothedKinematics.maxByOrNull { it.velocityMps }
        val maxVelGlobal = (peakKinematicSample?.velocityMps ?: 1.0).coerceAtLeast(0.5)
        val maxAccGlobal = (smoothedKinematics.maxOfOrNull { it.accelerationMps2 } ?: 5.0).coerceAtLeast(2.0)
        val minAccGlobal = (smoothedKinematics.minOfOrNull { it.accelerationMps2 } ?: -5.0).coerceAtMost(-2.0)

        val seriesStartMs = smoothedKinematics.firstOrNull()?.timestampMs ?: 0L
        val seriesEndMs = smoothedKinematics.lastOrNull()?.timestampMs ?: 1000L
        val totalSeriesDurationMs = (seriesEndMs - seriesStartMs).coerceAtLeast(300L)

        // Crear TextureOverlay de alto rendimiento con GPU texture reuse (cero memory churn / sin OOM)
        val overlay = object : TextureOverlay() {
            private var frameW = 1080
            private var frameH = 1920
            private var glTextureId = -1
            private var bmp: Bitmap? = null
            private var bmpCanvas: Canvas? = null

            // Voltear el eje Y directamente en el shader de la GPU para alinear coordenadas Canvas con OpenGL
            private val overlaySettings = OverlaySettings.Builder()
                .setScale(1f, -1f)
                .build()

            // Paints reutilizables
            private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val trackerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3.5f
            }
            private val trackerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                color = Color.parseColor("#8000FFCC")
                pathEffect = DashPathEffect(floatArrayOf(18f, 18f), 0f)
            }
            private val inflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                color = Color.parseColor("#80FFD600")
                pathEffect = DashPathEffect(floatArrayOf(14f, 14f), 0f)
            }
            private val hudCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(220, 16, 16, 16) // Obsidian semitransparente
            }
            private val hudBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.parseColor("#4D00FFCC")
            }
            private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00FFCC")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 23f
            }
            private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#00FFCC")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 21f
            }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.DEFAULT
                textSize = 19f
            }
            private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#BBBBBB")
                typeface = Typeface.DEFAULT
                textSize = 16f
            }

            override fun configure(videoFrameSize: Size) {
                frameW = videoFrameSize.width
                frameH = videoFrameSize.height
                bmp?.recycle()
                bmp = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
                bmpCanvas = Canvas(bmp!!)
            }

            override fun getTextureSize(presentationTimeUs: Long): Size {
                return Size(frameW, frameH)
            }

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                return overlaySettings
            }

            override fun getTextureId(presentationTimeUs: Long): Int {
                val currentBmp = bmp ?: return -1
                val canvas = bmpCanvas ?: return -1

                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                val currentMs = presentationTimeUs / 1000L

                // 1. Escala de coordenadas (mapea desde sourceFrameWidth/Height si viene de la cámara en vivo)
                val srcW = if (sourceFrameWidth != null && sourceFrameWidth > 0) sourceFrameWidth.toFloat() else frameW.toFloat()
                val srcH = if (sourceFrameHeight != null && sourceFrameHeight > 0) sourceFrameHeight.toFloat() else frameH.toFloat()
                val coordScale = maxOf(frameW / srcW, frameH / srcH)
                val offX = (frameW - srcW * coordScale) / 2f
                val offY = (frameH - srcH * coordScale) / 2f

                fun mapX(x: Float): Float = x * coordScale + offX
                fun mapY(y: Float): Float = y * coordScale + offY

                // Puntos de tracking hasta el momento actual
                val pointsUpToNow = effectivePoints.filter { it.timestampMs <= currentMs }
                val currentPoint = pointsUpToNow.lastOrNull() ?: effectivePoints.firstOrNull()

                val scaleFactor = frameW / 1080f
                val density = scaleFactor.coerceIn(0.32f, 2.5f)
                val lineDensity = scaleFactor.coerceIn(0.45f, 2.2f)

                // 2. Líneas Guía de Referencia
                originBaselineY?.let { origY ->
                    val my = mapY(origY)
                    canvas.drawLine(0f, my, frameW.toFloat(), my, baselinePaint)
                }
                lowestInflectionY?.let { inflectY ->
                    val my = mapY(inflectY)
                    canvas.drawLine(0f, my, frameW.toFloat(), my, inflectionPaint)
                }

                // 3. Dibujar Trayectoria Dinámica de la Barra (Bar Path suavizado con Catmull-Rom Bezier)
                if (pointsUpToNow.size > 1) {
                    val strokeW = 8.5f * lineDensity
                    linePaint.strokeWidth = strokeW
                    glowPaint.strokeWidth = strokeW * 2.5f
                    val segPath = android.graphics.Path()

                    for (i in 0 until pointsUpToNow.size - 1) {
                        val p0 = pointsUpToNow[(i - 1).coerceAtLeast(0)]
                        val p1 = pointsUpToNow[i]
                        val p2 = pointsUpToNow[i + 1]
                        val p3 = pointsUpToNow[(i + 2).coerceAtMost(pointsUpToNow.lastIndex)]

                        val x0 = mapX(p0.x); val y0 = mapY(p0.y)
                        val x1 = mapX(p1.x); val y1 = mapY(p1.y)
                        val x2 = mapX(p2.x); val y2 = mapY(p2.y)
                        val x3 = mapX(p3.x); val y3 = mapY(p3.y)

                        val dist = kotlin.math.hypot(x2 - x1, y2 - y1)
                        if (dist < 1.0f && p2.velocityMps < 0.05) continue

                        val cp1X = x1 + (x2 - x0) * 0.20f
                        val cp1Y = y1 + (y2 - y0) * 0.20f
                        val cp2X = x2 - (x3 - x1) * 0.20f
                        val cp2Y = y2 - (y3 - y1) * 0.20f

                        segPath.reset()
                        segPath.moveTo(x1, y1)
                        segPath.cubicTo(cp1X, cp1Y, cp2X, cp2Y, x2, y2)

                        val segColor = when {
                            p2.velocityMps >= 1.10 -> Color.parseColor("#FF3D00") // Rojo máxima aceleración
                            p2.velocityMps >= 0.60 -> Color.parseColor("#00E676") // Verde neón potencia
                            p2.isEccentric -> Color.parseColor("#FFD600")          // Amarillo descenso
                            else -> Color.parseColor("#00E5FF")                   // Cyan fase controlada
                        }

                        // Resplandor translúcido más intenso
                        glowPaint.color = Color.argb(105, Color.red(segColor), Color.green(segColor), Color.blue(segColor))
                        canvas.drawPath(segPath, glowPaint)

                        // Línea central sólida más gruesa
                        linePaint.color = segColor
                        canvas.drawPath(segPath, linePaint)
                    }

                    // Marcador visual permanente en la trayectoria física donde se alcanzó la Máxima Velocidad
                    if (peakKinematicSample != null && peakKinematicSample.velocityMps > 0.20) {
                        val peakPt = effectivePoints.find { it.timestampMs == peakKinematicSample.timestampMs }
                            ?: effectivePoints.maxByOrNull { it.velocityMps }
                        if (peakPt != null) {
                            val px = mapX(peakPt.x)
                            val py = mapY(peakPt.y)
                            val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.argb(125, 255, 61, 0)
                                style = Paint.Style.STROKE
                                strokeWidth = 5f * lineDensity
                            }
                            canvas.drawCircle(px, py, 10f * lineDensity, glowP)
                            val coreP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.parseColor("#FF3D00")
                                style = Paint.Style.FILL
                            }
                            canvas.drawCircle(px, py, 6f * lineDensity, coreP)
                            val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.WHITE
                                style = Paint.Style.FILL
                            }
                            canvas.drawCircle(px, py, 2.5f * lineDensity, dotP)
                        }
                    }
                }

                // 4. Dibujar la Mira / Tracker centrada en el disco con relleno translúcido visible y trazos gruesos
                if (currentPoint != null) {
                    val cx = mapX(currentPoint.x)
                    val cy = mapY(currentPoint.y)

                    val trackerColor = when {
                        currentPoint.velocityMps >= 1.10 -> Color.parseColor("#FF3D00")
                        currentPoint.velocityMps >= 0.60 -> Color.parseColor("#00E676")
                        currentPoint.isEccentric -> Color.parseColor("#FFD600")
                        else -> Color.parseColor("#00FFCC")
                    }

                    trackerCirclePaint.color = trackerColor
                    trackerCirclePaint.strokeWidth = 6f * lineDensity
                    trackerDotPaint.color = trackerColor
                    crosshairPaint.color = trackerColor
                    crosshairPaint.strokeWidth = 4.5f * lineDensity

                    // Radio exacto del disco olímpico calibrado (45 cm)
                    val plateRadius = if (calibratedPlateRadius != null && calibratedPlateRadius > 10f) {
                        calibratedPlateRadius * coordScale
                    } else {
                        45f * lineDensity
                    }

                    // 1. Relleno translúcido del disco completo
                    val plateAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = Color.argb(78, Color.red(trackerColor), Color.green(trackerColor), Color.blue(trackerColor))
                    }
                    canvas.drawCircle(cx, cy, plateRadius, plateAuraPaint)

                    // Halo perimetral continuo suave + Anillo exterior segmentado grueso
                    val outerGlowRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 11f * lineDensity
                        color = Color.argb(90, Color.red(trackerColor), Color.green(trackerColor), Color.blue(trackerColor))
                    }
                    canvas.drawCircle(cx, cy, plateRadius, outerGlowRingPaint)

                    val plateRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 6.5f * lineDensity
                        color = trackerColor
                        pathEffect = DashPathEffect(floatArrayOf(18f * lineDensity, 12f * lineDensity), 0f)
                    }
                    canvas.drawCircle(cx, cy, plateRadius, plateRimPaint)

                    // Marcas cardinales amarillas (N, S, E, O) en el borde del disco
                    val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 5f * lineDensity
                        strokeCap = Paint.Cap.ROUND
                        color = Color.parseColor("#FFD600")
                    }
                    val tickLen = 10f * lineDensity
                    canvas.drawLine(cx, cy - plateRadius - tickLen, cx, cy - plateRadius + tickLen, tickPaint)
                    canvas.drawLine(cx, cy + plateRadius - tickLen, cx, cy + plateRadius + tickLen, tickPaint)
                    canvas.drawLine(cx - plateRadius - tickLen, cy, cx - plateRadius + tickLen, cy, tickPaint)
                    canvas.drawLine(cx + plateRadius - tickLen, cy, cx + plateRadius + tickLen, cy, tickPaint)

                    // 2. Collarín interior con relleno translúcido más marcado y borde grueso
                    val collarR = (plateRadius * 0.30f).coerceAtLeast(14f * lineDensity)
                    val collarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = Color.argb(115, Color.red(trackerColor), Color.green(trackerColor), Color.blue(trackerColor))
                    }
                    canvas.drawCircle(cx, cy, collarR, collarFillPaint)
                    canvas.drawCircle(cx, cy, collarR, trackerCirclePaint)

                    // 3. Cruz de mira óptica (+) gruesa y punto bullseye central
                    val crossSize = collarR + 14f * lineDensity
                    canvas.drawLine(cx - crossSize, cy, cx + crossSize, cy, crosshairPaint)
                    canvas.drawLine(cx, cy - crossSize, cx, cy + crossSize, crosshairPaint)

                    canvas.drawCircle(cx, cy, 7.5f * lineDensity, trackerDotPaint)
                    val centerWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = Color.WHITE
                    }
                    canvas.drawCircle(cx, cy, 3.2f * lineDensity, centerWhitePaint)
                }

                // 5. Dibujar HUD Biomecánico Profesional en 4 filas independientes
                val hudMarginX = 24f * density
                val hudMarginY = 24f * density
                val hudW = frameW - (hudMarginX * 2f)
                val hudH = 186f * density
                val hudRect = RectF(hudMarginX, hudMarginY, hudMarginX + hudW, hudMarginY + hudH)

                titlePaint.textSize = 27f * density
                highlightPaint.textSize = 26f * density
                textPaint.textSize = 22f * density
                subTextPaint.textSize = 20f * density

                canvas.drawRoundRect(hudRect, 20f * density, 20f * density, hudCardPaint)
                canvas.drawRoundRect(hudRect, 20f * density, 20f * density, hudBorderPaint)

                val padX = 22f * density
                val leftX = hudRect.left + padX
                val rightX = hudRect.right - padX
                val fullContentW = (rightX - leftX).coerceAtLeast(50f)

                fun drawLeftAndRightRow(
                    leftText: String,
                    leftPaint: Paint,
                    leftBaseSize: Float,
                    rightText: String,
                    rightPaint: Paint,
                    rightBaseSize: Float,
                    y: Float
                ) {
                    leftPaint.textSize = leftBaseSize
                    rightPaint.textSize = rightBaseSize
                    val gap = 16f * density
                    var lW = leftPaint.measureText(leftText)
                    var rW = rightPaint.measureText(rightText)
                    if (lW + rW + gap > fullContentW) {
                        val ratio = (fullContentW - gap).coerceAtLeast(40f) / (lW + rW).coerceAtLeast(1f)
                        leftPaint.textSize = (leftBaseSize * ratio).coerceAtLeast(8f)
                        rightPaint.textSize = (rightBaseSize * ratio).coerceAtLeast(8f)
                        rW = rightPaint.measureText(rightText)
                    }
                    canvas.drawText(leftText, leftX, y, leftPaint)
                    canvas.drawText(rightText, rightX - rW, y, rightPaint)
                }

                fun drawFullRow(
                    text: String,
                    paint: Paint,
                    baseSize: Float,
                    y: Float
                ) {
                    paint.textSize = baseSize
                    val tW = paint.measureText(text)
                    if (tW > fullContentW && tW > 0f) {
                        paint.textSize = (baseSize * (fullContentW / tW)).coerceAtLeast(8f)
                    }
                    canvas.drawText(text, leftX, y, paint)
                }

                // Fila 1: Marca y Ejercicio
                var rowY = hudMarginY + 38f * density
                val modeTag = if (mode == "VBT") "VBT • $exerciseName" else "SALTO • $exerciseName"
                drawLeftAndRightRow(
                    leftText = "⚡ SPEED TRACKER PRO",
                    leftPaint = titlePaint,
                    leftBaseSize = 26f * density,
                    rightText = modeTag,
                    rightPaint = subTextPaint,
                    rightBaseSize = 20f * density,
                    y = rowY
                )

                // Fila 2: Velocidad Instantánea y Fase Cinemática
                rowY += 44f * density
                val phaseStr = when {
                    currentPoint?.isConcentric == true -> "Fase Concéntrica"
                    currentPoint?.isEccentric == true -> "Fase Excéntrica"
                    else -> "Reposo"
                }
                if (mode == "VBT") {
                    val velStr = "Velocidad: ${String.format(java.util.Locale.US, "%.2f", currentPoint?.velocityMps ?: 0.0)} m/s"
                    drawLeftAndRightRow(
                        leftText = velStr,
                        leftPaint = highlightPaint,
                        leftBaseSize = 26f * density,
                        rightText = phaseStr,
                        rightPaint = subTextPaint,
                        rightBaseSize = 20f * density,
                        y = rowY
                    )
                } else {
                    drawLeftAndRightRow(
                        leftText = "Salto Vertical",
                        leftPaint = highlightPaint,
                        leftBaseSize = 26f * density,
                        rightText = phaseStr,
                        rightPaint = subTextPaint,
                        rightBaseSize = 20f * density,
                        y = rowY
                    )
                }

                // Fila 3: Resumen Biomecánico
                rowY += 42f * density
                val cleanSummary = bestMetricSummary
                    .removePrefix("$exerciseName • ")
                    .removePrefix("$exerciseName   ")
                    .ifBlank {
                        if (mode == "VBT") {
                            "VP: ${String.format(java.util.Locale.US, "%.2f", currentPoint?.currentPeakVelocity ?: 0.0)} m/s"
                        } else {
                            ""
                        }
                    }
                if (cleanSummary.isNotBlank()) {
                    drawFullRow(
                        text = cleanSummary,
                        paint = textPaint,
                        baseSize = 22f * density,
                        y = rowY
                    )
                }

                // Fila 4: Nombre del Atleta
                rowY += 38f * density
                drawFullRow(
                    text = "Atleta: $athleteName",
                    paint = subTextPaint,
                    baseSize = 20f * density,
                    y = rowY
                )

                // 6. Gráfica Cinemática Dinámica en el Fondo del Video (Velocidad & Aceleración con Marcador de Velocidad Pico)
                if (smoothedKinematics.size >= 2) {
                    val gMarginX = 24f * density
                    val gW = frameW - (gMarginX * 2f)
                    val gH = 152f * density
                    val gBottom = frameH - 30f * density
                    val gTop = gBottom - gH
                    val gRect = RectF(gMarginX, gTop, gMarginX + gW, gBottom)

                    // Fondo Obsidian semitransparente
                    canvas.drawRoundRect(gRect, 18f * density, 18f * density, hudCardPaint)
                    canvas.drawRoundRect(gRect, 18f * density, 18f * density, hudBorderPaint)

                    // Título y Métrica en Vivo
                    val gHeaderY = gTop + 24f * density
                    val gTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#00FFCC")
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textSize = 17f * density
                    }
                    canvas.drawText("📈 CINEMÁTICA: VELOCIDAD & ACELERACIÓN", gRect.left + 18f * density, gHeaderY, gTitlePaint)

                    // Leyenda
                    val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 13f * density
                        typeface = Typeface.DEFAULT
                    }
                    val velLegend = "━ Velocidad (m/s)"
                    val accLegend = "━ Aceleración (m/s²)"

                    legendPaint.color = Color.parseColor("#00E5FF")
                    val velLegX = gRect.left + 18f * density
                    val legY = gHeaderY + 16f * density
                    canvas.drawText(velLegend, velLegX, legY, legendPaint)

                    legendPaint.color = Color.parseColor("#FFD600")
                    val accLegX = velLegX + legendPaint.measureText(velLegend) + 16f * density
                    canvas.drawText(accLegend, accLegX, legY, legendPaint)

                    // Métrica instantánea actual a la derecha
                    val curSample = smoothedKinematics.filter { it.timestampMs <= currentMs }.lastOrNull() ?: smoothedKinematics.first()
                    val liveMetricText = "V: ${String.format(Locale.US, "%.2f", curSample.velocityMps)} m/s | A: ${String.format(Locale.US, "%+.1f", curSample.accelerationMps2)} m/s²"
                    val liveValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textSize = 14f * density
                    }
                    val liveValW = liveValPaint.measureText(liveMetricText)
                    canvas.drawText(liveMetricText, gRect.right - 18f * density - liveValW, gHeaderY, liveValPaint)

                    // Área de trazado de curvas
                    val pLeft = gRect.left + 18f * density
                    val pRight = gRect.right - 18f * density
                    val pTop = legY + 10f * density
                    val pBottom = gBottom - 14f * density
                    val pW = pRight - pLeft
                    val pH = pBottom - pTop

                    fun timeToX(tMs: Long): Float {
                        val progress = ((tMs - seriesStartMs).toFloat() / totalSeriesDurationMs.toFloat()).coerceIn(0f, 1f)
                        return pLeft + progress * pW
                    }

                    fun velToY(v: Double): Float {
                        val norm = (v.toFloat() / maxVelGlobal.toFloat()).coerceIn(0f, 1f)
                        return pBottom - norm * (pH * 0.88f)
                    }

                    fun accToY(a: Double): Float {
                        val range = (maxAccGlobal - minAccGlobal).toFloat().coerceAtLeast(1f)
                        val norm = ((a.toFloat() - minAccGlobal.toFloat()) / range).coerceIn(0f, 1f)
                        return pBottom - norm * (pH * 0.88f)
                    }

                    // Línea base de aceleración cero
                    val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(60, 255, 255, 255)
                        style = Paint.Style.STROKE
                        strokeWidth = 1f * density
                        pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
                    }
                    val zeroAccY = accToY(0.0)
                    canvas.drawLine(pLeft, zeroAccY, pRight, zeroAccY, axisPaint)

                    // 1. Curva de Aceleración (Dorado)
                    val accPath = android.graphics.Path()
                    val accPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#FFD600")
                        style = Paint.Style.STROKE
                        strokeWidth = 2.2f * density
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    for (i in smoothedKinematics.indices) {
                        val sx = timeToX(smoothedKinematics[i].timestampMs)
                        val sy = accToY(smoothedKinematics[i].accelerationMps2)
                        if (i == 0) accPath.moveTo(sx, sy) else accPath.lineTo(sx, sy)
                    }
                    canvas.drawPath(accPath, accPaint)

                    // 2. Curva de Velocidad (Cyan Neón con relleno translúcido inferior)
                    val velPath = android.graphics.Path()
                    val velFillPath = android.graphics.Path()
                    val velPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#00E5FF")
                        style = Paint.Style.STROKE
                        strokeWidth = 3.5f * density
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    val velGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(80, 0, 229, 255)
                        style = Paint.Style.STROKE
                        strokeWidth = 7f * density
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }

                    velFillPath.moveTo(timeToX(smoothedKinematics.first().timestampMs), pBottom)
                    for (i in smoothedKinematics.indices) {
                        val sx = timeToX(smoothedKinematics[i].timestampMs)
                        val sy = velToY(smoothedKinematics[i].velocityMps)
                        if (i == 0) velPath.moveTo(sx, sy) else velPath.lineTo(sx, sy)
                        velFillPath.lineTo(sx, sy)
                    }
                    velFillPath.lineTo(timeToX(smoothedKinematics.last().timestampMs), pBottom)
                    velFillPath.close()

                    val velFillAreaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(35, 0, 229, 255)
                        style = Paint.Style.FILL
                    }
                    canvas.drawPath(velFillPath, velFillAreaPaint)
                    canvas.drawPath(velPath, velGlowPaint)
                    canvas.drawPath(velPath, velPaint)

                    // 3. INDICADOR DEL PUNTO DE MÁXIMA VELOCIDAD (Punto Pico / VP)
                    if (peakKinematicSample != null && peakKinematicSample.velocityMps > 0.20) {
                        val px = timeToX(peakKinematicSample.timestampMs)
                        val py = velToY(peakKinematicSample.velocityMps)

                        // Línea vertical punteada en el instante de máxima velocidad
                        val peakLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#FF3D00")
                            style = Paint.Style.STROKE
                            strokeWidth = 1.8f * density
                            pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
                        }
                        canvas.drawLine(px, pTop - 4f * density, px, pBottom, peakLinePaint)

                        // Anillo blanco y núcleo rojo en el vértice
                        val peakPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#FF3D00")
                            style = Paint.Style.FILL
                        }
                        val peakRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.WHITE
                            style = Paint.Style.STROKE
                            strokeWidth = 2f * density
                        }
                        canvas.drawCircle(px, py, 6f * density, peakPointPaint)
                        canvas.drawCircle(px, py, 6f * density, peakRingPaint)

                        // Insignia / Badge con el valor exacto de la Máxima Velocidad
                        val badgeText = "★ MÁX VEL: ${String.format(Locale.US, "%.2f", peakKinematicSample.velocityMps)} m/s"
                        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.WHITE
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            textSize = 11.5f * density
                        }
                        val bW = badgeTextPaint.measureText(badgeText) + (14f * density)
                        val bH = 18f * density
                        val bLeft = (px - bW / 2f).coerceIn(pLeft, pRight - bW)
                        val bTop = (py - bH - 6f * density).coerceAtLeast(pTop - 6f * density)
                        val badgeRect = RectF(bLeft, bTop, bLeft + bW, bTop + bH)

                        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.argb(230, 255, 61, 0) // Naranja/Rojo vivo
                            style = Paint.Style.FILL
                        }
                        canvas.drawRoundRect(badgeRect, 6f * density, 6f * density, badgeBgPaint)
                        canvas.drawText(badgeText, bLeft + 7f * density, bTop + 13f * density, badgeTextPaint)
                    }

                    // 4. Cursor Dinámico de Tiempo (Playhead Sync con el video actual)
                    val cursorX = timeToX(currentMs)
                    val curY = velToY(curSample.velocityMps)

                    val cursorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.STROKE
                        strokeWidth = 2.2f * density
                    }
                    canvas.drawLine(cursorX, pTop, cursorX, pBottom, cursorLinePaint)

                    // Punto brillante en el cursor actual
                    val cursorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#00FFCC")
                        style = Paint.Style.FILL
                    }
                    val cursorDotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.STROKE
                        strokeWidth = 2.5f * density
                    }
                    canvas.drawCircle(cursorX, curY, 6.5f * density, cursorDotPaint)
                    canvas.drawCircle(cursorX, curY, 6.5f * density, cursorDotRingPaint)
                }

                // 7. Subir textura GL eficiente reutilizando textureId preasignado
                if (glTextureId == -1) {
                    glTextureId = GlUtil.createTexture(frameW, frameH, false)
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTextureId)
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, currentBmp)
                GlUtil.checkGlError()

                return glTextureId
            }

            override fun release() {
                if (glTextureId != -1) {
                    try { GlUtil.deleteTexture(glTextureId) } catch (e: Exception) {}
                    glTextureId = -1
                }
                bmp?.recycle()
                bmp = null
                bmpCanvas = null
            }
        }

        val overlayEffect = OverlayEffect(ImmutableList.of(overlay))
        val mediaItemBuilder = MediaItem.Builder().setUri(inputUri)
        if (startMs != null && endMs != null && endMs > startMs) {
            val clippingConfig = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()
            mediaItemBuilder.setClippingConfiguration(clippingConfig)
        }
        val mediaItem = mediaItemBuilder.build()
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), listOf(overlayEffect)))
            .build()

        // Suspendible con Transformer listener
        suspendCancellableCoroutine { continuation ->
            var isResumed = false

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (!isResumed) {
                            isResumed = true
                            onProgress(1.0f)
                            continuation.resume(outputFile)
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (!isResumed) {
                            isResumed = true
                            exportException.printStackTrace()
                            continuation.resumeWithException(exportException)
                        }
                    }
                })
                .build()

            try {
                transformer.start(editedMediaItem, outputFile.absolutePath)
            } catch (e: Exception) {
                if (!isResumed) {
                    isResumed = true
                    continuation.resumeWithException(e)
                }
            }

            // Polling de progreso
            val progressHolder = ProgressHolder()
            val progressScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main + kotlinx.coroutines.Job())
            val job = progressScope.launch {
                while (continuation.isActive && !isResumed) {
                    try {
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((progressHolder.progress / 100f).coerceIn(0f, 0.99f))
                        }
                    } catch (e: Exception) {}
                    delay(150)
                }
            }

            continuation.invokeOnCancellation {
                try {
                    transformer.cancel()
                    job.cancel()
                } catch (e: Exception) {}
            }
        }
    }
}
