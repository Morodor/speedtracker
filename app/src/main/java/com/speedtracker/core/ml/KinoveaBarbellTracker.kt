package com.speedtracker.core.ml

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tracker visual biomecánico multizona de alta precisión para discos olímpicos (Kinovea Multi-Zone Circular + ZNCC).
 *
 * Combina cuatro invariantes geométricos y fotométricos del disco calibrado de radio R:
 * 1. Color y Cromaticidad del Anillo del Disco (0.52R y 0.76R) con Guardia de 4 Cuadrantes:
 *    Impide físicamente que el tracker se desplace hacia el borde superior o lateral del disco,
 *    ya que si el centro se desvía hacia el borde, el cuadrante superior sale hacia el fondo y es penalizado.
 * 2. Simetría Radial Concéntrica (0.20R, 0.32R, 0.58R y 0.78R):
 *    Garantiza que los puntos opuestos tanto en el buje interior como en el cuerpo del disco sean simétricos.
 * 3. Contraste Perimétrico del Borde Exterior (0.84R vs 1.14R):
 *    Mantiene el círculo de radio R anclado al contorno exterior real del disco.
 * 4. Correlación Cruzada Normalizada (ZNCC) en el Buje Central (0.24R) con Refinamiento Subpíxel Parabólico.
 */
class KinoveaBarbellTracker {

    private var templateHalfW = 14
    private var templateHalfH = 14
    private var templateW = 29
    private var templateH = 29

    // Plantilla ZNCC del buje central capturada en la calibración
    private var anchorTemplate: FloatArray? = null

    // Firma cromática mediana del cuerpo anular del disco (inmune a reflejos del eje central)
    private var plateMeanR = 0f
    private var plateMeanG = 0f
    private var plateMeanB = 0f

    // Vectores circulares precalculados (16 ángulos)
    private val numAngles = 16
    private val cosT = FloatArray(numAngles) { i -> cos(2.0 * Math.PI * i / numAngles).toFloat() }
    private val sinT = FloatArray(numAngles) { i -> sin(2.0 * Math.PI * i / numAngles).toFloat() }

    // Offsets enteros precalculados según calibratedRadius para velocidad máxima en tiempo real
    private val annulusDx = IntArray(numAngles * 2)
    private val annulusDy = IntArray(numAngles * 2)
    private val annulusQuadMask = IntArray(numAngles) // bitmask: 1=Top, 2=Bottom, 4=Left, 8=Right

    private val symRings = 4
    private val halfAngles = numAngles / 2
    private val symDx = IntArray(symRings * halfAngles)
    private val symDy = IntArray(symRings * halfAngles)

    private val rimInDx = IntArray(numAngles)
    private val rimInDy = IntArray(numAngles)
    private val rimOutDx = IntArray(numAngles)
    private val rimOutDy = IntArray(numAngles)

    // Buffers reutilizables de frame completo para evitar asignaciones de memoria por cuadro
    private var framePixelsBuffer = IntArray(0)
    private var frameLumBuffer = FloatArray(0)

    var calibratedRadius: Float = 62f
        private set

    var currentCenter: Offset = Offset.Zero
        private set
    var currentVelocity: Offset = Offset.Zero
        private set
    var lastConfidence: Float = 1.0f
        private set
    private var consecutiveLostFrames = 0

    var isInitialized = false
        private set

    init {
        for (i in 0 until numAngles) {
            var mask = 0
            if (sinT[i] < -0.35f) mask = mask or 1
            if (sinT[i] > 0.35f) mask = mask or 2
            if (cosT[i] < -0.35f) mask = mask or 4
            if (cosT[i] > 0.35f) mask = mask or 8
            annulusQuadMask[i] = mask
        }
    }

    private fun precomputeRingOffsets(R: Float) {
        val rAnn1 = R * 0.52f
        val rAnn2 = R * 0.76f
        for (i in 0 until numAngles) {
            annulusDx[i] = (rAnn1 * cosT[i]).roundToInt()
            annulusDy[i] = (rAnn1 * sinT[i]).roundToInt()
            annulusDx[numAngles + i] = (rAnn2 * cosT[i]).roundToInt()
            annulusDy[numAngles + i] = (rAnn2 * sinT[i]).roundToInt()

            rimInDx[i] = (R * 0.84f * cosT[i]).roundToInt()
            rimInDy[i] = (R * 0.84f * sinT[i]).roundToInt()
            rimOutDx[i] = (R * 1.14f * cosT[i]).roundToInt()
            rimOutDy[i] = (R * 1.14f * sinT[i]).roundToInt()
        }

        val symFactors = floatArrayOf(0.20f, 0.32f, 0.58f, 0.78f)
        var idx = 0
        for (rf in symFactors) {
            val rad = R * rf
            for (i in 0 until halfAngles) {
                symDx[idx] = (rad * cosT[i]).roundToInt()
                symDy[idx] = (rad * sinT[i]).roundToInt()
                idx++
            }
        }
    }

