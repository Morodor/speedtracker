package com.speedtracker.core.ml

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Tracker visual biomecánico de alta velocidad basado en Kinovea y Filtros de Correlación Adaptativa (Dual-Template DCF).
 * 
 * Diseñado específicamente para levantamientos olímpicos (Snatch, Clean & Jerk) y ejercicios VBT:
 * 1. Plantilla Ancla (Anchor Template): Inmutable desde el frame 0, anclada al buje real del disco.
 * 2. Plantilla Adaptativa (Adaptive Template): Aprende a una tasa suave (alpha = 0.03) para absorber
 *    el giro de las mangas sobre sus rodamientos, cambios de luz y desenfoque por velocidad extrema (motion blur).
 * 3. Ventana Elástica: Cubre aceleraciones explosivas de hasta 3.5 m/s sin perder la barra en el segundo tirón.
 * 4. Corrección Geométrica Radial por Rayos: Impide físicamente que la retícula se desplace a la punta superior
 *    de la barra o a vigas del techo en el bloqueo cenital overhead.
 */
class KinoveaBarbellTracker {

    private var templateHalfW = 16
    private var templateHalfH = 16
    private var templateW = 33
    private var templateH = 33

    // Plantilla Ancla fija del frame 0 (nunca muta; ancla inmutable al buje real)
    private var anchorTemplate: FloatArray? = null

    // Plantilla Adaptativa dinámica (absorbe rotación de manga, brillos y desenfoque suave)
    private var adaptiveTemplate: FloatArray? = null

    // Buffers contiguos reutilizables en memoria continua para máxima velocidad a 60/120 FPS
    private var roiPixelsBuffer = IntArray(0)
    private var roiLumBuffer = FloatArray(0)

    // Radio calibrado del disco olímpico (45 cm)
    var calibratedRadius: Float = 60f
        private set

    // Firma cromática del cuerpo del disco olímpico
    private var isChromaticPlate: Boolean = false
    private var targetHue: Float = 0f
    private var targetSat: Float = 0f
    private var targetNr: Float = 0.33f
    private var targetNg: Float = 0.33f
    private var targetNb: Float = 0.33f

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
     * y la firma de color del cuerpo del disco en el fotograma exacto de calibración.
     */
    fun initialize(bitmap: Bitmap, center: Offset, radiusPx: Float) {
        val bW = bitmap.width
        val bH = bitmap.height
        calibratedRadius = if (radiusPx > 10f) radiusPx else 60f

        // El buje y collarín ocupan aproximadamente el 28% del radio del disco
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

        val initialNorm = FloatArray(numPixels) { i ->
            (rawLuminance[i] - mean) / std
        }

        anchorTemplate = initialNorm.clone()
        adaptiveTemplate = initialNorm.clone()

        // Muestrear la firma cromática del cuerpo del disco en el anillo exterior (al 65% del radio)
        val sampleR = calibratedRadius * 0.65f
        val hsv = FloatArray(3)
        var sumHue = 0f
        var sumSat = 0f
        var sumNr = 0f
        var sumNg = 0f
        var sumNb = 0f
        var validColorSamples = 0

        val numSamples = 24
        for (i in 0 until numSamples) {
            val angle = (i.toFloat() / numSamples.toFloat()) * (2f * Math.PI.toFloat())
            val sx = (center.x + sampleR * kotlin.math.cos(angle)).toInt()
            val sy = (center.y + sampleR * kotlin.math.sin(angle)).toInt()
            if (sx in 0 until bW && sy in 0 until bH) {
                val p = bitmap.getPixel(sx, sy)
                Color.colorToHSV(p, hsv)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val sumRgb = (r + g + b + 1).toFloat()
                sumNr += r / sumRgb
                sumNg += g / sumRgb
                sumNb += b / sumRgb
                sumHue += hsv[0]
                sumSat += hsv[1]
                validColorSamples++
            }
        }

        if (validColorSamples > 0) {
            targetHue = sumHue / validColorSamples
            targetSat = sumSat / validColorSamples
            targetNr = sumNr / validColorSamples
            targetNg = sumNg / validColorSamples
            targetNb = sumNb / validColorSamples
            isChromaticPlate = targetSat > 0.15f
        }

        currentCenter = Offset(cX.toFloat(), cY.toFloat())
        currentVelocity = Offset.Zero
        lastConfidence = 1.0f
        consecutiveLostFrames = 0
        isInitialized = true
    }

