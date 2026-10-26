package com.speedtracker.core.ml

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tracker visual de alta velocidad basado en el algoritmo de correlación cruzada normalizada
 * de Kinovea (Zero-mean Normalized Cross-Correlation - ZNCC).
 * 
 * Rastrea con precisión milimétrica y subpíxel el centro del disco olímpico (buje central y collarín)
 * a lo largo de todo el levantamiento (suelo, primer tirón, segundo tirón explosivo, turnover y bloqueo).
 */
class KinoveaBarbellTracker {

    private var templateHalfW = 16
    private var templateHalfH = 16
    private var templateW = 33
    private var templateH = 33

    // Template normalizado: (T_i - mean) / std
    private var normTemplate: FloatArray? = null

    // Buffers reutilizables para evitar asignaciones de memoria y pausas de GC
    private var roiPixelsBuffer = IntArray(0)
    private var roiLumBuffer = FloatArray(0)

    // Estado cinemático del disco
    var currentCenter: Offset = Offset.Zero
        private set
    var currentVelocity: Offset = Offset.Zero
        private set
    var lastConfidence: Float = 1.0f
        private set
    private var consecutiveLostFrames = 0

    var isInitialized = false
        private set

    /**
     * Inicializa el tracker capturando la firma visual del centro del disco olímpico (buje y collarín)
     * en el frame inicial de referencia.
     */
    fun initialize(bitmap: Bitmap, center: Offset, radiusPx: Float) {
        val bW = bitmap.width
        val bH = bitmap.height

        // El buje y collarín ocupan aproximadamente el 28% del radio del disco
        val halfSize = (radiusPx * 0.28f).toInt().coerceIn(12, 32)
        templateHalfW = halfSize
        templateHalfH = halfSize
        templateW = halfSize * 2 + 1
        templateH = halfSize * 2 + 1

        val numPixels = templateW * templateH
        val rawLuminance = FloatArray(numPixels)

        val cX = center.x.toInt().coerceIn(templateHalfW, bW - templateHalfW - 1)
        val cY = center.y.toInt().coerceIn(templateHalfH, bH - templateHalfH - 1)

        val initLeft = cX - templateHalfW
        val initTop = cY - templateHalfH
        val initPixels = IntArray(numPixels)
        bitmap.getPixels(initPixels, 0, templateW, initLeft, initTop, templateW, templateH)

        var sum = 0f
        for (i in 0 until numPixels) {
            val p = initPixels[i]
            val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000f
            rawLuminance[i] = lum
            sum += lum
        }

        val mean = sum / numPixels
        var varSum = 0f
        for (i in 0 until numPixels) {
            val diff = rawLuminance[i] - mean
            varSum += diff * diff
        }

        val std = sqrt((varSum / numPixels).toDouble()).toFloat().coerceAtLeast(1.0f)

        normTemplate = FloatArray(numPixels) { i ->
            (rawLuminance[i] - mean) / std
        }

        currentCenter = Offset(cX.toFloat(), cY.toFloat())
        currentVelocity = Offset.Zero
        lastConfidence = 1.0f
        consecutiveLostFrames = 0
        isInitialized = true
    }

