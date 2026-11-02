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
                val density = scaleFactor.coerceIn(0.32f, 2.5f)
                val lineDensity = scaleFactor.coerceIn(0.45f, 2.2f)

                // 2. Líneas Guía de Referencia
                originBaselineY?.let { origY ->
                    canvas.drawLine(0f, origY, frameW.toFloat(), origY, baselinePaint)
                }
                lowestInflectionY?.let { inflectY ->
                    canvas.drawLine(0f, inflectY, frameW.toFloat(), inflectY, inflectionPaint)
                }

                // 3. Dibujar Trayectoria Dinámica de la Barra (Bar Path suavizado con curvas Catmull-Rom Cubic Bezier)
                // Filtramos puntos estáticos para no crear manchas mientras la barra está en el piso
                if (pointsUpToNow.size > 1) {
                    val strokeW = 6f * lineDensity
                    linePaint.strokeWidth = strokeW
                    glowPaint.strokeWidth = strokeW * 2f
                    val segPath = android.graphics.Path()

                    for (i in 0 until pointsUpToNow.size - 1) {
                        val p0 = pointsUpToNow[(i - 1).coerceAtLeast(0)]
                        val p1 = pointsUpToNow[i]
                        val p2 = pointsUpToNow[i + 1]
                        val p3 = pointsUpToNow[(i + 2).coerceAtMost(pointsUpToNow.lastIndex)]

                        // Solo conectar si pertenece a movimiento activo
                        val isMoving = p2.isConcentric || p2.isEccentric || p2.velocityMps > 0.15 || p1.isConcentric
                        if (!isMoving) continue

                        val cp1X = p1.x + (p2.x - p0.x) * 0.20f
                        val cp1Y = p1.y + (p2.y - p0.y) * 0.20f
                        val cp2X = p2.x - (p3.x - p1.x) * 0.20f
                        val cp2Y = p2.y - (p3.y - p1.y) * 0.20f

                        segPath.reset()
                        segPath.moveTo(p1.x, p1.y)
                        segPath.cubicTo(cp1X, cp1Y, cp2X, cp2Y, p2.x, p2.y)

                        val segColor = when {
                            p2.velocityMps >= 1.10 -> Color.parseColor("#FF3D00") // Rojo máxima aceleración
                            p2.velocityMps >= 0.60 -> Color.parseColor("#00E676") // Verde neón potencia
                            p2.isEccentric -> Color.parseColor("#FFD600")          // Amarillo descenso
                            else -> Color.parseColor("#00E5FF")                   // Cyan fase controlada
                        }

                        // Resplandor translúcido
                        glowPaint.color = Color.argb(60, Color.red(segColor), Color.green(segColor), Color.blue(segColor))
                        canvas.drawPath(segPath, glowPaint)

                        // Línea central sólida
                        linePaint.color = segColor
                        canvas.drawPath(segPath, linePaint)
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
                    trackerCirclePaint.strokeWidth = 3.5f * lineDensity
                    trackerDotPaint.color = trackerColor
                    crosshairPaint.color = trackerColor
                    crosshairPaint.strokeWidth = 2f * lineDensity

                    // Radio exacto del disco olímpico calibrado (45 cm)
                    val plateRadius = if (calibratedPlateRadius != null && calibratedPlateRadius > 10f) {
                        calibratedPlateRadius
                    } else {
                        35f * lineDensity
                    }

                    // 1. Círculo exterior con el tamaño real del disco (45 cm)
                    val plateAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = Color.argb(30, Color.red(trackerColor), Color.green(trackerColor), Color.blue(trackerColor))
                    }
                    canvas.drawCircle(currentPoint.x, currentPoint.y, plateRadius, plateAuraPaint)

                    val plateRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 3f * lineDensity
                        color = trackerColor
                        pathEffect = DashPathEffect(floatArrayOf(12f * lineDensity, 12f * lineDensity), 0f)
                    }
                    canvas.drawCircle(currentPoint.x, currentPoint.y, plateRadius, plateRimPaint)

                    // 2. Collarín interior y punto central
                    val collarR = plateRadius * 0.28f
                    canvas.drawCircle(currentPoint.x, currentPoint.y, collarR, trackerCirclePaint)
                    canvas.drawCircle(currentPoint.x, currentPoint.y, 4.5f * lineDensity, trackerDotPaint)

                    // Cruz de mira óptica (+)
                    val crossSize = collarR + 8f * lineDensity
                    canvas.drawLine(currentPoint.x - crossSize, currentPoint.y, currentPoint.x + crossSize, currentPoint.y, crosshairPaint)
                    canvas.drawLine(currentPoint.x, currentPoint.y - crossSize, currentPoint.x, currentPoint.y + crossSize, crosshairPaint)
                }

                // 5. Dibujar HUD Biomecánico Profesional en 4 filas independientes (Cero superposición de textos)
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

                // Fila 3: Resumen Biomecánico (Línea completa independiente para evitar cualquier superposición)
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
