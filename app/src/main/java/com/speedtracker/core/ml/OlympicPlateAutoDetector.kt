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
 * con refinamiento de gradiente circular óptico (Computer Vision).
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

        var baseRadius = (minOf(w, h) * 0.14f).coerceIn(35f, 400f)
        val candidateSeeds = mutableListOf<Offset>()
        var usedPose = false
        var targetHandsX = w * 0.5f
        var targetHandsY = h * 0.65f

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
                // Nivel de suelo estimado
                val ankleY = when {
                    leftAnkle != null && rightAnkle != null -> maxOf(leftAnkle.position.y, rightAnkle.position.y)
                    leftAnkle != null -> leftAnkle.position.y
                    rightAnkle != null -> rightAnkle.position.y
                    else -> h * 0.82f
                }

                val kneeY = when {
                    leftKnee != null && rightKnee != null -> (leftKnee.position.y + rightKnee.position.y) / 2f
                    leftKnee != null -> leftKnee.position.y
                    rightKnee != null -> rightKnee.position.y
                    else -> ankleY - (h * 0.22f)
                }

                // Longitud anatómica real de la tibia
                val shinLength = when {
                    leftAnkle != null && leftKnee != null && leftAnkle.inFrameLikelihood > 0.30f && leftKnee.inFrameLikelihood > 0.30f -> {
                        hypot(leftAnkle.position.x - leftKnee.position.x, leftAnkle.position.y - leftKnee.position.y)
                    }
                    rightAnkle != null && rightKnee != null && rightAnkle.inFrameLikelihood > 0.30f && rightKnee.inFrameLikelihood > 0.30f -> {
                        hypot(rightAnkle.position.x - rightKnee.position.x, rightAnkle.position.y - rightKnee.position.y)
                    }
                    else -> abs(ankleY - kneeY).coerceIn(h * 0.16f, h * 0.45f)
                }.coerceIn(h * 0.15f, h * 0.45f)

                val platformFloorY = ankleY + (shinLength * 0.12f)
                baseRadius = (shinLength * 0.52f).coerceIn(w * 0.08f, w * 0.35f)
                val floorPlateCenterY = platformFloorY - baseRadius

                // En vista lateral pura (estándar óptimo para VBT):
                // El disco circular de 45 cm está alineado verticalmente con las manos y pies en el plano sagital
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
                        (wX * 0.65f + aX * 0.35f)
                    }
                    hasWrist -> leftWrist?.position?.x ?: rightWrist?.position?.x ?: (w * 0.5f)
                    hasAnkle -> leftAnkle?.position?.x ?: rightAnkle?.position?.x ?: (w * 0.5f)
                    else -> w * 0.5f
                }

                targetHandsY = when {
                    leftWrist != null && rightWrist != null -> (leftWrist.position.y + rightWrist.position.y) / 2f
                    leftWrist != null -> leftWrist.position.y
                    rightWrist != null -> rightWrist.position.y
                    else -> floorPlateCenterY
                }

                // Semillas candidatas enfocadas en el plano lateral de la barra:
                // 1. A la altura de las manos/barra
                candidateSeeds.add(Offset(targetHandsX, targetHandsY))
                // 2. En el suelo apoyado frente al tobillo (inicio del levantamiento)
                candidateSeeds.add(Offset(targetHandsX, floorPlateCenterY))
                // 3. Ajuste fino anterior/posterior en el eje sagital
                candidateSeeds.add(Offset(targetHandsX + (baseRadius * 0.35f), targetHandsY))
                candidateSeeds.add(Offset(targetHandsX - (baseRadius * 0.35f), targetHandsY))
                candidateSeeds.add(Offset(targetHandsX, (targetHandsY + floorPlateCenterY) / 2f))

                usedPose = true
            }
        }

        // Semillas por defecto si no hay pose o para complementar
        if (candidateSeeds.isEmpty()) {
            candidateSeeds.add(Offset(w * 0.50f, h * 0.68f))
            candidateSeeds.add(Offset(w * 0.40f, h * 0.68f))
            candidateSeeds.add(Offset(w * 0.60f, h * 0.68f))
            candidateSeeds.add(Offset(w * 0.50f, h * 0.52f))
        }

        // Evaluar todas las semillas candidatas mediante análisis de contraste circular multiescala
        var bestCandidate: Pair<Offset, Float> = Pair(candidateSeeds.first(), baseRadius)
        var maxCandidateScore = -1.0

        for (seed in candidateSeeds) {
            val clampedSeed = Offset(
                seed.x.coerceIn(baseRadius * 0.8f, w - baseRadius * 0.8f),
                seed.y.coerceIn(baseRadius * 0.8f, h - baseRadius * 0.8f)
            )
            val result = refineCircleByRadialGradients(bitmap, clampedSeed, baseRadius)
            
            // Ponderación por cercanía a las manos del atleta para ignorar discos sueltos en el suelo
            val distToHands = if (usedPose) {
                hypot((result.first.x - targetHandsX).toDouble(), (result.first.y - targetHandsY).toDouble()).toFloat()
            } else {
                0f
            }
            val proximityFactor = if (usedPose) {
                1.0 / (1.0 + (distToHands / (baseRadius * 1.4f)).toDouble().let { it * it })
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
            confidence = if (usedPose && maxCandidateScore > 300.0) 0.94f else 0.72f,
            method = if (usedPose) "MULTI_SEED_POSE_CV" else "CIRCULAR_CV_FALLBACK"
        )
    }

    /**
     * Refina el centro y radio buscando el contorno circular de máximo contraste perimétrico
     * en torno a la semilla inicial calculada por la pose biomecánica.
     * Retorna Triple(bestCenter, bestRadius, maxScore)
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
        var maxScore = -1.0

        // Pasos de búsqueda local multiescala
        val xSteps = listOf(-30f, -15f, 0f, 15f, 30f)
        val ySteps = listOf(-30f, -15f, 0f, 15f, 30f)
        val rSteps = listOf(-24f, -12f, 0f, 12f, 24f)

        val numAngles = 24
        val angles = (0 until numAngles).map { (2.0 * Math.PI * it) / numAngles }

        val minAllowedRadius = (minOf(bW, bH) * 0.07f).coerceAtLeast(35f)
        val maxAllowedRadius = (minOf(bW, bH) * 0.45f)

        for (dx in xSteps) {
            for (dy in ySteps) {
                val candidateCenter = Offset(seedCenter.x + dx, seedCenter.y + dy)
                if (candidateCenter.x < seedRadius * 0.5f || candidateCenter.x > bW - seedRadius * 0.5f ||
                    candidateCenter.y < seedRadius * 0.5f || candidateCenter.y > bH - seedRadius * 0.5f
                ) {
                    continue
                }

                for (dr in rSteps) {
                    val candidateR = seedRadius + dr
                    if (candidateR < minAllowedRadius || candidateR > maxAllowedRadius) continue

                    var currentScore = 0.0

                    // Evaluar contraste de borde radial a lo largo del perímetro
                    for (theta in angles) {
                        val cosT = cos(theta).toFloat()
                        val sinT = sin(theta).toFloat()

                        // Puntos fuera y dentro del perímetro candidato (separados 5 px)
                        val outX = (candidateCenter.x + (candidateR + 5f) * cosT).toInt().coerceIn(0, bW - 1)
                        val outY = (candidateCenter.y + (candidateR + 5f) * sinT).toInt().coerceIn(0, bH - 1)

                        val inX = (candidateCenter.x + (candidateR - 5f) * cosT).toInt().coerceIn(0, bW - 1)
                        val inY = (candidateCenter.y + (candidateR - 5f) * sinT).toInt().coerceIn(0, bH - 1)

                        val pOut = bitmap.getPixel(outX, outY)
                        val pIn = bitmap.getPixel(inX, inY)

                        val lumOut = (Color.red(pOut) * 299 + Color.green(pOut) * 587 + Color.blue(pOut) * 114) / 1000
                        val lumIn = (Color.red(pIn) * 299 + Color.green(pIn) * 587 + Color.blue(pIn) * 114) / 1000

                        currentScore += abs(lumOut - lumIn)
                    }

                    // Penalizar desviación extrema de la proporción anatómica seedRadius para no caer en el buje
                    val radiusRatio = candidateR / seedRadius
                    val seedPenalty = if (radiusRatio < 0.75f) 0.65 else if (radiusRatio > 1.35f) 0.75 else 1.0
                    val finalScore = currentScore * seedPenalty

                    if (finalScore > maxScore) {
                        maxScore = finalScore
                        bestCenter = candidateCenter
                        bestRadius = candidateR
                    }
                }
            }
        }

        return Triple(bestCenter, bestRadius, maxScore)
    }

    /**
     * Rastreador óptico de alta velocidad del disco olímpico (CV con correlación cruzada).
     * Diseñado específicamente para levantamientos olímpicos explosivos (Snatch / Clean & Jerk).
     *
     * Características clave:
     * 1. Propagación inercial de velocidad: La ventana de búsqueda viaja con la barra a cualquier
     *    velocidad (incluso >2.8 m/s, o más de 120 px/frame en 1080p).
     * 2. Doble criterio de correlación:
     *    - Contraste perimétrico en ángulos laterales (inmune al desenfoque vertical por velocidad).
     *    - Contraste del buje/collar central metálico contra el cuerpo de caucho del disco.
     * 3. Refinamiento sub-píxel para trayectorias de barra perfectamente suaves y continuas.
     * 4. Inercia balística ante motion blur extremo, impidiendo que el tracker salte a rodillas o piso.
     */
    fun trackPlateFromPrevious(
        bitmap: Bitmap,
        previousCenter: Offset,
        plateRadius: Float,
        expectedVelocityPx: Offset = Offset.Zero
    ): Offset? {
        val bW = bitmap.width
        val bH = bitmap.height
        if (bW < 50 || bH < 50 || plateRadius <= 5f) return null

        // 1. Predicción cinemática inercial
        // En un snatch 1080p/4K a 30fps, la velocidad vertical puede superar -100 px/frame
        val predX = previousCenter.x + expectedVelocityPx.x.coerceIn(-40f, 40f)
        val predY = previousCenter.y + expectedVelocityPx.y.coerceIn(-140f, 140f)

        // 2. Ventana de búsqueda adaptada alrededor de la posición predicha
        val dxSteps = listOf(-24f, -16f, -10f, -5f, 0f, 5f, 10f, 16f, 24f)
        val dySteps = listOf(-60f, -45f, -32f, -22f, -14f, -8f, 0f, 8f, 14f, 22f, 32f, 45f, 60f)

        // Ángulos laterales: -45°, -30°, 0°, 30°, 45° y sus opuestos.
        // Tienen CERO desenfoque vertical durante un tirón vertical de snatch
        val lateralThetas = listOf(
            -0.785f, -0.523f, 0f, 0.523f, 0.785f,
            2.356f, 2.618f, 3.1415f, -2.618f, -2.356f
        )

        var bestCenter = Offset(predX, predY)
        var maxScore = -1.0

        for (dy in dySteps) {
            val candidateY = predY + dy
            if (candidateY < plateRadius * 0.4f || candidateY > bH - plateRadius * 0.4f) continue

            for (dx in dxSteps) {
                val candidateX = predX + dx
                if (candidateX < plateRadius * 0.4f || candidateX > bW - plateRadius * 0.4f) continue

                // Criterio A: Gradiente radial perimétrico en bordes laterales
                var rimScore = 0.0
                for (th in lateralThetas) {
                    val cosT = cos(th)
                    val sinT = sin(th)

                    val outX = (candidateX + (plateRadius + 6f) * cosT).toInt().coerceIn(0, bW - 1)
                    val outY = (candidateY + (plateRadius + 6f) * sinT).toInt().coerceIn(0, bH - 1)
                    val inX = (candidateX + (plateRadius - 6f) * cosT).toInt().coerceIn(0, bW - 1)
                    val inY = (candidateY + (plateRadius - 6f) * sinT).toInt().coerceIn(0, bH - 1)

                    val pOut = bitmap.getPixel(outX, outY)
                    val pIn = bitmap.getPixel(inX, inY)

                    val lumOut = (Color.red(pOut) * 299 + Color.green(pOut) * 587 + Color.blue(pOut) * 114) / 1000
                    val lumIn = (Color.red(pIn) * 299 + Color.green(pIn) * 587 + Color.blue(pIn) * 114) / 1000

                    rimScore += abs(lumOut - lumIn)
                }
                rimScore /= lateralThetas.size

                // Criterio B: Contraste del buje central con el cuerpo del disco
                val cXInt = candidateX.toInt().coerceIn(0, bW - 1)
                val cYInt = candidateY.toInt().coerceIn(0, bH - 1)
                val pCenter = bitmap.getPixel(cXInt, cYInt)
                val lumCenter = (Color.red(pCenter) * 299 + Color.green(pCenter) * 587 + Color.blue(pCenter) * 114) / 1000

                var bodyLum = 0.0
                val bodyOffsets = listOf(0.45f, -0.45f)
                for (bx in bodyOffsets) {
                    val pB = bitmap.getPixel((candidateX + plateRadius * bx).toInt().coerceIn(0, bW - 1), cYInt)
                    bodyLum += (Color.red(pB) * 299 + Color.green(pB) * 587 + Color.blue(pB) * 114) / 1000
                }
                bodyLum /= 2.0
                val hubContrast = abs(lumCenter - bodyLum)

                val compositeScore = rimScore + (0.75 * hubContrast)

                if (compositeScore > maxScore) {
                    maxScore = compositeScore
                    bestCenter = Offset(candidateX, candidateY)
                }
            }
        }

        // 3. Refinamiento sub-píxel alrededor del mejor candidato
        var fineCenter = bestCenter
        var fineMaxScore = maxScore
        val fineSteps = listOf(-3f, -1.5f, 0f, 1.5f, 3f)

        for (fdy in fineSteps) {
            val cy = bestCenter.y + fdy
            for (fdx in fineSteps) {
                val cx = bestCenter.x + fdx
                var rimScore = 0.0
                for (th in lateralThetas) {
                    val cosT = cos(th)
                    val sinT = sin(th)
                    val outX = (cx + (plateRadius + 6f) * cosT).toInt().coerceIn(0, bW - 1)
                    val outY = (cy + (plateRadius + 6f) * sinT).toInt().coerceIn(0, bH - 1)
                    val inX = (cx + (plateRadius - 6f) * cosT).toInt().coerceIn(0, bW - 1)
                    val inY = (cy + (plateRadius - 6f) * sinT).toInt().coerceIn(0, bH - 1)
                    val pOut = bitmap.getPixel(outX, outY)
                    val pIn = bitmap.getPixel(inX, inY)
                    val lumOut = (Color.red(pOut) * 299 + Color.green(pOut) * 587 + Color.blue(pOut) * 114) / 1000
                    val lumIn = (Color.red(pIn) * 299 + Color.green(pIn) * 587 + Color.blue(pIn) * 114) / 1000
                    rimScore += abs(lumOut - lumIn)
                }
                rimScore /= lateralThetas.size
                if (rimScore > fineMaxScore) {
                    fineMaxScore = rimScore
                    fineCenter = Offset(cx, cy)
                }
            }
        }

        // Si la confianza es sólida retornar fineCenter; de lo contrario proyectar por inercia
        return if (fineMaxScore >= 10.0) {
            fineCenter
        } else {
            Offset(predX, predY)
        }
    }
}
