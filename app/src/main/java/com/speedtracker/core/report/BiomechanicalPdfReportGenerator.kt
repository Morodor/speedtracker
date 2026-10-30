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
                val b = retriever?.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (b != null) frameBitmaps.add(b)
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

        val frameWidth = 175f
        val frameHeight = 125f
        val frameY = 170f
        val frameLabels = listOf("1. Despegue / Inicio", "2. Máxima Aceleración (PV)", "3. Bloqueo / Recepción")

        for (i in 0 until 3) {
            val fx = 20f + i * (frameWidth + 5f)
            val frameRect = RectF(fx, frameY, fx + frameWidth, frameY + frameHeight)

            if (i < frameBitmaps.size) {
                val bmp = frameBitmaps[i]
                // Dibujar con crop centrado proporcional
                val srcRect = Rect(0, 0, bmp.width, bmp.height)
                canvas.drawBitmap(bmp, srcRect, frameRect, paint)
            } else {
                paint.color = Color.parseColor("#0F172A")
                canvas.drawRoundRect(frameRect, 4f, 4f, paint)
            }

            // Borde exterior
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
        val interpTop = startY + 5 * (rowHeight + 4f) + 12f
        val interpRect = RectF(20f, interpTop, 575f, interpTop + 160f)
        paint.color = Color.parseColor("#0F172A")
        canvas.drawRoundRect(interpRect, 8f, 8f, paint)

        // Título de la tarjeta
        textPaint.color = Color.parseColor("#00FFCC")
        textPaint.textSize = 10.5f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("INTERPRETACIÓN BIOMECÁNICA & RECOMENDACIONES", 34f, interpTop + 24f, textPaint)

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

        textPaint.color = Color.parseColor("#F1F5F9")
        textPaint.textSize = 8.5f
        textPaint.typeface = Typeface.DEFAULT

        // Dibujar líneas de texto formateadas
        canvas.drawText("• ${eval.velocityZoneTag}: ${eval.velocityZoneDetail}", 34f, interpTop + 50f, textPaint)
        canvas.drawText("  Potencia relativa: ${String.format(Locale.US, "%.1f", relativeWattsPerKg)} W/kg | Fuerza pico: ${peakForceN.toInt()} N | Trabajo: ${workJoules.toInt()} J.", 34f, interpTop + 66f, textPaint)

        canvas.drawText("• ${eval.driftTag}: ${eval.driftDetail}", 34f, interpTop + 90f, textPaint)
        canvas.drawText("  ${eval.rfdDetail}", 34f, interpTop + 106f, textPaint)

        canvas.drawText(eval.loadRecommendation, 34f, interpTop + 132f, textPaint)

        // 7. Pie de Página
        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 8f
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("Speed Tracker v1.5 • Desarrollado para Entrenamiento Basado en la Velocidad (VBT) • Validación Científica", 24f, 825f, textPaint)
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

        context.startActivity(Intent.createChooser(intent, "Abrir o Compartir Informe PDF").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
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
                "ZONA DE VELOCIDAD EXPLOSIVA",
                "Excelente transmisión de potencia neuromuscular en el segundo tirón. La barra aceleró libremente sin puntos de estancamiento."
            )
            peakVelocity >= 1.70 -> Pair(
                "ZONA DE FUERZA-POTENCIA",
                "Velocidad de desplazamiento sólida y controlada para ${loadKg.toInt()} kg (~${pctOneRM.toInt()}% 1RM). Gran sincronización concéntrica."
            )
            else -> Pair(
                "ZONA DE FUERZA MÁXIMA / LÍMITE",
                "Carga pesada con velocidad desacelerada. Demanda de reclutamiento neuromuscular muy alta."
            )
        }

        val drift = horizontalDeviationCm ?: 0.0
        val (driftTag, driftDetail) = when {
            drift <= 10.0 -> Pair(
                "TRAYECTORIA VERTICAL ÓPTIMA (${String.format(Locale.US, "%.1f", drift)} cm)",
                "Desvío horizontal mínimo. La barra se mantuvo pegada al eje vertical de gravedad, optimizando el trabajo mecánico."
            )
            drift <= 18.0 -> Pair(
                "TRAYECTORIA CONTROLADA (${String.format(Locale.US, "%.1f", drift)} cm)",
                "Desvío moderado hacia adelante. Se sugiere concentrarse en codos altos y contacto cercano al torso en el tirón."
            )
            else -> Pair(
                "DESVÍO HACIA ADELANTE DETECTADO (${String.format(Locale.US, "%.1f", drift)} cm)",
                "La barra se despegó del cuerpo en el tirón. Trabajar en extensiones verticales sin proyectar la cadera hacia adelante."
            )
        }

        val rfdStr = "Tiempo a Vel. Pico: ${timeToPeakMs ?: "--"} ms | RFD: ${rfdNPerSec ?: "--"} N/s | Potencia: ${String.format(Locale.US, "%.1f", relativeWattsPerKg)} W/kg"

        val loadRec = when {
            pctOneRM < 70.0 && peakVelocity > 2.0 -> "• RECOMENDACIÓN: Puedes incrementar +2.5 kg a +5.0 kg para la siguiente serie manteniendo el foco en aceleración explosiva."
            pctOneRM in 70.0..85.0 -> "• RECOMENDACIÓN: Carga ideal para adaptaciones de potencia máxima. Mantén el peso en las próximas series."
            else -> "• RECOMENDACIÓN: Mantener o reducir levemente el volumen para evitar la degradación técnica por fatiga neuromuscular acumulada."
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
