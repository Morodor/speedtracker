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

    // Radio calibrado del disco olímpico
    var calibratedRadius: Float = 60f
        private set

    // Firma cromática del cuerpo del disco olímpico (espacio cromático normalizado r, g, b y HSV)
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
     * y la firma de color del cuerpo del disco (verde, amarillo, azul, rojo o negro) en el frame de calibración.
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

        normTemplate = FloatArray(numPixels) { i ->
            (rawLuminance[i] - mean) / std
        }

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

        // 1. Predicción cinemática basada en velocidad previa con límites físicos acotados
        val speed = hypot(currentVelocity.x.toDouble(), currentVelocity.y.toDouble()).toFloat()
        val isFast = speed > 5f

        // Sesgo de velocidad conservador:
        // A 2.5 m/s (velocidad élite de halterofilia) la barra recorre a lo sumo ~20 px por frame (33ms).
        // Limitamos el sesgo vertical a -10f hacia arriba para que la ventana NUNCA suba hacia el techo ni al borde del disco.
        val velBiasX = (currentVelocity.x * 0.30f).coerceIn(-4f, 4f)
        val velBiasY = (currentVelocity.y * 0.35f).coerceIn(-10f, 8f)

        val predX = currentCenter.x + velBiasX
        val predY = currentCenter.y + velBiasY

        // 2. Ventana de búsqueda adaptativa centrada en la posición actual de la barra
        val searchRadiusX = if (isFast) 16 else 12
        val searchRadiusY = if (isFast) 20 else 14

        val minSearchX = (currentCenter.x + velBiasX - searchRadiusX).toInt().coerceIn(templateHalfW, bW - templateHalfW - 1)
        val maxSearchX = (currentCenter.x + velBiasX + searchRadiusX).toInt().coerceIn(templateHalfW, bW - templateHalfW - 1)
        val minSearchY = (currentCenter.y + velBiasY - searchRadiusY).toInt().coerceIn(templateHalfH, bH - templateHalfH - 1)
        val maxSearchY = (currentCenter.y + velBiasY + searchRadiusY).toInt().coerceIn(templateHalfH, bH - templateHalfH - 1)

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

        // Búsqueda exhaustiva a resolución completa de 1 píxel
        for (cy in minSearchY..maxSearchY) {
            for (cx in minSearchX..maxSearchX) {
                val rawScore = computeZNCC(lumBuf, roiW, roiLeft, roiTop, cx, cy, tpl, numPixels)

                // Penalización de distancia 2D suave respecto a la posición física predicha (predX, predY)
                val dx = cx - predX
                val dy = cy - predY
                val distFromPred = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val distPenalty = (distFromPred / 25f) * 0.10f

                val hDev = (abs(cx - currentCenter.x) - 4f).coerceAtLeast(0f)
                val score = rawScore - distPenalty - (hDev / 16f) * 0.10f

                if (score > bestScore) {
                    bestScore = score
                    bestX = cx
                    bestY = cy
                }
            }
        }

        // 3. Validación y continuación por inercia física
        val resolvedCenter: Offset
        // Umbral de aceptación calibrado para capturar el buje en movimiento rápido y reposo
        if (bestScore >= 0.18f) {
            // Refinamiento subpíxel parabólico
            val sLeft = computeZNCC(lumBuf, roiW, roiLeft, roiTop, (bestX - 1).coerceAtLeast(minSearchX), bestY, tpl, numPixels)
            val sRight = computeZNCC(lumBuf, roiW, roiLeft, roiTop, (bestX + 1).coerceAtMost(maxSearchX), bestY, tpl, numPixels)
            val sTop = computeZNCC(lumBuf, roiW, roiLeft, roiTop, bestX, (bestY - 1).coerceAtLeast(minSearchY), tpl, numPixels)
            val sBottom = computeZNCC(lumBuf, roiW, roiLeft, roiTop, bestX, (bestY + 1).coerceAtMost(maxSearchY), tpl, numPixels)

            val denomX = (sLeft - 2f * bestScore + sRight)
            val subX = if (abs(denomX) > 1e-4f) ((sLeft - sRight) / (2f * denomX)).coerceIn(-0.5f, 0.5f) else 0f

            val denomY = (sTop - 2f * bestScore + sBottom)
            val subY = if (abs(denomY) > 1e-4f) ((sTop - sBottom) / (2f * denomY)).coerceIn(-0.5f, 0.5f) else 0f

            var targetX = bestX + subX
            var targetY = bestY + subY

            // Recentrado suave de simetría cromática sólo si hay certeza clara
            val colorSym = evaluateColorSymmetry(bitmap, bestX, bestY)
            if (colorSym.matchRatio > 0.45f) {
                if (colorSym.verticalBalance > 1.2f) {
                    val vShift = colorSym.verticalBalance * (calibratedRadius * 0.04f)
                    targetY += vShift.coerceIn(0f, calibratedRadius * 0.16f)
                } else if (colorSym.verticalBalance < -1.2f) {
                    val vShift = colorSym.verticalBalance * (calibratedRadius * 0.04f)
                    targetY += vShift.coerceIn(-calibratedRadius * 0.16f, 0f)
                }
            }

            val instVx = targetX - currentCenter.x
            val instVy = targetY - currentCenter.y

            // Detección de detención y cambio de fase en el bloqueo (lockout):
            // Si la barra se detiene o desacelera hacia cero (abs(instVy) < 0.8f),
            // anular inmediatamente la velocidad vertical para fijar el disco inmóvil
            val updatedVy = if (abs(instVy) < 0.8f) {
                0f
            } else {
                currentVelocity.y * 0.20f + instVy.coerceIn(-24f, 20f) * 0.80f
            }

            val updatedVx = if (abs(instVx) < 0.6f) {
                0f
            } else {
                instVx.coerceIn(-4f, 4f) * 0.70f
            }

            val movementMag = hypot(instVx.toDouble(), instVy.toDouble()).toFloat()
            currentVelocity = if (movementMag < 0.7f) {
                Offset.Zero
            } else {
                Offset(updatedVx, updatedVy)
            }

            resolvedCenter = Offset(targetX, targetY)
            lastConfidence = bestScore
            consecutiveLostFrames = 0
        } else {
            // Inercia cinemática suave si hay un frame con desenfoque de movimiento extremo
            consecutiveLostFrames++
            val inertX = (currentCenter.x + currentVelocity.x).coerceIn(templateHalfW.toFloat(), (bW - templateHalfW).toFloat())
            val inertY = (currentCenter.y + currentVelocity.y).coerceIn(templateHalfH.toFloat(), (bH - templateHalfH).toFloat())
            resolvedCenter = Offset(inertX, inertY)
            lastConfidence = 0.20f

            currentVelocity = Offset(
                currentVelocity.x * 0.75f,
                currentVelocity.y * 0.75f
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

    /**
     * Evalúa la simetría cromática en 8 direcciones radiales alrededor de un centro candidato (cx, cy).
     * Permite confirmar si el centro está equilibrado dentro del disco o si está desplazado hacia algún borde.
     */
    data class ColorSymmetryResult(
        val matchRatio: Float,
        val verticalBalance: Float,
        val horizontalBalance: Float
    )

    private fun evaluateColorSymmetry(bitmap: Bitmap, cx: Int, cy: Int): ColorSymmetryResult {
        val bW = bitmap.width
        val bH = bitmap.height
        val r = calibratedRadius * 0.58f

        val diag = r * 0.7071f
        val offsetsX = floatArrayOf(r, diag, 0f, -diag, -r, -diag, 0f, diag)
        val offsetsY = floatArrayOf(0f, -diag, -r, -diag, 0f, diag, r, diag)

        var totalMatches = 0
        var topMatches = 0
        var bottomMatches = 0
        var leftMatches = 0
        var rightMatches = 0

        val hsv = FloatArray(3)

        for (i in 0 until 8) {
            val px = (cx + offsetsX[i]).toInt()
            val py = (cy + offsetsY[i]).toInt()

            val isMatch: Boolean
            if (px in 0 until bW && py in 0 until bH) {
                val p = bitmap.getPixel(px, py)
                if (isChromaticPlate) {
                    val rVal = (p shr 16) and 0xFF
                    val gVal = (p shr 8) and 0xFF
                    val bVal = p and 0xFF
                    val sumRgb = (rVal + gVal + bVal + 1).toFloat()
                    val nr = rVal / sumRgb
                    val ng = gVal / sumRgb
                    val nb = bVal / sumRgb
                    val dNr = nr - targetNr
                    val dNg = ng - targetNg
                    val dNb = nb - targetNb
                    val distChroma = sqrt((dNr * dNr + dNg * dNg + dNb * dNb).toDouble()).toFloat()

                    Color.colorToHSV(p, hsv)
                    val diffHue = abs(hsv[0] - targetHue).let { minOf(it, 360f - it) }

                    isMatch = (distChroma < 0.17f) || (diffHue < 45f && hsv[1] > (targetSat * 0.40f))
                } else {
                    val rVal = (p shr 16) and 0xFF
                    val gVal = (p shr 8) and 0xFF
                    val bVal = p and 0xFF
                    val lum = ((rVal * 299 + gVal * 587 + bVal * 114) / 1000f)
                    isMatch = lum < 95f
                }
            } else {
                isMatch = false
            }

            if (isMatch) {
                totalMatches++
                if (i in 1..3) topMatches++
                if (i in 5..7) bottomMatches++
                if (i in 3..5) leftMatches++
                if (i == 7 || i == 0 || i == 1) rightMatches++
            }
        }

        val matchRatio = totalMatches / 8.0f
        val vBal = (bottomMatches - topMatches).toFloat()
        val hBal = (rightMatches - leftMatches).toFloat()

        return ColorSymmetryResult(matchRatio, vBal, hBal)
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

            return (distChroma < 0.22f) || (diffHue < 55f && hsv[1] > (targetSat * 0.28f))
        } else {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = ((r * 299 + g * 587 + b * 114) / 1000f)
            val hsv = FloatArray(3)
            Color.colorToHSV(p, hsv)
            return lum < 105f && hsv[1] < 0.40f
        }
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
        val maxExtent = (r * 1.6f).toInt()

        // 1. Rayo hacia arriba (buscar borde superior del disco)
        var yTop = -1
        var nonPlateUp = 0
        for (step in hubClearance..maxExtent) {
            val ty = cy - step
            if (ty < 0) { yTop = 0; break }
            val p = bitmap.getPixel(cx, ty)
            if (!isPlateColor(p)) {
                nonPlateUp++
                if (nonPlateUp >= 3) {
                    yTop = ty + 2
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
                if (nonPlateDown >= 3) {
                    yBottom = by - 2
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
                if (nonPlateLeft >= 3) {
                    xLeft = lx + 2
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
                if (nonPlateRight >= 3) {
                    xRight = rx - 2
                    break
                }
            } else {
                nonPlateRight = 0
            }
        }

        // Validar si encontramos ambos bordes verticales con un diámetro coherente con el disco de 45 cm
        if (yTop != -1 && yBottom != -1) {
            val vertDiameter = yBottom - yTop
            if (vertDiameter in (r * 1.25f).toInt()..(r * 2.5f).toInt()) {
                val geomY = (yTop + yBottom) / 2f
                val geomX = if (xLeft != -1 && xRight != -1 && (xRight - xLeft) in (r * 1.25f).toInt()..(r * 2.5f).toInt()) {
                    (xLeft + xRight) / 2f
                } else {
                    startX
                }
                return Offset(geomX, geomY)
            }
        }

        return null
    }

}