    /**
     * Rastrea el disco en el frame actual mediante coincidencia de plantilla ZNCC con ventana
     * de búsqueda guiada por inercia física (Kinematic Velocity Prediction) y límites biomecánicos estrictos.
     */
    fun trackFrame(bitmap: Bitmap): Offset {
        if (!isInitialized || normTemplate == null) {
            return currentCenter
        }

        val bW = bitmap.width
        val bH = bitmap.height
        val tpl = normTemplate!!
        val numPixels = templateW * templateH

        // 1. Predicción cinemática de la barra en movimiento
        val anchorX = currentCenter.x
        val anchorY = currentCenter.y

        // En un snatch de halterofilia explosivo (>2.5 m/s a 30 fps), la velocidad vertical
        // alcanza entre 40 y 90 píxeles por fotograma
        val predX = anchorX + currentVelocity.x.coerceIn(-45f, 45f)
        val predY = anchorY + currentVelocity.y.coerceIn(-110f, 85f)

        // 2. Ventana de búsqueda adaptada dinámicamente según la aceleración vertical
        // Con amplio alcance vertical hacia arriba (hasta 95 px) para no quedarse atrás en tirones explosivos
        val isFastMovement = abs(currentVelocity.y) > 10f
        val searchRadiusX = 36
        val searchRadiusYUp = if (isFastMovement) 95 else 70
        val searchRadiusYDown = if (isFastMovement) 70 else 50

        val minSearchX = (minOf(anchorX, predX) - searchRadiusX).toInt().coerceIn(templateHalfW, bW - templateHalfW - 1)
        val maxSearchX = (maxOf(anchorX, predX) + searchRadiusX).toInt().coerceIn(templateHalfW, bW - templateHalfW - 1)
        val minSearchY = (minOf(anchorY, predY) - searchRadiusYUp).toInt().coerceIn(templateHalfH, bH - templateHalfH - 1)
        val maxSearchY = (maxOf(anchorY, predY) + searchRadiusYDown).toInt().coerceIn(templateHalfH, bH - templateHalfH - 1)

        // Extraer en un único pase nativo la región rectangular completa de búsqueda (ROI)
        val roiLeft = (minSearchX - templateHalfW - 2).coerceIn(0, bW - 1)
        val roiTop = (minSearchY - templateHalfH - 2).coerceIn(0, bH - 1)
        val roiRight = (maxSearchX + templateHalfW + 2).coerceIn(0, bW - 1)
        val roiBottom = (maxSearchY + templateHalfH + 2).coerceIn(0, bH - 1)

        val roiW = roiRight - roiLeft + 1
        val roiH = roiBottom - roiTop + 1
        val roiSize = roiW * roiH

        if (roiPixelsBuffer.size < roiSize) {
            roiPixelsBuffer = IntArray(roiSize)
            roiLumBuffer = FloatArray(roiSize)
        }

        bitmap.getPixels(roiPixelsBuffer, 0, roiW, roiLeft, roiTop, roiW, roiH)

        // Vectorizar conversión de luminancia en array contiguo
        val lumBuf = roiLumBuffer
        val pixBuf = roiPixelsBuffer
        for (i in 0 until roiSize) {
            val p = pixBuf[i]
            lumBuf[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000f
        }

        var bestX = predX.toInt().coerceIn(minSearchX, maxSearchX)
        var bestY = predY.toInt().coerceIn(minSearchY, maxSearchY)
        var bestScore = -1.0f

        // Búsqueda gruesa con paso de 2 píxeles
        for (cy in minSearchY..maxSearchY step 2) {
            for (cx in minSearchX..maxSearchX step 2) {
                val score = computeZNCC(lumBuf, roiW, roiLeft, roiTop, cx, cy, tpl, numPixels)
                if (score > bestScore) {
                    bestScore = score
                    bestX = cx
                    bestY = cy
                }
            }
        }

        // Búsqueda fina local (paso 1 píxel) alrededor del mejor candidato
        val fineMinX = (bestX - 2).coerceIn(minSearchX, maxSearchX)
        val fineMaxX = (bestX + 2).coerceIn(minSearchX, maxSearchX)
        val fineMinY = (bestY - 2).coerceIn(minSearchY, maxSearchY)
        val fineMaxY = (bestY + 2).coerceIn(minSearchY, maxSearchY)

        for (cy in fineMinY..fineMaxY) {
            for (cx in fineMinX..fineMaxX) {
                val score = computeZNCC(lumBuf, roiW, roiLeft, roiTop, cx, cy, tpl, numPixels)
                if (score > bestScore) {
                    bestScore = score
                    bestX = cx
                    bestY = cy
                }
            }
        }

        // 3. Validación y continuación física con amortiguación
        val resolvedCenter: Offset
        // Umbral adaptado (0.30f) para aceptar correlación bajo motion blur extremo a alta velocidad (>2.5 m/s)
        if (bestScore >= 0.30f) {
            // Refinamiento subpíxel parabólico
            val sLeft = computeZNCC(lumBuf, roiW, roiLeft, roiTop, (bestX - 1).coerceAtLeast(minSearchX), bestY, tpl, numPixels)
            val sRight = computeZNCC(lumBuf, roiW, roiLeft, roiTop, (bestX + 1).coerceAtMost(maxSearchX), bestY, tpl, numPixels)
            val sTop = computeZNCC(lumBuf, roiW, roiLeft, roiTop, bestX, (bestY - 1).coerceAtLeast(minSearchY), tpl, numPixels)
            val sBottom = computeZNCC(lumBuf, roiW, roiLeft, roiTop, bestX, (bestY + 1).coerceAtMost(maxSearchY), tpl, numPixels)

            val denomX = (sLeft - 2f * bestScore + sRight)
            val subX = if (abs(denomX) > 1e-4f) ((sLeft - sRight) / (2f * denomX)).coerceIn(-0.5f, 0.5f) else 0f

            val denomY = (sTop - 2f * bestScore + sBottom)
            val subY = if (abs(denomY) > 1e-4f) ((sTop - sBottom) / (2f * denomY)).coerceIn(-0.5f, 0.5f) else 0f

            val targetX = bestX + subX
            val targetY = bestY + subY

            val instVx = targetX - currentCenter.x
            val instVy = targetY - currentCenter.y

            // Filtro de velocidad inercial con respuesta instantánea a picos de aceleración
            currentVelocity = Offset(
                currentVelocity.x * 0.10f + instVx * 0.90f,
                currentVelocity.y * 0.10f + instVy * 0.90f
            )

            resolvedCenter = Offset(targetX, targetY)
            lastConfidence = bestScore
            consecutiveLostFrames = 0
        } else {
            // PÉRDIDA TEMPORAL: Amortiguar gradualmente (evita saltos bruscos pero frena tras 3 frames)
            consecutiveLostFrames++
            currentVelocity = Offset(
                currentVelocity.x * 0.70f,
                currentVelocity.y * 0.70f
            )
            if (consecutiveLostFrames >= 3) {
                currentVelocity = Offset.Zero
            }

            val inertX = (currentCenter.x + currentVelocity.x).coerceIn(templateHalfW.toFloat(), (bW - templateHalfW).toFloat())
            val inertY = (currentCenter.y + currentVelocity.y).coerceIn(templateHalfH.toFloat(), (bH - templateHalfH).toFloat())
            resolvedCenter = Offset(inertX, inertY)
            lastConfidence = 0.25f
        }

        currentCenter = resolvedCenter
        return resolvedCenter
    }

    private fun computeZNCC(
        roiLum: FloatArray,
        roiW: Int,
        roiLeft: Int,
        roiTop: Int,
        cx: Int,
        cy: Int,
        tpl: FloatArray,
        numPixels: Int
    ): Float {
        val startX = cx - templateHalfW - roiLeft
        val startY = cy - templateHalfH - roiTop

        var sumP = 0f
        var sumSqP = 0f
        var cross = 0f
        var idx = 0

        for (dy in 0 until templateH) {
            val rowOffset = (startY + dy) * roiW + startX
            for (dx in 0 until templateW) {
                val p = roiLum[rowOffset + dx]
                sumP += p
                sumSqP += p * p
                cross += tpl[idx] * p
                idx++
            }
        }

        val meanP = sumP / numPixels
        val varP = (sumSqP / numPixels) - (meanP * meanP)
        if (varP < 1.0f) return 0f
        val stdP = sqrt(varP.toDouble()).toFloat()

        return (cross / (numPixels * stdP)).coerceIn(-1.0f, 1.0f)
    }
}