    /**
     * Rastrea el disco en el frame actual mediante el motor híbrido Dual-Template con recentrado geométrico radial.
     */
    fun trackFrame(bitmap: Bitmap): Offset {
        if (!isInitialized || anchorTemplate == null || adaptiveTemplate == null) {
            return currentCenter
        }

        val bW = bitmap.width
        val bH = bitmap.height
        val anchorTpl = anchorTemplate!!
        val adaptiveTpl = adaptiveTemplate!!
        val numPixels = templateW * templateH

        // 1. Cinemática predictiva acotada físicamente
        val speed = hypot(currentVelocity.x.toDouble(), currentVelocity.y.toDouble()).toFloat()
        val isFast = speed > 4f

        // Sesgo de velocidad vertical y horizontal para centrar la ventana elástica
        val velBiasX = (currentVelocity.x * 0.40f).coerceIn(-6f, 6f)
        val velBiasY = (currentVelocity.y * 0.55f).coerceIn(-24f, 18f)

        val predX = currentCenter.x + velBiasX
        val predY = currentCenter.y + velBiasY

        // 2. Ventana de búsqueda elástica:
        // Aceleración explosiva de hasta 3.5 m/s (~32 px/frame) cubierta de forma segura en Y
        val searchRadiusX = if (isFast) 22 else 15
        val searchRadiusY = if (isFast) 36 else 24

        val minSearchX = (currentCenter.x + velBiasX - searchRadiusX).toInt().coerceIn(templateHalfW, bW - templateHalfW - 1)
        val maxSearchX = (currentCenter.x + velBiasX + searchRadiusX).toInt().coerceIn(templateHalfW, bW - templateHalfW - 1)
        val minSearchY = (currentCenter.y + velBiasY - searchRadiusY).toInt().coerceIn(templateHalfH, bH - templateHalfH - 1)
        val maxSearchY = (currentCenter.y + velBiasY + searchRadiusY).toInt().coerceIn(templateHalfH, bH - templateHalfH - 1)

        // Extraer en memoria continua la región de búsqueda (ROI)
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

        // Búsqueda exhaustiva con evaluación simultánea Dual-Template (Anchor 75% + Adaptive 25%)
        for (cy in minSearchY..maxSearchY) {
            for (cx in minSearchX..maxSearchX) {
                val (scoreAdaptive, scoreAnchor) = computeDualZNCC(
                    lumBuf, roiW, roiLeft, roiTop, cx, cy, adaptiveTpl, anchorTpl, numPixels
                )

                // Ponderación de máxima fidelidad: 75% Ancla inmutable del buje + 25% Adaptativa suave
                val rawScore = scoreAnchor * 0.75f + scoreAdaptive * 0.25f

                val dx = cx - predX
                val dy = cy - predY
                val distFromPred = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val distPenalty = (distFromPred / 35f) * 0.10f

                val hDev = (abs(cx - currentCenter.x) - 4f).coerceAtLeast(0f)
                val score = rawScore - distPenalty - (hDev / 16f) * 0.08f

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
            val sLeft = computeSingleZNCC(lumBuf, roiW, roiLeft, roiTop, (bestX - 1).coerceAtLeast(minSearchX), bestY, anchorTpl, numPixels)
            val sRight = computeSingleZNCC(lumBuf, roiW, roiLeft, roiTop, (bestX + 1).coerceAtMost(maxSearchX), bestY, anchorTpl, numPixels)
            val sTop = computeSingleZNCC(lumBuf, roiW, roiLeft, roiTop, bestX, (bestY - 1).coerceAtLeast(minSearchY), anchorTpl, numPixels)
            val sBottom = computeSingleZNCC(lumBuf, roiW, roiLeft, roiTop, bestX, (bestY + 1).coerceAtMost(maxSearchY), anchorTpl, numPixels)

            val denomX = (sLeft - 2f * bestScore + sRight)
            val subX = if (abs(denomX) > 1e-4f) ((sLeft - sRight) / (2f * denomX)).coerceIn(-0.5f, 0.5f) else 0f

            val denomY = (sTop - 2f * bestScore + sBottom)
            val subY = if (abs(denomY) > 1e-4f) ((sTop - sBottom) / (2f * denomY)).coerceIn(-0.5f, 0.5f) else 0f

            var targetX = bestX + subX
            var targetY = bestY + subY

            // 4. Verificación y recentrado geométrico radial:
            // Impide categóricamente que el tracker salte a la punta superior de la barra o a las vigas del techo
            val geomCenter = findDiscGeometricCenter(bitmap, targetX, targetY)
            if (geomCenter != null) {
                val distY = abs(geomCenter.y - targetY)
                // Si la desviación vertical supera el 18% del radio, corregir fuertemente al centro geométrico real
                targetY = if (distY > calibratedRadius * 0.18f) {
                    geomCenter.y
                } else {
                    targetY * 0.40f + geomCenter.y * 0.60f
                }

                if (abs(geomCenter.x - targetX) > 2f) {
                    targetX = targetX * 0.50f + geomCenter.x * 0.50f
                }
            }

            // 5. Actualización conservadora de la plantilla adaptativa solo en alta certeza
            if (bestScore >= 0.60f) {
                updateAdaptiveTemplate(lumBuf, roiW, roiLeft, roiTop, bestX, bestY, adaptiveTpl, numPixels)
            }

            val instVx = targetX - currentCenter.x
            val instVy = targetY - currentCenter.y

            // Suavizado inercial de velocidad física
            val updatedVy = currentVelocity.y * 0.20f + instVy.coerceIn(-32f, 28f) * 0.80f
            val updatedVx = currentVelocity.x * 0.25f + instVx.coerceIn(-8f, 8f) * 0.75f

            val movementMag = hypot(instVx.toDouble(), instVy.toDouble()).toFloat()
            currentVelocity = if (movementMag < 0.4f) Offset.Zero else Offset(updatedVx, updatedVy)

            resolvedCenter = Offset(targetX, targetY)
            lastConfidence = bestScore
            consecutiveLostFrames = 0
        } else {
            // Inercia cinemática ante desenfoque oclusivo transitorio
            consecutiveLostFrames++
            val inertX = (currentCenter.x + currentVelocity.x).coerceIn(templateHalfW.toFloat(), (bW - templateHalfW).toFloat())
            val inertY = (currentCenter.y + currentVelocity.y).coerceIn(templateHalfH.toFloat(), (bH - templateHalfH).toFloat())
            resolvedCenter = Offset(inertX, inertY)
            lastConfidence = 0.20f

            currentVelocity = Offset(
                currentVelocity.x * 0.80f,
                currentVelocity.y * 0.80f
            )
        }

        currentCenter = resolvedCenter
        return resolvedCenter
    }

    /**
     * Rastrea mediante rayos ortogonales los bordes físicos superior, inferior, izquierdo y derecho
     * del disco olímpico a partir de una posición candidata.
     * Calcula el centro geométrico real (yTop + yBottom)/2 y (xLeft + xRight)/2, impidiendo
     * que la retícula se desplace hacia la punta superior de la barra o hacia el borde del disco.
     */
    fun findDiscGeometricCenter(bitmap: Bitmap, startX: Float, startY: Float): Offset? {
        val bW = bitmap.width
        val bH = bitmap.height
        val r = calibratedRadius

        val cx = startX.toInt().coerceIn(0, bW - 1)
        val cy = startY.toInt().coerceIn(0, bH - 1)

        val hubClearance = (r * 0.20f).toInt().coerceAtLeast(4)
        val maxExtent = (r * 1.55f).toInt()

        // 1. Rayo hacia arriba (buscar borde superior del disco)
        var yTop = -1
        var nonPlateUp = 0
        for (step in hubClearance..maxExtent) {
            val ty = cy - step
            if (ty < 0) { yTop = 0; break }
            val p = bitmap.getPixel(cx, ty)
            if (!isPlateColor(p)) {
                nonPlateUp++
                if (nonPlateUp >= 2) {
                    yTop = ty + 1
                    break
                }
            } else {
                nonPlateUp = 0
            }
        }

        // 2. Rayo hacia abajo (buscar borde inferior del disco)
        var yBottom = -1
        var nonPlateDown = 0
        for (step in hubClearance..maxExtent) {
            val by = cy + step
            if (by >= bH) { yBottom = bH - 1; break }
            val p = bitmap.getPixel(cx, by)
            if (!isPlateColor(p)) {
                nonPlateDown++
                if (nonPlateDown >= 2) {
                    yBottom = by - 1
                    break
                }
            } else {
                nonPlateDown = 0
            }
        }

        // 3. Rayo hacia la izquierda
        var xLeft = -1
        var nonPlateLeft = 0
        for (step in hubClearance..maxExtent) {
            val lx = cx - step
            if (lx < 0) { xLeft = 0; break }
            val p = bitmap.getPixel(lx, cy)
            if (!isPlateColor(p)) {
                nonPlateLeft++
                if (nonPlateLeft >= 2) {
                    xLeft = lx + 1
                    break
                }
            } else {
                nonPlateLeft = 0
            }
        }

        // 4. Rayo hacia la derecha
        var xRight = -1
        var nonPlateRight = 0
        for (step in hubClearance..maxExtent) {
            val rx = cx + step
            if (rx >= bW) { xRight = bW - 1; break }
            val p = bitmap.getPixel(rx, cy)
            if (!isPlateColor(p)) {
                nonPlateRight++
                if (nonPlateRight >= 2) {
                    xRight = rx - 1
                    break
                }
            } else {
                nonPlateRight = 0
            }
        }

        // Caso A: Ambos bordes verticales encontrados con diámetro coherente (45 cm)
        if (yTop != -1 && yBottom != -1) {
            val vertDiameter = yBottom - yTop
            if (vertDiameter in (r * 1.30f).toInt()..(r * 2.40f).toInt()) {
                val geomY = (yTop + yBottom) / 2f
                val geomX = if (xLeft != -1 && xRight != -1 && (xRight - xLeft) in (r * 1.30f).toInt()..(r * 2.40f).toInt()) {
                    (xLeft + xRight) / 2f
                } else {
                    startX
                }
                return Offset(geomX, geomY)
            }
        }

        // Caso B: Borde superior detectado a una distancia sospechosamente corta (candidato en la parte alta del disco)
        if (yTop != -1) {
            val distUp = cy - yTop
            if (distUp < r * 0.70f) {
                val geomY = yTop + r
                val geomX = if (xLeft != -1 && xRight != -1 && (xRight - xLeft) in (r * 1.30f).toInt()..(r * 2.40f).toInt()) {
                    (xLeft + xRight) / 2f
                } else {
                    startX
                }
                return Offset(geomX, geomY)
            }
        }

        // Caso C: Ambos bordes horizontales detectados con coherencia
        if (xLeft != -1 && xRight != -1) {
            val horizDiameter = xRight - xLeft
            if (horizDiameter in (r * 1.30f).toInt()..(r * 2.40f).toInt()) {
                return Offset((xLeft + xRight) / 2f, startY)
            }
        }

        return null
    }

    /**
     * Comprueba si un píxel coincide con la firma cromática o de brillo del disco calibrado.
     */
    fun isPlateColor(p: Int): Boolean {
        if (isChromaticPlate) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val sumRgb = (r + g + b + 1).toFloat()
            val nr = r / sumRgb
            val ng = g / sumRgb
            val nb = b / sumRgb
            val dNr = nr - targetNr
            val dNg = ng - targetNg
            val dNb = nb - targetNb
            val distChroma = sqrt((dNr * dNr + dNg * dNg + dNb * dNb).toDouble()).toFloat()

            val hsv = FloatArray(3)
            Color.colorToHSV(p, hsv)
            val diffHue = abs(hsv[0] - targetHue).let { minOf(it, 360f - it) }

            return (distChroma < 0.22f) || (diffHue < 50f && hsv[1] > (targetSat * 0.30f))
        } else {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = ((r * 299 + g * 587 + b * 114) / 1000f)
            val hsv = FloatArray(3)
            Color.colorToHSV(p, hsv)
            return lum < 100f && hsv[1] < 0.45f
        }
    }