    private fun ensureFrameBuffers(bW: Int, bH: Int, bitmap: Bitmap) {
        val total = bW * bH
        if (framePixelsBuffer.size < total) {
            framePixelsBuffer = IntArray(total)
            frameLumBuffer = FloatArray(total)
        }
        bitmap.getPixels(framePixelsBuffer, 0, bW, 0, 0, bW, bH)
        val pix = framePixelsBuffer
        val lum = frameLumBuffer
        for (i in 0 until total) {
            val p = pix[i]
            lum[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000f
        }
    }

    private fun extractPlateColorAndHub(bW: Int, bH: Int, cX: Int, cY: Int) {
        val rs = FloatArray(48)
        val gs = FloatArray(48)
        val bs = FloatArray(48)
        var count = 0

        val rFactors = floatArrayOf(0.50f, 0.65f, 0.78f)
        for (rf in rFactors) {
            val rad = calibratedRadius * rf
            for (i in 0 until numAngles) {
                // Evitar la base inferior extrema en el frame 0 por si el disco toca la tarima
                if (sinT[i] > 0.75f) continue
                val px = (cX + rad * cosT[i]).roundToInt().coerceIn(0, bW - 1)
                val py = (cY + rad * sinT[i]).roundToInt().coerceIn(0, bH - 1)
                val p = framePixelsBuffer[py * bW + px]
                rs[count] = (p shr 16 and 0xFF).toFloat()
                gs[count] = (p shr 8 and 0xFF).toFloat()
                bs[count] = (p and 0xFF).toFloat()
                count++
            }
        }

        if (count > 0) {
            val sortedR = rs.copyOfRange(0, count).apply { sort() }
            val sortedG = gs.copyOfRange(0, count).apply { sort() }
            val sortedB = bs.copyOfRange(0, count).apply { sort() }
            val mid = count / 2
            plateMeanR = sortedR[mid]
            plateMeanG = sortedG[mid]
            plateMeanB = sortedB[mid]
        }

        val halfSize = (calibratedRadius * 0.24f).toInt().coerceIn(8, 24)
        templateHalfW = halfSize
        templateHalfH = halfSize
        templateW = halfSize * 2 + 1
        templateH = halfSize * 2 + 1

        val numPixels = templateW * templateH
        val rawLuminance = FloatArray(numPixels)
        val clampedX = cX.coerceIn(templateHalfW, bW - templateHalfW - 1)
        val clampedY = cY.coerceIn(templateHalfH, bH - templateHalfH - 1)

        var sum = 0f
        var idx = 0
        for (dy in -templateHalfH..templateHalfH) {
            val rowOffset = (clampedY + dy) * bW + clampedX
            for (dx in -templateHalfW..templateHalfW) {
                val l = frameLumBuffer[rowOffset + dx]
                rawLuminance[idx++] = l
                sum += l
            }
        }

        val mean = sum / numPixels
        var varSum = 0f
        for (i in 0 until numPixels) {
            val diff = rawLuminance[i] - mean
            varSum += diff * diff
        }
        val std = sqrt((varSum / numPixels).toDouble()).toFloat().coerceAtLeast(1.0f)
        anchorTemplate = FloatArray(numPixels) { i -> (rawLuminance[i] - mean) / std }
    }

    /**
     * Inicializa el tracker en el fotograma de calibración, ajustando automáticamente
     * pequeñas desviaciones manuales (±14 px) hacia el centro de simetría radial real del disco.
     */
    fun initialize(bitmap: Bitmap, center: Offset, radiusPx: Float) {
        val bW = bitmap.width
        val bH = bitmap.height
        calibratedRadius = if (radiusPx > 15f) radiusPx else 62f
        precomputeRingOffsets(calibratedRadius)
        ensureFrameBuffers(bW, bH, bitmap)

        val margin = (calibratedRadius * 0.5f).toInt().coerceAtLeast(16)
        val initX = center.x.roundToInt().coerceIn(margin, bW - margin - 1)
        val initY = center.y.roundToInt().coerceIn(margin, bH - margin - 1)

        // Primera captura de color en la posición indicada
        extractPlateColorAndHub(bW, bH, initX, initY)

        // Micro-ajuste de centrado por simetría radial y anillo (±14 px)
        var snappedX = initX
        var snappedY = initY
        var bestSnapScore = -1e9f
        for (dy in -14..14 step 2) {
            val cy = initY + dy
            if (cy < margin || cy >= bH - margin) continue
            for (dx in -14..14 step 2) {
                val cx = initX + dx
                if (cx < margin || cx >= bW - margin) continue
                val s = computeMultiZoneScore(bW, bH, cx, cy, includeZncc = false)
                if (s > bestSnapScore) {
                    bestSnapScore = s
                    snappedX = cx
                    snappedY = cy
                }
            }
        }

        // Recapturar plantilla en el centro geométrico exacto del disco
        extractPlateColorAndHub(bW, bH, snappedX, snappedY)

        currentCenter = Offset(snappedX.toFloat(), snappedY.toFloat())
        currentVelocity = Offset.Zero
        lastConfidence = 1.0f
        consecutiveLostFrames = 0
        isInitialized = true
    }

    /**
     * Rastrea el centro exacto del disco en el fotograma actual mediante búsqueda multizona en 2 etapas.
     */
    fun trackFrame(bitmap: Bitmap): Offset {
        if (!isInitialized || anchorTemplate == null) {
            return currentCenter
        }

        val bW = bitmap.width
        val bH = bitmap.height
        ensureFrameBuffers(bW, bH, bitmap)

        // Predicción cinemática moderada (0.55) para acompañar tirones explosivos sin sobrepasar el freno en la recepción
        val maxDy = (calibratedRadius * 1.15f).coerceIn(45f, 140f)
        val maxDx = (calibratedRadius * 0.55f).coerceIn(20f, 55f)

        val predX = currentCenter.x + (currentVelocity.x * 0.55f).coerceIn(-maxDx, maxDx)
        val predY = currentCenter.y + (currentVelocity.y * 0.55f).coerceIn(-maxDy, maxDy)

        val searchRx = (calibratedRadius * 0.45f).toInt().coerceIn(24, 48)
        val searchRy = (calibratedRadius * 0.85f).toInt().coerceIn(36, 90)

        val margin = (calibratedRadius * 0.48f).toInt().coerceAtLeast(templateHalfW + 2)
        val minSearchX = (predX - searchRx).toInt().coerceIn(margin, bW - margin - 1)
        val maxSearchX = (predX + searchRx).toInt().coerceIn(margin, bW - margin - 1)
        val minSearchY = (predY - searchRy).toInt().coerceIn(margin, bH - margin - 1)
        val maxSearchY = (predY + searchRy).toInt().coerceIn(margin, bH - margin - 1)

        var bestX = currentCenter.x.roundToInt().coerceIn(minSearchX, maxSearchX)
        var bestY = currentCenter.y.roundToInt().coerceIn(minSearchY, maxSearchY)
        var bestCoarseScore = -1e9f

        val invSearchRy = 1f / searchRy.toFloat()

        // Etapa 1: Búsqueda gruesa multizona (paso de 3 píxeles)
        var cy = minSearchY
        while (cy <= maxSearchY) {
            var cx = minSearchX
            while (cx <= maxSearchX) {
                val baseScore = computeMultiZoneScore(bW, bH, cx, cy, includeZncc = false)
                val dist = hypot((cx - predX).toDouble(), (cy - predY).toDouble()).toFloat()
                val score = baseScore - (dist * invSearchRy * 0.03f)
                if (score > bestCoarseScore) {
                    bestCoarseScore = score
                    bestX = cx
                    bestY = cy
                }
                cx += 3
            }
            cy += 3
        }

        // Etapa 2: Refinamiento fino a 1 píxel alrededor del mejor candidato incluyendo ZNCC del buje
        val fineMinX = (bestX - 4).coerceAtLeast(minSearchX)
        val fineMaxX = (bestX + 4).coerceAtMost(maxSearchX)
        val fineMinY = (bestY - 4).coerceAtLeast(minSearchY)
        val fineMaxY = (bestY + 4).coerceAtMost(maxSearchY)

        var bestFineScore = -1e9f
        for (fy in fineMinY..fineMaxY) {
            for (fx in fineMinX..fineMaxX) {
                val fullScore = computeMultiZoneScore(bW, bH, fx, fy, includeZncc = true)
                val dist = hypot((fx - predX).toDouble(), (fy - predY).toDouble()).toFloat()
                val score = fullScore - (dist * invSearchRy * 0.03f)
                if (score > bestFineScore) {
                    bestFineScore = score
                    bestX = fx
                    bestY = fy
                }
            }
        }

        // Etapa 3: Interpolación subpíxel parabólica
        val sLeft = computeMultiZoneScore(bW, bH, (bestX - 1).coerceAtLeast(minSearchX), bestY, includeZncc = true)
        val sRight = computeMultiZoneScore(bW, bH, (bestX + 1).coerceAtMost(maxSearchX), bestY, includeZncc = true)
        val sTop = computeMultiZoneScore(bW, bH, bestX, (bestY - 1).coerceAtLeast(minSearchY), includeZncc = true)
        val sBottom = computeMultiZoneScore(bW, bH, bestX, (bestY + 1).coerceAtMost(maxSearchY), includeZncc = true)

        val denomX = (sLeft - 2f * bestFineScore + sRight)
        val subX = if (abs(denomX) > 1e-4f) ((sLeft - sRight) / (2f * denomX)).coerceIn(-0.5f, 0.5f) else 0f

        val denomY = (sTop - 2f * bestFineScore + sBottom)
        val subY = if (abs(denomY) > 1e-4f) ((sTop - sBottom) / (2f * denomY)).coerceIn(-0.5f, 0.5f) else 0f

        val targetX = bestX + subX
        val targetY = bestY + subY

        val instVx = targetX - currentCenter.x
        val instVy = targetY - currentCenter.y

        val updatedVx = currentVelocity.x * 0.30f + instVx.coerceIn(-maxDx, maxDx) * 0.70f
        val updatedVy = currentVelocity.y * 0.30f + instVy.coerceIn(-maxDy, maxDy) * 0.70f

        val movementMag = hypot(instVx.toDouble(), instVy.toDouble()).toFloat()
        currentVelocity = if (movementMag < 0.30f) Offset.Zero else Offset(updatedVx, updatedVy)

        val resolvedCenter = Offset(targetX, targetY)
        currentCenter = resolvedCenter
        lastConfidence = bestFineScore.coerceIn(0f, 1f)
        consecutiveLostFrames = 0
        return resolvedCenter
    }

    /**
     * Evalúa la coincidencia geométrica y cromática de disco completo + buje en (cx, cy).
     */
    private fun computeMultiZoneScore(
        bW: Int,
        bH: Int,
        cx: Int,
        cy: Int,
        includeZncc: Boolean
    ): Float {
        val pix = framePixelsBuffer
        val pR = plateMeanR
        val pG = plateMeanG
        val pB = plateMeanB
        val pChromaRB = pR - pB
        val pChromaGB = pG - pB

        // 1. Coincidencia de color del anillo del disco (0.52R y 0.76R) y penalización del peor cuadrante
        var annulusPenalty = 0f
        var qTop = 0f
        var qBot = 0f
        var qLeft = 0f
        var qRight = 0f
        var cTop = 0
        var cBot = 0
        var cLeft = 0
        var cRight = 0

        for (ring in 0..1) {
            val baseIdx = ring * numAngles
            for (i in 0 until numAngles) {
                val px = cx + annulusDx[baseIdx + i]
                val py = cy + annulusDy[baseIdx + i]
                val distCol: Float
                if (px < 0 || px >= bW || py < 0 || py >= bH) {
                    distCol = 120f
                } else {
                    val p = pix[py * bW + px]
                    val r = (p shr 16 and 0xFF).toFloat()
                    val g = (p shr 8 and 0xFF).toFloat()
                    val b = (p and 0xFF).toFloat()
                    val dr = abs(r - pR)
                    val dg = abs(g - pG)
                    val db = abs(b - pB)
                    val dc1 = abs((r - b) - pChromaRB)
                    val dc2 = abs((g - b) - pChromaGB)
                    distCol = 0.35f * (dr + dg + db) + 0.65f * (dc1 + dc2)
                }
                annulusPenalty += distCol
                val qMask = annulusQuadMask[i]
                if ((qMask and 1) != 0) { qTop += distCol; cTop++ }
                if ((qMask and 2) != 0) { qBot += distCol; cBot++ }
                if ((qMask and 4) != 0) { qLeft += distCol; cLeft++ }
                if ((qMask and 8) != 0) { qRight += distCol; cRight++ }
            }
        }
        annulusPenalty /= (2 * numAngles).toFloat()
        val mTop = if (cTop > 0) qTop / cTop else 0f
        val mBot = if (cBot > 0) qBot / cBot else 0f
        val mLeft = if (cLeft > 0) qLeft / cLeft else 0f
        val mRight = if (cRight > 0) qRight / cRight else 0f
        val worstQuad = maxOf(maxOf(mTop, mBot), maxOf(mLeft, mRight))
        val annulusScore = exp((-(0.60f * annulusPenalty + 0.40f * worstQuad) / 45.0f).toDouble()).toFloat()

        // 2. Simetría radial concéntrica (0.20R, 0.32R, 0.58R, 0.78R)
        var symDiff = 0
        val totalSymPairs = symRings * halfAngles
        for (idx in 0 until totalSymPairs) {
            val dx = symDx[idx]
            val dy = symDy[idx]
            val px1 = (cx + dx).coerceIn(0, bW - 1)
            val py1 = (cy + dy).coerceIn(0, bH - 1)
            val px2 = (cx - dx).coerceIn(0, bW - 1)
            val py2 = (cy - dy).coerceIn(0, bH - 1)
            val p1 = pix[py1 * bW + px1]
            val p2 = pix[py2 * bW + px2]
            symDiff += abs((p1 shr 16 and 0xFF) - (p2 shr 16 and 0xFF)) +
                    abs((p1 shr 8 and 0xFF) - (p2 shr 8 and 0xFF)) +
                    abs((p1 and 0xFF) - (p2 and 0xFF))
        }
        val symMean = symDiff.toFloat() / totalSymPairs.toFloat()
        val symScore = exp((-symMean / 50.0f).toDouble()).toFloat()

        // 3. Contraste del borde exterior del disco (0.84R vs 1.14R)
        var rimContrast = 0f
        for (i in 0 until numAngles) {
            val pxIn = (cx + rimInDx[i]).coerceIn(0, bW - 1)
            val pyIn = (cy + rimInDy[i]).coerceIn(0, bH - 1)
            val pxOut = (cx + rimOutDx[i]).coerceIn(0, bW - 1)
            val pyOut = (cy + rimOutDy[i]).coerceIn(0, bH - 1)
            val pIn = pix[pyIn * bW + pxIn]
            val pOut = pix[pyOut * bW + pxOut]
            val diff = abs((pIn shr 16 and 0xFF) - (pOut shr 16 and 0xFF)) +
                    abs((pIn shr 8 and 0xFF) - (pOut shr 8 and 0xFF)) +
                    abs((pIn and 0xFF) - (pOut and 0xFF))
            rimContrast += min(120f, diff.toFloat())
        }
        val rimScore = rimContrast / (numAngles * 120f)

        // 4. Correlación ZNCC del buje central (en etapa fina)
        val zncc = if (includeZncc && anchorTemplate != null) {
            computeSingleZNCC(bW, bH, cx, cy, anchorTemplate!!)
        } else {
            0f
        }

        return 0.45f * annulusScore + 0.30f * symScore + 0.15f * rimScore + 0.10f * zncc.coerceAtLeast(0f)
    }

    private fun computeSingleZNCC(
        bW: Int,
        bH: Int,
        cx: Int,
        cy: Int,
        tpl: FloatArray
    ): Float {
        if (cx - templateHalfW < 0 || cx + templateHalfW >= bW || cy - templateHalfH < 0 || cy + templateHalfH >= bH) {
            return 0f
        }
        val lumBuf = frameLumBuffer
        val numPixels = templateW * templateH
        val startX = cx - templateHalfW
        val startY = cy - templateHalfH

        var sum = 0f
        for (dy in 0 until templateH) {
            val rowOffset = (startY + dy) * bW + startX
            for (dx in 0 until templateW) {
                sum += lumBuf[rowOffset + dx]
            }
        }
        val patchMean = sum / numPixels

        var patchVar = 0f
        var crossSum = 0f
        var idx = 0
        for (dy in 0 until templateH) {
            val rowOffset = (startY + dy) * bW + startX
            for (dx in 0 until templateW) {
                val lum = lumBuf[rowOffset + dx]
                val diff = lum - patchMean
                patchVar += diff * diff
                crossSum += tpl[idx++] * diff
            }
        }

        val patchStd = sqrt((patchVar / numPixels).toDouble()).toFloat()
        if (patchStd < 1.5f) return 0f

        return (crossSum / (numPixels * patchStd)).coerceIn(-1.0f, 1.0f)
    }
}
