package com.speedtracker.core.report

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import com.speedtracker.core.export.VideoFrameTrackingPoint
import com.speedtracker.core.math.RepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object BiomechanicalPdfReportGenerator {

    suspend fun generateReport(
        context: Context,
        videoUri: Uri,
        athleteName: String,
        athleteWeightKg: Double,
        exerciseName: String,
        exerciseFamily: String,
        loadKg: Double,
        peakVelocity: Double,
        meanVelocity: Double,
        vmpVelocity: Double?,
        concentricDurationMs: Long?,
        timeToPeakMs: Long?,
        rfdNPerSec: Int?,
        peakPowerWatts: Double,
        relativeWattsPerKg: Double,
        peakForceN: Double,
        workJoules: Double,
        estimatedOneRM: Double,
        pctOneRM: Double,
        horizontalDeviationCm: Double?,
        trackingPoints: List<VideoFrameTrackingPoint>,
        trimStartMs: Long,
        trimEndMs: Long,
        repsList: List<RepResult> = emptyList(),
        exerciseSequenceNames: List<String> = emptyList(),
        exerciseMvt: Double? = null
    ): File = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 estándar
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Resolver lista final de repeticiones o fases de complex
        val baseReps: List<RepResult> = if (repsList.size > 1) {
            repsList
        } else {
            val detected = detectRepsFromTrackingPoints(trackingPoints)
            if (detected.size > 1) detected else repsList
        }

        val finalReps: List<RepResult> = if (exerciseSequenceNames.size > 1 && baseReps.size < exerciseSequenceNames.size && trackingPoints.size >= exerciseSequenceNames.size * 2) {
            // Segmentar puntos de tracking en las N fases del complex registrado por el usuario
            val nPhases = exerciseSequenceNames.size
            val chunkSize = (trackingPoints.size / nPhases).coerceAtLeast(1)
            val segmented = mutableListOf<RepResult>()
            for (pIdx in 0 until nPhases) {
                val startIdx = pIdx * chunkSize
                val endIdx = if (pIdx == nPhases - 1) trackingPoints.size else ((pIdx + 1) * chunkSize).coerceAtMost(trackingPoints.size)
                val slice = trackingPoints.subList(startIdx, endIdx)
                val pPeak = slice.maxOfOrNull { it.velocityMps }?.takeIf { it > 0.1 } ?: (peakVelocity * (1.0 - pIdx * 0.04))
                val pMean = slice.filter { it.velocityMps > 0.15 }.map { it.velocityMps }.average().takeIf { !it.isNaN() } ?: (pPeak * 0.72)
                segmented.add(
                    RepResult(
                        repNumber = pIdx + 1,
                        peakVelocity = pPeak,
                        meanVelocity = pMean,
                        romMeters = 0.55,
                        isCutoffReached = false,
                        velocityLossPercent = 0.0
                    )
                )
            }
            val bestP = segmented.maxOfOrNull { it.peakVelocity } ?: peakVelocity
            segmented.map { r ->
                val loss = if (bestP > 0) ((bestP - r.peakVelocity) / bestP * 100.0).coerceAtLeast(0.0) else 0.0
                r.copy(velocityLossPercent = loss)
            }
        } else {
            baseReps
        }

        val isMultiRepOrComplex = finalReps.size > 1 || exerciseSequenceNames.size > 1
        val maxVelocityLoss = if (finalReps.size > 1) {
            val bestPeak = finalReps.maxOfOrNull { it.peakVelocity } ?: peakVelocity
            val lastPeak = finalReps.last().peakVelocity
            if (bestPeak > 0) ((bestPeak - lastPeak) / bestPeak * 100.0).coerceAtLeast(0.0) else 0.0
        } else 0.0
        val effectiveMvt = exerciseMvt ?: 0.80

        // 1. Fondo general blanco/gris suave profesional
        canvas.drawColor(Color.parseColor("#F8FAFC"))

        // 2. Encabezado Superior (Barra oscura moderna con acentos neón)
        paint.color = Color.parseColor("#0F172A")
        paint.style = Paint.Style.FILL
        val headerRect = RectF(20f, 20f, 575f, 80f)
        canvas.drawRoundRect(headerRect, 8f, 8f, paint)

        // Título del Encabezado
        textPaint.color = Color.parseColor("#00FFCC")
        textPaint.textSize = 14f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("⚡ SPEED TRACKER • LABORATORIO BIOMECÁNICO", 36f, 46f, textPaint)

        // Subtítulo
        textPaint.color = Color.WHITE
        textPaint.textSize = 9.5f
        textPaint.typeface = Typeface.DEFAULT
        val headerSub = if (isMultiRepOrComplex) {
            "INFORME VBT • SERIE MULTI-REP / ANÁLISIS DE COMPLEX (${finalReps.size} REPETICIONES)"
        } else {
            "INFORME OFICIAL DE RENDIMIENTO Y ANÁLISIS CINEMÁTICO VBT"
        }
        canvas.drawText(headerSub, 36f, 65f, textPaint)

        // Fecha de emisión a la derecha
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val dateString = sdf.format(Date())
        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 8.5f
        val dateWidth = textPaint.measureText(dateString)
        canvas.drawText(dateString, 559f - dateWidth, 55f, textPaint)

        // 3. Ficha de Datos del Atleta y Levantamiento
        paint.color = Color.parseColor("#F1F5F9")
        val athleteCard = RectF(20f, 86f, 575f, 134f)
        canvas.drawRoundRect(athleteCard, 6f, 6f, paint)
        paint.color = Color.parseColor("#CBD5E1")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(athleteCard, 6f, 6f, paint)
        paint.style = Paint.Style.FILL

        // Columna 1: Atleta
        textPaint.color = Color.parseColor("#64748B")
        textPaint.textSize = 8f
        canvas.drawText("ATLETA", 32f, 101f, textPaint)
        textPaint.color = Color.parseColor("#0F172A")
        textPaint.textSize = 10.5f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(athleteName, 32f, 116f, textPaint)
        textPaint.color = Color.parseColor("#475569")
        textPaint.textSize = 8.5f
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("Peso corporal: ${String.format(Locale.US, "%.1f", athleteWeightKg)} kg", 32f, 127f, textPaint)

        // Columna 2: Ejercicio (Auto-ajuste de fuente para nombres largos o complexes sin invadir Columna 3)
        textPaint.color = Color.parseColor("#64748B")
        textPaint.textSize = 8f
        canvas.drawText(if (exerciseSequenceNames.size > 1) "SECUENCIA COMPLEX (${exerciseSequenceNames.size} EJERCICIOS)" else "EJERCICIO / MODALIDAD", 215f, 101f, textPaint)
        textPaint.color = Color.parseColor("#0F172A")
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        var exFontSize = 10.2f
        textPaint.textSize = exFontSize
        while (textPaint.measureText(exerciseName) > 190f && exFontSize > 7.2f) {
            exFontSize -= 0.4f
            textPaint.textSize = exFontSize
        }
        val fittedExerciseName = if (textPaint.measureText(exerciseName) > 192f) {
            var cut = exerciseName
            while (cut.length > 10 && textPaint.measureText("$cut...") > 190f) {
                cut = cut.dropLast(1)
            }
            "$cut..."
        } else exerciseName
        canvas.drawText(fittedExerciseName, 215f, 116f, textPaint)
        textPaint.color = Color.parseColor("#475569")
        textPaint.textSize = 8.2f
        textPaint.typeface = Typeface.DEFAULT
        val famSub = "Umbral MVT: ${String.format(Locale.US, "%.2f", effectiveMvt)} m/s • $exerciseFamily"
        canvas.drawText(famSub, 215f, 127f, textPaint)

        // Columna 3: Carga y Fatiga
        textPaint.color = Color.parseColor("#64748B")
        textPaint.textSize = 8f
        canvas.drawText(if (isMultiRepOrComplex) "CARGA & SERIE" else "CARGA EVALUADA", 415f, 101f, textPaint)
        textPaint.color = Color.parseColor("#00875A")
        textPaint.textSize = 12f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val loadTitle = if (isMultiRepOrComplex) "${loadKg.toInt()} kg (${finalReps.size} reps)" else "${loadKg.toInt()} kg"
        canvas.drawText(loadTitle, 415f, 116f, textPaint)
        textPaint.color = Color.parseColor("#475569")
        textPaint.textSize = 8.5f
        textPaint.typeface = Typeface.DEFAULT
        val loadSub = if (isMultiRepOrComplex) {
            "Fatiga: -${String.format(Locale.US, "%.1f", maxVelocityLoss)}% • 1RM: ${estimatedOneRM.toInt()} kg"
        } else {
            "1RM Est: ${estimatedOneRM.toInt()} kg (~${pctOneRM.toInt()}%)"
        }
        canvas.drawText(loadSub, 415f, 127f, textPaint)

        // 4. Extracción de Precisión (OPTION_CLOSEST) y Dibujo de 4 Fotogramas Biomecánicos Clave con HUD y Trayectoria
        val frameCount = 4
        val frameGap = 6f
        val frameWidth = (555f - (frameCount - 1) * frameGap) / frameCount // 134.25f
        val frameHeight = 100f
        val frameY = 155f
        val targetAspect = frameWidth / frameHeight

        data class KeyframeSpec(
            val title: String,
            val timeUs: Long,
            val relTimeSec: Double,
            val metricBadge: String,
            val accentColor: Int,
            val pointIndex: Int
        )

        val keyframeSpecs = mutableListOf<KeyframeSpec>()
        val accelerations = DoubleArray(trackingPoints.size)
        if (trackingPoints.isNotEmpty()) {
            for (idx in trackingPoints.indices) {
                if (idx > 0) {
                    val dt = (trackingPoints[idx].timestampMs - trackingPoints[idx - 1].timestampMs) / 1000.0
                    val dv = trackingPoints[idx].velocityMps - trackingPoints[idx - 1].velocityMps
                    accelerations[idx] = if (dt > 0.005) (dv / dt).coerceIn(-25.0, 35.0) else 0.0
                }
            }

            val firstTs = trackingPoints.first().timestampMs
            val isRelativeTs = firstTs < trimStartMs || trimStartMs == 0L
            fun toAbsMs(ptTs: Long): Long = if (isRelativeTs) (trimStartMs + ptTs) else ptTs

            // 3. Índice de Velocidad Pico (VP)
            val idxPeak = trackingPoints.indices.maxByOrNull { trackingPoints[it].velocityMps }
                ?: (trackingPoints.size / 2)

            // 1. Índice de Despegue / Punto de Inflexión (inicio real del tirón antes del pico)
            val searchEndStart = (idxPeak - 1).coerceAtLeast(0)
            val idxFirstConc = (0..searchEndStart).firstOrNull {
                trackingPoints[it].isConcentric && trackingPoints[it].velocityMps >= 0.14
            }
            val idxLowestBeforePeak = (0..searchEndStart).maxByOrNull { trackingPoints[it].y } ?: 0
            val idxStart = (idxFirstConc ?: idxLowestBeforePeak).coerceIn(0, searchEndStart)

            // 2. Índice de Máxima Aceleración / RFD (entre el despegue y la velocidad pico)
            val idxMaxAcc = if (idxPeak - idxStart >= 2) {
                ((idxStart + 1) until idxPeak).maxByOrNull { accelerations[it] }
                    ?: ((idxStart + idxPeak) / 2)
            } else {
                ((idxStart + idxPeak) / 2).coerceIn(0, trackingPoints.lastIndex)
            }

            // 4. Índice de Ápice / Recepción (altura máxima de la barra tras la velocidad pico)
            val idxCatch = if (idxPeak < trackingPoints.lastIndex) {
                (idxPeak..trackingPoints.lastIndex).minByOrNull { trackingPoints[it].y }
                    ?.takeIf { it > idxPeak }
                    ?: minOf(trackingPoints.lastIndex, idxPeak + maxOf(1, (trackingPoints.lastIndex - idxPeak) / 2))
            } else {
                trackingPoints.lastIndex
            }

            val chosenIndices = listOf(idxStart, idxMaxAcc, idxPeak, idxCatch)
            val startAbsMs = toAbsMs(trackingPoints[idxStart].timestampMs)

            val titles = listOf(
                "1. Despegue / Inicio",
                "2. Máx. Aceleración",
                "3. Velocidad Pico (VP)",
                "4. Ápice / Recepción"
            )
            val colors = listOf(
                Color.parseColor("#38BDF8"),
                Color.parseColor("#FF9100"),
                Color.parseColor("#00FFCC"),
                Color.parseColor("#FFD600")
            )

            for (k in 0 until 4) {
                val pIdx = chosenIndices[k]
                val pt = trackingPoints[pIdx]
                val absMs = toAbsMs(pt.timestampMs)
                val relSec = ((absMs - startAbsMs).coerceAtLeast(0L)) / 1000.0
                val badge = when (k) {
                    0 -> String.format(Locale.US, "V: %.2f m/s", pt.velocityMps)
                    1 -> String.format(Locale.US, "A: +%.1f m/s²", accelerations[pIdx].coerceAtLeast(0.5))
                    2 -> String.format(Locale.US, "★ %.2f m/s", maxOf(pt.velocityMps, peakVelocity))
                    else -> {
                        val startY = trackingPoints[idxStart].y
                        val liftedPx = (startY - pt.y).coerceAtLeast(0f)
                        if (liftedPx > 5f) "Ápice / Catch" else String.format(Locale.US, "V: %.2f m/s", pt.velocityMps)
                    }
                }
                keyframeSpecs.add(
                    KeyframeSpec(
                        title = titles[k],
                        timeUs = absMs * 1000L,
                        relTimeSec = relSec,
                        metricBadge = badge,
                        accentColor = colors[k],
                        pointIndex = pIdx
                    )
                )
            }
        } else {
            val durMs = (trimEndMs - trimStartMs).coerceAtLeast(400L)
            val titles = listOf("1. Despegue / Inicio", "2. Máx. Aceleración", "3. Velocidad Pico (VP)", "4. Ápice / Recepción")
            val colors = listOf(
                Color.parseColor("#38BDF8"),
                Color.parseColor("#FF9100"),
                Color.parseColor("#00FFCC"),
                Color.parseColor("#FFD600")
            )
            for (k in 0 until 4) {
                val frac = k / 3.0
                val tMs = trimStartMs + (durMs * frac).toLong()
                keyframeSpecs.add(
                    KeyframeSpec(
                        title = titles[k],
                        timeUs = tMs * 1000L,
                        relTimeSec = (tMs - trimStartMs) / 1000.0,
                        metricBadge = if (k == 2) String.format(Locale.US, "★ %.2f m/s", peakVelocity) else "--",
                        accentColor = colors[k],
                        pointIndex = -1
                    )
                )
            }
        }

        var retriever: MediaMetadataRetriever? = null
        val frameBitmaps = mutableListOf<Bitmap>()
        try {
            retriever = MediaMetadataRetriever().apply { setDataSource(context, videoUri) }

            for (spec in keyframeSpecs) {
                // OPTION_CLOSEST garantiza extraer el fotograma exacto del milisegundo biomecánico
                val rawBmp = retriever.getFrameAtTime(spec.timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(spec.timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (rawBmp != null) {
                    frameBitmaps.add(
                        prepareSmartCroppedKeyframeBitmap(
                            src = rawBmp,
                            trackingPoints = trackingPoints,
                            upToIndex = spec.pointIndex,
                            startIndex = keyframeSpecs.firstOrNull()?.pointIndex ?: 0,
                            accentColor = spec.accentColor,
                            targetAspect = targetAspect,
                            maxDimPx = 560
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever?.release() } catch (e: Exception) {}
        }

        // Título de la Franja Visual
        textPaint.color = Color.parseColor("#1E293B")
        textPaint.textSize = 9.5f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("SECUENCIA CINEMÁTICA DE PRECISIÓN (FOTOGRAMAS EXACTOS & TRAYECTORIA)", 24f, 149f, textPaint)

        for (i in 0 until frameCount) {
            val spec = keyframeSpecs[i]
            val fx = 20f + i * (frameWidth + frameGap)
            val frameRect = RectF(fx, frameY, fx + frameWidth, frameY + frameHeight)

            paint.color = Color.parseColor("#0F172A")
            canvas.drawRoundRect(frameRect, 4f, 4f, paint)

            if (i < frameBitmaps.size) {
                val bmp = frameBitmaps[i]
                val srcRect = Rect(0, 0, bmp.width, bmp.height)
                val dstRect = RectF(fx + 1f, frameY + 1f, fx + frameWidth - 1f, frameY + frameHeight - 1f)
                paint.isFilterBitmap = true
                canvas.drawBitmap(bmp, srcRect, dstRect, paint)
            }

            // Cintillo superior translúcido con tiempo relativo (+0.XXs) y métrica instantánea
            paint.color = Color.parseColor("#BF0F172A")
            val topBarRect = RectF(fx + 1f, frameY + 1f, fx + frameWidth - 1f, frameY + 15f)
            canvas.drawRect(topBarRect, paint)

            textPaint.color = Color.parseColor("#E2E8F0")
            textPaint.textSize = 7f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val timeStr = String.format(Locale.US, "+%.2fs", spec.relTimeSec)
            canvas.drawText(timeStr, fx + 4f, frameY + 10.5f, textPaint)

            textPaint.color = spec.accentColor
            textPaint.textSize = 7.2f
            val mW = textPaint.measureText(spec.metricBadge)
            canvas.drawText(spec.metricBadge, fx + frameWidth - mW - 4f, frameY + 10.5f, textPaint)

            // Cintillo inferior con el nombre de la fase biomecánica
            paint.color = Color.parseColor("#D90F172A")
            val labelRect = RectF(fx + 1f, frameY + frameHeight - 15f, fx + frameWidth - 1f, frameY + frameHeight - 1f)
            canvas.drawRect(labelRect, paint)

            // Indicador lateral de color de fase
            paint.color = spec.accentColor
            canvas.drawRect(RectF(fx + 1f, frameY + frameHeight - 15f, fx + 4f, frameY + frameHeight - 1f), paint)

            textPaint.color = Color.WHITE
            textPaint.textSize = 7.3f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText(spec.title, fx + 7f, frameY + frameHeight - 4.5f, textPaint)

            // Borde exterior con acento de fase
            paint.color = spec.accentColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (i == 2) 1.3f else 0.9f
            canvas.drawRoundRect(frameRect, 4f, 4f, paint)
            paint.style = Paint.Style.FILL
        }

        // 5. Tabla de Métricas Biomecánicas (2 Columnas x 5 Filas)
        val tableTop = 268f
        textPaint.color = Color.parseColor("#1E293B")
        textPaint.textSize = 9.5f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val metricsHeader = if (isMultiRepOrComplex) "MÉTRICAS CINEMÁTICAS (MEJOR REPETICIÓN / PICO DEL SET)" else "MÉTRICAS CINEMÁTICAS & DINÁMICAS REGISTRADAS"
        canvas.drawText(metricsHeader, 24f, tableTop, textPaint)

        val metricsLeft = listOf(
            Triple("Velocidad Pico (PV)", String.format(Locale.US, "%.2f m/s", peakVelocity), "Máxima aceleración alcanzada en el tirón"),
            Triple("Velocidad Media (MV)", String.format(Locale.US, "%.2f m/s", meanVelocity), "Promedio de la fase concéntrica completa"),
            Triple("Velocidad Propulsiva (VMP)", String.format(Locale.US, "%.2f m/s", vmpVelocity ?: meanVelocity), "Velocidad útil en fase de aceleración (a ≥ -g)"),
            Triple("Fase Concéntrica", "${concentricDurationMs ?: "--"} ms", "Duración total del tirón propulsivo"),
            Triple("Tiempo a Vel. Pico", "${timeToPeakMs ?: "--"} ms", "Rapidez de transición hacia el pico explosivo")
        )

        val metricsRight = listOf(
            Triple("RFD (Tasa de Fuerza)", "${rfdNPerSec ?: "--"} N/s", "Explosividad y reclutamiento motor en el despegue"),
            Triple("Potencia Mecánica", "${peakPowerWatts.toInt()} W (${String.format(Locale.US, "%.1f", relativeWattsPerKg)} W/kg)", "Producción de potencia pico y potencia relativa"),
            Triple("Fuerza Pico & Trabajo", "${peakForceN.toInt()} N • ${workJoules.toInt()} J", "Tensión muscular máxima y energía mecánica total"),
            Triple("1RM Estimado", "${estimatedOneRM.toInt()} kg (@ ${pctOneRM.toInt()}%)", "Capacidad máxima proyectada según perfil VBT"),
            Triple("Desvío de Barra (Drift)", if (horizontalDeviationCm != null) "${String.format(Locale.US, "%.1f", horizontalDeviationCm)} cm" else "-- cm", if ((horizontalDeviationCm ?: 0.0) <= 12.0) "Trayectoria vertical óptima y limpia" else "Desvío horizontal hacia adelante")
        )

        val rowHeight = 27f
        val colWidth = 270f
        val startY = tableTop + 7f

        // Dibujar Columna Izquierda
        for (row in 0 until 5) {
            val ry = startY + row * (rowHeight + 3f)
            val cardR = RectF(20f, ry, 20f + colWidth, ry + rowHeight)
            paint.color = Color.parseColor("#FFFFFF")
            canvas.drawRoundRect(cardR, 4f, 4f, paint)
            paint.color = Color.parseColor("#E2E8F0")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawRoundRect(cardR, 4f, 4f, paint)
            paint.style = Paint.Style.FILL

            val (title, value, sub) = metricsLeft[row]
            textPaint.color = Color.parseColor("#475569")
            textPaint.textSize = 8f
            textPaint.typeface = Typeface.DEFAULT
            canvas.drawText(title, 26f, ry + 12f, textPaint)

            textPaint.color = Color.parseColor("#0F172A")
            textPaint.textSize = 9.5f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val valW = textPaint.measureText(value)
            canvas.drawText(value, 20f + colWidth - valW - 8f, ry + 13f, textPaint)

            textPaint.color = Color.parseColor("#94A3B8")
            textPaint.textSize = 7f
            textPaint.typeface = Typeface.DEFAULT
            canvas.drawText(sub, 26f, ry + 23f, textPaint)
        }

        // Dibujar Columna Derecha
        for (row in 0 until 5) {
            val ry = startY + row * (rowHeight + 3f)
            val rx = 25f + colWidth + 5f
            val cardR = RectF(rx, ry, rx + colWidth, ry + rowHeight)
            paint.color = Color.parseColor("#FFFFFF")
            canvas.drawRoundRect(cardR, 4f, 4f, paint)
            paint.color = Color.parseColor("#E2E8F0")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawRoundRect(cardR, 4f, 4f, paint)
            paint.style = Paint.Style.FILL

            val (title, value, sub) = metricsRight[row]
            textPaint.color = Color.parseColor("#475569")
            textPaint.textSize = 8f
            textPaint.typeface = Typeface.DEFAULT
            canvas.drawText(title, rx + 8f, ry + 12f, textPaint)

            textPaint.color = Color.parseColor("#00875A")
            textPaint.textSize = 9.5f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val valW = textPaint.measureText(value)
            canvas.drawText(value, rx + colWidth - valW - 8f, ry + 13f, textPaint)

            textPaint.color = Color.parseColor("#94A3B8")
            textPaint.textSize = 7f
            textPaint.typeface = Typeface.DEFAULT
            canvas.drawText(sub, rx + 8f, ry + 23f, textPaint)
        }

        var nextSectionY = startY + 5 * (rowHeight + 3f) + 8f

        // 6. Tarjeta Especial: ANÁLISIS DE SERIE MULTI-REP / DESGLOSE DE COMPLEX (si hay > 1 repetición)
        if (isMultiRepOrComplex) {
            val multiRepTop = nextSectionY
            val multiRepHeight = 68f
            val multiRepRect = RectF(20f, multiRepTop, 575f, multiRepTop + multiRepHeight)

            paint.color = Color.parseColor("#0F172A")
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(multiRepRect, 6f, 6f, paint)

            paint.color = Color.parseColor("#00FFCC")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawRoundRect(multiRepRect, 6f, 6f, paint)
            paint.style = Paint.Style.FILL

            // Título de la tarjeta Multi-Rep
            textPaint.color = Color.parseColor("#00FFCC")
            textPaint.textSize = 8.8f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("📊 DESGLOSE DE SERIE / COMPLEX • PROGRESIÓN Y RETENCIÓN DE VELOCIDAD", 30f, multiRepTop + 14f, textPaint)

            // Pérdida total badge a la derecha
            val lossColor = when {
                maxVelocityLoss <= 10.0 -> Color.parseColor("#10B981") // Verde
                maxVelocityLoss <= 20.0 -> Color.parseColor("#F59E0B") // Ámbar
                else -> Color.parseColor("#EF4444") // Rojo
            }
            textPaint.color = lossColor
            textPaint.textSize = 8.5f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val lossBadgeText = "Pérdida Total: -${String.format(Locale.US, "%.1f", maxVelocityLoss)}%"
            val lbW = textPaint.measureText(lossBadgeText)
            canvas.drawText(lossBadgeText, 565f - lbW, multiRepTop + 14f, textPaint)

            // Fila de Repeticiones / Fases del complex
            val repsCount = finalReps.size.coerceAtMost(6)
            val totalAvailableW = 535f
            val gap = 5f
            val repW = (totalAvailableW - (repsCount - 1) * gap) / repsCount
            val bestPeak = finalReps.maxOfOrNull { it.peakVelocity } ?: peakVelocity

            for (idx in 0 until repsCount) {
                val r = finalReps[idx]
                val rx = 30f + idx * (repW + gap)
                val ry = multiRepTop + 22f
                val rBox = RectF(rx, ry, rx + repW, ry + 40f)

                paint.color = Color.parseColor("#1E293B")
                canvas.drawRoundRect(rBox, 3f, 3f, paint)

                val isBest = (r.peakVelocity == bestPeak)
                val borderC = if (isBest) Color.parseColor("#10B981") else Color.parseColor("#334155")
                paint.color = borderC
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = if (isBest) 1f else 0.6f
                canvas.drawRoundRect(rBox, 3f, 3f, paint)
                paint.style = Paint.Style.FILL

                // Rep / Ejercicio del Complex Label
                textPaint.color = if (isBest) Color.parseColor("#34D399") else Color.parseColor("#94A3B8")
                textPaint.textSize = 7.2f
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                val rawPhaseName = exerciseSequenceNames.getOrNull(idx)
                val rLabel = if (!rawPhaseName.isNullOrBlank()) {
                    val candidate = "${idx + 1}. $rawPhaseName"
                    var cut = candidate
                    while (cut.length > 6 && textPaint.measureText(cut) > repW - 8f) {
                        cut = cut.dropLast(1)
                    }
                    if (cut.length < candidate.length) "$cut." else candidate
                } else if (isMultiRepOrComplex && (exerciseName.contains("+") || exerciseName.contains("Complex", ignoreCase = true))) {
                    "Fase ${idx + 1}"
                } else {
                    "Rep ${idx + 1}"
                }
                canvas.drawText(rLabel, rx + 4f, ry + 11f, textPaint)

                // Peak Velocity
                textPaint.color = Color.WHITE
                textPaint.textSize = 8.5f
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText(String.format(Locale.US, "%.2f m/s", r.peakVelocity), rx + 4f, ry + 23f, textPaint)

                // Loss badge or Best tag
                textPaint.textSize = 7f
                if (isBest) {
                    textPaint.color = Color.parseColor("#10B981")
                    canvas.drawText("★ PICO MÁX", rx + 4f, ry + 34f, textPaint)
                } else {
                    val dropPct = if (bestPeak > 0) ((bestPeak - r.peakVelocity) / bestPeak * 100.0).coerceAtLeast(0.0) else 0.0
                    textPaint.color = if (dropPct <= 10.0) Color.parseColor("#38BDF8") else if (dropPct <= 20.0) Color.parseColor("#F59E0B") else Color.parseColor("#EF4444")
                    canvas.drawText(String.format(Locale.US, "-%.1f%%", dropPct), rx + 4f, ry + 34f, textPaint)
                }
            }

            nextSectionY = multiRepTop + multiRepHeight + 8f
        }

        // 7. Tarjeta de Interpretación Biomecánica & Recomendaciones Técnicas
        val interpTop = nextSectionY
        val interpRect = RectF(20f, interpTop, 575f, 812f)
        paint.color = Color.parseColor("#0F172A")
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(interpRect, 8f, 8f, paint)
        paint.color = Color.parseColor("#1E293B")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(interpRect, 8f, 8f, paint)
        paint.style = Paint.Style.FILL

        // Título de la tarjeta y línea divisoria
        textPaint.color = Color.parseColor("#00FFCC")
        textPaint.textSize = 9.5f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("⚡ INTERPRETACIÓN BIOMECÁNICA & RECOMENDACIONES TÉCNICAS", 34f, interpTop + 17f, textPaint)

        paint.color = Color.parseColor("#334155")
        canvas.drawLine(34f, interpTop + 23f, 561f, interpTop + 23f, paint)

        val eval = evaluateLift(
            peakVelocity = peakVelocity,
            loadKg = loadKg,
            pctOneRM = pctOneRM,
            horizontalDeviationCm = horizontalDeviationCm,
            relativeWattsPerKg = relativeWattsPerKg,
            peakForceN = peakForceN,
            workJoules = workJoules,
            timeToPeakMs = timeToPeakMs,
            rfdNPerSec = rfdNPerSec,
            multiReps = finalReps,
            exerciseName = exerciseName
        )

        val marginX = 34f
        val contentW = 527f
        var curY = interpTop + 33f

        // Barra Visual de Reserva de Velocidad sobre el Umbral MVT Oficial del Ejercicio
        val speedReserve = (peakVelocity - effectiveMvt).coerceAtLeast(0.0)
        val gaugeRect = RectF(marginX, curY - 5f, 561f, curY + 15f)
        paint.color = Color.parseColor("#1E293B")
        canvas.drawRoundRect(gaugeRect, 4f, 4f, paint)

        val fillFrac = ((peakVelocity / (effectiveMvt * 2.4)).toFloat()).coerceIn(0.12f, 1f)
        val fillRect = RectF(marginX, curY - 5f, marginX + contentW * fillFrac, curY + 15f)
        paint.color = if (speedReserve >= 0.45) Color.parseColor("#2600FFCC") else Color.parseColor("#26F59E0B")
        canvas.drawRoundRect(fillRect, 4f, 4f, paint)

        paint.color = Color.parseColor("#334155")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.7f
        canvas.drawRoundRect(gaugeRect, 4f, 4f, paint)
        paint.style = Paint.Style.FILL

        textPaint.color = Color.parseColor("#00FFCC")
        textPaint.textSize = 7.4f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val mvtBarText = String.format(
            Locale.US,
            "⚡ PERFIL MVT ESPECÍFICO: Vel. Pico %.2f m/s  |  Umbral 1RM (MVT): %.2f m/s  |  Reserva de Velocidad: +%.2f m/s",
            peakVelocity,
            effectiveMvt,
            speedReserve
        )
        canvas.drawText(mvtBarText, marginX + 8f, curY + 7.5f, textPaint)
        curY += 27f

        // Bloque 1: Zona de Velocidad y Potencia
        textPaint.color = Color.parseColor("#00E5FF")
        textPaint.textSize = 8.2f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("• ZONA CINEMÁTICA: ${eval.velocityZoneTag}", marginX, curY, textPaint)
        curY += 11f

        textPaint.color = Color.parseColor("#E2E8F0")
        textPaint.textSize = 7.8f
        textPaint.typeface = Typeface.DEFAULT
        curY = drawWrappedParagraph(canvas, eval.velocityZoneDetail, marginX + 8f, curY, contentW - 10f, 10f, textPaint)

        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 7.2f
        val potLine = "Potencia relativa: ${String.format(Locale.US, "%.1f", relativeWattsPerKg)} W/kg  |  Fuerza pico: ${peakForceN.toInt()} N  |  Trabajo: ${workJoules.toInt()} J"
        canvas.drawText(potLine, marginX + 8f, curY + 2f, textPaint)
        curY += 13f

        // Bloque 2: Fatiga Intraserie / Dinámica del Complex o Trayectoria
        val block2Title = if (isMultiRepOrComplex) "• DINÁMICA DE SERIE & FATIGA: ${eval.fatigueTag ?: "PERFIL DE PÉRDIDA DE VELOCIDAD"}" else "• CONTROL TÉCNICO: ${eval.driftTag}"
        textPaint.color = Color.parseColor("#FFD600")
        textPaint.textSize = 8.2f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(block2Title, marginX, curY, textPaint)
        curY += 11f

        textPaint.color = Color.parseColor("#E2E8F0")
        textPaint.textSize = 7.8f
        textPaint.typeface = Typeface.DEFAULT
        val block2Detail = if (isMultiRepOrComplex && eval.fatigueDetail != null) eval.fatigueDetail else eval.driftDetail
        curY = drawWrappedParagraph(canvas, block2Detail, marginX + 8f, curY, contentW - 10f, 10f, textPaint)

        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 7.2f
        val block2Sub = if (isMultiRepOrComplex) {
            "Control de barra: ${eval.driftTag} | Desvío: ${if (horizontalDeviationCm != null) "${String.format(Locale.US, "%.1f", horizontalDeviationCm)} cm" else "-- cm"}"
        } else {
            eval.rfdDetail
        }
        canvas.drawText(block2Sub, marginX + 8f, curY + 2f, textPaint)
        curY += 13f

        // Bloque 3: Caja de Prescripción de Carga Práctica para la Próxima Serie (Caja dinámica con auto-medición estricta)
        val recBoxTop = curY
        textPaint.color = Color.parseColor("#F8FAFC")
        textPaint.textSize = 7.6f
        textPaint.typeface = Typeface.DEFAULT
        val estimatedRecLines = estimateWrappedLines(eval.loadRecommendation, contentW - 18f, textPaint)
        val recBoxHeight = (20f + estimatedRecLines * 10.5f + 8f).coerceAtLeast(38f).coerceAtMost(810f - recBoxTop - 6f)

        val recBox = RectF(marginX, recBoxTop, 561f, recBoxTop + recBoxHeight)
        paint.color = Color.parseColor("#1E293B")
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(recBox, 5f, 5f, paint)
        paint.color = Color.parseColor("#00FFCC")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f
        canvas.drawRoundRect(recBox, 5f, 5f, paint)
        paint.style = Paint.Style.FILL

        textPaint.color = Color.parseColor("#00FFCC")
        textPaint.textSize = 7.8f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("🎯 PRESCRIPCIÓN PARA LA SIGUIENTE SERIE:", marginX + 8f, recBoxTop + 12f, textPaint)

        textPaint.color = Color.parseColor("#F8FAFC")
        textPaint.textSize = 7.5f
        textPaint.typeface = Typeface.DEFAULT
        drawWrappedParagraph(canvas, eval.loadRecommendation, marginX + 8f, recBoxTop + 23f, contentW - 18f, 10f, textPaint)

        // 8. Pie de Página
        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 7.5f
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("Speed Tracker v1.9 • Laboratorio VBT & Cinemática • Validación Científica (González-Badillo / Sánchez-Medina)", 24f, 826f, textPaint)
        val copyRight = "Pág 1 de 1"
        val crW = textPaint.measureText(copyRight)
        canvas.drawText(copyRight, 571f - crW, 826f, textPaint)

        pdfDocument.finishPage(page)

        // Guardar archivo PDF en almacenamiento interno accesible para informes
        val reportsDir = File(context.filesDir, "reports").apply { mkdirs() }
        val sanitizedAthlete = athleteName.replace(" ", "_").replace(Regex("[^a-zA-Z0-9_]"), "")
        val outputFile = File(reportsDir, "Informe_${sanitizedAthlete}_${System.currentTimeMillis()}.pdf")
        val outputStream = FileOutputStream(outputFile)
        pdfDocument.writeTo(outputStream)
        outputStream.flush()
        outputStream.close()
        pdfDocument.close()

        outputFile
    }

    /**
     * Detecta repeticiones y fases concéntricas continuas a partir de trackingPoints cuando
     * se analiza un video fuera de línea con varias repeticiones o complejos motores.
     */
    fun detectRepsFromTrackingPoints(points: List<VideoFrameTrackingPoint>): List<RepResult> {
        if (points.size < 6) return emptyList()
        val reps = mutableListOf<RepResult>()

        var inConcentric = false
        var phaseStartMs = 0L
        var phasePeakV = 0.0
        val phaseVelocities = mutableListOf<Double>()
        var phaseMinY = Float.MAX_VALUE
        var phaseMaxY = Float.MIN_VALUE

        for (pt in points) {
            val isConc = pt.isConcentric || pt.velocityMps > 0.22
            if (isConc) {
                if (!inConcentric) {
                    inConcentric = true
                    phaseStartMs = pt.timestampMs
                    phasePeakV = pt.velocityMps
                    phaseVelocities.clear()
                    phaseMinY = pt.y
                    phaseMaxY = pt.y
                }
                phaseVelocities.add(pt.velocityMps)
                if (pt.velocityMps > phasePeakV) phasePeakV = pt.velocityMps
                if (pt.y < phaseMinY) phaseMinY = pt.y
                if (pt.y > phaseMaxY) phaseMaxY = pt.y
            } else {
                if (inConcentric) {
                    inConcentric = false
                    val durationMs = (pt.timestampMs - phaseStartMs).coerceAtLeast(0L)
                    if (durationMs >= 140L && phasePeakV >= 0.28 && phaseVelocities.size >= 3) {
                        val meanV = phaseVelocities.average()
                        reps.add(
                            RepResult(
                                repNumber = reps.size + 1,
                                peakVelocity = phasePeakV,
                                meanVelocity = meanV,
                                romMeters = ((phaseMaxY - phaseMinY).toDouble() / 100.0).coerceAtLeast(0.12),
                                isCutoffReached = false,
                                velocityLossPercent = 0.0
                            )
                        )
                    }
                }
            }
        }

        if (inConcentric) {
            val durationMs = ((points.lastOrNull()?.timestampMs ?: phaseStartMs) - phaseStartMs).coerceAtLeast(0L)
            if (durationMs >= 140L && phasePeakV >= 0.28 && phaseVelocities.size >= 3) {
                reps.add(
                    RepResult(
                        repNumber = reps.size + 1,
                        peakVelocity = phasePeakV,
                        meanVelocity = phaseVelocities.average(),
                        romMeters = ((phaseMaxY - phaseMinY).toDouble() / 100.0).coerceAtLeast(0.12),
                        isCutoffReached = false,
                        velocityLossPercent = 0.0
                    )
                )
            }
        }

        if (reps.size > 1) {
            val bestPeak = reps.maxOfOrNull { it.peakVelocity } ?: 1.0
            return reps.mapIndexed { _, rep ->
                val loss = if (bestPeak > 0) ((bestPeak - rep.peakVelocity) / bestPeak * 100.0).coerceAtLeast(0.0) else 0.0
                rep.copy(velocityLossPercent = loss)
            }
        }
        return reps
    }

    /**
     * Estima la cantidad de líneas que ocupará un texto con ajuste de margen
     */
    private fun estimateWrappedLines(text: String, maxWidth: Float, paint: Paint): Int {
        var lines = 1
        val words = text.split(" ")
        var currentLine = StringBuilder()
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) > maxWidth && currentLine.isNotEmpty()) {
                lines++
                currentLine = StringBuilder(word)
            } else {
                currentLine.append(if (currentLine.isEmpty()) word else " $word")
            }
        }
        return lines
    }

    /**
     * Recorta inteligentemente el fotograma centrándose en la zona de acción del levantamiento (Smart Crop)
     * para eliminar bandas negras en videos verticales (9:16), y dibuja sobre el fotograma la línea base de
     * despegue, la estela de trayectoria (Bar Path) hasta ese milisegundo y la mira sobre el disco.
     */
    private fun prepareSmartCroppedKeyframeBitmap(
        src: Bitmap,
        trackingPoints: List<VideoFrameTrackingPoint>,
        upToIndex: Int,
        startIndex: Int,
        accentColor: Int,
        targetAspect: Float,
        maxDimPx: Int = 560
    ): Bitmap {
        val baseBmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = baseBmp.width.coerceAtLeast(1)
        val h = baseBmp.height.coerceAtLeast(1)

        val hasValidCoords = trackingPoints.isNotEmpty() &&
            upToIndex in trackingPoints.indices &&
            trackingPoints.maxOf { it.x } <= w * 1.1f &&
            trackingPoints.maxOf { it.y } <= h * 1.1f

        var cropLeft = 0
        var cropTop = 0
        var cropW = w
        var cropH = h

        if (hasValidCoords) {
            val minX = trackingPoints.minOf { it.x }
            val maxX = trackingPoints.maxOf { it.x }
            val minY = trackingPoints.minOf { it.y }
            val maxY = trackingPoints.maxOf { it.y }

            val cx = ((minX + maxX) / 2f).coerceIn(0f, w.toFloat())
            val cy = ((minY + maxY) / 2f).coerceIn(0f, h.toFloat())

            val spanY = (maxY - minY).coerceAtLeast(h * 0.28f)
            var desiredH = (spanY * 2.15f).coerceIn(h * 0.50f, h.toFloat())
            var desiredW = desiredH * targetAspect

            if (desiredW > w) {
                desiredW = w.toFloat()
                desiredH = (desiredW / targetAspect).coerceAtMost(h.toFloat())
            }
            if (desiredH > h) {
                desiredH = h.toFloat()
                desiredW = (desiredH * targetAspect).coerceAtMost(w.toFloat())
            }

            cropW = desiredW.toInt().coerceIn(1, w)
            cropH = desiredH.toInt().coerceIn(1, h)
            cropLeft = (cx - cropW / 2f).toInt().coerceIn(0, (w - cropW).coerceAtLeast(0))
            cropTop = (cy - cropH / 2f).toInt().coerceIn(0, (h - cropH).coerceAtLeast(0))

            // Dibujar detalles biomecánicos directamente sobre el fotograma antes del escalado
            val bmpCanvas = Canvas(baseBmp)
            val strokeScale = (maxOf(cropW, cropH) / 360f).coerceAtLeast(1f)

            // 1. Línea horizontal punteada de referencia de despegue
            val sIdx = startIndex.coerceIn(0, trackingPoints.lastIndex)
            val startY = trackingPoints[sIdx].y
            val baseLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 56, 189, 248)
                style = Paint.Style.STROKE
                strokeWidth = 2f * strokeScale
                pathEffect = DashPathEffect(floatArrayOf(10f * strokeScale, 8f * strokeScale), 0f)
            }
            bmpCanvas.drawLine(cropLeft.toFloat(), startY, (cropLeft + cropW).toFloat(), startY, baseLinePaint)

            // 2. Estela de la trayectoria (Bar Path) acumulada hasta este instante
            val fromIdx = minOf(sIdx, upToIndex)
            if (upToIndex > fromIdx) {
                val path = Path()
                for (k in fromIdx..upToIndex) {
                    val pt = trackingPoints[k]
                    if (k == fromIdx) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                val glowPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(95, 0, 255, 204)
                    style = Paint.Style.STROKE
                    strokeWidth = 8.5f * strokeScale
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                val corePathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#00FFCC")
                    style = Paint.Style.STROKE
                    strokeWidth = 3.8f * strokeScale
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                bmpCanvas.drawPath(path, glowPathPaint)
                bmpCanvas.drawPath(path, corePathPaint)
            }

            // 3. Mira telescópica sobre la posición exacta del disco en este fotograma
            val curPt = trackingPoints[upToIndex]
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(85, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                style = Paint.Style.FILL
            }
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.STROKE
                strokeWidth = 3.2f * strokeScale
            }
            val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            bmpCanvas.drawCircle(curPt.x, curPt.y, 14f * strokeScale, haloPaint)
            bmpCanvas.drawCircle(curPt.x, curPt.y, 9f * strokeScale, ringPaint)
            bmpCanvas.drawCircle(curPt.x, curPt.y, 3.5f * strokeScale, centerDotPaint)
        } else {
            // Si no hay coordenadas, aplicar Center-Crop suave a la proporción de la tarjeta
            val srcAspect = w.toFloat() / h.toFloat()
            if (srcAspect < targetAspect) {
                cropW = w
                cropH = (w / targetAspect).toInt().coerceIn(1, h)
                cropTop = ((h - cropH) / 2).coerceAtLeast(0)
            } else {
                cropH = h
                cropW = (h * targetAspect).toInt().coerceIn(1, w)
                cropLeft = ((w - cropW) / 2).coerceAtLeast(0)
            }
        }

        val croppedBmp = try {
            Bitmap.createBitmap(baseBmp, cropLeft, cropTop, cropW, cropH)
        } catch (e: Exception) {
            baseBmp
        }

        return preparePdfSafeBitmap(croppedBmp, maxDimPx = maxDimPx)
    }

    private fun preparePdfSafeBitmap(src: Bitmap, maxDimPx: Int = 480): Bitmap {
        val softBmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false)
        } else if (src.config != Bitmap.Config.ARGB_8888) {
            src.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            src
        }
        val w = softBmp.width.coerceAtLeast(1)
        val h = softBmp.height.coerceAtLeast(1)
        val maxSide = maxOf(w, h)
        if (maxSide <= maxDimPx) return softBmp
        val scale = maxDimPx.toFloat() / maxSide.toFloat()
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(softBmp, targetW, targetH, true)
    }

    suspend fun renderPdfPageToBitmap(pdfFile: File): Bitmap? = withContext(Dispatchers.IO) {
        if (!pdfFile.exists()) return@withContext null
        var pfd: android.os.ParcelFileDescriptor? = null
        var renderer: android.graphics.pdf.PdfRenderer? = null
        var page: android.graphics.pdf.PdfRenderer.Page? = null
        try {
            pfd = android.os.ParcelFileDescriptor.open(pdfFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = android.graphics.pdf.PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                page = renderer.openPage(0)
                val bmpW = (page.width * 2).coerceAtLeast(595)
                val bmpH = (page.height * 2).coerceAtLeast(842)
                val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.parseColor("#F8FAFC"))
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return@withContext bitmap
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try { page?.close() } catch (e: Exception) {}
            try { renderer?.close() } catch (e: Exception) {}
            try { pfd?.close() } catch (e: Exception) {}
        }
    }

    fun openOrSharePdf(context: Context, pdfFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Informe Biomecánico Speed Tracker - ${pdfFile.nameWithoutExtension}")
            putExtra(Intent.EXTRA_TEXT, "Adjunto informe biomecánico detallado generado por Speed Tracker.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        context.startActivity(Intent.createChooser(intent, "Compartir Informe PDF").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    fun openPdfInExternalViewer(context: Context, pdfFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(viewIntent, "Abrir Informe PDF").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            openOrSharePdf(context, pdfFile)
        }
    }

    private fun drawWrappedParagraph(
        canvas: Canvas,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float,
        paint: Paint
    ): Float {
        var currentY = startY
        val words = text.split(" ")
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val testWidth = paint.measureText(testLine)
            if (testWidth > maxWidth && currentLine.isNotEmpty()) {
                canvas.drawText(currentLine.toString(), x, currentY, paint)
                currentY += lineHeight
                currentLine = StringBuilder(word)
            } else {
                currentLine.append(if (currentLine.isEmpty()) word else " $word")
            }
        }
        if (currentLine.isNotEmpty()) {
            canvas.drawText(currentLine.toString(), x, currentY, paint)
            currentY += lineHeight
        }
        return currentY
    }

    data class BiomechanicalEvaluation(
        val velocityZoneTag: String,
        val velocityZoneDetail: String,
        val driftTag: String,
        val driftDetail: String,
        val rfdDetail: String,
        val fatigueTag: String? = null,
        val fatigueDetail: String? = null,
        val loadRecommendation: String
    )

    fun evaluateLift(
        peakVelocity: Double,
        loadKg: Double,
        pctOneRM: Double,
        horizontalDeviationCm: Double?,
        relativeWattsPerKg: Double,
        peakForceN: Double,
        workJoules: Double,
        timeToPeakMs: Long?,
        rfdNPerSec: Int?,
        multiReps: List<RepResult> = emptyList(),
        exerciseName: String = ""
    ): BiomechanicalEvaluation {
        val (velTag, velDetail) = when {
            peakVelocity >= 2.20 -> Pair(
                "VELOCIDAD EXPLOSIVA ALTA (VP ≥ 2.20 m/s)",
                "Excelente aceleración y transmisión de potencia neuromuscular en el tirón. La barra aceleró fluidamente y sin puntos de desaceleración temprana."
            )
            peakVelocity >= 1.70 -> Pair(
                "FUERZA-POTENCIA ÓPTIMA (VP 1.70 - 2.19 m/s)",
                "Desplazamiento vertical sólido y controlado para ${loadKg.toInt()} kg (~${pctOneRM.toInt()}% 1RM). Gran sincronización concéntrica y reclutamiento motor coordinado."
            )
            else -> Pair(
                "FUERZA MÁXIMA / ZONA DE CARGA LÍMITE (VP < 1.70 m/s)",
                "Carga pesada con velocidad desacelerada. Demanda máxima de reclutamiento neuromuscular y mayor tiempo bajo tensión mecánica."
            )
        }

        val drift = horizontalDeviationCm ?: 0.0
        val (driftTag, driftDetail) = when {
            drift <= 10.0 -> Pair(
                "TRAYECTORIA VERTICAL EFICIENTE (${String.format(Locale.US, "%.1f", drift)} cm)",
                "Desvío horizontal mínimo. La barra se mantuvo perfectamente alineada con el centro de gravedad corporal, optimizando el trabajo mecánico y la estabilidad."
            )
            drift <= 18.0 -> Pair(
                "TRAYECTORIA CONTROLADA (${String.format(Locale.US, "%.1f", drift)} cm)",
                "Desvío moderado hacia adelante. Se sugiere enfatizar codos altos en el tirón y mantener la barra próxima al cuerpo en la fase de extensión."
            )
            else -> Pair(
                "DESVÍO HORIZONTAL ELEVADO (${String.format(Locale.US, "%.1f", drift)} cm)",
                "La barra se alejó del eje vertical. Trabajar en extensiones verticales completas evitando proyectar la cadera horizontalmente hacia la barra."
            )
        }

        val rfdStr = "Tiempo a Vel. Pico: ${timeToPeakMs ?: "--"} ms | RFD: ${rfdNPerSec ?: "--"} N/s | Potencia: ${String.format(Locale.US, "%.1f", relativeWattsPerKg)} W/kg"

        var fatigueTag: String? = null
        var fatigueDetail: String? = null

        if (multiReps.size > 1) {
            val bestPeak = multiReps.maxOfOrNull { it.peakVelocity } ?: peakVelocity
            val lastPeak = multiReps.last().peakVelocity
            val lossPct = if (bestPeak > 0) ((bestPeak - lastPeak) / bestPeak * 100.0).coerceAtLeast(0.0) else 0.0
            val isComplex = exerciseName.contains("Complex", ignoreCase = true) || exerciseName.contains("+")

            if (isComplex) {
                fatigueTag = "TRANSICIÓN EN COMPLEX (Pérdida: -${String.format(Locale.US, "%.1f", lossPct)}%)"
                fatigueDetail = when {
                    lossPct <= 10.0 -> "Excelente retención de velocidad entre los movimientos del complex. La transferencia del tirón hacia la recepción o segundo ejercicio fue inmediata y altamente dinámica."
                    lossPct <= 20.0 -> "Pérdida moderada de velocidad en el encadenamiento del complex. Asegurar fijar bien la posición intermedia y respirar antes de iniciar la segunda fase."
                    else -> "Fatiga acusada en la fase final del complex (-${String.format(Locale.US, "%.1f", lossPct)}%). Se recomienda reducir 5-10% la carga para consolidar la sincronización sin degradar la técnica."
                }
            } else {
                fatigueTag = "FATIGA INTRASERIE VBT (-${String.format(Locale.US, "%.1f", lossPct)}%)"
                fatigueDetail = when {
                    lossPct <= 10.0 -> "Pérdida mínima (<10%). Estímulo de potencia pura y velocidad máxima sin fatiga metabólica acumulada. Ideal para velocidad y técnica limpia."
                    lossPct <= 20.0 -> "Pérdida moderada (10-20%). Zona óptima de volumen y desarrollo de fuerza-velocidad. La velocidad se mantuvo dentro del rango funcional recomendado."
                    else -> "Pérdida elevada (>20%). Fatiga neuromuscular y metabólica significativa. Se recomienda detener la serie o respetar mayor recuperación para evitar fallos técnicos."
                }
            }
        }

        val loadRec = when {
            multiReps.size > 1 -> {
                val bestPeak = multiReps.maxOfOrNull { it.peakVelocity } ?: peakVelocity
                val lastPeak = multiReps.last().peakVelocity
                val lossPct = if (bestPeak > 0) ((bestPeak - lastPeak) / bestPeak * 100.0).coerceAtLeast(0.0) else 0.0
                if (lossPct > 20.0) {
                    "Descanso de 3 a 4 minutos antes de la siguiente serie o reducir 2.5-5.0 kg para mantener la velocidad pico por encima del umbral técnico."
                } else if (lossPct < 8.0 && pctOneRM < 80.0) {
                    "Gran reserva de velocidad en la serie (pérdida < 8%). Puedes incrementar de 2.5 kg a 5.0 kg en la próxima serie con total seguridad."
                } else {
                    "Carga y volumen equilibrados. Mantener los ${loadKg.toInt()} kg para la siguiente serie para consolidar la técnica y potencia."
                }
            }
            pctOneRM < 70.0 && peakVelocity > 2.0 -> "Incrementar carga (+2.5 kg a +5.0 kg): La velocidad y aceleración explosiva demuestran gran reserva neuromuscular para admitir mayor peso sin perder dinamismo."
            pctOneRM in 70.0..85.0 -> "Mantener carga actual: Estás en la franja ideal de Potencia Máxima (70-85% 1RM). Mantén este peso para consolidar adaptaciones neuromusculares rápidas."
            else -> "Carga límite / submáxima (>85% 1RM): Reclutamiento neuromuscular máximo. No incrementar carga y respetar de 3 a 5 min de descanso entre series para evitar fatiga."
        }

        return BiomechanicalEvaluation(
            velocityZoneTag = velTag,
            velocityZoneDetail = velDetail,
            driftTag = driftTag,
            driftDetail = driftDetail,
            rfdDetail = rfdStr,
            fatigueTag = fatigueTag,
            fatigueDetail = fatigueDetail,
            loadRecommendation = loadRec
        )
    }
}
