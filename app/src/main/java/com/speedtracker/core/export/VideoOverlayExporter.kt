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
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
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

        // Crear BitmapOverlay con doble búfer para GPU
        val overlay = object : BitmapOverlay() {
            private var frameW = 1080
            private var frameH = 1920
            private var bmpA: Bitmap? = null
            private var bmpB: Bitmap? = null
            private var useA = true

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
                bmpA = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
                bmpB = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
            }

            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                val currentBmp = if (useA) {
                    if (bmpA == null || bmpA!!.width != frameW || bmpA!!.height != frameH) {
                        bmpA = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
                    }
                    bmpA!!
                } else {
                    if (bmpB == null || bmpB!!.width != frameW || bmpB!!.height != frameH) {
                        bmpB = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
                    }
                    bmpB!!
                }
                useA = !useA

                val canvas = Canvas(currentBmp)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                val currentMs = presentationTimeUs / 1000L

                // 1. Filtrar puntos hasta el timestamp actual
                val pointsUpToNow = effectivePoints.filter { it.timestampMs <= currentMs }
                val currentPoint = pointsUpToNow.lastOrNull()

                val scaleFactor = frameW / 1080f
                val density = scaleFactor.coerceIn(0.7f, 2.0f)

                // 2. Líneas Guía de Referencia
                originBaselineY?.let { origY ->
                    canvas.drawLine(0f, origY, frameW.toFloat(), origY, baselinePaint)
                }
                lowestInflectionY?.let { inflectY ->
                    canvas.drawLine(0f, inflectY, frameW.toFloat(), inflectY, inflectionPaint)
                }

                // 3. Dibujar Trayectoria Dinámica de la Barra (Bar Path)
                // Filtramos puntos estáticos para no crear manchas mientras la barra está en el piso
                if (pointsUpToNow.size > 1) {
                    val strokeW = 6f * density
                    linePaint.strokeWidth = strokeW
                    glowPaint.strokeWidth = strokeW * 2f

                    for (i in 0 until pointsUpToNow.size - 1) {
                        val p1 = pointsUpToNow[i]
                        val p2 = pointsUpToNow[i + 1]

                        // Solo conectar si pertenece a movimiento activo
                        val isMoving = p2.isConcentric || p2.isEccentric || p2.velocityMps > 0.15 || p1.isConcentric
                        if (!isMoving) continue

                        val segColor = when {
                            p2.velocityMps >= 1.10 -> Color.parseColor("#FF3D00") // Rojo máxima aceleración
                            p2.velocityMps >= 0.60 -> Color.parseColor("#00E676") // Verde neón potencia
                            p2.isEccentric -> Color.parseColor("#FFD600")          // Amarillo descenso
                            else -> Color.parseColor("#00E5FF")                   // Cyan fase controlada
                        }

                        // Resplandor translúcido
                        glowPaint.color = Color.argb(60, Color.red(segColor), Color.green(segColor), Color.blue(segColor))
                        canvas.drawLine(p1.x, p1.y, p2.x, p2.y, glowPaint)

                        // Línea central sólida
                        linePaint.color = segColor
                        canvas.drawLine(p1.x, p1.y, p2.x, p2.y, linePaint)
                    }
                }

                // 4. Dibujar la Mira / Tracker centrada exactamente en el disco
                if (currentPoint != null) {
                    val trackerColor = when {
                        currentPoint.velocityMps >= 1.10 -> Color.parseColor("#FF3D00")
                        currentPoint.velocityMps >= 0.60 -> Color.parseColor("#00E676")
                        currentPoint.isEccentric -> Color.parseColor("#FFD600")
                        else -> Color.parseColor("#00FFCC")
                    }

                    trackerCirclePaint.color = trackerColor
                    trackerCirclePaint.strokeWidth = 3.5f * density
                    trackerDotPaint.color = trackerColor
                    crosshairPaint.color = trackerColor
                    crosshairPaint.strokeWidth = 2f * density

                    // Radio exacto del disco olímpico calibrado (45 cm)
                    val plateRadius = if (calibratedPlateRadius != null && calibratedPlateRadius > 10f) {
                        calibratedPlateRadius
                    } else {
                        35f * density
                    }

                    // 1. Círculo exterior con el tamaño real del disco (45 cm)
                    val plateAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = Color.argb(30, Color.red(trackerColor), Color.green(trackerColor), Color.blue(trackerColor))
                    }
                    canvas.drawCircle(currentPoint.x, currentPoint.y, plateRadius, plateAuraPaint)

                    val plateRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 3f * density
                        color = trackerColor
                        pathEffect = DashPathEffect(floatArrayOf(12f * density, 12f * density), 0f)
                    }
                    canvas.drawCircle(currentPoint.x, currentPoint.y, plateRadius, plateRimPaint)

                    // 2. Collarín interior y punto central
                    val collarR = plateRadius * 0.28f
                    canvas.drawCircle(currentPoint.x, currentPoint.y, collarR, trackerCirclePaint)
                    canvas.drawCircle(currentPoint.x, currentPoint.y, 4.5f * density, trackerDotPaint)

                    // Cruz de mira óptica (+)
                    val crossSize = collarR + 8f * density
                    canvas.drawLine(currentPoint.x - crossSize, currentPoint.y, currentPoint.x + crossSize, currentPoint.y, crosshairPaint)
                    canvas.drawLine(currentPoint.x, currentPoint.y - crossSize, currentPoint.x, currentPoint.y + crossSize, crosshairPaint)
                }

                // 5. Dibujar HUD Biomecánico Profesional (Tarjeta Superior de Telemetría)
                val hudMarginX = 28f * density
                val hudMarginY = 32f * density
                val hudW = frameW - (hudMarginX * 2)
                val hudH = 135f * density
                val hudRect = RectF(hudMarginX, hudMarginY, hudMarginX + hudW, hudMarginY + hudH)

                titlePaint.textSize = 23f * density
                highlightPaint.textSize = 21f * density
                textPaint.textSize = 19f * density
                subTextPaint.textSize = 16f * density

                canvas.drawRoundRect(hudRect, 18f * density, 18f * density, hudCardPaint)
                canvas.drawRoundRect(hudRect, 18f * density, 18f * density, hudBorderPaint)

                val leftX = hudMarginX + 18f * density
                var rowY = hudMarginY + 34f * density

                // Fila 1: Título y Modalidad
                canvas.drawText("⚡ SPEED TRACKER PRO", leftX, rowY, titlePaint)
                val modeTag = if (mode == "VBT") "VBT • $exerciseName" else "SALTO • $exerciseName"
                val tagW = subTextPaint.measureText(modeTag)
                canvas.drawText(modeTag, hudRect.right - 18f * density - tagW, rowY, subTextPaint)

                // Fila 2: Velocidad instantánea y Resumen
                rowY += 40f * density
                if (mode == "VBT") {
                    val velStr = "Velocidad: ${String.format("%.2f", currentPoint?.velocityMps ?: 0.0)} m/s"
                    canvas.drawText(velStr, leftX, rowY, highlightPaint)

                    val summaryStr = if (bestMetricSummary.isNotBlank()) bestMetricSummary else "Pico: ${String.format("%.2f", currentPoint?.currentPeakVelocity ?: 0.0)} m/s"
                    val sumW = textPaint.measureText(summaryStr)
                    canvas.drawText(summaryStr, hudRect.right - 18f * density - sumW, rowY, textPaint)
                } else {
                    canvas.drawText("Salto Vertical", leftX, rowY, highlightPaint)
                    if (bestMetricSummary.isNotBlank()) {
                        val sumW = textPaint.measureText(bestMetricSummary)
                        canvas.drawText(bestMetricSummary, hudRect.right - 18f * density - sumW, rowY, textPaint)
                    }
                }

                // Fila 3: Atleta y Fase
                rowY += 34f * density
                val phaseStr = if (currentPoint?.isConcentric == true) "Fase Concéntrica" else if (currentPoint?.isEccentric == true) "Fase Excéntrica" else "Reposo"
                canvas.drawText("Atleta: $athleteName", leftX, rowY, subTextPaint)
                val phaseW = subTextPaint.measureText(phaseStr)
                canvas.drawText(phaseStr, hudRect.right - 18f * density - phaseW, rowY, subTextPaint)

                return currentBmp
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
