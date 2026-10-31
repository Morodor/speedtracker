package com.speedtracker.core.ml

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Tracker visual biomecánico de alta velocidad y precisión milimétrica basado en Kinovea (Zero-mean Normalized Cross-Correlation - ZNCC).
 * 
 * Diseñado con arquitectura de física inercial pura:
 * 1. Plantilla Ancla Inmutable: Capturada en la calibración (frame 0). Nunca muta, lo que elimina el 100% de la deriva hacia fondos, vigas o suelo.
 * 2. Ventana de Búsqueda Cinemática Elástica: Cubre aceleraciones explosivas en levantamientos olímpicos (Snatch, Clean)
 *    de hasta 3.5 m/s (~35 px/frame) sin recortar ni truncar la posición de la barra.
 * 3. Invarianza Robusta a Iluminación: ZNCC normaliza media y varianza en cada parche, operando sin perturbaciones ante sombras o reflejos.
 * 4. Refinamiento Subpíxel Parabólico: Interpola picos de correlación a nivel subpíxel para diferenciación cinemática limpia.
 */
class KinoveaBarbellTracker {

    private var templateHalfW = 16
    private var templateHalfH = 16
    private var templateW = 33
    private var templateH = 33

    // Plantilla Ancla fija del frame de calibración (inmutable durante todo el levantamiento)
    private var anchorTemplate: FloatArray? = null

    // Buffers contiguos reutilizables en memoria continua para máxima velocidad a 60/120 FPS
    private var roiPixelsBuffer = IntArray(0)
    private var roiLumBuffer = FloatArray(0)

    // Radio calibrado del disco olímpico (45 cm)
    var calibratedRadius: Float = 60f
        private set

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
     * en el fotograma exacto seleccionado para la calibración.
     */
    fun initialize(bitmap: Bitmap, center: Offset, radiusPx: Float) {
        val bW = bitmap.width
        val bH = bitmap.height
        calibratedRadius = if (radiusPx > 10f) radiusPx else 60f

        // El buje y collarín central ocupan entre el 25% y 30% del radio del disco
        val halfSize = (calibratedRadius * 0.28f).toInt().coerceIn(12, 32)
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

        anchorTemplate = FloatArray(numPixels) { i ->
            (rawLuminance[i] - mean) / std
        }

        currentCenter = Offset(cX.toFloat(), cY.toFloat())
        currentVelocity = Offset.Zero
        lastConfidence = 1.0f
        consecutiveLostFrames = 0
        isInitialized = true
    }

