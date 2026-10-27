package com.speedtracker.core.ml

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot
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

    // Template normalizado: (T_i - mean) / std (inmutable durante todo el levantamiento para evitar transferencias)
    private var normTemplate: FloatArray? = null

    // Buffers reutilizables en memoria continua para máxima velocidad y cero pausas de GC
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
     * de búsqueda adaptativa (amplia en velocidad, ultra-estable y compacta en bloqueo/reposo).
     */
    fun trackFrame(bitmap: Bitmap): Offset {
        if (!isInitialized || normTemplate == null) {
            return currentCenter
        }

        val bW = bitmap.width
        val bH = bitmap.height
        val tpl = normTemplate!!
        val numPixels = templateW * templateH

        // 1. Predicción cinemática basada en velocidad previa con amortiguación
        val speed = hypot(currentVelocity.x.toDouble(), currentVelocity.y.toDouble()).toFloat()
        val isFast = speed > 8f

        // Centrado conservador: la ventana de búsqueda acompaña el movimiento sin adelantarse artificialmente
        val predX = currentCenter.x + (currentVelocity.x * 0.4f).coerceIn(-5f, 5f)
        val predY = currentCenter.y + (currentVelocity.y * 0.6f).coerceIn(-55f, 40f)

        // 2. Ventana de búsqueda adaptativa:
        // - En velocidad: rango vertical ágil para seguir la aceleración concéntrica
        // - En reposo/bloqueo: compacta y estable
        val searchRadiusX = if (isFast) 20 else 14
        val searchRadiusY = if (isFast) 34 else 18

        val minSearchX = (minOf(currentCenter.x, predX) - searchRadiusX).toInt().coerceIn(templateHalfW, bW - templateHalfW - 1)
        val maxSearchX = (maxOf(currentCenter.x, predX) + searchRadiusX).toInt().coerceIn(templateHalfW, bW - templateHalfW - 1)
        val minSearchY = (minOf(currentCenter.y, predY) - searchRadiusY).toInt().coerceIn(templateHalfH, bH - templateHalfH - 1)
        val maxSearchY = (maxOf(currentCenter.y, predY) + searchRadiusY).toInt().coerceIn(templateHalfH, bH - templateHalfH - 1)

        // Extraer en un único pase nativo la región rectangular completa de búsqueda (ROI)
        val roiLeft = (minSearchX - templateHalfW - 1).coerceIn(0, bW - 1)
        val roiTop = (minSearchY - templateHalfH - 1).coerceIn(0, bH - 1)
        val roiRight = (maxSearchX + templateHalfW + 1).coerceIn(0, bW - 1)
        val roiBottom = (maxSearchY + templateHalfH + 1).coerceIn(0, bH - 1)

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

        var bestX = currentCenter.x.toInt().coerceIn(minSearchX, maxSearchX)
        var bestY = currentCenter.y.toInt().coerceIn(minSearchY, maxSearchY)
        var bestScore = -1.0f

        // Búsqueda exhaustiva a resolución completa de 1 píxel (sin omitir fotogramas ni saltar el centro)
        for (cy in minSearchY..maxSearchY) {
            for (cx in minSearchX..maxSearchX) {
                val rawScore = computeZNCC(lumBuf, roiW, roiLeft, roiTop, cx, cy, tpl, numPixels)

                // Corredor vertical sin sesgo: dentro de +-5 px de currentCenter.x no hay penalización alguna.
                // Esto garantiza que el centro real del buje gane siempre por similitud visual pura.
                val hDev = (abs(cx - currentCenter.x) - 5f).coerceAtLeast(0f)
                val score = rawScore - (hDev / 20f) * 0.12f

                if (score > bestScore) {
                    bestScore = score
                    bestX = cx
                    bestY = cy
                }
            }
        }

        // 3. Validación y continuación por inercia física
        val resolvedCenter: Offset
        // Umbral de aceptación para capturar el buje en movimiento y reposo
        if (bestScore >= 0.22f) {
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

            // Detección de detención y cambio de fase en el bloqueo (lockout):
            // Si la barra desacelera hacia cero (instVy >= -0.8f) o se detiene,
            // anular la inercia ascendente para mantener la retícula en el centro absoluto del disco
            val updatedVy = if (currentVelocity.y < -2f && instVy >= -0.8f) {
                0f
            } else if (abs(instVy) < 0.6f) {
                0f
            } else {
                currentVelocity.y * 0.20f + instVy.coerceIn(-80f, 60f) * 0.80f
            }

            val updatedVx = if (abs(instVx) < 0.5f) {
                0f
            } else {
                instVx.coerceIn(-5f, 5f) * 0.70f
            }

            // Zona muerta de reposo: si el movimiento es submilimétrico (<0.6 px),
            // clavar la velocidad en cero para mantener el disco 100% inmóvil y centrado
            val movementMag = hypot(instVx.toDouble(), instVy.toDouble()).toFloat()
            currentVelocity = if (movementMag < 0.6f) {
                Offset.Zero
            } else {
                Offset(updatedVx, updatedVy)
            }

            resolvedCenter = Offset(targetX, targetY)
            lastConfidence = bestScore
            consecutiveLostFrames = 0
        } else {
            // Inercia cinemática suave si hay un frame oscuro o con desenfoque extremo
            consecutiveLostFrames++
            val inertX = (currentCenter.x + currentVelocity.x).coerceIn(templateHalfW.toFloat(), (bW - templateHalfW).toFloat())
            val inertY = (currentCenter.y + currentVelocity.y).coerceIn(templateHalfH.toFloat(), (bH - templateHalfH).toFloat())
            resolvedCenter = Offset(inertX, inertY)
            lastConfidence = 0.22f

            currentVelocity = Offset(
                currentVelocity.x * 0.90f,
                currentVelocity.y * 0.90f
            )
        }

        currentCenter = resolvedCenter
        return resolvedCenter
    }

    /**
     * Correlación cruzada normalizada a media cero (ZNCC de 2 pasadas) ejecutada sobre arrays
     * en memoria continua para máxima velocidad y total invulnerabilidad a fondos claros o sombras.
     */
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

        // Paso 1: Media exacta del parche candidato
        var sum = 0f
        for (dy in 0 until templateH) {
            val rowOffset = (startY + dy) * roiW + startX
            for (dx in 0 until templateW) {
                sum += roiLum[rowOffset + dx]
            }
        }
        val patchMean = sum / numPixels

        // Paso 2: Varianza y correlación cruzada con diferencia explícita respecto a la media
        var patchVar = 0f
        var crossSum = 0f
        var idx = 0

        for (dy in 0 until templateH) {
            val rowOffset = (startY + dy) * roiW + startX
            for (dx in 0 until templateW) {
                val lum = roiLum[rowOffset + dx]
                val diff = lum - patchMean
                patchVar += diff * diff
                crossSum += tpl[idx] * diff
                idx++
            }
        }

        val patchStd = sqrt((patchVar / numPixels).toDouble()).toFloat()
        if (patchStd < 1.0f) return 0f

        return (crossSum / (numPixels * patchStd)).coerceIn(-1.0f, 1.0f)
    }
}
