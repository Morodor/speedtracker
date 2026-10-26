package com.speedtracker.core.math

import androidx.compose.ui.geometry.Offset
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import java.util.LinkedList
import kotlin.math.abs

data class BarPathPoint(
    val x: Float,
    val y: Float,
    val velocity: Double,
    val timestampMs: Long
)

data class RepResult(
    val repNumber: Int,
    val peakVelocity: Double,
    val meanVelocity: Double,
    val romMeters: Double,
    val isCutoffReached: Boolean = false,
    val velocityLossPercent: Double = 0.0
)

enum class VBTExerciseType {
    FLOOR_PULL,           // Snatch, Clean, Peso Muerto (parte desde el suelo/abajo sin excéntrica previa)
    HANG_PULL,            // Colgante: Hang Clean, Hang Snatch, Tirones, RDL (parte con la barra en suspensión a nivel muslo/rodilla)
    ECCENTRIC_CONCENTRIC  // Sentadilla, Press Banca, Jerk (bajada previa, punto de inflexión en el fondo, subida)
}

enum class VBTVelocityMetric {
    MEAN_VELOCITY, // MV: Estándar para fuerza (Sentadilla, Banca, Peso Muerto)
    PEAK_VELOCITY  // PV: Estándar para balísticos y halterofilia (Snatch, Clean)
}

object VBTAnalyzer {

    private var previousY: Float? = null
    private var previousTime: Long? = null
    private var previousDisplacementY: Float = 25f
    private var handsFarFrameCount: Int = 0
    
    // Suavizado de velocidad para mitigar el ruido de IA a altos FPS
    private val velocityHistory = LinkedList<Double>()
    private const val WINDOW_SIZE = 5

    // Ratio de calibración (metros por píxel):
    const val DEFAULT_PX_TO_METERS = 0.0024
    var pxToMetersRatio: Double = DEFAULT_PX_TO_METERS

    var currentPeakVelocity: Double = 0.0
        private set
    var currentMeanVelocity: Double = 0.0
        private set

    // Métrica principal seleccionada
    var primaryMetric: VBTVelocityMetric = VBTVelocityMetric.MEAN_VELOCITY

    // Tipo de ejercicio configurado
    var exerciseType: VBTExerciseType = VBTExerciseType.FLOOR_PULL

    // Punto de partida / Origen del movimiento
    var originBaselineX: Float? = null
        private set
    var originBaselineY: Float? = null
        private set

    // Punto de inflexión más bajo (el fondo de la sentadilla o pecho en banca)
    var lowestInflectionX: Float? = null
        private set
    var lowestInflectionY: Float? = null
        private set

    // Posición actual de la barra en coordenadas del frame
    var currentBarX: Float? = null
        private set
    var currentBarY: Float? = null
        private set

    // Trayectoria completa y del rep actual
    val barPath = mutableListOf<BarPathPoint>()
    val currentRepPath = mutableListOf<BarPathPoint>()
    val lastCompletedRepPath = mutableListOf<BarPathPoint>()
    
    var maxHorizontalDeviationPx: Float = 0f
        private set

    // Detección de repeticiones en vivo y umbrales (Cutoff & % Loss)
    val currentSetReps = mutableListOf<RepResult>()
    var cutoffVelocityMps: Double? = null
    var cutoffLossPercent: Double? = null
    var onRepCompleted: ((RepResult) -> Unit)? = null

    // Filtros de ruido para evitar falsos positivos estando parado:
    private const val VELOCITY_DEADBAND = 0.18
    private const val MIN_REP_ROM_METERS = 0.12
    private const val MIN_REP_PEAK_VELOCITY = 0.25
    private const val MIN_CONCENTRIC_DURATION_MS = 200L

    var isConcentricActive = false
        private set
    var isEccentricActive = false
        private set

    private var concentricStartY = 0f
    private var concentricMinY = Float.MAX_VALUE
    private var concentricStartTimeMs = 0L
    private var currentRepPeakVelocity = 0.0
    private val currentRepConcentricVelocities = mutableListOf<Double>()
    private var lastUpwardTimeMs = 0L

