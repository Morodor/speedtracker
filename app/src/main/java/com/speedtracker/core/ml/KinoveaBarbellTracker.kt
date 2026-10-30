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

        // 1. Predicción cinemática basada en velocidad previa con límites físicos
        val speed = hypot(currentVelocity.x.toDouble(), currentVelocity.y.toDouble()).toFloat()
        val isFast = speed > 6f

        // Centrado conservador: desplazamiento acotado a la biomecánica real de la barra por frame (33ms)
        // Evita que la predicción se dispare hacia el techo o vigas de fondo durante el segundo tirón
        val predX = currentCenter.x + (currentVelocity.x * 0.35f).coerceIn(-4f, 4f)
        val predY = currentCenter.y + (currentVelocity.y * 0.45f).coerceIn(-26f, 22f)

        // 2. Ventana de búsqueda adaptativa ceñida a la trayectoria del disco
        val searchRadiusX = if (isFast) 16 else 12
        val searchRadiusY = if (isFast) 22 else 14

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

        // Búsqueda exhaustiva a resolución completa de 1 píxel
        for (cy in minSearchY..maxSearchY) {
            for (cx in minSearchX..maxSearchX) {
                val rawScore = computeZNCC(lumBuf, roiW, roiLeft, roiTop, cx, cy, tpl, numPixels)

                // Penalización de distancia 2D respecto a la posición física predicha (predX, predY).
                // Evita que elementos lejanos de alto contraste en el fondo (como vigas o sombras de techo)
                // superen la firma del buje real de la barra cuando sube velozmente.
                val dx = cx - predX
                val dy = cy - predY
                val distFromPred = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val distPenalty = (distFromPred / 25f) * 0.15f

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
        // Umbral de aceptación para capturar el buje en movimiento y reposo
        if (bestScore >= 0.20f) {
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

            // Corrección de Centrado por Simetría Cromática del Disco:
            // Si la retícula se desplaza hacia la punta superior del disco (por perspectiva o contraste de techo),
            // la simetría cromática detecta que los puntos superiores caen en el fondo/vigas de madera y los inferiores en el disco verde.
            // Corrige automáticamente la posición hacia el centro geométrico real del disco (buje).
            val colorSym = evaluateColorSymmetry(bitmap, bestX, bestY)
            if (colorSym.verticalBalance > 0.5f) {
                // El cuerpo del disco está más abajo: empujar suavemente el centro hacia abajo al buje real
                val vShift = colorSym.verticalBalance * (calibratedRadius * 0.08f)
                targetY += vShift.coerceIn(0f, calibratedRadius * 0.35f)
            } else if (colorSym.verticalBalance < -0.5f) {
                val vShift = colorSym.verticalBalance * (calibratedRadius * 0.08f)
                targetY += vShift.coerceIn(-calibratedRadius * 0.35f, 0f)
            }

            if (kotlin.math.abs(colorSym.horizontalBalance) > 0.5f) {
                val hShift = colorSym.horizontalBalance * (calibratedRadius * 0.04f)
                targetX += hShift.coerceIn(-calibratedRadius * 0.18f, calibratedRadius * 0.18f)
            }

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
                currentVelocity.y * 0.20f + instVy.coerceIn(-30f, 26f) * 0.80f
            }

            val updatedVx = if (abs(instVx) < 0.5f) {
                0f
            } else {
                instVx.coerceIn(-4f, 4f) * 0.70f
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
}