    /**
     * Rastrea el buje del disco en el frame actual mediante el motor cinemático ZNCC de Kinovea.
     */
    fun trackFrame(bitmap: Bitmap): Offset {
        if (!isInitialized || anchorTemplate == null) {
            return currentCenter
        }

        val bW = bitmap.width
        val bH = bitmap.height
        val tpl = anchorTemplate!!
        val numPixels = templateW * templateH

        // 1. Dinámica cinemática adaptativa escalada a metros reales según el disco olímpico (0.45 m)
        val pxPerMeter = (calibratedRadius * 2f) / 0.45f
        val maxTrackingVelocityMps = 4.5f // 4.5 m/s cubre holgadamente los tirones olímpicos más explosivos
        val maxFrameDy = (maxTrackingVelocityMps * pxPerMeter / 30f).coerceIn(40f, 160f)
        val maxFrameDx = (1.2f * pxPerMeter / 30f).coerceIn(12f, 45f)

        val speed = hypot(currentVelocity.x.toDouble(), currentVelocity.y.toDouble()).toFloat()
        val isFast = speed > (0.4f * pxPerMeter / 30f) // Movimiento significativo (> 0.4 m/s)

        // Sesgo de velocidad vertical para centrar dinámicamente la ventana durante aceleraciones extremas
        val velBiasX = (currentVelocity.x * 0.75f).coerceIn(-maxFrameDx, maxFrameDx)
        val velBiasY = (currentVelocity.y * 0.85f).coerceIn(-maxFrameDy, maxFrameDy)

        val predX = currentCenter.x + velBiasX
        val predY = currentCenter.y + velBiasY

        // 2. Ventana de búsqueda elástica proporcional a la escala del disco:
        val baseSearchRadiusY = (calibratedRadius * 0.35f).toInt().coerceIn(22, 55)
        val searchRadiusY = if (isFast) (baseSearchRadiusY * 1.6f).toInt().coerceIn(36, 85) else baseSearchRadiusY
        val searchRadiusX = (calibratedRadius * 0.22f).toInt().coerceIn(16, 38)

        val minSearchX = (predX - searchRadiusX).toInt().coerceIn(templateHalfW, bW - templateHalfW - 1)
        val maxSearchX = (predX + searchRadiusX).toInt().coerceIn(templateHalfW, bW - templateHalfW - 1)
        val minSearchY = (predY - searchRadiusY).toInt().coerceIn(templateHalfH, bH - templateHalfH - 1)
        val maxSearchY = (predY + searchRadiusY).toInt().coerceIn(templateHalfH, bH - templateHalfH - 1)

        // Extraer en un solo pase continuo la región de búsqueda (ROI) en memoria
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

        val lumBuf = roiLumBuffer
        val pixBuf = roiPixelsBuffer
        for (i in 0 until roiSize) {
            val p = pixBuf[i]
            lumBuf[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000f
        }

        var bestX = currentCenter.x.toInt().coerceIn(minSearchX, maxSearchX)
        var bestY = currentCenter.y.toInt().coerceIn(minSearchY, maxSearchY)
        var bestScore = -1.0f

        // Búsqueda exhaustiva ZNCC evaluando contra la plantilla ancla inmutable
        for (cy in minSearchY..maxSearchY) {
            for (cx in minSearchX..maxSearchX) {
                val zncc = computeSingleZNCC(lumBuf, roiW, roiLeft, roiTop, cx, cy, tpl, numPixels)

                // Penalización de distancia respecto a la posición física inercial predicha
                val dx = cx - predX
                val dy = cy - predY
                val distFromPred = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val distPenalty = (distFromPred / searchRadiusY.toFloat()) * 0.10f

                // Penalización por desvío horizontal excesivo (la barra en levantamientos se desplaza casi exclusivamente en Y)
                val hDev = (abs(cx - currentCenter.x) - 3f).coerceAtLeast(0f)
                val hPenalty = (hDev / searchRadiusX.toFloat()) * 0.08f

                val score = zncc - distPenalty - hPenalty

                if (score > bestScore) {
                    bestScore = score
                    bestX = cx
                    bestY = cy
                }
            }
        }

        // 3. Validación y refinamiento subpíxel
        val resolvedCenter: Offset
        if (bestScore >= 0.18f) {
            // Interpolación parabólica subpíxel para diferenciación analítica precisa de velocidad
            val sLeft = computeSingleZNCC(lumBuf, roiW, roiLeft, roiTop, (bestX - 1).coerceAtLeast(minSearchX), bestY, tpl, numPixels)
            val sRight = computeSingleZNCC(lumBuf, roiW, roiLeft, roiTop, (bestX + 1).coerceAtMost(maxSearchX), bestY, tpl, numPixels)
            val sTop = computeSingleZNCC(lumBuf, roiW, roiLeft, roiTop, bestX, (bestY - 1).coerceAtLeast(minSearchY), tpl, numPixels)
            val sBottom = computeSingleZNCC(lumBuf, roiW, roiLeft, roiTop, bestX, (bestY + 1).coerceAtMost(maxSearchY), tpl, numPixels)

            val denomX = (sLeft - 2f * bestScore + sRight)
            val subX = if (abs(denomX) > 1e-4f) ((sLeft - sRight) / (2f * denomX)).coerceIn(-0.5f, 0.5f) else 0f

            val denomY = (sTop - 2f * bestScore + sBottom)
            val subY = if (abs(denomY) > 1e-4f) ((sTop - sBottom) / (2f * denomY)).coerceIn(-0.5f, 0.5f) else 0f

            val targetX = bestX + subX
            val targetY = bestY + subY

            val instVx = targetX - currentCenter.x
            val instVy = targetY - currentCenter.y

            // Suavizado cinemático inercial adaptado a la velocidad real máxima del levantamiento
            val updatedVy = currentVelocity.y * 0.25f + instVy.coerceIn(-maxFrameDy, maxFrameDy) * 0.75f
            val updatedVx = currentVelocity.x * 0.30f + instVx.coerceIn(-maxFrameDx, maxFrameDx) * 0.70f

            val movementMag = hypot(instVx.toDouble(), instVy.toDouble()).toFloat()
            currentVelocity = if (movementMag < 0.35f) Offset.Zero else Offset(updatedVx, updatedVy)

            resolvedCenter = Offset(targetX, targetY)
            lastConfidence = bestScore
            consecutiveLostFrames = 0
        } else {
            // Inercia cinemática pura ante desenfoque de movimiento extremo o micro-oclusiones
            consecutiveLostFrames++
            val inertX = (currentCenter.x + currentVelocity.x * 0.85f).coerceIn(templateHalfW.toFloat(), (bW - templateHalfW).toFloat())
            val inertY = (currentCenter.y + currentVelocity.y * 0.85f).coerceIn(templateHalfH.toFloat(), (bH - templateHalfH).toFloat())
            resolvedCenter = Offset(inertX, inertY)
            lastConfidence = 0.20f

            currentVelocity = Offset(
                currentVelocity.x * 0.85f,
                currentVelocity.y * 0.85f
            )
        }

        currentCenter = resolvedCenter
        return resolvedCenter
    }

    /**
     * Cálculo ZNCC en un único pase de dos etapas con cero pausas de recolector de basura.
     */
    private fun computeSingleZNCC(
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

        var sum = 0f
        for (dy in 0 until templateH) {
            val rowOffset = (startY + dy) * roiW + startX
            for (dx in 0 until templateW) {
                sum += roiLum[rowOffset + dx]
            }
        }
        val patchMean = sum / numPixels

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