    fun setManualOrigin() {
        originBaselineX = currentBarX
        originBaselineY = currentBarY
        lowestInflectionX = currentBarX
        lowestInflectionY = currentBarY
    }

    // Calibración y Anclaje Óptico del Disco Olímpico (Kinovea)
    var calibratedPlateCenter: Offset? = null
        private set
    var calibratedPlateRadius: Float? = null
        private set
    private var initialHandsPos: Offset? = null
    private var plateOffset: Offset? = null

    /**
     * Fija el disco olímpico calibrado como punto de anclaje de la trayectoria.
     */
    fun setCalibratedPlate(center: Offset, radius: Float) {
        calibratedPlateCenter = center
        calibratedPlateRadius = radius
        val diameterPx = radius * 2f
        if (diameterPx > 5f) {
            pxToMetersRatio = 0.45 / diameterPx.toDouble()
        }
        originBaselineX = center.x
        originBaselineY = center.y
        lowestInflectionX = center.x
        lowestInflectionY = center.y
        currentBarX = center.x
        currentBarY = center.y
        initialHandsPos = null
        plateOffset = null
        previousDisplacementY = 25f
        handsFarFrameCount = 0
    }

    /**
     * Calibra la relación de píxeles a metros a partir del diámetro medido de un disco olímpico
     * (Estándar internacional = 0.45 metros / 450 mm)
     */
    fun calibrateWithPlate(plateDiameterMeters: Double = 0.45, plateHeightPx: Float): Double {
        if (plateHeightPx > 10f) {
            pxToMetersRatio = plateDiameterMeters / plateHeightPx.toDouble()
        }
        return pxToMetersRatio
    }

    fun getHorizontalDeviationMeters(): Double {
        return maxHorizontalDeviationPx * pxToMetersRatio
    }

    fun reset() {
        previousY = null
        previousTime = null
        velocityHistory.clear()
        currentPeakVelocity = 0.0
        currentMeanVelocity = 0.0
        barPath.clear()
        currentRepPath.clear()
        lastCompletedRepPath.clear()
        maxHorizontalDeviationPx = 0f
        currentSetReps.clear()
        isConcentricActive = false
        isEccentricActive = false
        currentRepPeakVelocity = 0.0
        currentRepConcentricVelocities.clear()
        lastUpwardTimeMs = 0L
        initialHandsPos = null
        plateOffset = null
        previousDisplacementY = 25f
        handsFarFrameCount = 0
        if (calibratedPlateCenter != null) {
            currentBarX = calibratedPlateCenter?.x
            currentBarY = calibratedPlateCenter?.y
            originBaselineX = calibratedPlateCenter?.x
            originBaselineY = calibratedPlateCenter?.y
            lowestInflectionX = calibratedPlateCenter?.x
            lowestInflectionY = calibratedPlateCenter?.y
        } else {
            currentBarX = null
            currentBarY = null
            originBaselineX = null
            originBaselineY = null
            lowestInflectionX = null
            lowestInflectionY = null
        }
    }

    fun resetSet() {
        currentSetReps.clear()
        isConcentricActive = false
        isEccentricActive = false
        currentRepPeakVelocity = 0.0
        currentRepConcentricVelocities.clear()
        lastUpwardTimeMs = 0L
        barPath.clear()
        currentRepPath.clear()
        lastCompletedRepPath.clear()
        maxHorizontalDeviationPx = 0f
        lowestInflectionX = null
        lowestInflectionY = null
    }