    /**
     * Calcula en un único pase de dos etapas el ZNCC simultáneo para la plantilla adaptativa y la ancla.
     */
    private fun computeDualZNCC(
        roiLum: FloatArray,
        roiW: Int,
        roiLeft: Int,
        roiTop: Int,
        cx: Int,
        cy: Int,
        adTpl: FloatArray,
        anTpl: FloatArray,
        numPixels: Int
    ): Pair<Float, Float> {
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
        var crossSumAd = 0f
        var crossSumAn = 0f
        var idx = 0

        for (dy in 0 until templateH) {
            val rowOffset = (startY + dy) * roiW + startX
            for (dx in 0 until templateW) {
                val lum = roiLum[rowOffset + dx]
                val diff = lum - patchMean
                patchVar += diff * diff
                crossSumAd += adTpl[idx] * diff
                crossSumAn += anTpl[idx] * diff
                idx++
            }
        }

        val patchStd = sqrt((patchVar / numPixels).toDouble()).toFloat()
        if (patchStd < 1.0f) return Pair(0f, 0f)

        val denom = numPixels * patchStd
        val scoreAd = (crossSumAd / denom).coerceIn(-1.0f, 1.0f)
        val scoreAn = (crossSumAn / denom).coerceIn(-1.0f, 1.0f)
        return Pair(scoreAd, scoreAn)
    }

