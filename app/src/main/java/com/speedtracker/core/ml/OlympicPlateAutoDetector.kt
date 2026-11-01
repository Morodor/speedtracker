package com.speedtracker.core.ml

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.*

data class DetectedPlate(
    val center: Offset,
    val radiusPx: Float,
    val confidence: Float,
    val method: String
)

/**
 * Detector y calibrador automático de discos olímpicos (estándar 450 mm).
 * Combina estimación biomecánica basada en pose humana (tobillos, rodillas y muñecas)
 * con refinamiento de simetría radial, contraste cromático de borde y buje central.
 */
object OlympicPlateAutoDetector {

    /**
     * Detecta el disco olímpico en el frame proporcionado.
     * @param bitmap Imagen del frame actual del video en resolución nativa.
     * @param pose Estimación de pose de ML Kit sobre el frame (puede ser null).
     * @return DetectedPlate con coordenadas nativas del video (Offset) y radio en píxeles.
     */
    fun detect(bitmap: Bitmap, pose: Pose?): DetectedPlate {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        var baseRadius = (minOf(w, h) * 0.17f).coerceIn(42f, 400f)
        val candidateSeeds = mutableListOf<Offset>()
        var usedPose = false
        var targetHandsX = w * 0.54f
        var targetHandsY = h * 0.84f

        if (pose != null) {
            val leftAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
            val rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
            val leftKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
            val rightKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)
            val leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
            val rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)

            val hasAnkle = (leftAnkle != null && leftAnkle.inFrameLikelihood > 0.30f) ||
                    (rightAnkle != null && rightAnkle.inFrameLikelihood > 0.30f)
            val hasWrist = (leftWrist != null && leftWrist.inFrameLikelihood > 0.30f) ||
                    (rightWrist != null && rightWrist.inFrameLikelihood > 0.30f)

