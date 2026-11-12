package com.speedtracker.core.report

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import com.speedtracker.core.export.VideoFrameTrackingPoint
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
        trimEndMs: Long
    ): File = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 estándar
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Fondo general blanco/gris suave profesional
        canvas.drawColor(Color.parseColor("#F8FAFC"))

        // 2. Encabezado Superior (Barra oscura moderna con acentos neón)
        paint.color = Color.parseColor("#0F172A")
        paint.style = Paint.Style.FILL
        val headerRect = RectF(20f, 20f, 575f, 85f)
        canvas.drawRoundRect(headerRect, 8f, 8f, paint)

        // Título del Encabezado
        textPaint.color = Color.parseColor("#00FFCC")
        textPaint.textSize = 15f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("⚡ SPEED TRACKER • LABORATORIO BIOMECÁNICO", 36f, 48f, textPaint)

        // Subtítulo
        textPaint.color = Color.WHITE
        textPaint.textSize = 10f
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("INFORME OFICIAL DE RENDIMIENTO Y ANÁLISIS CINEMÁTICO VBT", 36f, 68f, textPaint)

        // Fecha de emisión a la derecha
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val dateString = sdf.format(Date())
        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 9f
        val dateWidth = textPaint.measureText(dateString)
        canvas.drawText(dateString, 559f - dateWidth, 58f, textPaint)

        // 3. Ficha de Datos del Atleta y Levantamiento
        paint.color = Color.parseColor("#F1F5F9")
        val athleteCard = RectF(20f, 92f, 575f, 142f)
        canvas.drawRoundRect(athleteCard, 6f, 6f, paint)
        paint.color = Color.parseColor("#E2E8F0")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(athleteCard, 6f, 6f, paint)
        paint.style = Paint.Style.FILL

        // Columna 1: Atleta
        textPaint.color = Color.parseColor("#64748B")
        textPaint.textSize = 8.5f
        canvas.drawText("ATLETA", 32f, 108f, textPaint)
        textPaint.color = Color.parseColor("#0F172A")
        textPaint.textSize = 11f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(athleteName, 32f, 124f, textPaint)
        textPaint.color = Color.parseColor("#475569")
        textPaint.textSize = 9f
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("Peso corporal: ${String.format(Locale.US, "%.1f", athleteWeightKg)} kg", 32f, 136f, textPaint)

        // Columna 2: Ejercicio
        textPaint.color = Color.parseColor("#64748B")
        textPaint.textSize = 8.5f
        canvas.drawText("EJERCICIO / DISCIPLINA", 215f, 108f, textPaint)
        textPaint.color = Color.parseColor("#0F172A")
        textPaint.textSize = 11f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(exerciseName, 215f, 124f, textPaint)
        textPaint.color = Color.parseColor("#475569")
        textPaint.textSize = 9f
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("Metodología: $exerciseFamily", 215f, 136f, textPaint)

        // Columna 3: Carga
        textPaint.color = Color.parseColor("#64748B")
        textPaint.textSize = 8.5f
        canvas.drawText("CARGA EVALUADA", 415f, 108f, textPaint)
        textPaint.color = Color.parseColor("#00875A")
        textPaint.textSize = 13f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("${loadKg.toInt()} kg", 415f, 124f, textPaint)
        textPaint.color = Color.parseColor("#475569")
        textPaint.textSize = 9f
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("1RM Est: ${estimatedOneRM.toInt()} kg (~${pctOneRM.toInt()}%)", 415f, 136f, textPaint)

        // 4. Extracción y Dibujo de Fotogramas Clave del Levantamiento (Keyframes)
        var retriever: MediaMetadataRetriever? = null
        val frameBitmaps = mutableListOf<Bitmap>()
        try {
            retriever = MediaMetadataRetriever().apply { setDataSource(context, videoUri) }

            // Timestamp de Despegue (Inicio)
            val t1 = trimStartMs * 1000L

            // Timestamp de Velocidad Pico (Máxima aceleración)
            val peakPt = trackingPoints.maxByOrNull { it.velocityMps }
            val t2 = if (peakPt != null && peakPt.timestampMs > 0) {
                (trimStartMs + peakPt.timestampMs) * 1000L
            } else {
                ((trimStartMs + trimEndMs) / 2L) * 1000L
            }

            // Timestamp de Bloqueo / Recepción (Fin concéntrico)
            val t3 = trimEndMs * 1000L

            listOf(t1, t2, t3).forEach { timeUs ->
                val rawBmp = retriever?.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (rawBmp != null) {
                    frameBitmaps.add(preparePdfSafeBitmap(rawBmp, maxDimPx = 540))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever?.release() } catch (e: Exception) {}
        }

        // Título de la Franja Visual
        textPaint.color = Color.parseColor("#1E293B")
        textPaint.textSize = 10f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("SECUENCIA CINEMÁTICA DEL LEVANTAMIENTO", 24f, 162f, textPaint)

        val frameWidth = 180f
        val frameHeight = 125f
        val frameY = 170f
        val frameLabels = listOf("1. Despegue / Inicio", "2. Máxima Aceleración (PV)", "3. Bloqueo / Recepción")

        for (i in 0 until 3) {
            val fx = 20f + i * (frameWidth + 7.5f)
            val frameRect = RectF(fx, frameY, fx + frameWidth, frameY + frameHeight)

            // Fondo oscuro para la tarjeta del fotograma
            paint.color = Color.parseColor("#0F172A")
            canvas.drawRoundRect(frameRect, 4f, 4f, paint)

            if (i < frameBitmaps.size) {
                val bmp = frameBitmaps[i]
                val bmpRatio = bmp.width.toFloat() / bmp.height.toFloat()
                val targetRatio = frameWidth / frameHeight

                val drawRect: RectF = if (bmpRatio > targetRatio) {
                    // Video horizontal: ajustar al ancho del contenedor y centrar verticalmente
                    val fittedH = frameWidth / bmpRatio
                    val yOff = (frameHeight - fittedH) / 2f
                    RectF(fx + 1f, frameY + yOff + 1f, fx + frameWidth - 1f, frameY + yOff + fittedH - 1f)
                } else {
                    // Video vertical (smartphone portrait 9:16): ajustar al alto y centrar horizontalmente con proporción natural
                    val fittedW = frameHeight * bmpRatio
                    val xOff = (frameWidth - fittedW) / 2f
                    RectF(fx + xOff, frameY + 1f, fx + xOff + fittedW, frameY + frameHeight - 1f)
                }

                val srcRect = Rect(0, 0, bmp.width, bmp.height)
                paint.isFilterBitmap = true
                // Dibujar el bitmap ARGB_8888 pre-escalado directamente (sin clipPath no rectangular)
                // para que PdfRenderer de Android renderice la página al instante sin quedar en blanco
                canvas.drawBitmap(bmp, srcRect, drawRect, paint)
            }

            // Borde exterior cian neón
            paint.color = Color.parseColor("#00FFCC")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            canvas.drawRoundRect(frameRect, 4f, 4f, paint)
            paint.style = Paint.Style.FILL

            // Etiqueta al pie de cada fotograma
            paint.color = Color.parseColor("#CC000000")
            val labelRect = RectF(fx, frameY + frameHeight - 18f, fx + frameWidth, frameY + frameHeight)
            canvas.drawRect(labelRect, paint)
            textPaint.color = Color.WHITE
            textPaint.textSize = 8.5f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText(frameLabels[i], fx + 6f, frameY + frameHeight - 5f, textPaint)
        }

        // 5. Tabla de Métricas Biomecánicas (2 Columnas x 5 Filas)
        val tableTop = 310f
        textPaint.color = Color.parseColor("#1E293B")
        textPaint.textSize = 10f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("MÉTRICAS CINEMÁTICAS & DINÁMICAS REGISTRADAS", 24f, tableTop, textPaint)

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

        val rowHeight = 36f
        val colWidth = 270f
        val startY = tableTop + 8f

        // Dibujar Columna Izquierda
        for (row in 0 until 5) {
            val ry = startY + row * (rowHeight + 4f)
            val cardR = RectF(20f, ry, 20f + colWidth, ry + rowHeight)
            paint.color = Color.parseColor("#FFFFFF")
            canvas.drawRoundRect(cardR, 5f, 5f, paint)
            paint.color = Color.parseColor("#E2E8F0")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawRoundRect(cardR, 5f, 5f, paint)
            paint.style = Paint.Style.FILL

            val (title, value, sub) = metricsLeft[row]
            textPaint.color = Color.parseColor("#475569")
            textPaint.textSize = 8.5f
            textPaint.typeface = Typeface.DEFAULT
            canvas.drawText(title, 28f, ry + 14f, textPaint)

            textPaint.color = Color.parseColor("#0F172A")
            textPaint.textSize = 10f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val valW = textPaint.measureText(value)
            canvas.drawText(value, 20f + colWidth - valW - 8f, ry + 15f, textPaint)

            textPaint.color = Color.parseColor("#94A3B8")
            textPaint.textSize = 7.5f
            textPaint.typeface = Typeface.DEFAULT
            canvas.drawText(sub, 28f, ry + 28f, textPaint)
        }

        // Dibujar Columna Derecha
        for (row in 0 until 5) {
            val ry = startY + row * (rowHeight + 4f)
            val rx = 25f + colWidth + 5f
            val cardR = RectF(rx, ry, rx + colWidth, ry + rowHeight)
            paint.color = Color.parseColor("#FFFFFF")
            canvas.drawRoundRect(cardR, 5f, 5f, paint)
            paint.color = Color.parseColor("#E2E8F0")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            canvas.drawRoundRect(cardR, 5f, 5f, paint)
            paint.style = Paint.Style.FILL

            val (title, value, sub) = metricsRight[row]
            textPaint.color = Color.parseColor("#475569")
            textPaint.textSize = 8.5f
            textPaint.typeface = Typeface.DEFAULT
            canvas.drawText(title, rx + 8f, ry + 14f, textPaint)

            textPaint.color = Color.parseColor("#00875A")
            textPaint.textSize = 10f
            textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val valW = textPaint.measureText(value)
            canvas.drawText(value, rx + colWidth - valW - 8f, ry + 15f, textPaint)

            textPaint.color = Color.parseColor("#94A3B8")
            textPaint.textSize = 7.5f
            textPaint.typeface = Typeface.DEFAULT
            canvas.drawText(sub, rx + 8f, ry + 28f, textPaint)
        }

        // 6. Tarjeta de Interpretación Biomecánica & Recomendaciones Técnicas
        val interpTop = startY + 5 * (rowHeight + 4f) + 10f
        val interpRect = RectF(20f, interpTop, 575f, 810f)
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
        textPaint.textSize = 10f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("⚡ INTERPRETACIÓN BIOMECÁNICA & RECOMENDACIONES TÉCNICAS", 34f, interpTop + 20f, textPaint)

        paint.color = Color.parseColor("#334155")
        canvas.drawLine(34f, interpTop + 27f, 561f, interpTop + 27f, paint)

        val eval = evaluateLift(
            peakVelocity = peakVelocity,
            loadKg = loadKg,
            pctOneRM = pctOneRM,
            horizontalDeviationCm = horizontalDeviationCm,
            relativeWattsPerKg = relativeWattsPerKg,
            peakForceN = peakForceN,
            workJoules = workJoules,
            timeToPeakMs = timeToPeakMs,
            rfdNPerSec = rfdNPerSec
        )

        val marginX = 34f
        val contentW = 527f
        var curY = interpTop + 41f

        // Bloque 1: Zona de Velocidad y Potencia
        textPaint.color = Color.parseColor("#00E5FF")
        textPaint.textSize = 8.5f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("• ZONA CINEMÁTICA: ${eval.velocityZoneTag}", marginX, curY, textPaint)
        curY += 12f

        textPaint.color = Color.parseColor("#E2E8F0")
        textPaint.textSize = 8f
        textPaint.typeface = Typeface.DEFAULT
        curY = drawWrappedParagraph(canvas, eval.velocityZoneDetail, marginX + 10f, curY, contentW - 10f, 10.5f, textPaint)

        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 7.5f
        val potLine = "Potencia relativa: ${String.format(Locale.US, "%.1f", relativeWattsPerKg)} W/kg  |  Fuerza pico: ${peakForceN.toInt()} N  |  Trabajo: ${workJoules.toInt()} J"
        canvas.drawText(potLine, marginX + 10f, curY + 2f, textPaint)
        curY += 15f

        // Bloque 2: Trayectoria de Barra y RFD
        textPaint.color = Color.parseColor("#FFD600")
        textPaint.textSize = 8.5f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("• CONTROL TÉCNICO: ${eval.driftTag}", marginX, curY, textPaint)
        curY += 12f

        textPaint.color = Color.parseColor("#E2E8F0")
        textPaint.textSize = 8f
        textPaint.typeface = Typeface.DEFAULT
        curY = drawWrappedParagraph(canvas, eval.driftDetail, marginX + 10f, curY, contentW - 10f, 10.5f, textPaint)

        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 7.5f
        canvas.drawText(eval.rfdDetail, marginX + 10f, curY + 2f, textPaint)
        curY += 16f

        // Bloque 3: Caja de Prescripción de Carga Práctica para la Próxima Serie
        val recBoxTop = curY
        val recBox = RectF(marginX, recBoxTop, 561f, recBoxTop + 40f)
        paint.color = Color.parseColor("#1E293B")
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(recBox, 5f, 5f, paint)
        paint.color = Color.parseColor("#00FFCC")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f
        canvas.drawRoundRect(recBox, 5f, 5f, paint)
        paint.style = Paint.Style.FILL

        textPaint.color = Color.parseColor("#00FFCC")
        textPaint.textSize = 8f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("🎯 PRESCRIPCIÓN PARA LA SIGUIENTE SERIE:", marginX + 8f, recBoxTop + 13f, textPaint)

        textPaint.color = Color.parseColor("#F8FAFC")
        textPaint.textSize = 7.8f
        textPaint.typeface = Typeface.DEFAULT
        drawWrappedParagraph(canvas, eval.loadRecommendation, marginX + 8f, recBoxTop + 24f, contentW - 16f, 10f, textPaint)

        // 7. Pie de Página
        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 8f
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("Speed Tracker v1.8 • Desarrollado para Entrenamiento Basado en la Velocidad (VBT) • Validación Científica", 24f, 825f, textPaint)
        val copyRight = "Pág 1 de 1"
        val crW = textPaint.measureText(copyRight)
        canvas.drawText(copyRight, 571f - crW, 825f, textPaint)

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
     * Abre el archivo PDF generado en el visor del sistema o lo comparte vía aplicaciones de mensajería/correo
     */
    /**
     * Convierte cualquier fotograma de video (incluyendo HARDWARE bitmaps de Android 10+)
     * a un Bitmap ARGB_8888 en software y pre-escalado a resolución óptima de impresión A4,
     * evitando que el motor PdfRenderer deje la página en blanco al abrir.
     */
    private fun preparePdfSafeBitmap(src: Bitmap, maxDimPx: Int = 540): Bitmap {
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

    /**
     * Renderiza la primera página del archivo PDF a un Bitmap ARGB_8888 de alta resolución (2x A4)
     * con fondo blanco pre-rellenado para visualización instantánea dentro de la app sin pantalla en blanco.
     */
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

    /**
     * Dibuja un párrafo con ajuste automático de línea (text wrapping) respetando márgenes estrictos
     * para que ninguna palabra se desborde del contenedor. Retorna la coordenada Y final tras el dibujo.
     */
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
        rfdNPerSec: Int?
    ): BiomechanicalEvaluation {
        val (velTag, velDetail) = when {
            peakVelocity >= 2.20 -> Pair(
                "VELOCIDAD EXPLOSIVA ALTA (VP ≥ 2.20 m/s)",
                "Excelente aceleración y transmisión de potencia neuromuscular en el segundo tirón. La barra aceleró fluidamente y sin puntos de desaceleración temprana."
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

        val loadRec = when {
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
            loadRecommendation = loadRec
        )
    }
}