    /**
     * ZNCC simple para el cálculo subpíxel.
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

    /**
     * Actualiza la plantilla adaptativa mediante un filtro IIR exponencial muy suave (alpha = 0.03).
     */
    private fun updateAdaptiveTemplate(
        roiLum: FloatArray,
        roiW: Int,
        roiLeft: Int,
        roiTop: Int,
        bestX: Int,
        bestY: Int,
        adTpl: FloatArray,
        numPixels: Int
    ) {
        val startX = bestX - templateHalfW - roiLeft
        val startY = bestY - templateHalfH - roiTop

        var sum = 0f
        for (dy in 0 until templateH) {
            val rowOffset = (startY + dy) * roiW + startX
            for (dx in 0 until templateW) {
                sum += roiLum[rowOffset + dx]
            }
        }
        val pMean = sum / numPixels

        var pVar = 0f
        for (dy in 0 until templateH) {
            val rowOffset = (startY + dy) * roiW + startX
            for (dx in 0 until templateW) {
                val d = roiLum[rowOffset + dx] - pMean
                pVar += d * d
            }
        }
        val pStd = sqrt((pVar / numPixels).toDouble()).toFloat().coerceAtLeast(1.0f)

        var idx = 0
        val alpha = 0.03f
        val oneMinusAlpha = 1.0f - alpha

        for (dy in 0 until templateH) {
            val rowOffset = (startY + dy) * roiW + startX
            for (dx in 0 until templateW) {
                val normPixel = (roiLum[rowOffset + dx] - pMean) / pStd
                adTpl[idx] = adTpl[idx] * oneMinusAlpha + normPixel * alpha
                idx++
            }
        }
    }
}