    /**
     * Procesa la pose en tiempo real con invariantes biomecánicas de halterofilia.
     * Incorpora:
     * 1. Invariante de bloqueo sobre la cabeza (Catch Overhead).
     * 2. Invariante antigravedad de ascenso concéntrico (impide caídas a rodillas en tirones rápidos).
     * 3. Continuación balística por inercia física ante desenfoque de movimiento (Motion Blur).
     */
    fun processPose(
        pose: Pose,
        timestampMs: Long
    ): Double? {
        val leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        val leftElbow = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW)
        val rightElbow = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW)
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val leftKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
        val rightKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)

        val lConf = leftWrist?.inFrameLikelihood ?: 0f
        val rConf = rightWrist?.inFrameLikelihood ?: 0f

        // 1. Invariante de Bloqueo Sobre la Cabeza (Catch de Snatch / Jerk):
        // Si los codos están más altos que los hombros, el atleta tiene los brazos extendidos arriba.
        val isLeftOverhead = leftElbow != null && leftShoulder != null &&
                leftElbow.inFrameLikelihood > 0.35f && leftShoulder.inFrameLikelihood > 0.35f &&
                leftElbow.position.y < leftShoulder.position.y
        val isRightOverhead = rightElbow != null && rightShoulder != null &&
                rightElbow.inFrameLikelihood > 0.35f && rightShoulder.inFrameLikelihood > 0.35f &&
                rightElbow.position.y < rightShoulder.position.y

        var rawHandsPos: Offset? = null

        if (isLeftOverhead || isRightOverhead) {
            // El atleta está sosteniendo la barra arriba de la cabeza.
            // Es anatómicamente imposible que las manos estén en la cadera o rodilla.
            val overheadY: Float
            val overheadX: Float
            if (isLeftOverhead && isRightOverhead) {
                val avgElbowY = (leftElbow!!.position.y + rightElbow!!.position.y) / 2f
                val avgShoulderY = (leftShoulder!!.position.y + rightShoulder!!.position.y) / 2f
                val armLen = abs(avgShoulderY - avgElbowY).coerceAtLeast(35f)
                overheadY = avgElbowY - (armLen * 0.85f)
                overheadX = (leftElbow.position.x + rightElbow.position.x) / 2f
            } else if (isLeftOverhead) {
                val armLen = abs(leftShoulder!!.position.y - leftElbow!!.position.y).coerceAtLeast(35f)
                overheadY = leftElbow.position.y - (armLen * 0.85f)
                overheadX = leftElbow.position.x
            } else {
                val armLen = abs(rightShoulder!!.position.y - rightElbow!!.position.y).coerceAtLeast(35f)
                overheadY = rightElbow!!.position.y - (armLen * 0.85f)
                overheadX = rightElbow.position.x
            }

            val leftWristUp = leftWrist != null && lConf > 0.30f && leftWrist.position.y < (leftShoulder?.position?.y ?: Float.MAX_VALUE)
            val rightWristUp = rightWrist != null && rConf > 0.30f && rightWrist.position.y < (rightShoulder?.position?.y ?: Float.MAX_VALUE)

            rawHandsPos = when {
                leftWristUp && rightWristUp -> Offset((leftWrist!!.position.x + rightWrist!!.position.x) / 2f, (leftWrist.position.y + rightWrist.position.y) / 2f)
                leftWristUp -> Offset(leftWrist!!.position.x, leftWrist.position.y)
                rightWristUp -> Offset(rightWrist!!.position.x, rightWrist.position.y)
                else -> Offset(overheadX, overheadY)
            }
        } else {
            // Fase de suelo, primer tirón o segundo tirón
            val isLeftValid = isLimbPlausible(leftWrist, leftElbow, leftShoulder, leftHip, leftKnee)
            val isRightValid = isLimbPlausible(rightWrist, rightElbow, rightShoulder, rightHip, rightKnee)

            rawHandsPos = when {
                isLeftValid && isRightValid -> {
                    if (lConf > rConf + 0.3f) Offset(leftWrist!!.position.x, leftWrist.position.y)
                    else if (rConf > lConf + 0.3f) Offset(rightWrist!!.position.x, rightWrist.position.y)
                    else Offset((leftWrist!!.position.x + rightWrist!!.position.x) / 2f, (leftWrist.position.y + rightWrist.position.y) / 2f)
                }
                isLeftValid -> Offset(leftWrist!!.position.x, leftWrist.position.y)
                isRightValid -> Offset(rightWrist!!.position.x, rightWrist.position.y)
                else -> null
            }
        }

        // 2. Continuación por Inercia Cinemática si las manos sufren desenfoque por velocidad
        if (rawHandsPos == null) {
            val cY = currentBarY
            val pY = previousY
            val pT = previousTime
            if (cY != null && pY != null && pT != null) {
                val dt = (timestampMs - pT) / 1000.0
                if (dt in 0.005..0.25 && isConcentricActive) {
                    val prevVyPx = (pY - cY) / dt.toFloat()
                    val upwardStep = (prevVyPx * dt.toFloat()).coerceIn(12f, 75f)
                    val nextY = (cY - upwardStep).coerceAtLeast(10f)
                    currentBarY = nextY
                    val point = BarPathPoint(currentBarX ?: 0f, nextY, currentRepPeakVelocity, timestampMs)
                    barPath.add(point)
                    currentRepPath.add(point)
                    previousDisplacementY = upwardStep
                    previousY = nextY
                    previousTime = timestampMs
                    return currentRepPeakVelocity
                }
            }
            return null
        }

        // 3. Sincronización y Anclaje con el Disco Calibrado
        if (calibratedPlateCenter != null) {
            val disc = calibratedPlateCenter!!
            val radius = calibratedPlateRadius ?: 100f
            if (initialHandsPos == null) {
                val dyToDisc = abs(rawHandsPos.y - disc.y)
                val dxToDisc = abs(rawHandsPos.x - disc.x)
                if (dyToDisc < radius * 3.0f && dxToDisc < radius * 5.5f) {
                    initialHandsPos = rawHandsPos
                    plateOffset = Offset(disc.x - rawHandsPos.x, disc.y - rawHandsPos.y)
                } else {
                    handsFarFrameCount++
                    if (handsFarFrameCount > 30) {
                        initialHandsPos = rawHandsPos
                        plateOffset = Offset(disc.x - rawHandsPos.x, 0f)
                    } else {
                        currentBarX = disc.x
                        currentBarY = disc.y
                        return 0.0
                    }
                }
            }
        }

        var candidateBarX = if (plateOffset != null) rawHandsPos.x + plateOffset!!.x else rawHandsPos.x
        var candidateBarY = if (plateOffset != null) rawHandsPos.y + plateOffset!!.y else rawHandsPos.y

        // 4. Invariante Antigravedad de Ascenso Concéntrico:
        // Durante el tirón concéntrico (subida), la barra NO puede saltar abruptamente hacia abajo (>20 px en 33ms)
        val prevY = previousY
        val prevT = previousTime
        if (prevY != null && prevT != null) {
            val dt = (timestampMs - prevT) / 1000.0
            if (dt in 0.005..0.30) {
                val dy = candidateBarY - prevY
                if (isConcentricActive && dy > 20f) {
                    // Rechazar alucinación hacia la rodilla; continuar inercia ascendente
                    val upwardStep = previousDisplacementY.coerceIn(12f, 75f)
                    candidateBarY = (prevY - upwardStep).coerceAtLeast(10f)
                    candidateBarX = currentBarX ?: candidateBarX
                }
            }
        }

        if (prevY != null && prevY > candidateBarY) {
            previousDisplacementY = prevY - candidateBarY
        }

        val barX = candidateBarX
        val barY = candidateBarY

        return processBarPosition(barX, barY, timestampMs)
    }

    /**
     * Procesa la posición de la barra/disco en coordenadas del video (Offset nativo).
     * Calcula cinemática, velocidad media/pico, repeticiones concéntricas/excéntricas y trayectoria.
     */
    fun processBarPosition(
        barX: Float,
        barY: Float,
        timestampMs: Long
    ): Double? {
        currentBarX = barX
        currentBarY = barY

        // Auto-establecer origen si aún no se ha fijado y está en reposo
        if (originBaselineY == null) {
            originBaselineX = barX
            originBaselineY = barY
        }

        if (previousY != null && previousTime != null) {
            val deltaSeconds = (timestampMs - previousTime!!) / 1000.0
            if (deltaSeconds < 0.005) {
                return 0.0
            }
            
            // Si la barra SUBE, pxMoved > 0. Si BAJA, pxMoved < 0.
            val pxMoved = previousY!! - barY 
            val rawDisplacementMeters = pxMoved * pxToMetersRatio
            val rawVelocity = (rawDisplacementMeters / deltaSeconds).coerceIn(-3.0, 3.0)

            // Filtro de media móvil
            velocityHistory.addLast(rawVelocity)
            if (velocityHistory.size > WINDOW_SIZE) {
                velocityHistory.removeFirst()
            }
            val smoothedVelocity = velocityHistory.average()

            // Filtro de zona muerta (< 0.18 m/s se trata como 0.0)
            val filteredVelocity = if (smoothedVelocity > VELOCITY_DEADBAND) smoothedVelocity else 0.0

            // 1. Manejo de fase excéntrica (bajada) para Sentadilla / Banca o Dip en Colgante
            val allowEccentric = exerciseType == VBTExerciseType.ECCENTRIC_CONCENTRIC || exerciseType == VBTExerciseType.HANG_PULL
            if (pxMoved < -2f && allowEccentric && !isConcentricActive) {
                isEccentricActive = true
                if (lowestInflectionY == null || barY > lowestInflectionY!!) {
                    lowestInflectionY = barY
                    lowestInflectionX = barX
                }
            }

            // 2. Manejo de fase concéntrica (subida)
            if (pxMoved > 2f && filteredVelocity > 0.0) {
                if (!isConcentricActive) {
                    isConcentricActive = true
                    
                    // El inicio exacto de la trayectoria concéntrica depende del ejercicio:
                    if (isEccentricActive && lowestInflectionY != null) {
                        concentricStartY = lowestInflectionY!!
                        concentricMinY = lowestInflectionY!!
                        currentRepPath.clear()
                        currentRepPath.add(BarPathPoint(lowestInflectionX ?: barX, lowestInflectionY!!, 0.0, timestampMs))
                    } else {
                        concentricStartY = barY
                        concentricMinY = barY
                        currentRepPath.clear()
                    }

                    concentricStartTimeMs = timestampMs
                    currentRepPeakVelocity = 0.0
                    currentRepConcentricVelocities.clear()
                    isEccentricActive = false
                }
                lastUpwardTimeMs = timestampMs

                if (barY < concentricMinY) {
                    concentricMinY = barY
                }

                if (filteredVelocity > currentPeakVelocity) {
                    currentPeakVelocity = filteredVelocity
                }
                if (filteredVelocity > currentRepPeakVelocity) {
                    currentRepPeakVelocity = filteredVelocity
                }

                // Guardar muestra para calcular la velocidad media de la fase concéntrica
                currentRepConcentricVelocities.add(filteredVelocity)

                // Guardar punto para la trayectoria en vivo
                val point = BarPathPoint(barX, barY, filteredVelocity, timestampMs)
                barPath.add(point)
                currentRepPath.add(point)

                // Calcular desvío horizontal total
                val minX = currentRepPath.minOfOrNull { it.x } ?: barX
                val maxX = currentRepPath.maxOfOrNull { it.x } ?: barX
                maxHorizontalDeviationPx = maxX - minX

                previousY = barY
                previousTime = timestampMs
                return filteredVelocity
            } else {
                // 3. Finalización de la repetición al detenerse en el bloqueo o iniciar descenso
                if (isConcentricActive && (timestampMs - lastUpwardTimeMs > 250)) {
                    val concentricDuration = timestampMs - concentricStartTimeMs
                    val romMeters = (concentricStartY - concentricMinY) * pxToMetersRatio

                    if (romMeters >= MIN_REP_ROM_METERS &&
                        currentRepPeakVelocity >= MIN_REP_PEAK_VELOCITY &&
                        concentricDuration >= MIN_CONCENTRIC_DURATION_MS
                    ) {
                        val repNum = currentSetReps.size + 1
                        
                        // Cálculo de Velocidad Media Concéntrica (Mean Velocity - MV)
                        val meanVelocity = if (currentRepConcentricVelocities.isNotEmpty()) {
                            currentRepConcentricVelocities.average()
                        } else {
                            currentRepPeakVelocity
                        }
                        currentMeanVelocity = meanVelocity

                        // Seleccionar métrica objetivo para cálculo de fatiga y umbral
                        val primaryMetricValue = if (primaryMetric == VBTVelocityMetric.MEAN_VELOCITY) {
                            meanVelocity
                        } else {
                            currentRepPeakVelocity
                        }

                        val bestRepMetric = currentSetReps.maxOfOrNull { 
                            if (primaryMetric == VBTVelocityMetric.MEAN_VELOCITY) it.meanVelocity else it.peakVelocity 
                        } ?: primaryMetricValue

                        val loss = if (bestRepMetric > 0) ((bestRepMetric - primaryMetricValue) / bestRepMetric) * 100.0 else 0.0

                        var isCutoff = false
                        if (cutoffVelocityMps != null && primaryMetricValue < cutoffVelocityMps!!) {
                            isCutoff = true
                        }
                        if (cutoffLossPercent != null && loss >= cutoffLossPercent!! && currentSetReps.isNotEmpty()) {
                            isCutoff = true
                        }

                        val rep = RepResult(
                            repNumber = repNum,
                            peakVelocity = currentRepPeakVelocity,
                            meanVelocity = meanVelocity,
                            romMeters = romMeters,
                            isCutoffReached = isCutoff,
                            velocityLossPercent = loss
                        )
                        currentSetReps.add(rep)

                        lastCompletedRepPath.clear()
                        lastCompletedRepPath.addAll(currentRepPath)

                        onRepCompleted?.invoke(rep)
                    }

                    isConcentricActive = false
                    isEccentricActive = false
                    currentRepPeakVelocity = 0.0
                    currentRepConcentricVelocities.clear()
                    currentRepPath.clear()
                    lowestInflectionY = null
                    lowestInflectionX = null
                }
            }
        }
        
        previousY = barY
        previousTime = timestampMs
        return 0.0
    }

    /**
     * Valida si un landmark de muñeca es anatómicamente plausible para evitar
     * que la IA confunda manos con rodillas o caderas durante movimientos explosivos.
     */
    private fun isLimbPlausible(
        wrist: PoseLandmark?,
        elbow: PoseLandmark?,
        shoulder: PoseLandmark?,
        hip: PoseLandmark?,
        knee: PoseLandmark?
    ): Boolean {
        if (wrist == null || wrist.inFrameLikelihood < 0.28f) return false

        // Validación biomecánica de proporciones del brazo (antebrazo vs brazo superior)
        if (elbow != null && shoulder != null && elbow.inFrameLikelihood > 0.30f && shoulder.inFrameLikelihood > 0.30f) {
            val upperArm = kotlin.math.hypot(elbow.position.x - shoulder.position.x, elbow.position.y - shoulder.position.y)
            val forearm = kotlin.math.hypot(wrist.position.x - elbow.position.x, wrist.position.y - elbow.position.y)
            if (upperArm > 25f) {
                // Si la distancia muñeca-codo excede 1.8x o es menor a 0.15x del brazo, es una alucinación
                if (forearm > upperArm * 1.80f || forearm < upperArm * 0.15f) {
                    return false
                }
            }
        }

        // Si el codo está por encima de la cadera (tirón alto o barra en el pecho)
        if (elbow != null && hip != null && elbow.inFrameLikelihood > 0.30f && hip.inFrameLikelihood > 0.30f) {
            if (elbow.position.y < hip.position.y) {
                val kneeY = knee?.position?.y ?: (hip.position.y + 110f)
                // Es anatómicamente imposible que la muñeca esté debajo de la rodilla si el codo está en el pecho
                if (wrist.position.y > kneeY) {
                    return false
                }
            }
        }

        return true
    }
}