            if (hasAnkle || hasWrist) {
                val ankleY = when {
                    leftAnkle != null && rightAnkle != null -> maxOf(leftAnkle.position.y, rightAnkle.position.y)
                    leftAnkle != null -> leftAnkle.position.y
                    rightAnkle != null -> rightAnkle.position.y
                    else -> h * 0.86f
                }

                val kneeY = when {
                    leftKnee != null && rightKnee != null -> (leftKnee.position.y + rightKnee.position.y) / 2f
                    leftKnee != null -> leftKnee.position.y
                    rightKnee != null -> rightKnee.position.y
                    else -> ankleY - (h * 0.18f)
                }

                val shinLength = when {
                    leftAnkle != null && leftKnee != null && leftAnkle.inFrameLikelihood > 0.30f && leftKnee.inFrameLikelihood > 0.30f -> {
                        hypot(leftAnkle.position.x - leftKnee.position.x, leftAnkle.position.y - leftKnee.position.y)
                    }
                    rightAnkle != null && rightKnee != null && rightAnkle.inFrameLikelihood > 0.30f && rightKnee.inFrameLikelihood > 0.30f -> {
                        hypot(rightAnkle.position.x - rightKnee.position.x, rightAnkle.position.y - rightKnee.position.y)
                    }
                    else -> abs(ankleY - kneeY).coerceIn(h * 0.15f, h * 0.45f)
                }.coerceIn(h * 0.14f, h * 0.45f)

                val platformFloorY = ankleY + (shinLength * 0.14f)
                // El disco olímpico en primer plano tiene un radio ~62% de la longitud aparente de la tibia
                baseRadius = (shinLength * 0.62f).coerceIn(w * 0.145f, w * 0.35f)
                val floorPlateCenterY = platformFloorY - baseRadius

                targetHandsX = when {
                    hasWrist && hasAnkle -> {
                        val wX = when {
                            leftWrist != null && rightWrist != null -> (leftWrist.position.x + rightWrist.position.x) / 2f
                            leftWrist != null -> leftWrist.position.x
                            else -> rightWrist!!.position.x
                        }
                        val aX = when {
                            leftAnkle != null && rightAnkle != null -> (leftAnkle.position.x + rightAnkle.position.x) / 2f
                            leftAnkle != null -> leftAnkle.position.x
                            else -> rightAnkle!!.position.x
                        }
                        (wX * 0.60f + aX * 0.40f)
                    }
                    hasWrist -> leftWrist?.position?.x ?: rightWrist?.position?.x ?: (w * 0.52f)
                    hasAnkle -> leftAnkle?.position?.x ?: rightAnkle?.position?.x ?: (w * 0.52f)
                    else -> w * 0.52f
                }

                val wristY = when {
                    leftWrist != null && rightWrist != null -> (leftWrist.position.y + rightWrist.position.y) / 2f
                    leftWrist != null -> leftWrist.position.y
                    rightWrist != null -> rightWrist.position.y
                    else -> floorPlateCenterY
                }
                targetHandsY = maxOf(wristY, floorPlateCenterY)

                candidateSeeds.add(Offset(targetHandsX, floorPlateCenterY))
                candidateSeeds.add(Offset(targetHandsX, targetHandsY))
                candidateSeeds.add(Offset(targetHandsX + (baseRadius * 0.25f), floorPlateCenterY))
                candidateSeeds.add(Offset(targetHandsX - (baseRadius * 0.25f), floorPlateCenterY))
                candidateSeeds.add(Offset(targetHandsX, (wristY + floorPlateCenterY) / 2f))

                usedPose = true
            }
        }

        if (candidateSeeds.isEmpty()) {
            candidateSeeds.add(Offset(w * 0.54f, h * 0.85f))
            candidateSeeds.add(Offset(w * 0.50f, h * 0.78f))
            candidateSeeds.add(Offset(w * 0.50f, h * 0.68f))
            candidateSeeds.add(Offset(w * 0.44f, h * 0.82f))
        }

        var bestCandidate: Pair<Offset, Float> = Pair(candidateSeeds.first(), baseRadius)
        var maxCandidateScore = -1e9

        for (seed in candidateSeeds) {
            val clampedSeed = Offset(
                seed.x.coerceIn(baseRadius * 0.75f, w - baseRadius * 0.75f),
                seed.y.coerceIn(baseRadius * 0.75f, h - baseRadius * 0.75f)
            )
            val result = refineCircleByRadialGradients(bitmap, clampedSeed, baseRadius)

            val distToTarget = if (usedPose) {
                hypot((result.first.x - targetHandsX).toDouble(), (result.first.y - targetHandsY).toDouble()).toFloat()
            } else {
                0f
            }
            val proximityFactor = if (usedPose) {
                1.0 / (1.0 + (distToTarget / (baseRadius * 1.8f)).toDouble().let { it * it })
            } else {
                1.0
            }
            val totalScore = result.third * proximityFactor

            if (totalScore > maxCandidateScore) {
                maxCandidateScore = totalScore
                bestCandidate = Pair(result.first, result.second)
            }
        }

        return DetectedPlate(
            center = bestCandidate.first,
            radiusPx = bestCandidate.second,
            confidence = if (usedPose && maxCandidateScore > 300.0) 0.95f else 0.78f,
            method = if (usedPose) "MULTI_SEED_POSE_CV" else "CIRCULAR_CV_FALLBACK"
        )
    }

    /**
     * Refina el centro y radio combinando:
     * 1. Contraste cromático y de luminancia en el borde perimétrico (con cobertura angular).
     * 2. Simetría radial interna en el cuerpo del disco (0.62 * R) para evitar bordes rectos del cuerpo.
     * 3. Contraste del buje central frente al anillo del disco.
     */
    private fun refineCircleByRadialGradients(
        bitmap: Bitmap,
        seedCenter: Offset,
        seedRadius: Float
    ): Triple<Offset, Float, Double> {
        val bW = bitmap.width
        val bH = bitmap.height
        if (bW < 50 || bH < 50) return Triple(seedCenter, seedRadius, 0.0)

        var bestCenter = seedCenter
        var bestRadius = seedRadius
        var maxScore = -1e9

        val xSteps = listOf(-28f, -14f, 0f, 14f, 28f)
        val ySteps = listOf(-28f, -14f, 0f, 14f, 28f)
        val rSteps = listOf(-12f, -6f, 0f, 6f, 12f)

        val numAngles = 24
        val cosT = FloatArray(numAngles) { cos(2.0 * Math.PI * it / numAngles).toFloat() }
        val sinT = FloatArray(numAngles) { sin(2.0 * Math.PI * it / numAngles).toFloat() }

        val minAllowedRadius = (minOf(bW, bH) * 0.145f).coerceAtLeast(42f)
        val maxAllowedRadius = (minOf(bW, bH) * 0.40f)

        for (dx in xSteps) {
            for (dy in ySteps) {
                val cx = seedCenter.x + dx
                val cy = seedCenter.y + dy
                if (cx < seedRadius * 0.6f || cx > bW - seedRadius * 0.6f ||
                    cy < seedRadius * 0.6f || cy > bH - seedRadius * 0.6f
                ) {
                    continue
                }

                val pCenter = bitmap.getPixel(cx.toInt().coerceIn(0, bW - 1), cy.toInt().coerceIn(0, bH - 1))
                val rc = Color.red(pCenter)
                val gc = Color.green(pCenter)
                val bc = Color.blue(pCenter)

                for (dr in rSteps) {
                    val candidateR = seedRadius + dr
                    if (candidateR < minAllowedRadius || candidateR > maxAllowedRadius) continue

                    var rimEdge = 0.0
                    var activeAngles = 0
                    var symPenalty = 0.0

                    for (i in 0 until numAngles) {
                        val ct = cosT[i]
                        val st = sinT[i]

                        val outX = (cx + (candidateR + 5f) * ct).toInt().coerceIn(0, bW - 1)
                        val outY = (cy + (candidateR + 5f) * st).toInt().coerceIn(0, bH - 1)
                        val inX = (cx + (candidateR - 5f) * ct).toInt().coerceIn(0, bW - 1)
                        val inY = (cy + (candidateR - 5f) * st).toInt().coerceIn(0, bH - 1)

                        val pOut = bitmap.getPixel(outX, outY)
                        val pIn = bitmap.getPixel(inX, inY)

                        val ro = Color.red(pOut)
                        val go = Color.green(pOut)
                        val bo = Color.blue(pOut)
                        val ri = Color.red(pIn)
                        val gi = Color.green(pIn)
                        val bi = Color.blue(pIn)

                        val diffRgb = abs(ro - ri) + abs(go - gi) + abs(bo - bi)
                        val diffChroma = abs((ro - bo) - (ri - bi)) + abs((go - bo) - (gi - bi))
                        val diffCol = diffRgb + diffChroma

                        rimEdge += min(90, diffCol)
                        if (diffCol > 20) activeAngles++
                    }

                    val bodyR = candidateR * 0.62f
                    val halfAngles = numAngles / 2
                    for (i in 0 until halfAngles) {
                        val ct = cosT[i]
                        val st = sinT[i]
                        val x1 = (cx + bodyR * ct).toInt().coerceIn(0, bW - 1)
                        val y1 = (cy + bodyR * st).toInt().coerceIn(0, bH - 1)
                        val x2 = (cx - bodyR * ct).toInt().coerceIn(0, bW - 1)
                        val y2 = (cy - bodyR * st).toInt().coerceIn(0, bH - 1)

                        val p1 = bitmap.getPixel(x1, y1)
                        val p2 = bitmap.getPixel(x2, y2)
                        symPenalty += abs(Color.red(p1) - Color.red(p2)) +
                                abs(Color.green(p1) - Color.green(p2)) +
                                abs(Color.blue(p1) - Color.blue(p2))
                    }
                    symPenalty /= halfAngles.toDouble()

                    var hubDiff = 0.0
                    var hubSamples = 0
                    for (i in 0 until numAngles step 3) {
                        val xa = (cx + bodyR * cosT[i]).toInt().coerceIn(0, bW - 1)
                        val ya = (cy + bodyR * sinT[i]).toInt().coerceIn(0, bH - 1)
                        val pa = bitmap.getPixel(xa, ya)
                        hubDiff += abs(Color.red(pa) - rc) + abs(Color.green(pa) - gc) + abs(Color.blue(pa) - bc)
                        hubSamples++
                    }
                    hubDiff /= maxOf(1, hubSamples).toDouble()

                    val coverage = activeAngles.toDouble() / numAngles.toDouble()
                    val sizeBonus = 1.0 + 0.15 * ((candidateR - minAllowedRadius) / maxOf(1f, maxAllowedRadius - minAllowedRadius))
                    val finalScore = ((rimEdge * coverage) + (hubDiff * 4.0) - (symPenalty * 12.0)) * sizeBonus

                    if (finalScore > maxScore) {
                        maxScore = finalScore
                        bestCenter = Offset(cx, cy)
                        bestRadius = candidateR
                    }
                }
            }
        }

        return Triple(bestCenter, bestRadius, maxScore)
    }

    fun trackPlateFromPrevious(
        bitmap: Bitmap,
        previousCenter: Offset,
        plateRadius: Float,
        expectedVelocityPx: Offset = Offset.Zero
    ): Offset? {
        val bW = bitmap.width
        val bH = bitmap.height
        if (bW < 50 || bH < 50 || plateRadius <= 5f) return null

        val predX = previousCenter.x + expectedVelocityPx.x.coerceIn(-40f, 40f)
        val predY = previousCenter.y + expectedVelocityPx.y.coerceIn(-140f, 140f)
        return Offset(predX.coerceIn(plateRadius * 0.5f, bW - plateRadius * 0.5f), predY.coerceIn(plateRadius * 0.5f, bH - plateRadius * 0.5f))
    }
}
