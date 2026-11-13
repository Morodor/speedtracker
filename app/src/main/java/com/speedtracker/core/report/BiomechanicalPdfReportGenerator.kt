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
        repsList: List<RepResult> = emptyList()
    ): File = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 estándar
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Resolver lista final de repeticiones (de la lista suministrada o detectando fases concéntricas en trackingPoints)
        val finalReps: List<RepResult> = if (repsList.size > 1) {
            repsList
        } else {
            val detected = detectRepsFromTrackingPoints(trackingPoints)
            if (detected.size > 1) detected else repsList
        }
        val isMultiRepOrComplex = finalReps.size > 1
        val maxVelocityLoss = if (isMultiRepOrComplex) {
            val bestPeak = finalReps.maxOfOrNull { it.peakVelocity } ?: peakVelocity
            val lastPeak = finalReps.last().peakVelocity
            if (bestPeak > 0) ((bestPeak - lastPeak) / bestPeak * 100.0).coerceAtLeast(0.0) else 0.0
        } else 0.0

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

        // Columna 2: Ejercicio
        textPaint.color = Color.parseColor("#64748B")
        textPaint.textSize = 8f
        canvas.drawText("EJERCICIO / MODALIDAD", 215f, 101f, textPaint)
        textPaint.color = Color.parseColor("#0F172A")
        textPaint.textSize = 10.5f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(exerciseName, 215f, 116f, textPaint)
        textPaint.color = Color.parseColor("#475569")
        textPaint.textSize = 8.5f
        textPaint.typeface = Typeface.DEFAULT
        val famSub = if (isMultiRepOrComplex) "Serie: ${finalReps.size} reps • $exerciseFamily" else "Metodología: $exerciseFamily"
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

        // 4. Extracción y Dibujo de Fotogramas Clave del Levantamiento (Keyframes)
        var retriever: MediaMetadataRetriever? = null
        val frameBitmaps = mutableListOf<Bitmap>()
        try {
            retriever = MediaMetadataRetriever().apply { setDataSource(context, videoUri) }

            val t1 = trimStartMs * 1000L
            val peakPt = trackingPoints.maxByOrNull { it.velocityMps }
            val t2 = if (peakPt != null && peakPt.timestampMs > 0) {
                (trimStartMs + peakPt.timestampMs) * 1000L
            } else {
                ((trimStartMs + trimEndMs) / 2L) * 1000L
            }
            val t3 = trimEndMs * 1000L

            listOf(t1, t2, t3).forEach { timeUs ->
                val rawBmp = retriever?.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (rawBmp != null) {
                    frameBitmaps.add(preparePdfSafeBitmap(rawBmp, maxDimPx = 480))
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
        canvas.drawText("SECUENCIA CINEMÁTICA DEL LEVANTAMIENTO", 24f, 150f, textPaint)

        val frameWidth = 180f
        val frameHeight = 98f
        val frameY = 156f
        val frameLabels = listOf("1. Despegue / Inicio", "2. Máxima Aceleración (PV)", "3. Bloqueo / Recepción")

        for (i in 0 until 3) {
            val fx = 20f + i * (frameWidth + 7.5f)
            val frameRect = RectF(fx, frameY, fx + frameWidth, frameY + frameHeight)

            paint.color = Color.parseColor("#0F172A")
            canvas.drawRoundRect(frameRect, 4f, 4f, paint)

            if (i < frameBitmaps.size) {
                val bmp = frameBitmaps[i]
                val bmpRatio = bmp.width.toFloat() / bmp.height.toFloat()
                val targetRatio = frameWidth / frameHeight

                val drawRect: RectF = if (bmpRatio > targetRatio) {
                    val fittedH = frameWidth / bmpRatio
                    val yOff = (frameHeight - fittedH) / 2f
                    RectF(fx + 1f, frameY + yOff + 1f, fx + frameWidth - 1f, frameY + yOff + fittedH - 1f)
                } else {
                    val fittedW = frameHeight * bmpRatio
                    val xOff = (frameWidth - fittedW) / 2f
                    RectF(fx + xOff, frameY + 1f, fx + xOff + fittedW, frameY + frameHeight - 1f)
                }

                val srcRect = Rect(0, 0, bmp.width, bmp.height)
                paint.isFilterBitmap = true
                canvas.drawBitmap(bmp, srcRect, drawRect, paint)
            }

            paint.color = Color.parseColor("#00FFCC")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawRoundRect(frameRect, 4f, 4f, paint)
            paint.style = Paint.Style.FILL

            paint.color = Color.parseColor("#CC000000")
            val labelRect = RectF(fx, frameY + frameHeight - 16f, fx + frameWidth, frameY + frameHeight)
            canvas.drawRect(labelRect, paint)
            textPaint.color = Color.WHITE
            textPaint.textSize = 8f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText(frameLabels[i], fx + 5f, frameY + frameHeight - 5f, textPaint)
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

                // Rep Label
                textPaint.color = if (isBest) Color.parseColor("#34D399") else Color.parseColor("#94A3B8")
                textPaint.textSize = 7.5f
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                val rLabel = if (isMultiRepOrComplex && exerciseName.contains("Complex", ignoreCase = true)) "Fase ${idx + 1}" else "Rep ${idx + 1}"
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
        var curY = interpTop + 35f

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
